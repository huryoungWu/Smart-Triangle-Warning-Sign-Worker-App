#!/usr/bin/env node
/* eslint-disable no-console */
/**
 * 三角警示牌作业 —— 联调用 Mock 服务端（零依赖，Node 内置模块）
 *
 * 用法：node tools/mock-server/server.js [端口]     （默认 8080）
 *
 * 实现《docs/后端接口设计文档.md》的全部接口形状：
 *  - 认证：POST /api/v1/auth/login|refresh|logout、GET /api/v1/auth/profile（Mock 不强制鉴权，带 token 也接受）
 *  - 设备：GET /api/v1/devices、POST /api/v1/devices/bind、DELETE /api/v1/devices/:id/bind、GET /api/v1/devices/:id
 *  - 状态：GET /api/v1/device/status?deviceId=   （1Hz 轮询；字段与 APP DeviceJson 解析口径一致）
 *  - 命令：POST /api/v1/device/command           （内置小车状态机模拟：分段路线 / 急停 / 取消 / 回收 …）
 *  - 业务：POST/GET /api/v1/tasks、POST/GET /api/v1/snapshots、POST /api/v1/warnings、
 *          GET /api/v1/risks、GET /api/v1/app/version、POST /api/v1/feedback
 *  - 视频：GET /api/v1/device/video/snapshot.jpg、GET /api/v1/device/video/stream（占位帧，非真实摄像头）
 *
 * 说明：
 *  - 状态推进由 /status 的调用节奏驱动（与 APP 1Hz 轮询一致），手动遥控带 800ms 看门狗
 *  - 任意 deviceId 都能返回模拟状态（便于「扫码添加自定义设备」后也能连）
 *  - 全部数据在内存中，重启即清空
 */

const http = require('http');
const os = require('os');
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT || process.argv[2] || 8080);

// ══════════════════════════ 小车状态机（对应 FakeDeviceRepository） ══════════════════════════

const SPEED = { move: 0.25, recover: 0.5, turnDps: 60 }; // 米/秒、度/秒（与 APP Fake 一致）
const LIMIT = { segMax: 10, mMin: 1, mMax: 50, degMin: 5, degMax: 180, totalMax: 200 };
const WATCHDOG_MS = 800;

let seq = 1;
const nid = (p) => `${p}-${(seq++).toString(36)}${Math.random().toString(36).slice(2, 5)}`;

function newDevice(id, name, opts = {}) {
  return {
    id,
    name,
    fw: '0.3.1',
    mode: 'standby',            // standby | manual | auto | safe_stop
    motion: 'idle',             // idle | moving | fault
    estopLocked: false,
    stopSource: 'none',
    light: 'off',
    task: { id: null, kind: null, source: 'app', targetM: 0, traveledM: 0, state: 'idle', stage: null, resultNote: null, heading: 0, segments: [] },
    odo: { leftPulse: 0, rightPulse: 0, traveledM: 0, speedMps: 0, diffPulse: 0 },
    batteryPercent: opts.battery ?? null,   // null = 无来源
    custom: !!opts.custom,
    /** 绑定归属：设备信息按登录用户获取（见 docs 第 4 节） */
    owner: opts.owner || DEFAULT_USER,
    started: opts.started ?? true,
    available: opts.available ?? true,
    lastTick: Date.now(),
    lastMoveAt: 0,
    movingDir: null,
  };
}

// ── 用户与鉴权（Mock 宽松模式）──
// 默认调试账号：demo / 123456（登录页已预填）。无 token 或未知 token 一律按 demo 处理，方便调试；
// 首次出现的其他用户名自动建号（其设备列表为空，需绑定后才可见 —— 演示「按登录状态获取设备信息」）。
// 归属：TC-001/TC-002 属于 demo；扫码/编号添加的设备自动注册归属当前用户。
const DEFAULT_USER = 'demo';
const users = new Map([[DEFAULT_USER, { id: 'u-1001', password: '123456', name: '演示用户' }]]);
const tokens = new Map(); // token → username

function currentUser(req) {
  const m = /^Bearer\s+(.+)$/i.exec(req.headers['authorization'] || '');
  const name = m && tokens.get(m[1]);
  return name && users.has(name) ? name : DEFAULT_USER;
}

// ── 车端 GPS（WGS-84）──
/** 是否模拟车端 GPS 模块：true = 状态里带 gps 块（APP 走 GPS 定位链路并在高德地图上映射）；
 *  false = 模拟无 GPS 模块（省略字段），APP 自动退化为「行程估算」路径 */
const EMIT_GPS = true;
/** 无手机锚点上报时的车端 GPS 基准点（GCJ-02） */
const DEFAULT_GPS_BASE = { lat: 39.9928, lng: 116.4004 };

// GCJ-02 → WGS-84（近似）：车端 GPS 按真实形态输出 WGS-84，APP 端负责 wgs84ToGcj02 转换后上高德地图
const GCJ_A = 6378245.0;
const GCJ_EE = 0.00669342162296594323;
function gcjTransformLat(x, y) {
  let ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0;
  ret += ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0;
  return ret;
}
function gcjTransformLng(x, y) {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0;
  ret += ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0;
  return ret;
}
/** 中国境外不做偏移（与 APP 端 CoordTransform.isOutOfChina 一致，保证往返一致） */
function isOutOfChina(lat, lng) {
  return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
}
function gcj02ToWgs84(lat, lng) {
  if (isOutOfChina(lat, lng)) return { lat, lng };
  let dLat = gcjTransformLat(lng - 105.0, lat - 35.0);
  let dLng = gcjTransformLng(lng - 105.0, lat - 35.0);
  const radLat = (lat / 180.0) * Math.PI;
  let magic = Math.sin(radLat);
  magic = 1 - GCJ_EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((GCJ_A * (1 - GCJ_EE)) / (magic * sqrtMagic)) * Math.PI);
  dLng = (dLng * 180.0) / ((GCJ_A / sqrtMagic) * Math.cos(radLat) * Math.PI);
  return { lat: lat - dLat, lng: lng - dLng };
}

