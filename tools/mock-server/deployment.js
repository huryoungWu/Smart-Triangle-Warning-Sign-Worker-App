'use strict';

/**
 * 锥桶协同布设 —— Mock 协同会话（零依赖）
 *
 * 与 server.js 共用设备 Map（通过 init 注入），但**独立保存会话**，不改动单设备任务状态机。
 *
 * 设计要点（对应 docs/锥桶协同布设功能需求规格说明书.md）：
 *  - 服务端中继：主桶 → 服务端 → 从1；从k上报 → 服务端 → 主桶/代发 → 从k+1；
 *  - 触发只由“设备自身累计行程达到 d”的边沿产生，不能用主桶 2d 触发从2；
 *  - 每台设备到达自身 targetM 才停车；通过 d 后继续移动；
 *  - 不自动跳过；人工跳过仅异常阻断时、仅从桶、需确认与处置方式；
 *  - 一个作业人员同一时间只允许一个非终态会话；
 *  - 会话落盘到 data/deployments.json，重启可恢复。
 */

const fs = require('fs');
const path = require('path');

const LIMITS = {
  nMax: 5,
  lMin: 1,
  lMax: 200,
  dMax: 50,
  precision: 0.1,
  speed: 1.0,
  slowSpeed: 0.5,
  turnDps: 60,
  /** v1 自动路线固定转弯角度 */
  turnDeg: 90,
  /** 自动路线最多允许的转弯次数 */
  maxTurns: 5,
  manualWatchdogMs: 800,
  readyTtlMs: 30 * 60 * 1000,
  persistEveryMs: 2000,
};

const MANUAL_DIRS = new Set(['fwd', 'back', 'left', 'right']);

const TERMINAL = new Set(['completed', 'aborted', 'failed']);
const SKIP_DISPOSITIONS = new Set(['placed_manually', 'removed_from_route', 'not_required']);

let ctx = null;
const deployments = new Map();
let seq = 1;
let lastPersistAt = 0;
const anomalies = new Map(); // deviceCode → Set(type)

function log(...args) {
  (ctx && typeof ctx.log === 'function' ? ctx.log : console.log)(...args);
}

function init(c) {
  ctx = c;
  load();
  applyEnvAnomalies();
  // 恢复后让 tick 从当前时间继续，避免把停机时间算成行驶距离
  const now = Date.now();
  for (const dep of deployments.values()) {
    for (const dd of dep.devices) dd.lastTick = now;
  }
}

function nid(p) {
  return `${p}-${(seq++).toString(36)}${Math.random().toString(36).slice(2, 5)}`;
}

function dataFile() {
  // 测试可用 DEPLOY_DATA_FILE 指向临时文件，避免污染本地联调数据
  return process.env.DEPLOY_DATA_FILE || path.join(ctx.dataDir, 'deployments.json');
}

function persist() {
  try {
    fs.mkdirSync(ctx.dataDir, { recursive: true });
    const out = {
      seq,
      deployments: [...deployments.values()],
    };
    fs.writeFileSync(dataFile(), JSON.stringify(out));
    lastPersistAt = Date.now();
  } catch (e) {
    console.warn(`  [部署持久化] 写盘失败: ${e.message}`);
  }
}

function load() {
  try {
    const st = JSON.parse(fs.readFileSync(dataFile(), 'utf8'));
    seq = Math.max(seq, Number(st.seq) || 1);
    for (const dep of st.deployments || []) {
      if (!dep || !dep.id) continue;
      // 兼容旧落盘：role → device_type，masterCode → master_code，并补齐 binding_code
      if (dep.masterCode && !dep.master_code) dep.master_code = dep.masterCode;
      // 兼容旧落盘：单个 turn → turns 数组；非法项丢弃并按 atM 去重排序
      const rawTurns = Array.isArray(dep.turns)
        ? dep.turns
        : (dep.turn && typeof dep.turn === 'object' ? [dep.turn] : []);
      let lastAt = -Infinity;
      dep.turns = rawTurns
        .filter((t) => t && typeof t === 'object' &&
          (t.dir === 'left' || t.dir === 'right') &&
          Number.isFinite(t.atM) && t.atM > 0 &&
          (!Number.isFinite(dep.L) || t.atM < dep.L - 1e-9))
        .map((t) => ({ dir: t.dir, atM: t.atM }))
        .sort((a, b) => a.atM - b.atM)
        .filter((t) => {
          if (t.atM <= lastAt + 1e-9) return false;
          lastAt = t.atM;
          return true;
        });
      delete dep.turn;
      for (const dd of dep.devices || []) {
        if (!dd.device_type && dd.role) {
          dd.device_type = dd.role === 'master' ? 'master_cone'
            : dd.role === 'slave' ? 'slave_cone' : dd.role;
        }
        if (!dd.binding_code) {
          const g = ctx.devices.get(dd.device_code);
          if (g) dd.binding_code = g.bindingCode;
        }
        // 旧落盘没有 pose：按原语义（正北投影）迁移；重启不自动续跑手动
        if (!Number.isFinite(dd.posX)) dd.posX = 0;
        if (!Number.isFinite(dd.posY)) dd.posY = Number(dd.traveledM) || 0;
        if (!Number.isFinite(dd.headingDeg)) dd.headingDeg = 0;
        // 旧数据只有 turnApplied；新数据用 nextTurnIndex。只读不写回旧字段。
        if (Number.isFinite(dd.nextTurnIndex)) {
          dd.nextTurnIndex = Math.max(0, Math.min(dep.turns.length, Math.floor(dd.nextTurnIndex)));
        } else {
          dd.nextTurnIndex = dd.turnApplied === true ? Math.min(1, dep.turns.length) : 0;
        }
        delete dd.turnApplied;
        dd.manual = null;
        dd.lastManual = null;
      }
      for (const a of dep.anomalies || []) {
        if (!a.device_code && a.deviceCode) a.device_code = a.deviceCode;
      }
      deployments.set(dep.id, dep);
    }
    // 恢复占用与设备上的部署视图：READY 未启动、不占用设备
    for (const dep of deployments.values()) {
      if (TERMINAL.has(dep.state) || dep.state === 'ready') continue;
      for (const dd of dep.devices) {
        const g = ctx.getDevice(dd.device_code);
        g.occupied = true;
        g.occupiedByEvent = dep.id;
        g.deployment = deploymentView(dd, dep);
        g.pos = { x: dd.posX, y: dd.posY };
        if (g.task) g.task.heading = dd.headingDeg;
      }
    }
    if (deployments.size) {
      console.log(`  已从 data/ 恢复布设会话 ${deployments.size} 个`);
    }
  } catch {
    /* 首次运行或文件损坏：忽略 */
  }
}

function applyEnvAnomalies() {
  const raw = process.env.DEPLOY_ANOMALIES || '';
  for (const item of raw.split(',')) {
    const [code, type] = item.split(':').map((s) => s && s.trim());
    if (code && type) setAnomaly(code, type, true);
  }
  if (anomalies.size) {
    console.log(`  [部署模拟] 异常注入: ${[...anomalies.entries()].map(([k, v]) => `${k}=${[...v].join('+')}`).join(', ')}`);
  }
}

function setAnomaly(deviceCode, type, enabled) {
  if (!anomalies.has(deviceCode)) anomalies.set(deviceCode, new Set());
  const set = anomalies.get(deviceCode);
  if (enabled) set.add(type); else set.delete(type);
  if (!set.size) anomalies.delete(deviceCode);
  const g = ctx.devices.get(deviceCode);
  if (g && type === 'offline') g.online = !enabled;
}

function hasAnomaly(deviceCode, type) {
  return anomalies.get(deviceCode)?.has(type) === true;
}

function clearAnomalies(deviceCode) {
  anomalies.delete(deviceCode);
}

function round1(v) {
  return Math.round(v * 10) / 10;
}

function isPrecisionOk(v) {
  return Math.abs(v * 10 - Math.round(v * 10)) < 1e-6;
}

function deviceOf(dd) {
  return ctx.devices.get(dd.device_code) || ctx.getDevice(dd.device_code);
}

function byOrder(dep, order) {
  return dep.devices.find((x) => x.order === order);
}

function byCode(dep, code) {
  return dep.devices.find((x) => x.device_code === code);
}

/** 控制类请求统一按 device_code + device_type 定位设备（二者都必须与事件内记录一致） */
function targetDevice(dep, body) {
  const code = String(body.device_code || '');
  const deviceType = String(body.device_type || '');
  const dd = byCode(dep, code);
  if (!dd) return { error: { status: 404, code: 'not_found', message: '设备不在本事件中' } };
  if (deviceType !== dd.device_type) {
    return {
      error: {
        status: 400,
        code: 'invalid_device_type',
        message: `device_type 与事件设备不一致：${code}（应为 ${dd.device_type}）`,
      },
    };
  }
  return { dd };
}

