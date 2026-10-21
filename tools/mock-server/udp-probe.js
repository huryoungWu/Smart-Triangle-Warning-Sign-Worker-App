#!/usr/bin/env node
'use strict';

/**
 * UDP 实时视频探针（零依赖）—— 替代原来的 `curl -N .../video/stream`。
 *
 * 用法：
 *   node tools/mock-server/udp-probe.js [host] [port] [device_code] [out.png]
 *   node tools/mock-server/udp-probe.js 127.0.0.1 8080 TC-001 frame.png
 *
 * 行为：发 sub → 等 ack → 重组一帧写文件 → 发 unsub → 退出。
 * 成功退出码 0，失败 1。
 */

const dgram = require('dgram');
const fs = require('fs');
const net = require('net');
const crypto = require('crypto');
const protocol = require('./udp-protocol');

const host = process.argv[2] || '127.0.0.1';
const port = Number(process.argv[3] || 8080);
const deviceCode = process.argv[4] || 'TC-001';
const outFile = process.argv[5] || 'frame.png';

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  console.error(`端口不合法：${process.argv[3]}`);
  process.exit(1);
}

const cid = crypto.randomBytes(4).toString('hex');
const target = `/api/v1/device/video/stream?device_code=${encodeURIComponent(deviceCode)}&fps=10`;
const type = net.isIPv6(host) ? 'udp6' : 'udp4';
const socket = dgram.createSocket(type);
let sid = null;
let done = false;
const assembler = new protocol.FrameAssembler();

function send(obj) {
  const buf = Buffer.from(JSON.stringify(obj), 'utf8');
  socket.send(buf, 0, buf.length, port, host, (err) => {
    if (err) finish(1, `发送失败：${err.message}`);
  });
}

function finish(code, message) {
  if (done) return;
  done = true;
  if (message) {
    if (code === 0) console.log(message);
    else console.error(message);
  }
  if (sid != null) {
    try {
      send({ v: 1, t: 'unsub', sid });
    } catch {
      /* ignore */
    }
  }
  setTimeout(() => {
    try {
      socket.close();
    } catch {
      /* ignore */
    }
    process.exit(code);
  }, 50);
}

socket.on('error', (err) => finish(1, `UDP 错误：${err.message}`));

socket.on('message', (msg) => {
  if (done) return;
  if (msg[0] === 0x7b) { // '{'
    let obj;
    try {
      obj = JSON.parse(msg.toString('utf8'));
    } catch {
      return;
    }
    if (obj.t === 'err') {
      return finish(1, `服务端拒绝：status=${obj.status} code=${obj.error && obj.error.code} message=${obj.error && obj.error.message}`);
    }
    if (obj.t === 'ack') {
      sid = obj.sid;
      console.log(`收到 ack: sid=${sid} format=${obj.format} ${obj.w}x${obj.fps}fps`);
      return;
    }
    return;
  }

  const fragment = protocol.parseFragment(msg);
  if (!fragment) return;
  if (sid == null) return; // ack 之前不处理帧（与 APP 一致）
  if (fragment.sid !== sid) return;
  const frame = assembler.accept(fragment);
  if (!frame) return;

  try {
    fs.writeFileSync(outFile, frame);
  } catch (e) {
    return finish(1, `写文件失败 ${outFile}: ${e.message}`);
  }
  const png = frame.length >= 4 && frame[0] === 0x89 && frame[1] === 0x50 &&
    frame[2] === 0x4e && frame[3] === 0x47;
  return finish(0, `OK: ${frame.length} 字节，fragCount=${fragment.fragCount}，PNG=${png} → ${outFile}`);
});

socket.bind(0, type === 'udp6' ? '::' : '0.0.0.0', () => {
  console.log(`订阅 udp://${host}:${port}${target}`);
  send({ v: 1, t: 'sub', cid, target });
  // 1s 重发一次（UDP 下 sub 可能丢；服务端按 cid 幂等）
  const retry = setInterval(() => {
    if (done || sid != null) {
      clearInterval(retry);
      return;
    }
    send({ v: 1, t: 'sub', cid, target });
  }, 1000);
  retry.unref();
});

setTimeout(() => finish(1, '超时：5s 内未收到完整帧'), 5000);
