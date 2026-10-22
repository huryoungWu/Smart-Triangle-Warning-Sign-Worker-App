#!/usr/bin/env node
'use strict';

/**
 * 锥桶协同布设 Mock 验收脚本（零依赖，Node 18+）
 *
 * 覆盖：
 *  0. device_type / binding_code 一致性校验
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
// 纯函数：一步跨多个转弯点的确定性测试
const { _advanceAlongRoute } = require('./deployment.js');

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

const MASTER = {
  device_code: 'CONE-M-001',
  device_type: 'master_cone',
  binding_code: '123456',
};

function slave(code, order) {
  return { device_code: code, device_type: 'slave_cone', binding_code: '123456', order };
}

async function create(name, L, d, slaves, turnOrTurns) {
  const body = {
    clientRequestId: `test-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    name,
    lane: 2,
    master: MASTER,
    slaves,
    L,
    d,
  };
  // 数组 = 多转弯 turns；对象 = 单转弯旧字段 turn
  if (Array.isArray(turnOrTurns)) body.turns = turnOrTurns;
  else if (turnOrTurns) body.turn = turnOrTurns;
  const r = await req('POST', '/api/v1/deployments', body);
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

async function pause(id) {
  const r = await req('POST', `/api/v1/deployments/${id}/pause`, { commandId: 'pause-test', reason: 'manual_test' });
  assert.strictEqual(r.status, 202, `pause failed: ${r.status} ${r.text}`);
  return r.json;
}

async function resume(id) {
  const r = await req('POST', `/api/v1/deployments/${id}/resume`, { commandId: 'resume-test', confirmed: true });
  assert.strictEqual(r.status, 202, `resume failed: ${r.status} ${r.text}`);
  return r.json;
}

async function manualMove(id, device, dir, sessionId, seq) {
  return req('POST', `/api/v1/deployments/${id}/manual/move`, {
    commandId: `manual-${sessionId}-${seq}`,
    sessionId,
    seq,
    device_code: device.device_code,
    device_type: device.device_type,
    dir,
  });
}

async function manualStop(id, device, sessionId, seq) {
  return req('POST', `/api/v1/deployments/${id}/manual/stop`, {
    commandId: `manual-stop-${sessionId}-${seq}`,
    sessionId,
    seq,
    device_code: device.device_code,
    device_type: device.device_type,
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function verifyAll(event) {
  for (const d of event.devices) {
    const r = await req('POST', `/api/v1/deployments/${event.deploymentId}/verify`, {
      device_code: d.device_code, device_type: d.device_type, result: 'pass', note: 'test',
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

  console.log('0.0) route step across multiple turns (pure function)');
  const routeDep = {
    id: 'dep-pure',
    turns: [{ dir: 'left', atM: 1 }, { dir: 'right', atM: 2 }],
    steps: [],
  };
  const routeDd = {
    device_code: 'T', targetM: 3, traveledM: 0,
    posX: 0, posY: 0, headingDeg: 0, nextTurnIndex: 0,
  };
  _advanceAlongRoute(routeDd, routeDep, 3);
  assert.ok(Math.abs(routeDd.posX + 1) < 1e-9, `x=${routeDd.posX}`);
  assert.ok(Math.abs(routeDd.posY - 2) < 1e-9, `y=${routeDd.posY}`);
  assert.strictEqual(routeDd.headingDeg, 0, 'left then right restores north');
  assert.strictEqual(routeDd.nextTurnIndex, 2);
  assert.strictEqual(routeDep.steps.filter((s) => s.type === 'turn').length, 2);

  // target == atM：停在拐角，不转
  const stopDd = {
    device_code: 'T', targetM: 1, traveledM: 0,
    posX: 0, posY: 0, headingDeg: 0, nextTurnIndex: 0,
  };
  const stopDep = { id: 'dep-pure-stop', turns: [{ dir: 'left', atM: 1 }], steps: [] };
  _advanceAlongRoute(stopDd, stopDep, 1);
  assert.strictEqual(stopDd.headingDeg, 0);
  assert.strictEqual(stopDd.nextTurnIndex, 0);
  assert.ok(Math.abs(stopDd.posX) < 1e-9 && Math.abs(stopDd.posY - 1) < 1e-9);

  // 手动已越过两处转弯：resume 时先补齐，且不会重复转
  const resumeDd = {
    device_code: 'T', targetM: 5, traveledM: 2.5,
    posX: 1, posY: 1.5, headingDeg: 0, nextTurnIndex: 0,
  };
  const resumeDep = {
    id: 'dep-pure-resume',
    turns: [{ dir: 'left', atM: 1 }, { dir: 'right', atM: 2 }],
    steps: [],
  };
  _advanceAlongRoute(resumeDd, resumeDep, 0.5);
  assert.strictEqual(resumeDd.nextTurnIndex, 2);
  assert.strictEqual(resumeDd.headingDeg, 0);
  assert.strictEqual(resumeDep.steps.filter((s) => s.type === 'turn').length, 2);
  _advanceAlongRoute(resumeDd, resumeDep, 0.5);
  assert.strictEqual(resumeDd.nextTurnIndex, 2, 'turns must not be re-applied');
  assert.strictEqual(resumeDep.steps.filter((s) => s.type === 'turn').length, 2);
  console.log('   ok: one step crosses two turns; target==atM no turn; resume applies once');

  console.log('0) device_type / binding_code validation');
  const listed = await req('GET', '/api/v1/devices?device_type=master_cone');
  assert.strictEqual(listed.status, 200);
  assert.strictEqual(listed.json.devices[0].device_type, 'master_cone');
  assert.strictEqual(listed.json.devices[0].binding_code, '123456');

  const badBinding = await req('POST', '/api/v1/deployments', {
    clientRequestId: 'bad-binding', name: 'bad', lane: 2,
    master: { ...MASTER, binding_code: 'wrong' },
    slaves: [slave('CONE-S-001', 1)], L: 8, d: 2,
  });
  assert.strictEqual(badBinding.status, 400);
  assert.strictEqual(badBinding.json.error.code, 'invalid_binding_code');

  const badType = await req('POST', '/api/v1/deployments', {
    clientRequestId: 'bad-type', name: 'bad', lane: 2,
    master: MASTER,
    slaves: [{ ...slave('CONE-S-001', 1), device_type: 'master_cone' }], L: 8, d: 2,
  });
  assert.strictEqual(badType.status, 400);
  assert.strictEqual(badType.json.error.code, 'invalid_device_type');
  console.log('   ok: device_type/binding_code rejected when inconsistent');

  console.log('0.1) turn validation');
  // 必须在任何有效事件之前：createDeployment 先查 activeDeployment，再校验 turn
  for (const [label, turn] of [
    ['bad dir', { dir: 'up', atM: 3 }],
    ['missing atM', { dir: 'left' }],
    ['atM <= 0', { dir: 'left', atM: 0 }],
    ['atM >= L', { dir: 'left', atM: 8 }],
    ['bad precision', { dir: 'left', atM: 3.14 }],
    ['custom deg', { dir: 'left', atM: 3, deg: 45 }],
  ]) {
    const r = await req('POST', '/api/v1/deployments', {
      clientRequestId: `bad-turn-${Math.random().toString(36).slice(2, 8)}`,
      name: 'bad-turn', lane: 2, master: MASTER, slaves: [], L: 8, d: 1,
      turn,
    });
    assert.strictEqual(r.status, 400, `${label}: expected 400, got ${r.status} ${r.text}`);
    assert.strictEqual(r.json.error.code, 'invalid_turn', `${label}: ${r.text}`);
  }
  for (const [label, extra] of [
    ['turns not array', { turns: { dir: 'left', atM: 3 } }],
    ['bad dir in turns', { turns: [{ dir: 'up', atM: 3 }] }],
    ['not increasing', { turns: [{ dir: 'left', atM: 4 }, { dir: 'right', atM: 3 }] }],
    ['duplicate atM', { turns: [{ dir: 'left', atM: 3 }, { dir: 'right', atM: 3 }] }],
    ['too many turns', { turns: Array.from({ length: 6 }, (_, i) => ({ dir: 'left', atM: i + 1 })) }],
    ['atM >= L', { turns: [{ dir: 'left', atM: 8 }] }],
    ['turn and turns together', {
      turn: { dir: 'left', atM: 3 },
      turns: [{ dir: 'left', atM: 3 }],
    }],
  ]) {
    const r = await req('POST', '/api/v1/deployments', {
      clientRequestId: `bad-turns-${Math.random().toString(36).slice(2, 8)}`,
      name: 'bad-turns', lane: 2, master: MASTER, slaves: [], L: 8, d: 1,
      ...extra,
    });
    assert.strictEqual(r.status, 400, `${label}: expected 400, got ${r.status} ${r.text}`);
    assert.strictEqual(r.json.error.code, 'invalid_turn', `${label}: ${r.text}`);
  }
  console.log('   ok: invalid turn(s) rejected (dir/atM/range/precision/deg/order/count)');

  console.log('1) normal chain');
  const slaves = [slave('CONE-S-001', 1), slave('CONE-S-002', 2), slave('CONE-S-003', 3)];
  let ev = await create('normal', 8, 2, slaves);
  assert.strictEqual(ev.params.turn, null, 'straight deployment must echo turn=null');
  assert.deepStrictEqual(ev.params.turns, [], 'straight deployment must echo turns=[]');
  assert.deepStrictEqual(ev.devices.map((x) => x.targetM), [8, 6, 4, 2]);
  assert.deepStrictEqual(
    ev.devices.map((x) => x.device_type),
    ['master_cone', 'slave_cone', 'slave_cone', 'slave_cone'],
  );
  assert.deepStrictEqual(ev.devices.map((x) => x.binding_code), ['123456', '123456', '123456', '123456']);
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

  console.log('1.1) master only (N=0)');
  ev = await create('master-only', 2, 1, []);
  assert.strictEqual(ev.devices.length, 1, 'master-only event should contain only the master');
  assert.strictEqual(ev.devices[0].device_type, 'master_cone');
  assert.strictEqual(ev.params.N, 0);
  await start(ev.deploymentId);
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.state === 'pending_verify' ? s : null;
  }, 'master-only arrived');
  const masterOnlyDone = await verifyAll(ev);
  assert.strictEqual(masterOnlyDone.result, 'completed');
  console.log('   ok: N=0 allowed, master alone completed');

  console.log('2) one active event per operator');
  ev = await create('one-active', 6, 2, [slave('CONE-S-001', 1)]);
  await start(ev.deploymentId);
  const dup = await req('POST', '/api/v1/deployments', {
    clientRequestId: `dup-${Date.now()}`,
    name: 'dup', lane: 2, master: MASTER,
    slaves: [slave('CONE-S-001', 1)], L: 6, d: 2,
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
    device_code: 'CONE-S-001',
    device_type: 'slave_cone',
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

  console.log('4) manual jog (paused only, four-way)');
  ev = await create('manual', 10, 2, [slave('CONE-S-001', 1), slave('CONE-S-002', 2)]);
  let r = await manualMove(ev.deploymentId, MASTER, 'fwd', 'hold-running', 1);
  assert.strictEqual(r.status, 409);
  assert.strictEqual(r.json.error.code, 'deployment_not_paused');
  await start(ev.deploymentId);
  await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.devices[0].phase === 'moving' && s.devices[0].traveledM > 0.2 ? s : null;
  }, 'manual master moving');
  await pause(ev.deploymentId);
  await pause(ev.deploymentId); // 暂停全部在任何非终止状态都可重复调用（幂等）
  let paused = await status(ev.deploymentId);
  assert.strictEqual(paused.state, 'paused');
  assert.strictEqual(paused.devices[0].phase, 'stopped');
  const masterBefore = paused.devices[0];
  const slaveBefore = paused.devices[1];

  const session = 'hold-1';
  let seq = 1;
  for (let i = 0; i < 7; i++) {
    r = await manualMove(ev.deploymentId, MASTER, 'fwd', session, seq++);
    assert.strictEqual(r.status, 202, `manual move failed: ${r.status} ${r.text}`);
    await sleep(300);
  }
  let mid = await status(ev.deploymentId);
  assert.ok(mid.devices[0].traveledM > masterBefore.traveledM + 0.2, 'master should move manually');
  assert.ok(mid.devices[0].pos.y > masterBefore.pos.y, 'pos.y should advance');
  assert.ok(Math.abs(mid.devices[1].traveledM - slaveBefore.traveledM) < 1e-9, 'slave must not move');
  assert.strictEqual(mid.devices[0].manualActive, true);

  r = await manualMove(ev.deploymentId, slave('CONE-S-001', 1), 'fwd', 'other', 1);
  assert.strictEqual(r.status, 409);
  assert.strictEqual(r.json.error.code, 'manual_busy');

  r = await req('POST', '/api/v1/device/command', {
    device_code: MASTER.device_code, type: 'move', dir: 'fwd',
  });
  assert.strictEqual(r.status, 200);
  assert.strictEqual(r.json.accepted, false);
  assert.strictEqual(r.json.reason, 'deployment_managed');

  r = await manualStop(ev.deploymentId, MASTER, session, seq++);
  assert.strictEqual(r.status, 202);
  await sleep(500);
  let stopped = await status(ev.deploymentId);
  assert.strictEqual(stopped.devices[0].manualActive, false);
  const stoppedM = stopped.devices[0].traveledM;
  await sleep(500);
  stopped = await status(ev.deploymentId);
  assert.ok(Math.abs(stopped.devices[0].traveledM - stoppedM) < 1e-9, 'manual stop should freeze');

  // stop 之后迟到的同 session 保活不能重启
  r = await manualMove(ev.deploymentId, MASTER, 'fwd', session, seq - 1);
  assert.strictEqual(r.status, 202);
  await sleep(400);
  const late = await status(ev.deploymentId);
  assert.strictEqual(late.devices[0].manualActive, false);
  assert.ok(Math.abs(late.devices[0].traveledM - stoppedM) < 1e-9, 'late keepalive must be ignored');

  // left turn changes heading; fwd then changes pos.x as well as pos.y
  const headingBefore = late.devices[0].headingDeg;
  const session2 = 'hold-2';
  let seq2 = 1;
  for (let i = 0; i < 3; i++) {
    r = await manualMove(ev.deploymentId, MASTER, 'left', session2, seq2++);
    assert.strictEqual(r.status, 202, r.text);
    await sleep(200);
  }
  await manualStop(ev.deploymentId, MASTER, session2, seq2++);
  const turned = await status(ev.deploymentId);
  assert.notStrictEqual(turned.devices[0].headingDeg, headingBefore, 'heading should change');
  const beforeFwd = turned.devices[0];
  const session3 = 'hold-3';
  let seq3 = 1;
  for (let i = 0; i < 3; i++) {
    r = await manualMove(ev.deploymentId, MASTER, 'fwd', session3, seq3++);
    assert.strictEqual(r.status, 202, r.text);
    await sleep(200);
  }
  await manualStop(ev.deploymentId, MASTER, session3, seq3++);
  const afterFwd = await status(ev.deploymentId);
  assert.ok(Math.abs(afterFwd.devices[0].pos.x - beforeFwd.pos.x) > 0.03, 'pos.x should change after turn');

  // 暂停中越过 d 不触发后继
  const dcheck = await status(ev.deploymentId);
  assert.strictEqual(dcheck.devices[0].reachedInterval, false);
  assert.strictEqual(dcheck.devices[1].startCommand.state, 'none');
  await resume(ev.deploymentId);
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.devices[1].startCommand.state === 'acked' ? s : null;
  }, 'slave1 started after resume');
  await req('POST', `/api/v1/deployments/${ev.deploymentId}/abort`, { commandId: 'abort-manual', reason: 'test' });
  console.log('   ok: paused-only jog, four-way, busy/stale guards, no trigger while paused, resume trigger');

  console.log('5) manual to target -> resume pending_verify');
  ev = await create('manual-target', 2, 1, []);
  await start(ev.deploymentId);
  await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.devices[0].phase === 'moving' ? s : null;
  }, 'target master moving');
  await pause(ev.deploymentId);
  const targetSession = 'hold-target';
  let targetSeq = 1;
  for (let i = 0; i < 30; i++) {
    r = await manualMove(ev.deploymentId, MASTER, 'fwd', targetSession, targetSeq++);
    if (r.status === 409) {
      assert.strictEqual(r.json.error.code, 'device_at_target');
      break;
    }
    assert.strictEqual(r.status, 202, r.text);
    await sleep(150);
    const s = await status(ev.deploymentId);
    if (s.devices[0].traveledM >= s.devices[0].targetM - 1e-9) break;
  }
  await manualStop(ev.deploymentId, MASTER, targetSession, targetSeq++);
  let targetState = await status(ev.deploymentId);
  assert.ok(targetState.devices[0].traveledM >= targetState.devices[0].targetM - 1e-9, 'target reached');
  assert.strictEqual(targetState.devices[0].phase, 'stopped');
  assert.strictEqual(targetState.devices[0].arrived, false);
  await resume(ev.deploymentId);
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.state === 'pending_verify' ? s : null;
  }, 'manual target pending_verify');
  const targetDone = await verifyAll(ev);
  assert.strictEqual(targetDone.result, 'completed');
  console.log('   ok: target reached while paused, resume -> pending_verify');

  console.log('6) automatic left turn (single 90° corner)');
  ev = await create('left-turn', 8, 2, slaves, { dir: 'left', atM: 4 });
  assert.deepStrictEqual(ev.params.turn, { dir: 'left', atM: 4, deg: 90 });
  assert.deepStrictEqual(ev.params.turns, [{ dir: 'left', atM: 4, deg: 90 }],
    'legacy turn must also be exposed as turns[]');
  await start(ev.deploymentId);
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.state === 'pending_verify' ? s : null;
  }, 'left turn all arrived');
  assert.deepStrictEqual(ev.devices.map((x) => x.traveledM), [8, 6, 4, 2]);
  // 左转后 pos.x 为负；targetM == atM 的从2 在拐角处停车，不转（heading=0）
  assert.deepStrictEqual(
    ev.devices.map((x) => [x.pos.x, x.pos.y]),
    [[-4, 4], [-2, 4], [0, 4], [0, 2]],
  );
  assert.deepStrictEqual(ev.devices.map((x) => x.headingDeg), [270, 270, 0, 0]);
  await verifyAll(ev);
  console.log('   ok: left corner at 4m, target==atM stops without turning');

  console.log('7) automatic right turn at d');
  ev = await create('right-turn', 6, 2, [slave('CONE-S-001', 1)], { dir: 'right', atM: 2 });
  await start(ev.deploymentId);
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.state === 'pending_verify' ? s : null;
  }, 'right turn all arrived');
  // 右转后 pos.x 为正；转弯点与 d 同距离，触发链仍必须正常
  assert.deepStrictEqual(ev.devices.map((x) => [x.pos.x, x.pos.y]), [[4, 2], [2, 2]]);
  assert.deepStrictEqual(ev.devices.map((x) => x.headingDeg), [90, 90]);
  await verifyAll(ev);
  console.log('   ok: right corner at d, heading 90, trigger chain intact');

  console.log('8) manual crossing of the corner then resume turns once');
  ev = await create('manual-turn', 5, 1, [], { dir: 'left', atM: 2 });
  await start(ev.deploymentId);
  await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.devices[0].phase === 'moving' && s.devices[0].traveledM > 0.2 ? s : null;
  }, 'turn master moving');
  await pause(ev.deploymentId);
  const turnSession = 'hold-turn';
  let turnSeq = 1;
  for (let i = 0; i < 20; i++) {
    r = await manualMove(ev.deploymentId, MASTER, 'fwd', turnSession, turnSeq++);
    if (r.status === 409) break;
    assert.strictEqual(r.status, 202, r.text);
    await sleep(150);
    const s = await status(ev.deploymentId);
    if (s.devices[0].traveledM >= 2.5) break;
  }
  await manualStop(ev.deploymentId, MASTER, turnSession, turnSeq++);
  const beforeResume = (await status(ev.deploymentId)).devices[0];
  assert.ok(beforeResume.traveledM > 2.0, 'manual crossing should pass atM');
  assert.strictEqual(beforeResume.headingDeg, 0, 'manual fwd keeps heading before resume');
  await resume(ev.deploymentId);
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.state === 'pending_verify' ? s : null;
  }, 'manual turn resume arrived');
  const manualTurn = ev.devices[0];
  assert.strictEqual(manualTurn.headingDeg, 270);
  assert.ok(manualTurn.pos.x < -2.0, `expected westward second leg, got pos=${JSON.stringify(manualTurn.pos)}`);
  assert.ok(manualTurn.pos.y >= beforeResume.pos.y - 0.05, 'must not teleport back to the nominal corner');
  await verifyAll(ev);
  console.log('   ok: manual crossing applies planned turn once on resume, no teleport');

  console.log('9) multi-turn S shape (left then right)');
  ev = await create('s-shape', 12, 3, [slave('CONE-S-001', 1), slave('CONE-S-002', 2)], [
    { dir: 'left', atM: 4 },
    { dir: 'right', atM: 8 },
  ]);
  assert.deepStrictEqual(ev.params.turns, [
    { dir: 'left', atM: 4, deg: 90 },
    { dir: 'right', atM: 8, deg: 90 },
  ]);
  assert.strictEqual(ev.params.turn, null, 'multi-turn must not expose a single-turn alias');
  await start(ev.deploymentId);
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.state === 'pending_verify' ? s : null;
  }, 'S shape all arrived');
  assert.deepStrictEqual(ev.devices.map((x) => x.targetM), [12, 9, 6]);
  // 主桶 N4→W4→N4；从1 N4→W4→N1；从2 N4→W2（第二转弯点在其目标之后，不转）
  assert.deepStrictEqual(
    ev.devices.map((x) => [x.pos.x, x.pos.y]),
    [[-4, 8], [-4, 5], [-2, 4]],
  );
  assert.deepStrictEqual(ev.devices.map((x) => x.headingDeg), [0, 0, 270]);
  await verifyAll(ev);
  console.log('   ok: S shape positions/headings; turn beyond slave target skipped');

  console.log('10) two left turns -> heading 180');
  ev = await create('double-left', 8, 1, [], [
    { dir: 'left', atM: 2 },
    { dir: 'left', atM: 4 },
  ]);
  await start(ev.deploymentId);
  ev = await waitFor(async () => {
    const s = await status(ev.deploymentId);
    return s.state === 'pending_verify' ? s : null;
  }, 'double left arrived');
  // N2 → W2 → S4
  assert.deepStrictEqual([ev.devices[0].pos.x, ev.devices[0].pos.y], [-2, -2]);
  assert.strictEqual(ev.devices[0].headingDeg, 180);
  await verifyAll(ev);
  console.log('   ok: consecutive left turns compose to south');

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