function isActive(dep) {
  return !TERMINAL.has(dep.state);
}

function isAvailable(d, owner) {
  return (
    d &&
    (d.owner || ctx.defaultUser) === owner &&
    d.online !== false &&
    d.available !== false &&
    !d.occupied &&
    d.estopLocked !== true &&
    !(d.task && d.task.state === 'running')
  );
}

function conflictReason(d, owner) {
  if (!d) return 'unknown_device';
  if ((d.owner || ctx.defaultUser) !== owner) return 'unknown_device';
  if (d.estopLocked) return 'estop_locked';
  if (d.task && d.task.state === 'running') return 'device_busy';
  if (d.online === false) return 'device_offline';
  if (d.occupied) return 'device_occupied';
  return null;
}

function expireReady(owner) {
  const now = Date.now();
  for (const dep of deployments.values()) {
    if (dep.owner !== owner) continue;
    if (dep.state === 'ready' && now - (dep.createdAt || 0) > LIMITS.readyTtlMs) {
      dep.state = 'aborted';
      dep.abortedAt = now;
      dep.abortReason = 'ready_ttl_expired';
      log(`[布设] READY 超时自动取消 ${dep.id}`);
      persist();
    }
  }
}

function activeDeployment(owner) {
  for (const dep of deployments.values()) {
    if (dep.owner === owner && isActive(dep)) return dep;
  }
  return null;
}

function addAnomaly(dep, dd, code, message) {
  if (dd.anomaly && dd.anomaly.code === code) return;
  dd.anomaly = { code, message };
  if (!dep.anomalies.some((a) => a.device_code === dd.device_code && a.code === code)) {
    dep.anomalies.push({ device_code: dd.device_code, code, message, detectedAt: Date.now() });
  }
  log(`[布设] 异常 ${dep.id} ${dd.device_code} ${code}${message ? ' ' + message : ''}`);
}

function clearAnomaly(dep, dd, code) {
  if (dd.anomaly && (!code || dd.anomaly.code === code)) dd.anomaly = null;
  dep.anomalies = dep.anomalies.filter((a) => !(a.device_code === dd.device_code && (!code || a.code === code)));
}

function addStep(dep, step) {
  dep.steps.push({ seq: dep.steps.length + 1, at: Date.now(), ...step });
}

function deploymentView(dd, dep) {
  return {
    id: dep.id,
    device_code: dd.device_code,
    device_type: dd.device_type,
    binding_code: dd.binding_code,
    order: dd.order,
    targetM: dd.targetM,
    traveledM: dd.traveledM,
    phase: dd.phase,
    reachedInterval: dd.reachedInterval,
    arrived: dd.arrived,
    skipped: dd.phase === 'skipped_manual',
    posX: round2(dd.posX),
    posY: round2(dd.posY),
    headingDeg: round2(dd.headingDeg),
    manualActive: !!dd.manual,
  };
}

function updateGlobal(dd, dep) {
  const g = deviceOf(dd);
  if (!g) return;
  g.pos = { x: dd.posX, y: dd.posY };
  if (g.task) g.task.heading = dd.headingDeg;
  g.deployment = deploymentView(dd, dep);
  g.occupied = true;
  g.occupiedByEvent = dep.id;
}

function round2(v) {
  return Math.round(Number(v || 0) * 100) / 100;
}

function releaseAll(dep) {
  for (const dd of dep.devices) {
    const g = deviceOf(dd);
    dd.manual = null;
    if (!g) continue;
    g.occupied = false;
    g.occupiedByEvent = null;
    g.deployment = null;
    g.motion = 'idle';
    g.mode = 'standby';
    g.odo.speedMps = 0;
  }
}

function stopDevice(dd, dep) {
  const g = deviceOf(dd);
  if (dd.phase === 'moving') dd.phase = 'stopped';
  if (g) {
    g.motion = 'idle';
    g.mode = 'standby';
    g.odo.speedMps = 0;
  }
  updateGlobal(dd, dep);
}

function startDevice(dep, order, cause, causeReportId, commandId) {
  const dd = byOrder(dep, order);
  if (!dd) return false;
  if (dd.phase !== 'not_started') return true; // 幂等：已启动/已跳过
  const g = deviceOf(dd);
  if (!g || g.online === false) {
    addAnomaly(dep, dd, 'device_offline', '设备离线，无法下发启动指令');
    return false;
  }
  const now = Date.now();
  const cmdId = commandId || nid('cmd');

  if (hasAnomaly(dd.device_code, 'reject')) {
    dd.startCommand = { state: 'rejected', commandId: cmdId, sentAt: now, ackedAt: null, reason: 'device_rejected' };
    dd.phase = 'failed';
    addAnomaly(dep, dd, 'start_rejected', '设备拒绝启动指令');
    addStep(dep, { type: 'start', from: cause === 'start' ? 'server' : dep.master_code, to: dd.device_code, cause, causeReportId: causeReportId || null, commandId: cmdId, state: 'rejected' });
    updateGlobal(dd, dep);
    return false;
  }
  if (hasAnomaly(dd.device_code, 'ack_timeout')) {
    dd.startCommand = { state: 'timeout', commandId: cmdId, sentAt: now, ackedAt: null, reason: null };
    dd.phase = 'start_sent';
    addAnomaly(dep, dd, 'start_timeout', '启动指令超时，结果未知');
    addStep(dep, { type: 'start', from: cause === 'start' ? 'server' : dep.master_code, to: dd.device_code, cause, causeReportId: causeReportId || null, commandId: cmdId, state: 'timeout' });
    updateGlobal(dd, dep);
    return false;
  }

  dd.startCommand = { state: 'acked', commandId: cmdId, sentAt: now, ackedAt: now, reason: null };
  dd.lastTick = now;
  dd.reachedInterval = false;
  dd.arrived = dd.targetM <= 0.001;
  if (dd.targetM <= 0.001) {
    dd.phase = 'no_move';
    dd.report = { state: 'not_applicable', seq: dd.order, sentAt: now, ackedAt: now };
    g.motion = 'idle';
    g.mode = 'standby';
    addStep(dep, { type: 'start', from: cause === 'start' ? 'server' : dep.master_code, to: dd.device_code, cause, causeReportId: causeReportId || null, commandId: cmdId, state: 'acked', noMove: true });
    addStep(dep, { type: 'arrived', from: dd.device_code, to: null, state: 'acked', noMove: true });
  } else {
    dd.phase = 'moving';
    dd.report = { state: 'waiting', seq: dd.order, sentAt: null, ackedAt: null };
    g.motion = 'moving';
    g.mode = 'auto';
    addStep(dep, { type: 'start', from: cause === 'start' ? 'server' : dep.master_code, to: dd.device_code, cause, causeReportId: causeReportId || null, commandId: cmdId, state: 'acked' });
  }
  updateGlobal(dd, dep);
  log(`[布设] ${dep.id} 启动 ${dd.device_code} order=${dd.order} target=${dd.targetM} cause=${cause}${causeReportId ? ' causeReport=' + causeReportId : ''}`);
  checkComplete(dep);
  persist();
  return true;
}

function checkComplete(dep) {
  if (dep.state !== 'running') return;
  const done = dep.devices.every((dd) =>
    dd.arrived ||
    (dd.phase === 'skipped_manual' && dd.skip && SKIP_DISPOSITIONS.has(dd.skip.disposition)),
  );
  if (!done) return;
  dep.state = 'pending_verify';
  addStep(dep, { type: 'pending_verify', from: 'server', to: null, state: 'done' });
  log(`[布设] ${dep.id} 全部设备到达/处置完成 → 待现场核验`);
  persist();
}

function stopManual(dd, dep, reason) {
  const g = deviceOf(dd);
  const active = dd.manual;
  dd.manual = null;
  if (active) {
    dd.lastManual = { sessionId: active.sessionId, seq: active.seq };
    addStep(dep, {
      type: reason === 'manual_timeout' ? 'manual_timeout' : 'manual_stop',
      from: 'operator',
      to: dd.device_code,
      state: reason,
      sessionId: active.sessionId,
    });
  }
  if (g) {
    g.motion = 'idle';
    g.mode = 'standby';
    g.odo.speedMps = 0;
  }
  updateGlobal(dd, dep);
  persist();
}