/** deviceId → 设备。TC-001/TC-002 为内置（归属 demo）；其他进来自动注册 */
const devices = new Map([
  ['TC-001', newDevice('TC-001', '三角警示牌一号车', { battery: 86, owner: DEFAULT_USER })],
  ['TC-002', newDevice('TC-002', '三角警示牌二号车', { battery: null, started: false, available: false, owner: DEFAULT_USER })],
]);

function getDevice(id, owner = DEFAULT_USER) {
  if (!devices.has(id)) {
    devices.set(id, newDevice(id, '自定义设备', { custom: true, battery: null, owner }));
  }
  return devices.get(id);
}

function currentSegment(dev) {
  return dev.task.segments.find((s) => s.state === 'running' || s.state === 'waiting_confirm');
}

function beginSegment(dev, idx, autoStart) {
  const seg = dev.task.segments[idx];
  if (!seg) return;
  seg.state = autoStart ? 'running' : 'waiting_confirm';
  seg.progress = 0;
}

/** 中止当前任务：当前段 failed，后续段 skipped（熔断） */
function failRoute(dev, note, finalState = 'failed') {
  for (const s of dev.task.segments) {
    if (s.state === 'running' || s.state === 'waiting_confirm') s.state = 'failed';
    else if (s.state === 'pending') s.state = 'skipped';
  }
  dev.task.state = finalState;
  dev.task.resultNote = note;
  dev.mode = 'standby';
  dev.motion = 'idle';
}

function tick(dev) {
  const now = Date.now();
  let dt = (now - dev.lastTick) / 1000;
  dev.lastTick = now;
  if (dt <= 0) return;
  if (dt > 5) dt = 5; // 长时间没轮询 → 不补算

  // 遥控看门狗：800ms 无新指令自动停车
  if (dev.movingDir && now - dev.lastMoveAt > WATCHDOG_MS) {
    dev.movingDir = null;
    dev.motion = 'idle';
    dev.stopSource = 'link_loss';
    if (dev.task.state !== 'running' && dev.mode === 'manual') dev.mode = 'standby';
  }

  // 手动微调移动
  if (dev.movingDir && dev.motion === 'moving') {
    const step = SPEED.move * dt;
    dev.odo.traveledM += step;
    dev.odo.leftPulse += Math.round(step * 500);
    dev.odo.rightPulse += Math.round(step * 500);
    dev.odo.speedMps = SPEED.move;
  }

  // 任务推进
  if (dev.task.state === 'running') {
    if (dev.task.kind === 'recover') {
      const step = SPEED.recover * dt;
      dev.task.traveledM = Math.max(0, dev.task.traveledM - step);
      dev.odo.traveledM = dev.task.traveledM;
      dev.odo.leftPulse = Math.round(dev.task.traveledM * 500);
      dev.odo.rightPulse = dev.odo.leftPulse;
      dev.odo.speedMps = SPEED.recover;
      dev.motion = 'moving';
      if (dev.task.traveledM <= 0.01) {
        dev.task.state = 'done';
        dev.task.resultNote = 'recovered';
        dev.motion = 'idle';
        dev.mode = 'standby';
        dev.odo.traveledM = 0;
        dev.odo.speedMps = 0;
      }
    } else {
      tickRoute(dev, dt);
    }
  } else if (dev.motion !== 'moving') {
    dev.odo.speedMps = 0;
  }
}

function tickRoute(dev, dt) {
  const idx = dev.task.segments.findIndex((s) => s.state === 'running');
  if (idx === -1) {
    dev.motion = 'idle';
    dev.odo.speedMps = 0;
    return;
  }
  const seg = dev.task.segments[idx];
  if (seg.action === 'fwd') {
    const step = SPEED.move * dt;
    seg.progress = Math.min(1, seg.progress + step / seg.value);
    dev.task.traveledM += step; // 任务里程随进度增长（完成时按路线合计校正）
    dev.odo.traveledM += step;
    dev.odo.leftPulse += Math.round(step * 500);
    dev.odo.rightPulse += Math.round(step * 500);
    dev.odo.speedMps = SPEED.move;
    dev.motion = 'moving';
    if (seg.progress >= 1) completeSegment(dev, idx);
  } else {
    const stepDeg = SPEED.turnDps * dt;
    seg.progress = Math.min(1, seg.progress + stepDeg / Math.abs(seg.value));
    dev.task.heading = (dev.task.heading + (seg.value < 0 ? -stepDeg : stepDeg) + 360) % 360;
    dev.motion = 'moving';
    dev.odo.speedMps = 0;
    if (seg.progress >= 1) completeSegment(dev, idx);
  }
}

function completeSegment(dev, idx) {
  dev.task.segments[idx].state = 'done';
  dev.task.segments[idx].progress = 1;
  const next = idx + 1;
  if (next >= dev.task.segments.length) {
    dev.task.state = 'done';
    dev.task.resultNote = 'route_done';
    dev.task.traveledM = dev.task.segments.filter((s) => s.action === 'fwd').reduce((a, s) => a + s.value, 0);
    dev.motion = 'idle';
    dev.mode = 'standby';
    dev.odo.speedMps = 0;
  } else {
    beginSegment(dev, next, dev.task.mode === 'auto');
    if (dev.task.mode !== 'auto') {
      // 分段确认模式：停在段间待人工确认，允许手动微调
      dev.mode = 'standby';
      dev.motion = 'idle';
    }
  }
}

// ══════════════════════════ 命令处理 ══════════════════════════

