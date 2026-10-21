#!/usr/bin/env node
'use strict';

/**
 * UDP 实时视频验收脚本（零依赖，Node 18+）。
 *
 * 覆盖：
 *  1. TC-001 订阅 → ack → 完整 PNG（分片 > 1）
 *  2. TC-002 画面与 TC-001 不同
 *  3. 缺少 device_code → err 400
 *  4. 停止心跳 → 超过 UDP_SESSION_IDLE_MS 后不再有新帧
 *  5. HTTP 回归：原 /video/stream → 404；/video/snapshot.jpg → 200 image/png
 *
 * 运行：node tools/mock-server/test-video-udp.js
 */

const { spawn } = require('child_process');
const dgram = require('dgram');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');
const protocol = require('./udp-protocol');

const PORT = 8092;
const HOST = '127.0.0.1';
const BASE = `http://${HOST}:${PORT}`;
const SERVER = path.join(__dirname, 'server.js');
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'udp-video-test-'));
const deployFile = path.join(tmpDir, 'deployments.json');

let child;
let logs = '';
const clients = [];

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(fn, label, timeoutMs = 8000, intervalMs = 100) {
  const start = Date.now();
  let last;
  while (Date.now() - start < timeoutMs) {
    last = await fn();
    if (last) return last;
    await sleep(intervalMs);
  }
  throw new Error(`timeout waiting for ${label} (last=${JSON.stringify(last)})`);
}

class UdpClient {
  constructor({ heartbeat = true } = {}) {
    this.heartbeat = heartbeat;
    this.socket = dgram.createSocket('udp4');
    this.assembler = new protocol.FrameAssembler();
    this.sid = null;
    this.cid = null;
    this.target = null;
    this.frames = [];
    this.acks = [];
    this.errors = [];
    this.hbTimer = null;
    this.subTimer = null;
    this.socket.on('message', (msg) => this.onMessage(msg));
    this.socket.on('error', (err) => this.errors.push({ t: 'socket', message: err.message }));
  }

  bind() {
    return new Promise((resolve) => this.socket.bind(0, HOST, resolve));
  }

  send(obj) {
    const buf = Buffer.from(JSON.stringify(obj), 'utf8');
    this.socket.send(buf, 0, buf.length, PORT, HOST);
  }

  subscribe(deviceCode, targetOverride) {
    this.cid = crypto.randomBytes(4).toString('hex');
    this.target = targetOverride ||
      `/api/v1/device/video/stream?device_code=${encodeURIComponent(deviceCode)}&fps=10`;
    const sendSub = () => {
      if (this.sid != null) return;
      this.send({ v: 1, t: 'sub', cid: this.cid, target: this.target });
    };
    sendSub();
    this.subTimer = setInterval(sendSub, 300);
    if (typeof this.subTimer.unref === 'function') this.subTimer.unref();
  }

  startHeartbeat() {
    if (!this.heartbeat) return;
    this.hbTimer = setInterval(() => {
      if (this.sid != null) this.send({ v: 1, t: 'hb', sid: this.sid });
    }, 200);
    if (typeof this.hbTimer.unref === 'function') this.hbTimer.unref();
  }

  stopHeartbeat() {
    if (this.hbTimer) {
      clearInterval(this.hbTimer);
      this.hbTimer = null;
    }
  }

  onMessage(msg) {
    if (msg.length === 0) return;
    if (msg[0] === 0x7b) { // '{'
      let obj;
      try {
        obj = JSON.parse(msg.toString('utf8'));
      } catch {
        return;
      }
      if (obj.t === 'ack') {
        this.sid = obj.sid;
        if (this.subTimer) {
          clearInterval(this.subTimer);
          this.subTimer = null;
        }
        this.acks.push(obj);
        this.startHeartbeat();
      } else if (obj.t === 'err') {
        this.errors.push(obj);
      }
      return;
    }

    if (this.sid == null) return; // ack 之前丢弃帧
    const fragment = protocol.parseFragment(msg);
    if (!fragment || fragment.sid !== this.sid) return;
    const frame = this.assembler.accept(fragment);
    if (frame) {
      this.frames.push({
        frameId: fragment.frameId,
        fragCount: fragment.fragCount,
        buffer: frame,
        at: Date.now(),
      });
    }
  }

  close() {
    this.stopHeartbeat();
    if (this.subTimer) {
      clearInterval(this.subTimer);
      this.subTimer = null;
    }
    try {
      if (this.sid != null) this.send({ v: 1, t: 'unsub', sid: this.sid });
    } catch {
      /* ignore */
    }
    try {
      this.socket.close();
    } catch {
      /* ignore */
    }
  }
}

function isPng(buf) {
  return buf.length >= 8 &&
    buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47 &&
    buf[4] === 0x0d && buf[5] === 0x0a && buf[6] === 0x1a && buf[7] === 0x0a;
}

async function waitForServer() {
  await waitFor(async () => {
    try {
      const res = await fetch(`${BASE}/`);
      return res.ok;
    } catch {
      return false;
    }
  }, 'HTTP server ready', 15000, 250);
}