function manualMove(dep, body) {
  if (dep.state !== 'paused') {
    return { error: { status: 409, code: 'deployment_not_paused', message: '请先暂停全部锥桶再手动干预' } };
  }
  const t = targetDevice(dep, body);
  if (t.error) return t;
  const dd = t.dd;
  const g = deviceOf(dd);
  if (!g || g.online === false) {
    return { error: { status: 409, code: 'device_offline', message: `设备 ${dd.device_code} 离线` } };
  }
  if (g.estopLocked || g.mode === 'safe_stop') {
    return { error: { status: 409, code: 'estop_locked', message: `设备 ${dd.device_code} 急停锁定/安全停止` } };
  }
  // 同一事件同一时刻只允许一只桶手动（优先返回 busy，避免第二只桶先报自身状态）
  const other = dep.devices.find((x) => x !== dd && x.manual);
  if (other) {
    return { error: { status: 409, code: 'manual_busy', message: `设备 ${other.device_code} 正在手动中` } };
  }
  if (dd.phase === 'skipped_manual' || (dd.skip && dd.skip.skipped)) {
    return { error: { status: 409, code: 'device_skipped', message: `设备 ${dd.device_code} 已跳过` } };
  }
  if (dd.arrived) {
    return { error: { status: 409, code: 'device_not_jogging', message: `设备 ${dd.device_code} 已到位，不能手动点动` } };
  }
  if (dd.phase !== 'stopped') {
    return { error: { status: 409, code: 'device_not_jogging', message: `设备 ${dd.device_code} 当前状态 ${dd.phase}，不能手动点动` } };
  }
  if (dd.traveledM >= dd.targetM - 1e-9) {
    return { error: { status: 409, code: 'device_at_target', message: `设备 ${dd.device_code} 已到目标` } };
  }
  const dir = String(body.dir || '');
  if (!MANUAL_DIRS.has(dir)) {
    return { error: { status: 400, code: 'invalid_direction', message: 'dir 必须为 fwd/back/left/right' } };
  }
  const sessionId = String(body.sessionId || '');
  if (!sessionId) return { error: { status: 400, code: 'bad_request', message: '缺少 sessionId' } };
  const seq = Number(body.seq);
  if (!Number.isFinite(seq) || seq < 0) {
    return { error: { status: 400, code: 'bad_request', message: 'seq 必须是非负数字' } };
  }

  if (dd.manual && dd.manual.sessionId === sessionId && seq <= dd.manual.seq) {
    return { dd }; // 重复/乱序保活
  }
  if (dd.manual && dd.manual.sessionId !== sessionId) {
    // 同一只桶的新按住可以接管旧 session（旧的 stop 可能还在路上；旧 stop 会被 session 校验忽略）
    stopManual(dd, dep, 'manual_replaced');
  }
  if (!dd.manual && dd.lastManual && dd.lastManual.sessionId === sessionId && seq <= dd.lastManual.seq) {
    return { dd }; // stop 之后迟到的保活，不重启
  }

  const now = Date.now();
  if (!dd.manual) {
    dd.manual = { dir, sessionId, seq, lastAt: now, lastTick: now };
    addStep(dep, {
      type: 'manual_move',
      from: dep.owner || 'operator',
      to: dd.device_code,
      dir,
      sessionId,
      state: 'active',
    });
  } else {
    dd.manual.dir = dir;
    dd.manual.seq = seq;
    dd.manual.lastAt = now;
  }
  g.motion = 'moving';
  g.mode = 'manual';
  updateGlobal(dd, dep);
  return { dd };
}

function manualStop(dep, body) {
  const t = targetDevice(dep, body);
  if (t.error) return t;
  const dd = t.dd;
  const sessionId = String(body.sessionId || '');
  const seq = Number(body.seq);
  if (dd.manual) {
    // 旧 session 的迟到 stop 不得停掉新按住
    if (sessionId && dd.manual.sessionId !== sessionId) return { dd, ignored: true };
    stopManual(dd, dep, 'manual_stopped');
    dd.lastManual = {
      sessionId: dd.lastManual ? dd.lastManual.sessionId : sessionId,
      seq: Number.isFinite(seq) ? Math.max(seq, dd.lastManual ? dd.lastManual.seq : 0) : (dd.lastManual ? dd.lastManual.seq : 0),
    };
    persist();
    return { dd };
  }
  // 幂等：没有 active 手动。只有 paused 时才清理运动状态，running/terminal 不碰自动 tick
  if (dep.state === 'paused') {
    const g = deviceOf(dd);
    if (g) {
      g.motion = 'idle';
      g.mode = 'standby';
      g.odo.speedMps = 0;
    }
    updateGlobal(dd, dep);
  }
  // 即使 stop 先于第一条 move 到达，也要记住 session/seq，拒绝随后迟到的保活包
  if (sessionId) {
    dd.lastManual = {
      sessionId,
      seq: Number.isFinite(seq)
        ? Math.max(seq, dd.lastManual ? dd.lastManual.seq : 0)
        : (dd.lastManual ? dd.lastManual.seq : 0),
    };
    persist();
  }
  return { dd };
}

function tickManual(dep, now) {
  for (const dd of dep.devices) {
    const m = dd.manual;
    if (!m) continue;
    const g = deviceOf(dd);
    if (!g || g.online === false) {
      stopManual(dd, dep, 'device_offline');
      continue;
    }
    if (g.estopLocked || g.mode === 'safe_stop') {
      stopManual(dd, dep, 'estop_locked');
      continue;
    }
    if (now - m.lastAt > LIMITS.manualWatchdogMs) {
      stopManual(dd, dep, 'manual_timeout');
      continue;
    }
    let dt = (now - (m.lastTick || now)) / 1000;
    m.lastTick = now;
    if (dt <= 0) continue;
    if (dt > 1) dt = 1;

    if (m.dir === 'left' || m.dir === 'right') {
      const delta = LIMITS.turnDps * dt;
      dd.headingDeg = (dd.headingDeg + (m.dir === 'left' ? -delta : delta) + 360) % 360;
      if (g.task) g.task.heading = dd.headingDeg;
      g.motion = 'moving';
      g.mode = 'manual';
      updateGlobal(dd, dep);
      continue;
    }

    const sign = m.dir === 'back' ? -1 : 1;
    const remaining = sign > 0 ? Math.max(0, dd.targetM - dd.traveledM) : dd.traveledM;
    const step = Math.min(LIMITS.speed * dt, remaining);
    if (step <= 0) {
      stopManual(dd, dep, sign > 0 ? 'manual_reached_target' : 'manual_stopped');
      continue;
    }
    const rad = (dd.headingDeg * Math.PI) / 180;
    dd.traveledM = Math.max(0, Math.min(dd.targetM, dd.traveledM + sign * step));
    dd.posX += sign * step * Math.sin(rad);
    dd.posY += sign * step * Math.cos(rad);
    g.odo.traveledM += Math.abs(step);
    g.odo.leftPulse += Math.round(Math.abs(step) * 500);
    g.odo.rightPulse += Math.round(Math.abs(step) * 500);
    g.odo.speedMps = LIMITS.speed;
    g.motion = 'moving';
    g.mode = 'manual';
    if (sign > 0 && dd.traveledM >= dd.targetM - 1e-9) {
      stopManual(dd, dep, 'manual_reached_target');
      continue;
    }
    updateGlobal(dd, dep);
  }
  if (now - lastPersistAt > LIMITS.persistEveryMs) persist();
}