/** 返回 {accepted, reason?}；accepted 的命令已作用到设备状态 */
function applyCommand(dev, body) {
  const t = body.type;
  if (t === 'move') {
    if (dev.estopLocked || dev.mode === 'safe_stop') return { accepted: false, reason: 'estop_locked' };
    const seg = currentSegment(dev);
    if (dev.task.state === 'running' && seg && seg.state === 'running') return { accepted: false, reason: 'task_running' };
    if (!['fwd', 'back', 'left', 'right'].includes(body.dir)) return { accepted: false, reason: 'param_out_of_range' };
    dev.movingDir = body.dir;
    dev.lastMoveAt = Date.now();
    if (dev.task.state !== 'running') dev.mode = 'manual';
    dev.motion = 'moving';
    return { accepted: true };
  }
  if (t === 'stop') {
    dev.movingDir = null;
    dev.lastMoveAt = Date.now();
    dev.motion = 'idle';
    const seg = currentSegment(dev);
    if (dev.task.state === 'running' && seg && seg.state === 'running') {
      failRoute(dev, 'manual_stop', 'canceled');
      dev.stopSource = 'manual';
    } else {
      if (dev.task.state !== 'running' && !dev.estopLocked) dev.mode = 'standby';
      dev.stopSource = 'manual';
    }
    return { accepted: true };
  }
  if (t === 'estop') {
    dev.movingDir = null;
    dev.motion = 'idle';
    dev.estopLocked = true;
    dev.mode = 'safe_stop';
    dev.stopSource = 'estop';
    if (dev.task.state === 'running') failRoute(dev, 'estop', 'canceled');
    return { accepted: true };
  }
  if (t === 'resume') {
    if (!dev.estopLocked && dev.mode !== 'safe_stop') return { accepted: false, reason: 'not_locked' };
    dev.estopLocked = false;
    dev.mode = 'standby';
    dev.motion = 'idle';
    dev.stopSource = 'none';
    return { accepted: true };
  }
  if (t === 'light') {
    if (!['off', 'on', 'blink'].includes(body.mode)) return { accepted: false, reason: 'param_out_of_range' };
    dev.light = body.mode; // 灯光与运动分离
    return { accepted: true };
  }
  if (t === 'odo_reset') {
    if (dev.motion === 'moving' || dev.task.state === 'running') return { accepted: false, reason: 'busy' };
    dev.odo = { leftPulse: 0, rightPulse: 0, traveledM: 0, speedMps: 0, diffPulse: 0 };
    return { accepted: true };
  }
  if (t === 'task_start') return startTask(dev, body);
  if (t === 'segment_confirm') {
    if (dev.task.state !== 'running') return { accepted: false, reason: 'no_task' };
    const idx = dev.task.segments.findIndex((s) => s.state === 'waiting_confirm');
    if (idx === -1) return { accepted: false, reason: 'not_waiting_confirm' };
    beginSegment(dev, idx, true);
    return { accepted: true };
  }
  if (t === 'task_cancel') {
    if (dev.task.state === 'running') {
      failRoute(dev, 'canceled_by_app', 'canceled');
      dev.movingDir = null;
      dev.motion = 'idle';
    }
    return { accepted: true };
  }
  if (t === 'recover') {
    if (dev.estopLocked || dev.mode === 'safe_stop') return { accepted: false, reason: 'estop_locked' };
    if (dev.task.state === 'running') return { accepted: false, reason: 'task_running' };
    if (dev.odo.traveledM <= 0.01) return { accepted: false, reason: 'already_home' };
    dev.task = {
      id: nid('t'), kind: 'recover', source: 'app', targetM: dev.odo.traveledM, traveledM: dev.odo.traveledM,
      state: 'running', stage: 'recovering', resultNote: null, heading: dev.task.heading, segments: [],
    };
    dev.mode = 'auto';
    dev.motion = 'idle';
    return { accepted: true };
  }
  return { accepted: false, reason: 'param_out_of_range' };
}

function startTask(dev, body) {
  if (dev.estopLocked || dev.mode === 'safe_stop') return { accepted: false, reason: 'estop_locked' };
  if (dev.task.state === 'running') return { accepted: false, reason: 'task_running' };

  let segments = body.segments;
  if (!segments || segments.length === 0) {
    // 形态1：单段快捷（定距布置）
    const targetM = Number(body.targetM || 0);
    if (!(targetM >= LIMIT.mMin && targetM <= LIMIT.mMax)) return { accepted: false, reason: 'param_out_of_range' };
    segments = [{ action: 'fwd', value: targetM }];
  }
  if (segments.length > LIMIT.segMax) return { accepted: false, reason: 'too_many_segments' };
  for (const s of segments) {
    const v = Number(s.value);
    if (s.action === 'fwd' && !(v >= LIMIT.mMin && v <= LIMIT.mMax)) return { accepted: false, reason: 'segment_out_of_range' };
    if (s.action === 'turn' && !(Math.abs(v) >= LIMIT.degMin && Math.abs(v) <= LIMIT.degMax)) return { accepted: false, reason: 'segment_out_of_range' };
    if (!['fwd', 'turn'].includes(s.action)) return { accepted: false, reason: 'segment_out_of_range' };
  }
  const total = segments.filter((s) => s.action === 'fwd').reduce((a, s) => a + Number(s.value), 0);
  if (total > LIMIT.totalMax) return { accepted: false, reason: 'param_out_of_range' };

  const mode = body.mode === 'segment_confirm' ? 'segment_confirm' : 'auto';
  dev.task = {
    id: nid('t'),
    kind: body.kind || 'fixed_distance',
    source: 'app',
    mode,
    targetM: total,
    traveledM: 0,
    state: 'running',
    stage: null,
    resultNote: null,
    heading: dev.task.heading,
    segments: segments.map((s) => ({ action: s.action, value: Number(s.value), state: 'pending', progress: 0 })),
  };
  if (body.light) dev.light = body.light;
  beginSegment(dev, 0, mode === 'auto');
  dev.mode = mode === 'auto' ? 'auto' : 'standby';
  dev.motion = 'idle';
  return { accepted: true };
}