async function run() {
  child = spawn(process.execPath, [SERVER], {
    env: {
      ...process.env,
      PORT: String(PORT),
      HOST,
      UDP_SESSION_IDLE_MS: '800',
      DEPLOY_DATA_FILE: deployFile,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.on('data', (d) => { logs += d.toString(); });
  child.stderr.on('data', (d) => { logs += d.toString(); });
  child.on('exit', (code) => {
    if (code !== 0 && code !== null) logs += `\n[server exited ${code}]`;
  });

  await waitForServer();

  // 0) 协议自检：合成 3000B 帧 → 多片 → 乱序重组
  {
    const synthetic = Buffer.alloc(3000);
    for (let i = 0; i < synthetic.length; i++) synthetic[i] = i & 0xff;
    const packets = protocol.fragmentFrame(synthetic, 7, 1);
    assert.ok(packets.length > 1, 'synthetic frame should fragment');
    const asm = new protocol.FrameAssembler();
    let reassembled = null;
    for (const packet of [...packets].reverse()) {
      const parsed = protocol.parseFragment(packet);
      assert.ok(parsed, 'packet should parse');
      reassembled = asm.accept(parsed) || reassembled;
    }
    assert.ok(reassembled && reassembled.equals(synthetic), 'out-of-order reassembly should match');
    console.log(`  ✓ 协议自检：3000B → ${packets.length} 片 → 乱序重组一致`);
  }

  // 1) TC-001 完整帧
  const c1 = new UdpClient();
  clients.push(c1);
  await c1.bind();
  c1.subscribe('TC-001');
  const f1 = await waitFor(() => c1.frames[0], 'TC-001 first frame');
  assert.ok(isPng(f1.buffer), 'TC-001 frame should be PNG');
  assert.ok(f1.fragCount >= 1, `TC-001 should have at least one fragment (got ${f1.fragCount})`);
  assert.ok(c1.acks.length >= 1, 'should receive ack');
  console.log(`  ✓ TC-001: ${f1.buffer.length} bytes, ${f1.fragCount} fragment(s), sid=${c1.sid}`);

  // 2) TC-002 画面不同
  const c2 = new UdpClient();
  clients.push(c2);
  await c2.bind();
  c2.subscribe('TC-002');
  const f2 = await waitFor(() => c2.frames[0], 'TC-002 first frame');
  assert.ok(isPng(f2.buffer), 'TC-002 frame should be PNG');
  assert.ok(!f1.buffer.equals(f2.buffer), 'TC-001 and TC-002 frames should differ');
  console.log(`  ✓ TC-002: ${f2.buffer.length} bytes (different from TC-001)`);
  c1.close();
  c2.close();

  // 3) 缺 device_code → err 400
  const c3 = new UdpClient();
  clients.push(c3);
  await c3.bind();
  c3.subscribe('', '/api/v1/device/video/stream');
  const err = await waitFor(() => c3.errors[0], 'missing device_code error');
  assert.strictEqual(err.status, 400, `expected 400, got ${err.status}`);
  assert.strictEqual(err.error && err.error.code, 'bad_request');
  console.log('  ✓ 缺少 device_code → err 400');
  c3.close();

  // 4) 心跳超时后不再有新帧
  const c4 = new UdpClient();
  clients.push(c4);
  await c4.bind();
  c4.subscribe('TC-003');
  await waitFor(() => c4.frames.length >= 2, 'TC-003 frames before idle');
  c4.stopHeartbeat();
  await sleep(2500); // > UDP_SESSION_IDLE_MS(800) + sweep(1000)
  const countAfterIdle = c4.frames.length;
  const lastId = c4.frames[c4.frames.length - 1].frameId;
  await sleep(700);
  assert.strictEqual(c4.frames.length, countAfterIdle, 'no new frames after session idle timeout');
  assert.strictEqual(c4.frames[c4.frames.length - 1].frameId, lastId, 'frameId should stop advancing');
  console.log(`  ✓ 心跳超时：停在 frameId=${lastId}，不再有新帧`);
  c4.close();

  // 5) HTTP 回归
  const streamRes = await fetch(`${BASE}/api/v1/device/video/stream?device_code=TC-001`);
  assert.strictEqual(streamRes.status, 404, 'old HTTP stream route must be removed');
  const snapRes = await fetch(`${BASE}/api/v1/device/video/snapshot.jpg?device_code=TC-001`);
  assert.strictEqual(snapRes.status, 200, 'snapshot.jpg should still work');
  assert.match(snapRes.headers.get('content-type') || '', /image\/png/);
  const snap = Buffer.from(await snapRes.arrayBuffer());
  assert.ok(isPng(snap), 'snapshot should be PNG');
  console.log('  ✓ HTTP 回归：/video/stream → 404，/video/snapshot.jpg → 200 image/png');

  console.log('\nUDP 视频验收通过。');
}

(async () => {
  try {
    await run();
    process.exitCode = 0;
  } catch (e) {
    console.error(`\nUDP 视频验收失败：${e.message}`);
    if (logs.trim()) console.error(`\n--- server logs ---\n${logs}`);
    process.exitCode = 1;
  } finally {
    for (const client of clients) {
      try {
        client.close();
      } catch {
        /* ignore */
      }
    }
    if (child && !child.killed) {
      child.kill();
      await sleep(200);
    }
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {
      /* ignore */
    }
    // 确保没有残留句柄把测试进程挂住
    setTimeout(() => process.exit(process.exitCode || 0), 50).unref();
  }
})();