function tick() {
  const now = Date.now();
  for (const dep of deployments.values()) {
    if (dep.state === 'paused') {
      tickManual(dep, now);
      continue;
    }
    if (dep.state !== 'running') continue;
    for (const dd of dep.devices) {
      if (dd.phase !== 'moving') continue;
      const g = deviceOf(dd);
      if (!g || g.online === false) {
        addAnomaly(dep, dd, 'device_offline', '设备离线，链路阻断');
        stopDevice(dd, dep);
        continue;
      }
      let dt = (now - (dd.lastTick || now)) / 1000;
      dd.lastTick = now;
      if (dt <= 0) continue;
      if (dt > 5) dt = 5;
      const speed = hasAnomaly(dd.device_code, 'slow') ? LIMITS.slowSpeed : LIMITS.speed;
      const remaining = Math.max(0, dd.targetM - dd.traveledM);
      const step = Math.min(speed * dt, remaining);
      advanceAlongRoute(dd, dep, step);
      g.odo.traveledM += step;
      g.odo.leftPulse += Math.round(step * 500);
      g.odo.rightPulse += Math.round(step * 500);
      g.odo.speedMps = speed;
      g.pos = { x: dd.posX, y: dd.posY };
      g.motion = 'moving';
      g.mode = 'auto';

      // 本地 d 边沿：只触发一次（轮询/多次 tick 不重复）
      if (!dd.reachedInterval && dd.traveledM >= dep.d - 1e-9) {
        dd.reachedInterval = true;
        dd.report.sentAt = now;
        if (hasAnomaly(dd.device_code, 'report_lost')) {
          dd.report.state = 'lost';
          addAnomaly(dep, dd, 'report_lost', '已达到启动间隔，但上报丢失');
          addStep(dep, { type: 'report', from: dd.device_code, to: dd.order === 0 ? null : dep.master_code, reportId: reportId(dep, dd), state: 'lost' });
        } else {
          dd.report.state = 'acked';
          dd.report.ackedAt = now;
          addStep(dep, { type: 'report', from: dd.device_code, to: dd.order === 0 ? null : dep.master_code, reportId: reportId(dep, dd), state: 'acked' });
          if (dd.order === 0) {
            startDevice(dep, 1, 'master_interval', null);
          } else if (dd.order < dep.N) {
            startDevice(dep, dd.order + 1, 'report', reportId(dep, dd));
          }
        }
      }

      // 到达自身目标：停车；超程模拟时继续一小段
      if (dd.traveledM >= dd.targetM - 1e-9 && !dd.arrived) {
        dd.arrived = true;
        if (hasAnomaly(dd.device_code, 'overshoot') && !dd.overshootUntil) {
          dd.overshootUntil = dd.targetM + 2.0;
          addAnomaly(dep, dd, 'overshoot', '设备越过目标位置');
        }
        if (!dd.overshootUntil || dd.traveledM >= dd.overshootUntil) {
          dd.phase = 'arrived';
          g.motion = 'idle';
          g.mode = 'standby';
          g.odo.speedMps = 0;
          addStep(dep, { type: 'arrived', from: dd.device_code, to: null, state: 'acked', traveledM: round1(dd.traveledM) });
          checkComplete(dep);
        }
      } else if (dd.overshootUntil && dd.traveledM >= dd.overshootUntil) {
        dd.phase = 'arrived';
        g.motion = 'idle';
        g.mode = 'standby';
        g.odo.speedMps = 0;
        addStep(dep, { type: 'arrived', from: dd.device_code, to: null, state: 'acked', traveledM: round1(dd.traveledM) });
        checkComplete(dep);
      }
      updateGlobal(dd, dep);
    }
  }
  if (now - lastPersistAt > LIMITS.persistEveryMs) persist();
}

/**
 * 计划朝向序列：第一段 0°，之后每处 left 减 90°、right 加 90°（归一化 0..360）。
 * 注意：dir 是相对**计划中的前一段**，不是设备手动转过的实际 heading；
 * 到转弯点时会按计划绝对朝向纠正（与单转弯版本一致）。
 */
function plannedHeadings(turns) {
  let heading = 0;
  return turns.map((t) => {
    heading = (heading + (t.dir === 'left' ? -90 : 90) + 360) % 360;
    return heading;
  });
}

function addTurnStep(dd, dep, turn, index, headingDeg) {
  addStep(dep, {
    type: 'turn',
    from: dd.device_code,
    to: null,
    turnIndex: index + 1,
    dir: turn.dir,
    atM: turn.atM,
    deg: LIMITS.turnDeg,
    headingDeg,
    state: 'done',
  });
}

/**
 * 沿自动路线推进 step 米（只更新 traveledM/pos/heading；odo/触发/到位由 tick 统一处理）。
 *
 * - 无 turns：沿用当前 heading 直行，保持旧行为；
 * - 有 turns：按 atM 顺序推进，到达每个转弯点时应用预计算的绝对朝向；
 * - 一次 step 可跨过多处转弯；
 * - nextTurnIndex 单向：手动倒退跨过已转点不会反转/重转；手动前进跨过未转点时，
 *   恢复自动后先补齐这些转弯再继续；
 * - 目标 targetM <= atM 的桶在拐角前/拐角处停车，不应用该转弯及后续转弯。
 */
function advanceAlongRoute(dd, dep, step) {
  if (step <= 0) return;
  const turns = Array.isArray(dep.turns) ? dep.turns : [];
  if (turns.length === 0) {
    moveByHeading(dd, dd.headingDeg, step);
    return;
  }
  const planned = plannedHeadings(turns);

  // 手动前进可能已越过一个/多个转弯点：仅在本次确实要移动时补齐
  while (dd.nextTurnIndex < turns.length) {
    const t = turns[dd.nextTurnIndex];
    if (dd.targetM <= t.atM + 1e-9) break;
    if (dd.traveledM < t.atM - 1e-9) break;
    dd.headingDeg = planned[dd.nextTurnIndex];
    addTurnStep(dd, dep, t, dd.nextTurnIndex, dd.headingDeg);
    dd.nextTurnIndex += 1;
  }

  let remaining = step;
  while (remaining > 1e-9) {
    const i = dd.nextTurnIndex;
    const t = turns[i];
    // 目标没有越过该转弯点（后续转弯点只会更远）：直线走到目标
    if (!t || dd.targetM <= t.atM + 1e-9) {
      moveByHeading(dd, dd.headingDeg, remaining);
      return;
    }
    const toCorner = t.atM - dd.traveledM;
    if (toCorner > 1e-9) {
      const d = Math.min(remaining, toCorner);
      moveByHeading(dd, dd.headingDeg, d);
      remaining -= d;
      if (dd.traveledM < t.atM - 1e-9) return;
    }
    dd.headingDeg = planned[i];
    addTurnStep(dd, dep, t, i, dd.headingDeg);
    dd.nextTurnIndex += 1;
  }
}

function moveByHeading(dd, headingDeg, distance) {
  if (distance <= 0) return;
  const rad = (headingDeg * Math.PI) / 180;
  dd.posX += distance * Math.sin(rad);
  dd.posY += distance * Math.cos(rad);
  dd.traveledM += distance;
}

function reportId(dep, dd) {
  return `${dep.id}:${dd.device_code}:interval:${dd.order}`;
}

