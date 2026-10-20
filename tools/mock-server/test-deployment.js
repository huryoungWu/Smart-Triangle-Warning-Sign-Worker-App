#!/usr/bin/env node
'use strict';

/**
 * 锥桶协同布设 Mock 验收脚本（零依赖，Node 18+）
 *
 * 覆盖：
 *  1. 正常链路：1 主 + 3 从，L=8/d=2，所有设备到达，核验后 completed
 *  2. 关键不变量：主桶到 2m 触发从1；从1到 2m 触发从2；不是主桶 4m 触发从2
 *  3. 同一作业人员只允许一个进行中事件
 *  4. 上报丢失 → 人工跳过从1 → completed_with_exception
 *
 * 运行：node tools/mock-server/test-deployment.js
 */

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const PORT = 8091;
const BASE = `http://127.0.0.1:${PORT}`;
const SERVER = path.join(__dirname, 'server.js');
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'cone-dep-test-'));
const dataFile = path.join(tmpDir, 'deployments.json');

let child;
let logs = '';

async function waitFor(fn, label, timeoutMs = 20000, intervalMs = 250) {
  const start = Date.now();
  let last;
  while (Date.now() - start < timeoutMs) {
    last = await fn();
    if (last) return last;
    await new Promise((r) => setTimeout(r, intervalMs));
  }
  throw new Error(`timeout waiting for ${label} (last=${JSON.stringify(last)})`);
}

