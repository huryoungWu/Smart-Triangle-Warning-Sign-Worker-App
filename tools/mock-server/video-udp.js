'use strict';

/**
 * Mock 服务端的 UDP 实时视频（APP ↔ 服务端下行）。
 *
 * - 与 HTTP 共用同一个数字端口（TCP 8080 / UDP 8080 互不冲突）。
 * - 每台设备一个 100ms 定时器：只生成/切片一次，扇出给该设备的所有观看端。
 * - 控制报文见 udp-protocol.js 顶部注释。
 * - 尽力而为：不做 NACK/重传；客户端缺片丢整帧，3s 无完整帧自行重连。
 *
 * 生产服务端应把 makeFrame 换成“车端摄像头的解码帧”，并补鉴权/DTLS/反放大。
 */

const dgram = require('dgram');
const net = require('net');
const crypto = require('crypto');
const protocol = require('./udp-protocol');

const STREAM_W = 320;
const STREAM_H = 180;
const FRAME_INTERVAL_MS = 100;
const CONTROL_MAX_BYTES = 4096;
const MAX_SESSIONS = 64;
const MAX_SESSIONS_PER_ADDRESS = 8;
/** 6s 收不到 hb 就清理；测试可用 UDP_SESSION_IDLE_MS=800 缩短 */
const SESSION_IDLE_MS = Number(process.env.UDP_SESSION_IDLE_MS || 6000);