function statusJson(dev, anchor = null) {
  const t = dev.task;
  const task = { id: t.id, kind: t.kind, source: t.source, targetM: t.targetM, traveledM: t.traveledM, state: t.state, heading: t.heading, segments: t.segments };
  // 省略空值字段（与 APP DeviceJson 解析口径一致：缺字段回落安全默认）
  if (t.stage) task.stage = t.stage;
  if (t.resultNote) task.resultNote = t.resultNote;
  if (!t.id) delete task.id;
  if (!t.kind) delete task.kind;
  const out = {
    deviceId: dev.id,
    fw: dev.fw,
    online: true,
    mode: dev.mode,
    motion: dev.motion,
    estopLocked: dev.estopLocked,
    stopSource: dev.stopSource,
    light: dev.light,
    task,
    odo: dev.odo,
    batteryPercent: dev.batteryPercent,
    tilt: null,
    obstacle: null,
    ts: Date.now(),
  };
  // 车端 GPS（可选字段）：EMIT_GPS=false 时省略整个字段，APP 自动走「行程估算」路径
  if (EMIT_GPS) {
    // 位置 = 基准点（APP 上报的手机位置，或默认点）沿航向投影 odo 里程 —— 车动则坐标动（1Hz 实时）
    const base = anchor || DEFAULT_GPS_BASE;
    const heading = dev.task.heading || 0;
    const rad = (heading * Math.PI) / 180;
    const dLat = (dev.odo.traveledM * Math.cos(rad)) / 111320;
    const dLng = (dev.odo.traveledM * Math.sin(rad)) / (111320 * Math.cos((base.lat * Math.PI) / 180));
    const wgs = gcj02ToWgs84(base.lat + dLat, base.lng + dLng);
    out.gps = {
      lat: +wgs.lat.toFixed(7),   // WGS-84（APP 端 wgs84ToGcj02 后上高德地图）
      lng: +wgs.lng.toFixed(7),
      acc: 5.0,
      speed: dev.odo.speedMps,
      heading,
      state: 'fixed',
      at: Date.now(),
    };
  }
  return out;
}

// ══════════════════════════ 业务数据（记录 / 快照 / 预警） ══════════════════════════

let bizSeq = 1;
const workRecords = [];
const snapshots = [];
/** 作业人员上传（快照登记）产生的风险事件 —— 风险页可见（统一：上传后才能在风险中展示） */
const workerRisks = [];
/** 快照图片存储（内存）：文件名 → {buffer, contentType}，经 /media/{name} 回放 */
const mediaStore = new Map();

// ══════════════════════════ 快照/媒体「保留在服务器端」（磁盘持久化） ══════════════════════════
// 设计：视频流仍由服务器**实时自动生成**（makeFrame，不落盘）；但每次上传快照时
// 服务端从视频流截出的那一帧（以及快照登记记录）**落盘保存**，服务重启后仍可回放/查询。
// 目录结构：tools/mock-server/data/media/{snap-id}.png  +  data/state.json（快照记录/风险登记/序号）

const DATA_DIR = path.join(__dirname, 'data');
const MEDIA_DIR = path.join(DATA_DIR, 'media');
const STATE_FILE = path.join(DATA_DIR, 'state.json');

/** 媒体落盘（尽力而为：失败不影响接口响应） */
function persistMedia(name, buffer) {
  try {
    fs.writeFileSync(path.join(MEDIA_DIR, name), buffer);
  } catch (e) {
    console.warn(`  [持久化] 媒体写盘失败 ${name}: ${e.message}`);
  }
}

/** 状态落盘：任务记录（/tasks 上传）+ 快照记录 + 其登记的风险事件 + 全局序号（保证重启后编号不重复） */
function persistState() {
  try {
    fs.writeFileSync(STATE_FILE, JSON.stringify({ snapshots, workerRisks, workRecords, bizSeq }));
  } catch (e) {
    console.warn(`  [持久化] 状态写盘失败: ${e.message}`);
  }
}

/** 启动时恢复：内存表 ← 磁盘（无文件/损坏时静默按首次运行处理） */
function restoreFromDisk() {
  fs.mkdirSync(MEDIA_DIR, { recursive: true });
  let mediaCount = 0;
  for (const f of fs.readdirSync(MEDIA_DIR)) {
    if (!/\.(png|jpg)$/i.test(f)) continue;
    mediaStore.set(f, {
      buffer: fs.readFileSync(path.join(MEDIA_DIR, f)),
      contentType: /\.png$/i.test(f) ? 'image/png' : 'image/jpeg',
    });
    mediaCount++;
  }
  try {
    const st = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    snapshots.push(...(st.snapshots || []));
    workerRisks.push(...(st.workerRisks || []));
    workRecords.push(...(st.workRecords || []));
    bizSeq = Math.max(bizSeq, Number(st.bizSeq) || 1);
  } catch {
    /* 首次运行或文件损坏：忽略 */
  }
  if (mediaCount || snapshots.length || workRecords.length) {
    console.log(
      `  已从 data/ 恢复：任务记录 ${workRecords.length} 条、快照记录 ${snapshots.length} 条、媒体文件 ${mediaCount} 个`,
    );
  }
}
restoreFromDisk();

// ══════════════════════════ 占位图片（运行时生成 PNG，避免二进制硬编码） ══════════════════════════

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

/** HSV → RGB（h/s/v 均为 0..1），用于背景色缓慢轮转 */
function hsvToRgb(h, s, v) {
  const i = Math.floor(h * 6);
  const f = h * 6 - i;
  const p = v * (1 - s);
  const q = v * (1 - f * s);
  const t = v * (1 - (1 - f) * s);
  const m = [[v, t, p], [q, v, p], [p, v, t], [p, q, v], [t, p, v], [v, p, q]][i % 6];
  return [Math.round(m[0] * 255), Math.round(m[1] * 255), Math.round(m[2] * 255)];
}

/** 当前帧号：由时间驱动（10fps）。多个观看端与"截帧"看到的内容一致 */
const frameIndex = () => Math.floor(Date.now() / 100);