async function req(method, urlPath, body) {
  const res = await fetch(`${BASE}${urlPath}`, {
    method,
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* ignore */ }
  return { status: res.status, json, text };
}

async function create(name, L, d, slaves) {
  const r = await req('POST', '/api/v1/deployments', {
    clientRequestId: `test-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    name,
    lane: 2,
    masterCode: 'CONE-M-001',
    slaves,
    L,
    d,
  });
  assert.strictEqual(r.status, 201, `create failed: ${r.status} ${r.text}`);
  return r.json;
}

async function status(id) {
  const r = await req('GET', `/api/v1/deployments/${id}`);
  assert.strictEqual(r.status, 200, `status failed: ${r.status} ${r.text}`);
  return r.json;
}

async function start(id) {
  const r = await req('POST', `/api/v1/deployments/${id}/start`, { confirmed: true });
  assert.strictEqual(r.status, 202, `start failed: ${r.status} ${r.text}`);
  return r.json;
}

async function verifyAll(event) {
  for (const d of event.devices) {
    const r = await req('POST', `/api/v1/deployments/${event.deploymentId}/verify`, {
      deviceCode: d.device_code, result: 'pass', note: 'test',
    });
    assert.strictEqual(r.status, 200, `verify failed: ${r.status} ${r.text}`);
  }
  const r = await req('POST', `/api/v1/deployments/${event.deploymentId}/complete`, { commandId: 'test-complete' });
  assert.strictEqual(r.status, 200, `complete failed: ${r.status} ${r.text}`);
  return r.json;
}

async function main() {
  child = spawn(process.execPath, [SERVER, String(PORT)], {
    env: { ...process.env, PORT: String(PORT), DEPLOY_DATA_FILE: dataFile, STATUS_LOG_EVERY: '999' },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.on('data', (b) => { logs += b.toString(); logs = logs.slice(-10000); });
  child.stderr.on('data', (b) => { logs += b.toString(); logs = logs.slice(-10000); });

  await waitFor(async () => {
    try { return (await req('GET', '/api/v1/devices')).status === 200; } catch { return false; }
  }, 'server ready', 10000, 200);

  console.log('1) normal chain');
  const slaves = [
    { deviceCode: 'CONE-S-001', order: 1 },
    { deviceCode: 'CONE-S-002', order: 2 },
    { deviceCode: 'CONE-S-003', order: 3 },
  ];
  let ev = await create('normal', 8, 2, slaves);
  assert.deepStrictEqual(ev.devices.map((x) => x.targetM), [8, 6, 4, 2]);
  await start(ev.deploymentId);
  let masterTriggered = false;
  let slave1Triggered = false;
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    if (!masterTriggered && s.devices[0].reachedInterval) masterTriggered = true;
    if (masterTriggered && s.devices[1].startCommand.state === 'acked') slave1Triggered = true;
    // 关键：从2 启动前，从1 必须已经到过 d（不是主桶 2d 直接触发）
    if (s.devices[2].startCommand.state === 'acked') {
      assert.ok(s.devices[1].reachedInterval, 'slave2 started before slave1 reached d');
    }
    return s.state === 'pending_verify' ? s : null;
  }, 'normal all arrived');
  assert.ok(masterTriggered && slave1Triggered, 'trigger chain did not run');
  assert.deepStrictEqual(ev.devices.map((x) => x.traveledM), [8, 6, 4, 2]);
  const done = await verifyAll(ev);
  assert.strictEqual(done.result, 'completed');
  console.log('   ok: targets [8,6,4,2], trigger chain, completed');

  console.log('2) one active event per operator');
  ev = await create('one-active', 6, 2, [{ deviceCode: 'CONE-S-001', order: 1 }]);
  await start(ev.deploymentId);
  const dup = await req('POST', '/api/v1/deployments', {
    clientRequestId: `dup-${Date.now()}`,
    name: 'dup', lane: 2, masterCode: 'CONE-M-001',
    slaves: [{ deviceCode: 'CONE-S-001', order: 1 }], L: 6, d: 2,
  });
  assert.strictEqual(dup.status, 409);
  assert.strictEqual(dup.json.error.code, 'active_deployment_exists');
  await req('POST', `/api/v1/deployments/${ev.deploymentId}/abort`, { commandId: 'abort-1', reason: 'test' });
  console.log('   ok: second create rejected, aborted');

  console.log('3) report lost -> manual skip -> completed_with_exception');
  await req('POST', '/api/v1/deployment-sim/anomaly', {
    device_code: 'CONE-S-001', type: 'report_lost', enabled: true,
  });
  ev = await create('skip', 10, 2, slaves);
  await start(ev.deploymentId);
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.devices[1].report.state === 'lost' ? s : null;
  }, 'report lost');
  assert.strictEqual(ev.devices[2].startCommand.state, 'none', 'slave2 must not start on lost report');

  const skip = await req('POST', `/api/v1/deployments/${ev.deploymentId}/skip`, {
    skipRequestId: 'skip-test-1',
    deviceCode: 'CONE-S-001',
    reason: 'test stuck',
    disposition: 'removed_from_route',
    confirmations: {
      deviceStoppedOrRemoved: true,
      nextPathSafe: true,
      understandException: true,
    },
  });
  assert.strictEqual(skip.status, 202, `skip failed: ${skip.status} ${skip.text}`);
  assert.strictEqual(skip.json.nextTriggered, 'CONE-S-002');

  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.state === 'pending_verify' ? s : null;
  }, 'skip all arrived');
  assert.strictEqual(ev.devices[1].phase, 'skipped_manual');
  assert.strictEqual(ev.devices[1].arrived, false);
  assert.strictEqual(ev.devices[1].skip.disposition, 'removed_from_route');

  const skipDone = await verifyAll(ev);
  assert.strictEqual(skipDone.result, 'completed_with_exception');
  await req('POST', '/api/v1/deployment-sim/anomaly', {
    device_code: 'CONE-S-001', type: 'report_lost', enabled: false,
  });
  console.log('   ok: no auto-skip, manual skip started slave2, completed_with_exception');

  console.log('\nALL DEPLOYMENT TESTS PASSED');
}

main()
  .catch((e) => {
    console.error('\nTEST FAILED:', e.message);
    if (logs) console.error('\n--- server log tail ---\n' + logs);
    process.exitCode = 1;
  })
  .finally(() => {
    if (child) child.kill();
    try { fs.rmSync(tmpDir, { recursive: true, force: true }); } catch { /* ignore */ }
  });