function start({ port, host, makeFrame, log = console.log }) {
  const sessions = new Map();      // key -> session
  const deviceStreams = new Map(); // deviceCode -> { timer, sessions:Set<key>, frameSeq }
  const sockets = [];
  let closed = false;

  const keyOf = (rinfo) => `${rinfo.family}|${rinfo.address}|${rinfo.port}`;

  function randomSid() {
    let sid;
    do {
      sid = crypto.randomBytes(4).readUInt32BE(0);
    } while (sid === 0);
    return sid;
  }

  function sendControl(socket, rinfo, obj) {
    const buf = Buffer.from(JSON.stringify(obj), 'utf8');
    socket.send(buf, 0, buf.length, rinfo.port, rinfo.address, (err) => {
      if (err) log(`[视频流/UDP] 控制包发送失败: ${err.message}`);
    });
  }

  function sendErr(socket, rinfo, cid, status, code, message) {
    sendControl(socket, rinfo, {
      v: 1,
      t: 'err',
      cid: cid || '',
      status,
      error: { code, message },
    });
  }

  function sendAck(socket, rinfo, session) {
    sendControl(socket, rinfo, {
      v: 1,
      t: 'ack',
      cid: session.cid,
      sid: session.sid,
      status: 200,
      format: 'image/png',
      fps: 1000 / FRAME_INTERVAL_MS,
      w: STREAM_W,
    });
  }

  function attach(deviceCode, session) {
    let stream = deviceStreams.get(deviceCode);
    if (!stream) {
      stream = { timer: null, sessions: new Set(), frameSeq: 0 };
      deviceStreams.set(deviceCode, stream);
      stream.timer = setInterval(() => tick(deviceCode, stream), FRAME_INTERVAL_MS);
    }
    stream.sessions.add(session.key);
  }

  function detach(session) {
    const stream = deviceStreams.get(session.deviceCode);
    if (!stream) return;
    stream.sessions.delete(session.key);
    if (stream.sessions.size === 0) {
      clearInterval(stream.timer);
      deviceStreams.delete(session.deviceCode);
    }
  }

  function removeSession(session, reason) {
    if (sessions.get(session.key) !== session) return;
    sessions.delete(session.key);
    detach(session);
    if (reason) {
      log(`[视频流/UDP] 断开 sid=${session.sid} device=${session.deviceCode}（${reason}）`);
    }
  }

  function sendPackets(session, packets) {
    for (const packet of packets) {
      session.socket.send(packet, 0, packet.length, session.port, session.address, (err) => {
        if (!err) return;
        session.sendErrors = (session.sendErrors || 0) + 1;
        if (session.sendErrors === 1) {
          log(`[视频流/UDP] 发送失败 sid=${session.sid}: ${err.message}`);
        }
        if (session.sendErrors >= 10) removeSession(session, '发送连续失败');
      });
    }
  }

  function makeStreamFrame(deviceCode) {
    return makeFrame(undefined, STREAM_W, STREAM_H, deviceCode);
  }

  /** 给单个新 session 立即发一帧（不让首帧等到下一个 100ms tick） */
  function sendImmediateFrame(session, stream) {
    let frame;
    try {
      frame = makeStreamFrame(session.deviceCode);
    } catch (e) {
      log(`[视频流/UDP] 生成帧失败 device=${session.deviceCode}: ${e.message}`);
      return;
    }
    stream.frameSeq = (stream.frameSeq + 1) >>> 0;
    let packets;
    try {
      packets = protocol.fragmentFrame(frame, session.sid, stream.frameSeq);
    } catch (e) {
      log(`[视频流/UDP] 分片失败: ${e.message}`);
      return;
    }
    sendPackets(session, packets);
  }

  function tick(deviceCode, stream) {
    if (closed || stream.sessions.size === 0) return;
    let frame;
    try {
      frame = makeStreamFrame(deviceCode);
    } catch (e) {
      log(`[视频流/UDP] 生成帧失败 device=${deviceCode}: ${e.message}`);
      return;
    }
    stream.frameSeq = (stream.frameSeq + 1) >>> 0;
    const frameId = stream.frameSeq;
    for (const key of [...stream.sessions]) {
      const session = sessions.get(key);
      if (!session || session.deviceCode !== deviceCode) {
        stream.sessions.delete(key);
        continue;
      }
      let packets;
      try {
        packets = protocol.fragmentFrame(frame, session.sid, frameId);
      } catch (e) {
        log(`[视频流/UDP] 分片失败: ${e.message}`);
        continue;
      }
      sendPackets(session, packets);
    }
    if (stream.sessions.size === 0) {
      clearInterval(stream.timer);
      deviceStreams.delete(deviceCode);
    }
  }

  function onSub(obj, rinfo, socket) {
    const cid = typeof obj.cid === 'string' ? obj.cid.slice(0, 64) : '';
    const target = typeof obj.target === 'string' ? obj.target : '';
    let url;
    try {
      url = new URL(target, 'http://localhost');
    } catch {
      return sendErr(socket, rinfo, cid, 400, 'bad_request', '订阅 target 不是合法 URL');
    }
    if (url.pathname !== '/api/v1/device/video/stream') {
      return sendErr(socket, rinfo, cid, 404, 'not_found', `未知视频接口：${url.pathname}`);
    }
    const deviceCode = url.searchParams.get('device_code');
    if (!deviceCode) {
      return sendErr(socket, rinfo, cid, 400, 'bad_request', '缺少 device_code 查询参数');
    }

    const key = keyOf(rinfo);
    const existing = sessions.get(key);

    // 幂等重订阅：同地址:端口 + 同 cid + 同设备 → 只回 ack / 刷新 lastSeen
    if (existing && existing.cid === cid && existing.deviceCode === deviceCode) {
      existing.lastSeen = Date.now();
      existing.socket = socket;
      sendAck(socket, rinfo, existing);
      return;
    }

    // 上限：同一地址最多 8 路；总量最多 64 路（替换已有 key 不占新名额）
    const sameAddress = [...sessions.values()].filter((s) => s.address === rinfo.address).length;
    if (!existing && sessions.size >= MAX_SESSIONS) {
      return sendErr(socket, rinfo, cid, 503, 'server_busy', '视频会话数已满');
    }
    if (!existing && sameAddress >= MAX_SESSIONS_PER_ADDRESS) {
      return sendErr(socket, rinfo, cid, 503, 'server_busy', '同一地址的视频会话数已满');
    }

    if (existing) removeSession(existing, '重新订阅');

    const session = {
      key,
      sid: randomSid(),
      cid,
      socket,
      address: rinfo.address,
      port: rinfo.port,
      family: rinfo.family,
      deviceCode,
      lastSeen: Date.now(),
      sendErrors: 0,
    };
    sessions.set(key, session);
    attach(deviceCode, session);
    log(`[视频流/UDP] 接入 device=${deviceCode} sid=${session.sid}`);
    sendAck(socket, rinfo, session);
    sendImmediateFrame(session, deviceStreams.get(deviceCode));
  }

  function onHb(obj, rinfo) {
    const session = sessions.get(keyOf(rinfo));
    if (!session) return;
    if ((Number(obj.sid) >>> 0) !== session.sid) return;
    session.lastSeen = Date.now();
  }

  function onUnsub(obj, rinfo) {
    const session = sessions.get(keyOf(rinfo));
    if (!session) return;
    if ((Number(obj.sid) >>> 0) !== session.sid) return;
    removeSession(session, '客户端退订');
  }

  function onMessage(msg, rinfo, socket) {
    if (closed || msg.length === 0 || msg.length > CONTROL_MAX_BYTES) return;
    let obj;
    try {
      obj = JSON.parse(msg.toString('utf8'));
    } catch {
      return;
    }
    if (!obj || typeof obj !== 'object') return;
    switch (obj.t) {
      case 'sub':
        return onSub(obj, rinfo, socket);
      case 'hb':
        return onHb(obj, rinfo);
      case 'unsub':
        return onUnsub(obj, rinfo);
      default:
        return;
    }
  }

  function bindOne({ type, bindHost, ipv6Only }) {
    return new Promise((resolve) => {
      const options = type === 'udp6' ? { type: 'udp6', ipv6Only: !!ipv6Only } : 'udp4';
      const socket = dgram.createSocket(options);
      let settled = false;
      const finish = (value) => {
        if (settled) return;
        settled = true;
        resolve(value);
      };
      socket.on('message', (msg, rinfo) => onMessage(msg, rinfo, socket));
      socket.on('error', (err) => {
        log(`[视频流/UDP] ${type} 监听/运行错误（${bindHost}:${port}）: ${err.message}`);
        try {
          socket.close();
        } catch {
          /* ignore */
        }
        finish(null);
      });
      socket.bind(port, bindHost, () => {
        log(`[视频流/UDP] ${type} 监听 ${bindHost}:${port}`);
        finish(socket);
      });
    });
  }

  async function startSockets() {
    const hosts = [];
    if (host) {
      const literal = String(host).replace(/^\[(.*)\]$/, '$1');
      if (net.isIPv6(literal)) {
        hosts.push({ type: 'udp6', bindHost: literal, ipv6Only: true });
      } else {
        hosts.push({ type: 'udp4', bindHost: literal, ipv6Only: false });
      }
    } else {
      hosts.push({ type: 'udp4', bindHost: '0.0.0.0', ipv6Only: false });
      hosts.push({ type: 'udp6', bindHost: '::', ipv6Only: true });
    }
    for (const h of hosts) {
      const socket = await bindOne(h);
      if (socket) sockets.push(socket);
    }
    if (sockets.length === 0) {
      log('[视频流/UDP] 未启动：UDP 端口不可用（HTTP 服务仍正常运行）');
    }
  }

  const sweep = setInterval(() => {
    if (closed) return;
    const now = Date.now();
    for (const session of [...sessions.values()]) {
      if (now - session.lastSeen > SESSION_IDLE_MS) {
        removeSession(session, '心跳超时');
      }
    }
  }, 1000);
  if (typeof sweep.unref === 'function') sweep.unref();

  const ready = startSockets().catch((e) => {
    log(`[视频流/UDP] 启动失败: ${e.message}`);
  });

  return {
    ready,
    close() {
      if (closed) return;
      closed = true;
      clearInterval(sweep);
      for (const stream of deviceStreams.values()) clearInterval(stream.timer);
      deviceStreams.clear();
      sessions.clear();
      for (const socket of sockets) {
        try {
          socket.close();
        } catch {
          /* ignore */
        }
      }
    },
  };
}

module.exports = { start, STREAM_W, STREAM_H, FRAME_INTERVAL_MS };