/**
 * 生成动画视频帧（PNG，320×180）——Mock 的"视频源"，所有视频共用这一路流：
 * - 天色缓慢轮转（约 12 秒一轮）→ 不同时刻帧颜色不同
 * - 道路 + 向近处滚动的中央虚线 → 运动感
 * - 左右往返的三角警示牌 → 主体运动
 * 接真实视频源（车端摄像头/视频文件）后，替换本函数与 /device/video/* 即可。
 */
function makeFrame(fi = frameIndex(), w = 320, h = 180) {
  const raw = Buffer.alloc(h * (1 + w * 3));
  const [skyR, skyG, skyB] = hsvToRgb(((fi * 3) % 360) / 360, 0.45, 0.5);
  const roadTop = 96;
  const dashOffset = (fi * 6) % 48;
  // 三角警示牌：左右往返（周期约 16 秒）
  const span = w - 80;
  const tPos = (fi * 2) % (2 * span);
  const signX = 40 + (tPos < span ? tPos : 2 * span - tPos);
  for (let y = 0; y < h; y++) {
    const row = y * (1 + w * 3);
    raw[row] = 0;
    for (let x = 0; x < w; x++) {
      const o = row + 1 + x * 3;
      let r;
      let g;
      let b;
      if (y < roadTop) {
        r = skyR; g = skyG; b = skyB;                 // 天空（轮转色）
      } else if (y < roadTop + 6) {
        r = 96; g = 96; b = 104;                       // 路缘
      } else {
        r = 52; g = 52; b = 60;                        // 路面
      }
      // 中央滚动虚线
      if (y >= roadTop + 8 && y < roadTop + 16 && ((x + dashOffset) % 48) < 24) {
        r = 232; g = 232; b = 232;
      }
      // 三角警示牌（立在路上：底边贴路缘，顶点朝上）
      const signH = 34;
      const rowInSign = y - (roadTop - signH);
      if (rowInSign >= 0 && rowInSign < signH) {
        const half = (rowInSign / signH) * 16;
        if (Math.abs(x - signX) <= half) {
          r = 255; g = 140; b = 0;                     // 橙
          if (Math.abs(x - signX) > half - 3 || roadTop - y < 3) { r = 255; g = 255; b = 255; } // 白边
        }
      }
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ══════════════════════════ HTTP 工具 ══════════════════════════

function sendJson(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(body);
}
const sendError = (res, code, errCode, message, detail) =>
  sendJson(res, code, { error: { code: errCode, message, ...(detail ? { detail } : {}) } });

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
  });
}

/** multipart 解析（二进制安全）：文本字段 + image 文件。返回 { fields, image }，image 为 {buffer, contentType}|null */
function parseMultipart(req, raw) {
  const m = /boundary=(.+?)(;|$)/.exec(req.headers['content-type'] || '');
  if (!m) return { fields: {}, image: null };
  const boundary = Buffer.from(`--${m[1]}`);
  const fields = {};
  let image = null;
  let pos = raw.indexOf(boundary);
  while (pos !== -1) {
    const start = pos + boundary.length;
    if (start + 2 > raw.length) break;
    if (raw.slice(start, start + 2).toString() === '--') break; // 结束标记
    const headerEnd = raw.indexOf('\r\n\r\n', start);
    if (headerEnd === -1) break;
    const headers = raw.slice(start, headerEnd).toString('utf8');
    const bodyStart = headerEnd + 4;
    const next = raw.indexOf(boundary, bodyStart);
    if (next === -1) break;
    const body = raw.slice(bodyStart, Math.max(bodyStart, next - 2)); // 去掉段尾 \r\n
    const nameM = /name="([^"]+)"/.exec(headers);
    const fileM = /filename="([^"]*)"/.exec(headers);
    if (nameM) {
      if (nameM[1] === 'image' || fileM) {
        const typeM = /Content-Type:\s*(.+?)\r\n/i.exec(headers);
        if (body.length) image = { buffer: body, contentType: (typeM ? typeM[1] : 'image/jpeg').trim() };
      } else {
        fields[nameM[1]] = body.toString('utf8');
      }
    }
    pos = next;
  }
  return { fields, image };
}

function baseUrl(req) {
  return `http://${req.headers.host || `localhost:${PORT}`}`;
}