function validateCreate(body, owner) {
  const errors = [];

  // 主桶：{ device_code, device_type=master_cone, binding_code }
  const rawMaster = body.master && typeof body.master === 'object' ? body.master : {};
  const master = {
    device_code: String(rawMaster.device_code || '').trim(),
    device_type: String(rawMaster.device_type || '').trim(),
    binding_code: String(rawMaster.binding_code || '').trim(),
  };
  if (!master.device_code) errors.push({ code: 'invalid_params', message: 'master.device_code 不能为空' });
  if (master.device_type !== 'master_cone') {
    errors.push({ code: 'invalid_device_type', message: 'master.device_type 必须为 master_cone' });
  }
  if (!master.binding_code) errors.push({ code: 'invalid_binding_code', message: 'master.binding_code 不能为空' });

  // 从桶：每只都要 device_code / device_type=slave_cone / binding_code / order
  const rawSlaves = Array.isArray(body.slaves) ? body.slaves : [];
  const slaves = rawSlaves.map((s, i) => ({
    device_code: String(s.device_code || '').trim(),
    device_type: String(s.device_type || '').trim(),
    binding_code: String(s.binding_code || '').trim(),
    order: Number(s.order ?? i + 1),
  }));
  const n = slaves.length;
  // N=0 合法：仅主桶执行；上限仍为 nMax
  if (n > LIMITS.nMax) errors.push({ code: 'invalid_params', message: `从桶数量不能超过 ${LIMITS.nMax}` });
  const codes = new Set();
  const orders = new Set();
  slaves.forEach((s, i) => {
    const label = s.device_code || `#${i + 1}`;
    if (!s.device_code) errors.push({ code: 'invalid_params', message: `从桶 #${i + 1} 的 device_code 不能为空` });
    if (s.device_type !== 'slave_cone') {
      errors.push({ code: 'invalid_device_type', message: `从桶 ${label} 的 device_type 必须为 slave_cone` });
    }
    if (!s.binding_code) {
      errors.push({ code: 'invalid_binding_code', message: `从桶 ${label} 的 binding_code 不能为空` });
    }
    if (!Number.isInteger(s.order) || s.order < 1 || s.order > n) errors.push({ code: 'duplicate_order', message: '从桶顺序必须为连续的 1..N' });
    if (codes.has(s.device_code)) errors.push({ code: 'invalid_params', message: `从桶重复：${s.device_code}` });
    if (orders.has(s.order)) errors.push({ code: 'duplicate_order', message: `从桶顺序重复：${s.order}` });
    codes.add(s.device_code);
    orders.add(s.order);
  });
  if (codes.has(master.device_code)) errors.push({ code: 'invalid_device_type', message: '主桶不能同时作为从桶' });

  const L = Number(body.L);
  const d = Number(body.d);
  if (!Number.isFinite(L) || !Number.isFinite(d)) {
    errors.push({ code: 'invalid_params', message: 'L/d 必须是数字' });
  } else {
    if (!isPrecisionOk(L) || !isPrecisionOk(d)) errors.push({ code: 'invalid_params', message: 'L/d 精度最多 0.1 m' });
    if (L < LIMITS.lMin || L > LIMITS.lMax) errors.push({ code: 'invalid_params', message: `L 必须在 ${LIMITS.lMin}~${LIMITS.lMax} m` });
    if (d <= 0 || d > LIMITS.dMax) errors.push({ code: 'invalid_params', message: `d 必须大于 0 且不超过 ${LIMITS.dMax} m` });
    if (L < n * d - 1e-9) errors.push({ code: 'invalid_params', message: `L 必须 ≥ N×d（${L} < ${n}×${d}）` });
  }
  // 可选多处 90° 转弯：turns = [{dir, atM}, ...]；旧字段 turn 作为单元素兼容
  const turns = validateTurns(body, Number.isFinite(L) ? L : null, errors);

  const lane = normalizeLane(body.lane);
  if (lane === null || Number.isNaN(lane)) errors.push({ code: 'invalid_params', message: '作业车道必须为 1~8 的整数' });

  // 设备校验：device_type 与 binding_code 必须与登记信息一致
  const masterDev = ctx.devices.get(master.device_code);
  if (!masterDev) {
    errors.push({ code: 'unknown_device', message: `主桶不存在：${master.device_code}` });
  } else {
    if (masterDev.deviceType !== 'master_cone') {
      errors.push({ code: 'invalid_device_type', message: `主桶 device_type 不正确：${master.device_code}` });
    }
    if (!masterDev.bindingCode || masterDev.bindingCode !== master.binding_code) {
      errors.push({ code: 'invalid_binding_code', message: `主桶绑定码不正确：${master.device_code}` });
    }
  }
  for (const s of slaves) {
    const dev = ctx.devices.get(s.device_code);
    if (!dev) {
      errors.push({ code: 'unknown_device', message: `从桶不存在：${s.device_code}` });
      continue;
    }
    if (dev.deviceType !== 'slave_cone') {
      errors.push({ code: 'invalid_device_type', message: `从桶 device_type 不正确：${s.device_code}` });
    }
    if (!dev.bindingCode || dev.bindingCode !== s.binding_code) {
      errors.push({ code: 'invalid_binding_code', message: `从桶绑定码不正确：${s.device_code}` });
    }
  }
  if (errors.length) return { errors };

  const conflicts = [];
  for (const code of [master.device_code, ...slaves.map((s) => s.device_code)]) {
    const reason = conflictReason(ctx.devices.get(code), owner);
    if (reason) conflicts.push({ device_code: code, reason });
  }
  return { master, slaves, n, L: round1(L), d: round1(d), lane, turns, conflicts };
}

/**
 * 校验可选多处自动转弯参数。
 *
 * 请求形态（二选一，不能同时出现）：
 * - 新：`turns: [{ dir: 'left'|'right', atM: number }, ...]`
 * - 旧：`turn: { dir, atM }`（等价于单元素 turns）
 *
 * - 缺省/null/空数组 → 直行（返回 []）
 * - atM 为从起点沿路线累计到拐角的距离，0.1m 精度，严格递增，且 0 < atM < L
 * - v1 每处固定 90°；显式传非 90 的 deg 直接拒绝
 * - 最多 LIMITS.maxTurns 处
 */
function validateTurns(body, L, errors) {
  const rawTurns = body.turns;
  const hasTurn = body.turn !== undefined && body.turn !== null;
  const hasNonEmptyTurns = Array.isArray(rawTurns) && rawTurns.length > 0;
  if (hasNonEmptyTurns && hasTurn) {
    errors.push({ code: 'invalid_turn', message: 'turn 与 turns 不能同时出现' });
    return [];
  }
  let raw;
  if (hasNonEmptyTurns) {
    raw = rawTurns;
  } else if (hasTurn) {
    raw = [body.turn];
  } else if (rawTurns !== undefined && rawTurns !== null && !Array.isArray(rawTurns)) {
    errors.push({ code: 'invalid_turn', message: 'turns 必须是数组' });
    return [];
  } else {
    // 缺省 / null / 空数组 = 直行
    return [];
  }
  if (raw.length === 0) return [];
  if (raw.length > LIMITS.maxTurns) {
    errors.push({ code: 'invalid_turn', message: `转弯不能超过 ${LIMITS.maxTurns} 处` });
    return [];
  }
  const turns = [];
  let prevAt = 0;
  raw.forEach((item, i) => {
    const label = `第 ${i + 1} 个转弯`;
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      errors.push({ code: 'invalid_turn', message: `${label}必须是对象 { dir, atM }` });
      return;
    }
    const dir = String(item.dir || '').trim().toLowerCase();
    if (dir !== 'left' && dir !== 'right') {
      errors.push({ code: 'invalid_turn', message: `${label}的 dir 必须为 left 或 right` });
    }
    if (item.deg !== undefined && item.deg !== null && Number(item.deg) !== LIMITS.turnDeg) {
      errors.push({ code: 'invalid_turn', message: `${label}的 deg 当前固定为 ${LIMITS.turnDeg}，请勿自定义` });
    }
    const atM = Number(item.atM);
    if (!Number.isFinite(atM)) {
      errors.push({ code: 'invalid_turn', message: `${label}的 atM 必须是数字` });
      return;
    }
    if (!isPrecisionOk(atM)) errors.push({ code: 'invalid_turn', message: `${label}的 atM 精度最多 0.1 m` });
    if (atM <= 0) errors.push({ code: 'invalid_turn', message: `${label}的 atM 必须大于 0` });
    if (L !== null && Number.isFinite(L) && atM >= L - 1e-9) {
      errors.push({ code: 'invalid_turn', message: `${label}的 atM 必须小于 L（${atM} ≥ ${L}）` });
    }
    if (atM <= prevAt + 1e-9) {
      errors.push({ code: 'invalid_turn', message: `${label}的 atM 必须大于前一个转弯点（${prevAt} m）` });
    }
    prevAt = atM;
    if (dir === 'left' || dir === 'right') turns.push({ dir, atM: round1(atM) });
  });
  return turns;
}

function normalizeLane(v) {
  if (v === undefined || v === null || v === '') return null;
  const n = Number(v);
  return Number.isInteger(n) && n >= 1 && n <= 8 ? n : NaN;
}

function createDeployment(body, owner) {
  expireReady(owner);
  const clientRequestId = body.clientRequestId || body.client_request_id || null;
  if (clientRequestId) {
    for (const dep of deployments.values()) {
      if (dep.owner === owner && dep.clientRequestId === clientRequestId) return { existing: dep };
    }
  }
  const active = activeDeployment(owner);
  if (active) return { error: { status: 409, code: 'active_deployment_exists', message: '你有一个进行中的布设事件，请先完成或终止后再新建', detail: { deploymentId: active.id } } };
  const v = validateCreate(body, owner);
  if (v.errors) {
    const first = v.errors[0];
    return { error: { status: first.code === 'unknown_device' ? 404 : 400, code: first.code, message: first.message, detail: { errors: v.errors } } };
  }
  if (v.conflicts.length) {
    return { error: { status: 409, code: v.conflicts[0].reason, message: '部分设备不可用', detail: { conflicts: v.conflicts } } };
  }
  const now = Date.now();
  const dep = {
    id: nid('dep'),
    clientRequestId,
    eventId: body.eventId || null,
    name: String(body.name || '锥桶协同布设').slice(0, 30),
    lane: v.lane,
    owner,
    state: 'ready',
    result: null,
    L: v.L,
    d: v.d,
    N: v.n,
    /** 可选多处 90° 转弯；空数组 = 直行（旧事件） */
    turns: v.turns,
    master_code: v.master.device_code,
    note: String(body.note || '').slice(0, 100),
    createdAt: now,
    startedAt: null,
    completedAt: null,
    devices: [
      {
        device_code: v.master.device_code,
        device_type: 'master_cone',
        binding_code: v.master.binding_code,
        order: 0,
        targetM: v.L,
        traveledM: 0,
        posX: 0,
        posY: 0,
        headingDeg: 0,
        nextTurnIndex: 0,
        manual: null,
        lastManual: null,
        phase: 'not_started',
        lastTick: now,
        startCommand: { state: 'none', commandId: null, sentAt: null, ackedAt: null, reason: null },
        report: { state: 'none', seq: 0, sentAt: null, ackedAt: null },
        trigger: null,
        reachedInterval: false,
        arrived: false,
        overshootUntil: null,
        skip: null,
        verify: { state: 'not_done', note: null, verifiedAt: null },
        anomaly: null,
      },
      ...v.slaves.sort((a, b) => a.order - b.order).map((s) => ({
        device_code: s.device_code,
        device_type: 'slave_cone',
        binding_code: s.binding_code,
        order: s.order,
        targetM: round1(v.L - s.order * v.d),
        traveledM: 0,
        posX: 0,
        posY: 0,
        headingDeg: 0,
        nextTurnIndex: 0,
        manual: null,
        lastManual: null,
        phase: 'not_started',
        lastTick: now,
        startCommand: { state: 'none', commandId: null, sentAt: null, ackedAt: null, reason: null },
        report: { state: 'none', seq: s.order, sentAt: null, ackedAt: null },
        trigger: null,
        reachedInterval: false,
        arrived: false,
        overshootUntil: null,
        skip: null,
        verify: { state: 'not_done', note: null, verifiedAt: null },
        anomaly: null,
      })),
    ],
    steps: [],
    anomalies: [],
  };
  deployments.set(dep.id, dep);
  persist();
  log(`[布设] 创建 ${dep.id} owner=${owner} ${dep.master_code} + ${dep.N} 从 L=${dep.L} d=${dep.d} lane=${dep.lane}`);
  return { dep };
}

function startDeployment(dep, body) {
  if (dep.state !== 'ready') return { status: 409, code: 'deployment_state_invalid', message: `当前状态 ${dep.state} 不能启动` };
  const conflicts = [];
  for (const dd of dep.devices) {
    const reason = conflictReason(deviceOf(dd), dep.owner);
    if (reason) conflicts.push({ device_code: dd.device_code, reason });
  }
  if (conflicts.length) {
    return { status: 409, code: conflicts[0].reason, message: '部分设备不可用', detail: { conflicts } };
  }
  dep.state = 'starting';
  dep.startedAt = Date.now();
  for (const dd of dep.devices) {
    const g = deviceOf(dd);
    dd.posX = 0;
    dd.posY = 0;
    dd.headingDeg = 0;
    dd.nextTurnIndex = 0;
    dd.manual = null;
    dd.lastManual = null;
    g.occupied = true;
    g.occupiedByEvent = dep.id;
    g.pos = { x: 0, y: 0 };
    g.odo = { leftPulse: 0, rightPulse: 0, traveledM: 0, speedMps: 0, diffPulse: 0 };
    g.task = { id: null, kind: null, source: 'app', targetM: 0, traveledM: 0, state: 'idle', stage: null, resultNote: null, heading: 0, lane: dep.lane, segments: [] };
    g.deployment = deploymentView(dd, dep);
    g.online = g.online !== false;
  }
  const ok = startDevice(dep, 0, 'start', null);
  if (!ok) {
    const master = byOrder(dep, 0);
    if (master.phase === 'failed') {
      dep.state = 'failed';
      dep.completedAt = Date.now();
      releaseAll(dep);
      persist();
      return { status: 409, code: 'start_rejected', message: '主桶拒绝启动指令', detail: { device_code: master.device_code } };
    }
    // ack 超时/离线：保持 starting，等待核对/重试/终止
    persist();
    return { ok: true, state: dep.state };
  }
  dep.state = 'running';
  persist();
  return { ok: true, state: dep.state };
}

function pauseDeployment(dep, body) {
  // 暂停全部在任意状态都可调用：终止态 no-op；running → paused；
  // starting / pending_verify / verify_failed / paused → 只停运动/清手动，不改变事件状态。
  if (TERMINAL.has(dep.state)) {
    return { ok: true, state: dep.state, noop: true, paused: false };
  }
  const wasRunning = dep.state === 'running';
  let changed = false;
  if (wasRunning) {
    dep.state = 'paused';
    dep.pausedAt = Date.now();
    dep.pauseReason = String(body.reason || '');
    changed = true;
  }
  for (const dd of dep.devices) {
    if (dd.manual) {
      dd.manual = null;
      const g = deviceOf(dd);
      if (g) {
        g.motion = 'idle';
        g.mode = 'standby';
        g.odo.speedMps = 0;
      }
      updateGlobal(dd, dep);
      changed = true;
    }
    if (dd.phase === 'moving') {
      addStep(dep, { type: 'pause', from: 'server', to: dd.device_code, state: 'acked' });
      stopDevice(dd, dep);
      changed = true;
    }
  }
  if (changed) {
    addStep(dep, { type: wasRunning ? 'pause_all' : 'stop_all', from: 'server', to: null, state: 'done' });
    persist();
    log(`[布设] ${dep.id} 暂停全体 reason=${dep.pauseReason || ''} state=${dep.state}`);
  }
  return { ok: true, state: dep.state, noop: !changed, paused: wasRunning };
}

function resumeDeployment(dep) {
  if (dep.state !== 'paused') return { status: 409, code: 'deployment_state_invalid', message: '当前状态不能继续' };
  dep.state = 'running';
  dep.resumedAt = Date.now();
  for (const dd of dep.devices) {
    dd.manual = null;
    const g0 = deviceOf(dd);
    if (g0) {
      g0.motion = 'idle';
      g0.odo.speedMps = 0;
    }
    if (dd.phase === 'stopped') {
      dd.phase = 'moving';
      dd.lastTick = Date.now();
      const g = deviceOf(dd);
      if (g) { g.motion = 'moving'; g.mode = 'auto'; }
      addStep(dep, { type: 'resume', from: 'server', to: dd.device_code, state: 'acked' });
    }
  }
  addStep(dep, { type: 'resume_all', from: 'server', to: null, state: 'done' });
  persist();
  log(`[布设] ${dep.id} 继续`);
  return { ok: true, state: dep.state };
}

function abortDeployment(dep, body) {
  if (TERMINAL.has(dep.state)) return { status: 409, code: 'deployment_state_invalid', message: '事件已结束' };
  dep.state = 'aborted';
  dep.completedAt = Date.now();
  dep.abortReason = String(body.reason || '');
  for (const dd of dep.devices) {
    if (dd.phase === 'moving' || dd.phase === 'stopped') {
      addStep(dep, { type: 'abort', from: 'server', to: dd.device_code, state: 'acked' });
      stopDevice(dd, dep);
    }
  }
  releaseAll(dep);
  addStep(dep, { type: 'abort_all', from: 'server', to: null, state: 'done' });
  persist();
  log(`[布设] ${dep.id} 终止 reason=${dep.abortReason}`);
  return { ok: true, state: dep.state };
}

function retryDeployment(dep, body) {
  const type = body.type;
  const t = targetDevice(dep, body);
  if (t.error) return t.error;
  const dd = t.dd;
  if (type === 'start_command') {
    if (dd.phase !== 'failed' && dd.startCommand.state !== 'timeout' && dd.phase !== 'start_sent') {
      return { status: 409, code: 'deployment_state_invalid', message: '当前启动步骤不需要重试' };
    }
    clearAnomalies(dd.device_code);
    clearAnomaly(dep, dd);
    dd.phase = 'not_started';
    const ok = startDevice(dep, dd.order, 'retry', null, body.originalCommandId || dd.startCommand.commandId);
    persist();
    return ok ? { ok: true, state: dep.state } : { status: 409, code: 'chain_blocked', message: '重试启动失败' };
  }
  if (type === 'report') {
    if (dd.report.state !== 'lost') return { status: 409, code: 'deployment_state_invalid', message: '当前没有待重试的上报' };
    clearAnomalies(dd.device_code);
    clearAnomaly(dep, dd, 'report_lost');
    dd.report.state = 'acked';
    dd.report.ackedAt = Date.now();
    addStep(dep, { type: 'report', from: dd.device_code, to: dd.order === 0 ? null : dep.master_code, reportId: reportId(dep, dd), state: 'acked', retry: true });
    if (dd.order < dep.N) startDevice(dep, dd.order + 1, 'report', reportId(dep, dd));
    persist();
    return { ok: true, state: dep.state };
  }
  return { status: 400, code: 'invalid_params', message: 'type 必须为 start_command 或 report' };
}

function predecessorReady(dep, order) {
  if (order <= 0) return true;
  const prev = byOrder(dep, order - 1);
  return !!prev && (prev.reachedInterval || prev.arrived || prev.phase === 'skipped_manual');
}

function nextUsable(d, dep) {
  return !!d &&
    d.online !== false &&
    d.available !== false &&
    !d.estopLocked &&
    !(d.task && d.task.state === 'running') &&
    (!d.occupied || d.occupiedByEvent === dep.id);
}