// ══════════════════════════ 路由 ══════════════════════════

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://x`);
  const path = url.pathname;
  const raw = ['POST', 'PUT', 'PATCH'].includes(req.method) ? await readBody(req) : Buffer.alloc(0);
  const isJson = (req.headers['content-type'] || '').includes('json');
  let body = {};
  if (raw.length && isJson) {
    try { body = JSON.parse(raw.toString('utf8')); } catch { return sendError(res, 400, 'bad_request', 'JSON 解析失败'); }
  }

  const log = (extra) => console.log(`${new Date().toLocaleTimeString('zh-CN')}  ${req.method} ${path}${url.search}  ${extra || ''}`);

  // ── 根路径：人工自检页 ──
  if (path === '/' && req.method === 'GET') {
    log();
    return sendJson(res, 200, {
      name: '三角警示牌 Mock 服务端',
      version: '1.0',
      devices: [...devices.values()].map((d) => ({ id: d.id, name: d.name, online: true })),
      hint: '接口清单见 docs/后端接口设计文档.md',
    });
  }

  // ── 认证（Mock 宽松：鉴权可选，无 token 按默认用户 demo）──
  if (path === '/api/v1/auth/login' && req.method === 'POST') {
    if (!body.username || !body.password) return sendError(res, 401, 'bad_credentials', '用户名或密码错误');
    // 任意账号可登录；首次出现的用户名自动建号（其设备列表为空，绑定后才可见）
    if (!users.has(body.username)) {
      users.set(body.username, { id: `u-${nid('9')}`, password: body.password, name: body.username });
    }
    const user = users.get(body.username);
    const token = `mock-${nid('tok')}`;
    tokens.set(token, body.username);
    log(`[登录] ${body.username}${body.username === DEFAULT_USER ? '（默认调试账号）' : ''}`);
    return sendJson(res, 200, {
      token,
      refreshToken: `mock-refresh-${nid('rt')}`,
      expiresIn: 7200,
      user: { id: user.id, name: user.name, role: 'operator', phone: '138****0000' },
    });
  }
  if (path === '/api/v1/auth/refresh' && req.method === 'POST') {
    return sendJson(res, 200, { token: `mock-${nid('tok')}`, refreshToken: `mock-refresh-${nid('rt')}`, expiresIn: 7200 });
  }
  if (path === '/api/v1/auth/profile' && req.method === 'GET') {
    const u = users.get(currentUser(req));
    return sendJson(res, 200, { id: u.id, name: u.name, role: 'operator', phone: '138****0000' });
  }
  if (path === '/api/v1/auth/logout' && req.method === 'POST') {
    const m = /^Bearer\s+(.+)$/i.exec(req.headers['authorization'] || '');
    if (m) tokens.delete(m[1]);
    log(`[登出] ${m ? 'token 已失效' : '（无 token）'}`);
    res.writeHead(204, { 'Access-Control-Allow-Origin': '*' });
    return res.end();
  }

  // ── 设备台账（按登录用户过滤：设备信息根据用户登录状态获取）──
  if (path === '/api/v1/devices' && req.method === 'GET') {
    const user = currentUser(req);
    const mine = [...devices.values()].filter((d) => d.owner === user);
    log(`[设备] u=${user} 共 ${mine.length} 台`);
    return sendJson(res, 200, {
      devices: mine.map((d) => ({
        deviceId: d.id, name: d.name, bound: true, online: true,
        started: d.started, available: d.available, batteryPercent: d.batteryPercent,
        fw: d.fw, lastSeenAt: Date.now(),
      })),
    });
  }
  let mBind = /^\/api\/v1\/devices\/([^/]+)\/bind$/.exec(path);
  if (mBind && req.method === 'DELETE') {
    const id = decodeURIComponent(mBind[1]);
    const d = devices.get(id);
    if (!d || d.owner !== currentUser(req)) {
      return sendError(res, 404, 'not_found', '设备不存在或不属于当前账号');
    }
    if (!d.custom) return sendError(res, 400, 'cannot_unbind_builtin', '内置演示设备不可删除');
    devices.delete(id);
    log(`[设备] u=${currentUser(req)} 解绑 ${id}`);
    res.writeHead(204, { 'Access-Control-Allow-Origin': '*' });
    return res.end();
  }
  if (path === '/api/v1/devices/bind' && req.method === 'POST') {
    const id = String(body.deviceId || '').trim();
    if (!id || id.length > 32) return sendError(res, 400, 'bad_request', '设备编号不能为空且不超过 32 字符');
    const user = currentUser(req);
    const exists = devices.get(id);
    if (exists && exists.owner !== user) {
      return sendError(res, 409, 'already_bound', '该设备已被其他账号绑定');
    }
    const d = getDevice(id, user); // 未知编号自动注册到当前用户（Mock 便利行为，正式服务端应按 doc 返回 404）
    d.owner = user;
    log(`[设备] u=${user} 绑定 ${id}（${d.custom ? '自动注册为自定义设备' : '内置设备'}）`);
    return sendJson(res, 201, { device: { deviceId: d.id, name: d.name, bound: true } });
  }
  let mDev = /^\/api\/v1\/devices\/([^/]+)$/.exec(path);
  if (mDev && req.method === 'GET') {
    const d = getDevice(decodeURIComponent(mDev[1]), currentUser(req));
    return sendJson(res, 200, {
      deviceId: d.id, name: d.name, fw: d.fw, sn: 'ESP32-MOCK', boundAt: Date.now() - 86400000,
      lastSeenAt: Date.now(), online: true,
      capabilities: { gps: EMIT_GPS, camera: true, battery: d.batteryPercent !== null, tilt: false, obstacle: false },
    });
  }

  // ── 实时状态（APP 1Hz 轮询；tick 由本调用驱动）──
  if (path === '/api/v1/device/status' && req.method === 'GET') {
    const id = url.searchParams.get('deviceId') || 'TC-001';
    const d = getDevice(id);
    tick(d);
    // 可选的手机位置锚点（GCJ-02）：APP 定位可用时上报，服务端把"车端 GPS"锚定在作业人员附近
    const lat = Number(url.searchParams.get('lat'));
    const lng = Number(url.searchParams.get('lng'));
    const anchor = Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) > 1e-6 ? { lat, lng } : null;
    return sendJson(res, 200, statusJson(d, anchor));
  }

  // ── 控制命令 ──
  if (path === '/api/v1/device/command' && req.method === 'POST') {
    const id = String(body.deviceId || '');
    if (!id) return sendError(res, 400, 'bad_request', '缺少 deviceId');
    const d = getDevice(id);
    const r = applyCommand(d, body);
    log(`[cmd] u=${currentUser(req)} ${id} ${body.type}${body.dir ? '/' + body.dir : ''}${body.mode ? '/' + body.mode : ''}${body.segments ? ` (${body.segments.length} 段)` : ''} → ${r.accepted ? 'accepted' : 'rejected: ' + r.reason}`);
    return sendJson(res, 200, r.accepted ? { accepted: true, cmdId: nid('c') } : { accepted: false, reason: r.reason });
  }

  // ── 作业记录上传 / 查询 ──
  if (path === '/api/v1/tasks' && req.method === 'POST') {
    const idem = req.headers['idempotency-key'];
    const exist = idem && workRecords.find((r) => r.clientId === idem);
    if (exist) {
      log(`记录重复上传（幂等命中） clientId=${idem}`);
      return sendJson(res, 201, { id: exist.id, receivedAt: exist.receivedAt });
    }
    const rec = {
      ...body,
      id: `srv-${String(bizSeq++).padStart(4, '0')}`,
      clientId: body.id || idem || null,
      deviceId: body.deviceId || 'TC-001',
      receivedAt: Date.now(),
    };
    workRecords.push(rec);
    log(`[记录] u=${currentUser(req)} ${rec.deviceId} ${body.kind || '-'} ${body.deviceResult || '-'} 核验=${body.verifyResult || '-'} → ${rec.id}`);
    persistState(); // 作业记录落盘 → 重启后仍可查询（GET /api/v1/tasks）
    return sendJson(res, 201, { id: rec.id, receivedAt: rec.receivedAt });
  }
  if (path === '/api/v1/tasks' && req.method === 'GET') {
    return sendJson(res, 200, { items: [...workRecords].reverse(), page: 1, size: 20, total: workRecords.length });
  }
  let mTask = /^\/api\/v1\/tasks\/([^/]+)$/.exec(path);
  if (mTask && req.method === 'GET') {
    const rec = workRecords.find((r) => r.id === decodeURIComponent(mTask[1]));
    if (!rec) return sendError(res, 404, 'not_found', '记录不存在');
    return sendJson(res, 200, { ...rec, eventNo: `EV-${new Date().toISOString().slice(0, 10).replace(/-/g, '')}-${rec.id.slice(-4)}` });
  }

  // ── 快照上传（multipart：字段 + image 文件；也兼容纯 JSON）──
  if (path === '/api/v1/snapshots' && req.method === 'POST') {
    const parsed = isJson ? { fields: body, image: null } : parseMultipart(req, raw);
    const fields = parsed.fields;
    const snap = {
      id: `snap-${String(bizSeq++).padStart(4, '0')}`,
      deviceId: fields.deviceId || 'TC-001',
      taskName: fields.taskName || '',
      eventLabel: fields.eventLabel || '现场抓拍',
      capturedAt: Number(fields.capturedAt) || Date.now(),
      note: fields.note || '',
      hasImage: true, // 服务端从视频流截帧（接真机后为车端抓帧/APP 抓帧）
    };
    snapshots.push(snap);
    // 快照图片：**统一从视频流截帧** —— 取当前帧（时间驱动，与观看端看到的一致）。
    // 客户端 multipart 里若带 image 暂不采用（联调约定；接真机后改为车端抓帧或 APP 抓帧，见 docs 第 9 节）。
    const frame = makeFrame(frameIndex());
    const fileName = `${snap.id}.png`;
    mediaStore.set(fileName, { buffer: frame, contentType: 'image/png' });
    persistMedia(fileName, frame); // 截帧落盘 → 重启后经 /media/{name} 仍可回放
    const snapUrl = `${baseUrl(req)}/media/${fileName}`;
    // 统一：上传的快照在服务端登记为「风险事件」，风险页（GET /api/v1/risks）即可看到（见 docs 第 9/12 节）
    // 坐标：带 lat/lng 用真实坐标；未带（演示阶段 APP 不上报坐标）则查询时就近于查询点放置（Mock 演示行为）
    const lat = Number(fields.lat);
    const lng = Number(fields.lng);
    const at = Number.isFinite(lat) && Number.isFinite(lng) && lat !== 0 ? { lat, lng } : null;
    const risk = {
      id: `r-${snap.id}`,
      title: `作业人员上报：${snap.eventLabel}`,
      type: 'other',
      lat: at ? at.lat : null,
      lng: at ? at.lng : null,
      occurredAt: snap.capturedAt,
      description: `${snap.taskName || '作业任务'}（${snap.note || '自动抓拍'}）`,
      hasPhoto: true,
      hasVideo: false,
      photoUrls: [snapUrl],
      videoUrl: null,
      source: 'worker', // worker=作业人员上传 / external=外部数据源
    };
    workerRisks.push(risk);
    log(
      `[快照] u=${currentUser(req)} ${snap.deviceId} ${snap.eventLabel} → ${snap.id}` +
        `（从视频流截帧 ${(frame.length / 1024).toFixed(0)}KB）` +
        `（已登记风险事件 ${risk.id}）`,
    );
    persistState(); // 快照记录 + 风险登记落盘 → 重启后列表仍可见
    return sendJson(res, 201, { id: snap.id, url: snapUrl, placeholder: true, riskId: risk.id });
  }
  if (path === '/api/v1/snapshots' && req.method === 'GET') {
    return sendJson(res, 200, { items: [...snapshots].reverse(), page: 1, size: 20, total: snapshots.length });
  }

  // ── 预警信息 ──
  if (path === '/api/v1/warnings' && req.method === 'POST') {
    const w = { id: `warn-${String(bizSeq++).padStart(4, '0')}`, publishedAt: Date.now(), channels: ['nav_event'] };
    log(`[预警] ${body.eventLabel || '-'}「${body.title || '-'}」→ ${w.id}`);
    return sendJson(res, 201, w);
  }

  // ── 道路风险（围绕请求点生成演示数据；坐标为 GCJ-02）──
  if (path === '/api/v1/risks' && req.method === 'GET') {
    const lat = Number(url.searchParams.get('lat') || 39.9928);
    const lng = Number(url.searchParams.get('lng') || 116.4004);
    const radiusKm = Number(url.searchParams.get('radiusKm') || 2);
    const seeds = [
      { m: 400, heading: 25, type: 'breakdown', title: '小车抛锚占用最右车道', desc: '一辆白色小轿车因发动机故障停在最右车道，未设置警示标志，后方车辆请注意避让。', photo: false, video: true },
      { m: 1100, heading: 140, type: 'rear_end', title: '两车追尾，占用左侧两车道', desc: '高峰时段两辆小客车发生追尾，双方驾驶员无受伤，车辆停在左侧两条车道，正在等待拖车。', photo: true, video: true },
      { m: 1600, heading: 210, type: 'construction', title: '路面养护施工，单向半幅封闭', desc: '路面沥青摊铺作业，施工区域长约 300 米，已布设锥桶与警示灯，请提前变道。', photo: true, video: false },
      { m: 1900, heading: 75, type: 'rollover', title: '货车侧翻，货物散落', desc: '一辆轻型货车因雨天路滑发生侧翻，货物散落占用两条车道，现场正在清理。', photo: true, video: true },
      { m: 2200, heading: 300, type: 'debris', title: '车道内有散落物', desc: '前方车道内有较大面积散落物（疑似轮胎碎片），请减速通过并注意避让。', photo: false, video: false },
      { m: 2600, heading: 340, type: 'other', title: '前方长下坡，事故多发路段', desc: '该路段为长下坡弯道，历史事故多发，请控制车速、保持车距。', photo: true, video: false },
    ];
    const now = Date.now();
    const base = baseUrl(req);
    const risks = seeds
      .filter((s) => s.m <= radiusKm * 1000)
      .map((s, i) => {
        const rad = (s.heading * Math.PI) / 180;
        const dLat = (s.m * Math.cos(rad)) / 111320;
        const dLng = (s.m * Math.sin(rad)) / (111320 * Math.cos((lat * Math.PI) / 180));
        return {
          id: `r-${1000 + i}`,
          title: s.title,
          type: s.type,
          lat: +(lat + dLat).toFixed(6),
          lng: +(lng + dLng).toFixed(6),
          occurredAt: now - s.m * 360,
          description: s.desc,
          hasPhoto: s.photo,
          hasVideo: s.video,
          photoUrls: s.photo ? [`${base}/media/acc-${1000 + i}-1.png`] : [],
          videoUrl: s.video ? `${base}/api/v1/device/video/stream?deviceId=TC-001` : null, // 所有视频共用一路流
        };
      });
    log(`风险查询 (${lat},${lng}) r=${radiusKm}km → ${risks.length} 条`);
    // 追加「作业人员上传」登记的风险事件（上传后才可见 —— 与快照上传统一）：
    // - 带坐标：按查询半径过滤（生产语义）
    // - 未带坐标（演示）：就近于查询点放置（约 250m 内外），保证不同位置都能看到演示效果
    const withinKm = (aLat, aLng, bLat, bLng) => {
      const dLat = (aLat - bLat) * 111.32;
      const dLng = (aLng - bLng) * 111.32 * Math.cos((bLat * Math.PI) / 180);
      return Math.sqrt(dLat * dLat + dLng * dLng);
    };
    const uploaded = workerRisks
      .map((r, i) => {
        if (r.lat != null) {
          return withinKm(r.lat, r.lng, lat, lng) <= radiusKm ? r : null;
        }
        const heading = (i * 70 + 45) % 360;
        const m = 250 + (i % 3) * 120;
        const rad = (heading * Math.PI) / 180;
        const dLat = (m * Math.cos(rad)) / 111320;
        const dLng = (m * Math.sin(rad)) / (111320 * Math.cos((lat * Math.PI) / 180));
        return { ...r, lat: +(lat + dLat).toFixed(6), lng: +(lng + dLng).toFixed(6) };
      })
      .filter(Boolean);
    if (uploaded.length) log(`（含作业人员上传 ${uploaded.length} 条）`);
    return sendJson(res, 200, { risks: [...uploaded, ...risks] });
  }

  // ── 版本 / 反馈 ──
  if (path === '/api/v1/app/version' && req.method === 'GET') {
    return sendJson(res, 200, { latestVersion: '1.0', versionCode: 1, url: '', forceUpdate: false, releaseNote: 'Mock 服务端：当前已是最新版本' });
  }
  if (path === '/api/v1/feedback' && req.method === 'POST') {
    log(`[反馈] ${String(body.content || '').slice(0, 50)}`);
    return sendJson(res, 201, { id: `fb-${String(bizSeq++).padStart(4, '0')}` });
  }

  // ── 视频流（统一视频源：实时视频与所有"事故视频"共用这一路流；接真实视频源后替换本段）──
  if (path === '/api/v1/device/video/snapshot.jpg' && req.method === 'GET') {
    const png = makeFrame(); // 当前帧（时间驱动，与观看端一致）
    res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length, 'Access-Control-Allow-Origin': '*' });
    return res.end(png);
  }
  if (path === '/api/v1/device/video/stream' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'multipart/x-mixed-replace; boundary=frame', 'Access-Control-Allow-Origin': '*' });
    log(`[视频流] 客户端接入 deviceId=${url.searchParams.get('deviceId') || '-'}（10fps 动画帧）`);
    const timer = setInterval(() => {
      const png = makeFrame(); // 10fps；帧内容由时间驱动
      res.write(`--frame\r\nContent-Type: image/png\r\nContent-Length: ${png.length}\r\n\r\n`);
      res.write(png);
      res.write('\r\n');
    }, 100);
    req.on('close', () => clearInterval(timer));
    return;
  }
  if (path.startsWith('/media/')) {
    // 优先回放 APP 上传的快照图片（原始字节）；未命中则给占位图
    const stored = mediaStore.get(path.slice('/media/'.length));
    if (stored) {
      res.writeHead(200, {
        'Content-Type': stored.contentType,
        'Content-Length': stored.buffer.length,
        'Access-Control-Allow-Origin': '*',
      });
      return res.end(stored.buffer);
    }
    if (path.endsWith('.jpg') || path.endsWith('.png')) {
      const png = makeFrame(0);
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length, 'Access-Control-Allow-Origin': '*' });
      return res.end(png);
    }
    return sendError(res, 404, 'not_found', 'Mock 未提供该媒体文件（占位）');
  }

  log('→ 404');
  sendError(res, 404, 'not_found', `未知接口：${req.method} ${path}`);
});

server.listen(PORT, () => {
  console.log('');
  console.log('  三角警示牌 Mock 服务端已启动（Ctrl+C 停止）');
  console.log(`  本机访问:   http://127.0.0.1:${PORT}`);
  for (const infos of Object.values(os.networkInterfaces())) {
    for (const info of infos || []) {
      if (info.family === 'IPv4' && !info.internal) {
        console.log(`  局域网访问: http://${info.address}:${PORT}   ← 真机同 Wi-Fi 时用这个地址`);
      }
    }
  }
  console.log('  自检:       http://127.0.0.1:' + PORT + '/  （浏览器打开可见设备列表）');
  console.log('');
});