function blockedDevice(dep) {
  for (const dd of dep.devices) {
    const g = deviceOf(dd);
    if (dd.phase === 'failed' || dd.startCommand.state === 'timeout' || dd.startCommand.state === 'rejected' || dd.report.state === 'lost') {
      return dd;
    }
    if (g && g.online === false && dd.phase !== 'arrived' && dd.phase !== 'skipped_manual' && dd.phase !== 'no_move') {
      // 尚未轮到的从桶离线不算“当前阻断”；只有前序已到 d/已跳过，或它自己已经开始，才算
      if (dd.phase !== 'not_started' || predecessorReady(dep, dd.order)) return dd;
    }
  }
  return null;
}

function skipDeployment(dep, body) {
  if (dep.state !== 'running') return { status: 409, code: 'skip_not_allowed', message: '仅 RUNNING 状态可人工跳过（暂停请先继续）' };
  const t = targetDevice(dep, body);
  if (t.error) return t.error;
  const dd = t.dd;
  if (dd.device_type === 'master_cone' || dd.order === 0) return { status: 409, code: 'cannot_skip_master', message: '主桶不可人工跳过' };
  const blocked = blockedDevice(dep);
  if (!blocked || blocked.device_code !== dd.device_code) {
    return { status: 409, code: 'skip_not_allowed', message: '只能跳过当前异常阻断步骤对应的从桶' };
  }
  const c = body.confirmations || {};
  if (!(c.deviceStoppedOrRemoved && c.nextPathSafe && c.understandException)) {
    return { status: 400, code: 'skip_confirmation_required', message: '必须完成三项现场安全确认' };
  }
  const disposition = String(body.disposition || '');
  if (!SKIP_DISPOSITIONS.has(disposition)) {
    return { status: 400, code: 'skip_disposition_required', message: '必须选择处置方式' };
  }
  const reason = String(body.reason || '').trim();
  if (!reason) return { status: 400, code: 'bad_request', message: '必须填写跳过原因' };

  // 先校验下一只可用；否则整个跳过失败（不标记）
  let next = null;
  if (dd.order < dep.N) {
    next = byOrder(dep, dd.order + 1);
    if (!next) return { status: 409, code: 'chain_blocked', message: '找不到下一只从桶' };
    const ng = deviceOf(next);
    if (!nextUsable(ng, dep) ||
        hasAnomaly(next.device_code, 'offline') ||
        hasAnomaly(next.device_code, 'reject') ||
        hasAnomaly(next.device_code, 'ack_timeout')) {
      return { status: 409, code: !nextUsable(ng, dep) ? 'device_offline' : 'chain_blocked', message: '下一只从桶不可用，跳过失败', detail: { nextDevice: next.device_code } };
    }
  }

  const g = deviceOf(dd);
  if (g) {
    g.motion = 'idle';
    g.mode = 'standby';
    g.odo.speedMps = 0;
  }
  dd.phase = 'skipped_manual';
  dd.arrived = false;
  dd.report.state = 'not_applicable';
  dd.skip = {
    skipped: true,
    reason,
    disposition,
    note: String(body.note || ''),
    skippedAt: Date.now(),
    skippedBy: dep.owner,
    stopConfirmed: !!(g && g.online !== false),
  };
  updateGlobal(dd, dep);
  addStep(dep, { type: 'manual_skip', from: 'operator', to: dd.device_code, state: 'done', reason, disposition });
  log(`[布设] ${dep.id} 人工跳过 ${dd.device_code} reason=${reason} disposition=${disposition}${next ? ' → 触发 ' + next.device_code : ''}`);
  if (next) startDevice(dep, next.order, 'manual_skip', null, nid('cmd'));
  checkComplete(dep);
  persist();
  return { ok: true, state: dep.state, skippedDevice: dd.device_code, nextTriggered: next ? next.device_code : null, resultHint: 'completed_with_exception' };
}

function verifyDeployment(dep, body) {
  if (dep.state !== 'pending_verify' && dep.state !== 'verify_failed') {
    return { status: 409, code: 'deployment_state_invalid', message: '当前状态不能核验' };
  }
  const t = targetDevice(dep, body);
  if (t.error) return t.error;
  const dd = t.dd;
  const result = String(body.result || '');
  if (result !== 'pass' && result !== 'fail') return { status: 400, code: 'invalid_params', message: 'result 必须是 pass/fail' };
  dd.verify = { state: result, note: String(body.note || ''), verifiedAt: Date.now() };
  const anyFail = dep.devices.some((x) => x.verify.state === 'fail');
  const allPass = dep.devices.every((x) => x.verify.state === 'pass');
  dep.state = anyFail ? 'verify_failed' : allPass ? 'pending_verify' : dep.state;
  addStep(dep, { type: 'verify', from: dep.owner, to: dd.device_code, state: result, note: dd.verify.note });
  persist();
  return { ok: true, verify: dd.verify, state: dep.state };
}

function completeDeployment(dep) {
  if (dep.state !== 'pending_verify' && dep.state !== 'verify_failed') {
    return { status: 409, code: 'deployment_state_invalid', message: '当前状态不能完成' };
  }
  if (dep.devices.some((dd) => dd.verify.state !== 'pass')) {
    return { status: 409, code: 'not_all_verified', message: '未全部核验通过，不能完成' };
  }
  dep.state = 'completed';
  dep.completedAt = Date.now();
  dep.result = dep.devices.some((dd) => dd.skip && dd.skip.skipped) ? 'completed_with_exception' : 'completed';
  releaseAll(dep);
  addStep(dep, { type: 'complete', from: dep.owner, to: null, state: 'done', result: dep.result });
  persist();
  log(`[布设] ${dep.id} 完成 result=${dep.result}`);
  return { ok: true, state: dep.state, result: dep.result };
}

function deviceJson(dep, dd) {
  const g = deviceOf(dd);
  return {
    device_code: dd.device_code,
    name: g ? g.name : dd.device_code,
    device_type: dd.device_type,
    binding_code: dd.binding_code,
    order: dd.order,
    online: !!(g && g.online !== false),
    targetM: round1(dd.targetM),
    traveledM: round1(dd.traveledM),
    pos: { x: round2(dd.posX), y: round2(dd.posY) },
    headingDeg: round2(dd.headingDeg),
    manualActive: !!dd.manual,
    phase: dd.phase,
    startState: dd.phase,
    startCommand: dd.startCommand,
    report: dd.report,
    trigger: dd.device_type === 'master_cone' ? dd.trigger : null,
    reachedInterval: dd.reachedInterval,
    arrived: dd.arrived,
    skip: dd.skip,
    verify: dd.verify,
    anomaly: dd.anomaly,
  };
}

function nextExpected(dep) {
  if (dep.state !== 'running') return null;
  for (const dd of dep.devices) {
    if (dd.phase !== 'moving') continue;
    if (!dd.reachedInterval) {
      return {
        type: dd.order === 0 ? 'trigger' : 'report',
        device_code: dd.device_code,
        thresholdM: dep.d,
        after_report_trigger: dd.order < dep.N ? byOrder(dep, dd.order + 1)?.device_code : null,
        state: 'waiting',
      };
    }
  }
  return null;
}

function deploymentJson(dep) {
  return {
    deploymentId: dep.id,
    eventId: dep.eventId,
    name: dep.name,
    lane: dep.lane,
    state: dep.state,
    result: dep.result,
    params: {
      L: dep.L,
      d: dep.d,
      N: dep.N,
      unit: 'm',
      turns: (dep.turns || []).map((t) => ({
        dir: t.dir,
        atM: t.atM,
        deg: LIMITS.turnDeg,
      })),
      // 单转弯旧客户端兼容别名：仅恰好 1 处时返回
      turn: (dep.turns && dep.turns.length === 1)
        ? { dir: dep.turns[0].dir, atM: dep.turns[0].atM, deg: LIMITS.turnDeg }
        : null,
    },
    devices: dep.devices.map((dd) => deviceJson(dep, dd)),
    chain: { nextExpected: nextExpected(dep), steps: dep.steps },
    anomalies: dep.anomalies,
    startedAt: dep.startedAt,
    updatedAt: Date.now(),
    completedAt: dep.completedAt,
  };
}

function listJson(dep) {
  return {
    deploymentId: dep.id,
    name: dep.name,
    lane: dep.lane,
    state: dep.state,
    result: dep.result,
    L: dep.L,
    d: dep.d,
    N: dep.N,
    master_code: dep.master_code,
    createdAt: dep.createdAt,
    startedAt: dep.startedAt,
    completedAt: dep.completedAt,
    hasSkip: dep.devices.some((dd) => dd.skip && dd.skip.skipped),
  };
}

function ownerOf(req) {
  return ctx.currentUser(req);
}

function getOwned(req, id) {
  const dep = deployments.get(id);
  if (!dep || dep.owner !== ownerOf(req)) return null;
  return dep;
}

async function handle(req, res, path, url, body, log) {
  // ── 套装从桶 ──
  if (path === '/api/v1/device-sets' && req.method === 'GET') {
    const masterCode = url.searchParams.get('master_code');
    const user = ownerOf(req);
    if (!masterCode) return send(res, 400, 'bad_request', '缺少 master_code');
    const master = ctx.devices.get(masterCode);
    if (!master || master.deviceType !== 'master_cone') return send(res, 404, 'set_not_found', '主桶或套装不存在');
    if ((master.owner || ctx.defaultUser) !== user) return send(res, 404, 'set_not_found', '主桶或套装不存在');
    const slaves = [...ctx.devices.values()]
      .filter((d) => d.deviceType === 'slave_cone' && d.setId && d.setId === master.setId)
      .sort((a, b) => (a.setOrder || 0) - (b.setOrder || 0))
      .map((d) => ({
        device_code: d.id,
        name: d.name,
        device_type: 'slave_cone',
        binding_code: d.bindingCode,
        order: d.setOrder || 0,
        online: d.online !== false,
        available: d.available !== false && !d.occupied && !(d.task && d.task.state === 'running'),
        occupied: !!d.occupied,
      }));
    return ok(res, 200, {
      set_id: master.setId || null,
      master: {
        device_code: master.id,
        device_type: 'master_cone',
        binding_code: master.bindingCode,
        online: master.online !== false,
        occupied: !!master.occupied,
      },
      slaves,
    });
  }

  // ── 布设事件列表（含 active）──
  if (path === '/api/v1/deployments' && req.method === 'GET') {
    const user = ownerOf(req);
    const stateFilter = url.searchParams.get('state');
    const page = Math.max(1, Number(url.searchParams.get('page') || 1));
    const size = Math.max(1, Math.min(100, Number(url.searchParams.get('size') || 20)));
    let items = [...deployments.values()].filter((d) => d.owner === user);
    if (stateFilter === 'active') items = items.filter((d) => isActive(d));
    else if (stateFilter) items = items.filter((d) => d.state === stateFilter);
    items.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    const total = items.length;
    const pageItems = items.slice((page - 1) * size, page * size).map(listJson);
    return ok(res, 200, { items: pageItems, page, size, total });
  }

  // ── 创建 ──
  if (path === '/api/v1/deployments' && req.method === 'POST') {
    const user = ownerOf(req);
    const r = createDeployment(body, user);
    if (r.error) return send(res, r.error.status, r.error.code, r.error.message, r.error.detail);
    if (r.existing) return ok(res, 201, deploymentJson(r.existing));
    return ok(res, 201, deploymentJson(r.dep));
  }

  // ── 单事件 ──
  const m = /^\/api\/v1\/deployments\/([^/]+)$/.exec(path);
  if (m) {
    const dep = getOwned(req, decodeURIComponent(m[1]));
    if (!dep) return send(res, 404, 'deployment_not_found', '布设事件不存在');
    if (req.method === 'GET') return ok(res, 200, deploymentJson(dep));
    if (req.method === 'DELETE') {
      if (dep.state !== 'ready') return send(res, 409, 'deployment_state_invalid', '仅 READY 可取消');
      deployments.delete(dep.id);
      persist();
      log(`[布设] ${dep.id} 取消（READY）`);
      res.writeHead(204, { 'Access-Control-Allow-Origin': ctx.corsOrigin || '*' });
      return res.end();
    }
  }

  // ── 记录详情 ──
  const mRecord = /^\/api\/v1\/deployments\/([^/]+)\/record$/.exec(path);
  if (mRecord && req.method === 'GET') {
    const dep = getOwned(req, decodeURIComponent(mRecord[1]));
    if (!dep) return send(res, 404, 'deployment_not_found', '布设事件不存在');
    return ok(res, 200, { ...deploymentJson(dep), commands: dep.steps, anomalies: dep.anomalies });
  }

  // ── 控制动作 ──
  const mAction = /^\/api\/v1\/deployments\/([^/]+)\/(start|pause|resume|abort|retry|skip|verify|complete)$/.exec(path);
  if (mAction && req.method === 'POST') {
    const dep = getOwned(req, decodeURIComponent(mAction[1]));
    if (!dep) return send(res, 404, 'deployment_not_found', '布设事件不存在');
    const action = mAction[2];
    if (action === 'start') {
      const r = startDeployment(dep, body);
      if (r.status) return send(res, r.status, r.code, r.message, r.detail);
      return ok(res, 202, { deploymentId: dep.id, state: dep.state, startedAt: dep.startedAt, commandId: dep.devices[0].startCommand.commandId });
    }
    if (action === 'pause') {
      const r = pauseDeployment(dep, body);
      if (r.status) return send(res, r.status, r.code, r.message);
      return ok(res, 202, { deploymentId: dep.id, state: dep.state });
    }
    if (action === 'resume') {
      const r = resumeDeployment(dep);
      if (r.status) return send(res, r.status, r.code, r.message);
      return ok(res, 202, { deploymentId: dep.id, state: dep.state });
    }
    if (action === 'abort') {
      const r = abortDeployment(dep, body);
      if (r.status) return send(res, r.status, r.code, r.message);
      return ok(res, 202, { deploymentId: dep.id, state: dep.state });
    }
    if (action === 'retry') {
      const r = retryDeployment(dep, body);
      if (r.status) return send(res, r.status, r.code, r.message, r.detail);
      return ok(res, 202, { deploymentId: dep.id, state: dep.state });
    }
    if (action === 'skip') {
      const r = skipDeployment(dep, body);
      if (r.status) return send(res, r.status, r.code, r.message, r.detail);
      return ok(res, 202, {
        deploymentId: dep.id,
        state: dep.state,
        skippedDevice: r.skippedDevice,
        skippedPhase: 'skipped_manual',
        nextTriggered: r.nextTriggered,
        resultHint: r.resultHint,
      });
    }
    if (action === 'verify') {
      const r = verifyDeployment(dep, body);
      if (r.status) return send(res, r.status, r.code, r.message);
      return ok(res, 200, { deploymentId: dep.id, verify: r.verify, state: r.state });
    }
    if (action === 'complete') {
      const r = completeDeployment(dep);
      if (r.status) return send(res, r.status, r.code, r.message);
      return ok(res, 200, { deploymentId: dep.id, state: r.state, result: r.result });
    }
  }

  // ── 单桶手动干预（仅 PAUSED）──
  const mManual = /^\/api\/v1\/deployments\/([^/]+)\/manual\/(move|stop)$/.exec(path);
  if (mManual && req.method === 'POST') {
    const dep = getOwned(req, decodeURIComponent(mManual[1]));
    if (!dep) return send(res, 404, 'deployment_not_found', '布设事件不存在');
    const r = mManual[2] === 'move' ? manualMove(dep, body) : manualStop(dep, body);
    if (r.error) return send(res, r.error.status, r.error.code, r.error.message, r.error.detail);
    const dd = r.dd;
    return ok(res, 202, {
      deploymentId: dep.id,
      state: dep.state,
      device_code: dd.device_code,
      traveledM: round1(dd.traveledM),
      targetM: round1(dd.targetM),
      phase: dd.phase,
      headingDeg: round2(dd.headingDeg),
      pos: { x: round2(dd.posX), y: round2(dd.posY) },
      ignored: !!r.ignored,
    });
  }

  // ── 模拟异常注入（仅 Mock）──
  if (path === '/api/v1/deployment-sim/anomaly' && req.method === 'POST') {
    const code = String(body.device_code || '');
    const type = String(body.type || '');
    if (!code || !type) return send(res, 400, 'bad_request', 'device_code/type 必填');
    setAnomaly(code, type, body.enabled !== false);
    log(`[部署模拟] ${code} ${type}=${body.enabled !== false}`);
    return ok(res, 200, { ok: true, anomalies: [...(anomalies.get(code) || [])] });
  }

  return false;
}

function ok(res, status, obj) {
  ctx.sendJson(res, status, obj);
  return true;
}

function send(res, status, code, message, detail) {
  ctx.sendError(res, status, code, message, detail);
  return true;
}

module.exports = {
  init,
  tick,
  handle,
  // 供测试/调试
  _deployments: deployments,
  _setAnomaly: setAnomaly,
  // 纯函数（供单测一步跨多弯等场景）
  _advanceAlongRoute: advanceAlongRoute,
};
