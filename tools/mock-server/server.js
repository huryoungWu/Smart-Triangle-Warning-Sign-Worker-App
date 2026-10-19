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
 *          （bind = 连接设备：username/password/device_code/binding_code/device_type；
 *           绑定码仅校验「非空」—— 宽松模式，正式服务端必须比对设备登记信息）
 *  - 状态：GET /api/v1/device/status?device_code=   （连接后读取一次 + 手动刷新；字段与 APP DeviceJson 解析口径一致，含 task.lane 回显）
 *  - 命令：POST /api/v1/device/command           （内置小车状态机模拟：分段路线 / 急停 / 取消 / 回收 …；
 *                                                  task_start 带作业车道 lane 1~8）
 *  - 业务：POST/GET /api/v1/tasks、POST/GET /api/v1/snapshots、POST /api/v1/warnings、
 *          GET /api/v1/risks、GET /api/v1/app/version、POST /api/v1/feedback
 *  - 视频：GET /api/v1/device/video/snapshot.jpg、GET /api/v1/device/video/stream?device_code=（按设备各一路占位帧，非真实摄像头）
 *
 * 说明：
 *  - 状态推进由 Mock 服务端定时驱动（APP 只在连接后读取一次、之后由用户手动刷新），
 *    手动遥控带 800ms 看门狗
 *  - 任意 deviceCode 都能返回模拟状态（便于「扫码添加自定义设备」后也能连）
 *  - 全部数据在内存中，重启即清空
 */

const http = require('http');
const os = require('os');
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT || process.argv[2] || 8080);
/** 监听网卡：默认全部（0.0.0.0 + ::），即局域网/公网可直接访问；限制只监听内网时设 HOST */
const HOST = process.env.HOST || undefined;
/** 请求体上限（字节）：超过回 413，见 readBody() */
const MAX_BODY_BYTES = Number(process.env.MAX_BODY_BYTES || 2 * 1024 * 1024);
/** CORS 允许来源：默认全开（联调方便）；公网部署建议收敛成具体来源 */
const CORS_ORIGIN = process.env.CORS_ORIGIN || '*';

// ══════════════════════════ 小车状态机（对应 FakeDeviceRepository） ══════════════════════════

const SPEED = { move: 0.25, recover: 0.5, turnDps: 60 }; // 米/秒、度/秒（与 APP Fake 一致）
const LIMIT = { segMax: 10, mMin: 1, mMax: 50, degMin: 5, degMax: 180, totalMax: 200 };
const WATCHDOG_MS = 800;
/** 车端状态机定时推进间隔（不依赖 APP 的状态查询节奏；250ms 足够让 800ms 看门狗及时生效） */
const TICK_INTERVAL_MS = 250;

/** 设备类型（连接设备时上报的 device_type）：当前唯一取值，APP 端为默认常量 */
const DEVICE_TYPE_TRIANGLE = 'triangle';
/** 作业车道（第几车道）取值范围：与 APP TaskSpec / LaneRange 一致 */
const LANE_MIN = 1;
const LANE_MAX = 8;
const normalizeLane = (v) => {
  if (v === undefined || v === null || v === '') return null;
  const n = Number(v);
  return Number.isInteger(n) && n >= LANE_MIN && n <= LANE_MAX ? n : NaN; // NaN = 非法
};

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
    task: { id: null, kind: null, source: 'app', targetM: 0, traveledM: 0, state: 'idle', stage: null, resultNote: null, heading: 0, lane: null, segments: [] },
    odo: { leftPulse: 0, rightPulse: 0, traveledM: 0, speedMps: 0, diffPulse: 0 },
    batteryPercent: opts.battery ?? null,   // null = 无来源
    /** 设备类型（连接时上报）；连接过的设备记住最后一次的类型 */
    deviceType: opts.deviceType || DEVICE_TYPE_TRIANGLE,
    /** 设备绑定码（连接时用户输入的 binding_code，Mock 仅记录不比对 —— 宽松模式） */
    bindingCode: null,
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

/** deviceCode → 设备。TC-001/TC-002 为内置（归属 demo）；其他进来自动注册 */
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
  if (dt > 5) dt = 5; // 长时间未推进 → 不补算

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

/**
 * 状态机由服务端定时推进，而不是由 APP 的状态查询次数驱动。
 * APP 现在只在连接成功后读取一次状态、之后由用户手动刷新；若仍由查询驱动，
 * 两次刷新之间车辆/任务会完全静止，无法演示执行过程。
 */
setInterval(() => {
  for (const dev of devices.values()) tick(dev);
}, TICK_INTERVAL_MS).unref();

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

  // 作业车道（第几车道，1~8）：发布任务时必填；非法值整条命令拒绝
  const lane = normalizeLane(body.lane);
  if (Number.isNaN(lane)) return { accepted: false, reason: 'param_out_of_range' };

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
    lane,   // 作业车道：随任务保存并在 /status 的 task.lane 回显
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
  if (t.lane) task.lane = t.lane;      // 作业车道（第几车道）：无则整字段省略
  if (!t.id) delete task.id;
  if (!t.kind) delete task.kind;
  const out = {
    device_code: dev.id,
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

/** 兼容旧版落盘数据：历史字段 deviceId 统一迁移为 device_code */
function normalizeDeviceCode(obj) {
  if (obj && obj['deviceId'] !== undefined && obj.device_code === undefined) {
    obj.device_code = obj['deviceId'];
    delete obj['deviceId'];
  }
  return obj;
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
    snapshots.push(...(st.snapshots || []).map(normalizeDeviceCode));
    workerRisks.push(...(st.workerRisks || []));
    workRecords.push(...(st.workRecords || []).map(normalizeDeviceCode));
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

/** 字符串稳定哈希（FNV-1a）→ 用于按 deviceCode 派生画面风格（同设备恒定、异设备不同） */
function hashStr(s) {
  let h = 2166136261 >>> 0;
  const t = String(s || '');
  for (let i = 0; i < t.length; i++) {
    h ^= t.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return h >>> 0;
}

/**
 * 5×7 点阵字体（仅含 deviceCode 所需字符 0-9 A-Z 与 '-'）。
 * 每字符 7 行，每行 5bit（bit4 在左）；用于在帧左上角渲染设备号 OSD。
 */
const FONT_5x7 = {
  '0': [0x0e, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0e], '1': [0x04, 0x0c, 0x04, 0x04, 0x04, 0x04, 0x0e],
  '2': [0x0e, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1f], '3': [0x1f, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0e],
  '4': [0x02, 0x06, 0x0a, 0x12, 0x1f, 0x02, 0x02], '5': [0x1f, 0x10, 0x1e, 0x01, 0x01, 0x11, 0x0e],
  '6': [0x06, 0x08, 0x10, 0x1e, 0x11, 0x11, 0x0e], '7': [0x1f, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08],
  '8': [0x0e, 0x11, 0x11, 0x0e, 0x11, 0x11, 0x0e], '9': [0x0e, 0x11, 0x11, 0x0f, 0x01, 0x02, 0x0c],
  A: [0x0e, 0x11, 0x11, 0x1f, 0x11, 0x11, 0x11], B: [0x1e, 0x11, 0x11, 0x1e, 0x11, 0x11, 0x1e],
  C: [0x0e, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0e], D: [0x1c, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1c],
  E: [0x1f, 0x10, 0x10, 0x1e, 0x10, 0x10, 0x1f], F: [0x1f, 0x10, 0x10, 0x1e, 0x10, 0x10, 0x10],
  G: [0x0e, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0f], H: [0x11, 0x11, 0x11, 0x1f, 0x11, 0x11, 0x11],
  I: [0x0e, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0e], J: [0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0c],
  K: [0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11], L: [0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1f],
  M: [0x11, 0x1b, 0x15, 0x15, 0x11, 0x11, 0x11], N: [0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11],
  O: [0x0e, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0e], P: [0x1e, 0x11, 0x11, 0x1e, 0x10, 0x10, 0x10],
  Q: [0x0e, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0d], R: [0x1e, 0x11, 0x11, 0x1e, 0x14, 0x12, 0x11],
  S: [0x0f, 0x10, 0x10, 0x0e, 0x01, 0x01, 0x1e], T: [0x1f, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04],
  U: [0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0e], V: [0x11, 0x11, 0x11, 0x11, 0x11, 0x0a, 0x04],
  W: [0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0a], X: [0x11, 0x11, 0x0a, 0x04, 0x0a, 0x11, 0x11],
  Y: [0x11, 0x11, 0x0a, 0x04, 0x04, 0x04, 0x04], Z: [0x1f, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1f],
  '-': [0x00, 0x00, 0x00, 0x1f, 0x00, 0x00, 0x00], ' ': [0, 0, 0, 0, 0, 0, 0],
};

/** 在 raw（PNG filter-0 扫描线缓冲）上画 5×7 点阵文字（scale 为像素放大倍数） */
function drawText5x7(raw, w, h, text, x0, y0, scale, rgb) {
  let x = x0;
  for (const ch of String(text || '').toUpperCase()) {
    const glyph = FONT_5x7[ch];
    if (glyph) {
      for (let ry = 0; ry < 7; ry++) {
        for (let rx = 0; rx < 5; rx++) {
          if (!((glyph[ry] >> (4 - rx)) & 1)) continue;
          for (let sy = 0; sy < scale; sy++) {
            for (let sx = 0; sx < scale; sx++) {
              const px = x + rx * scale + sx;
              const py = y0 + ry * scale + sy;
              if (px < 0 || px >= w || py < 0 || py >= h) continue;
              const o = py * (1 + w * 3) + 1 + px * 3;
              raw[o] = rgb[0]; raw[o + 1] = rgb[1]; raw[o + 2] = rgb[2];
            }
          }
        }
      }
    }
    x += 6 * scale;
  }
}

/** 当前帧号：由时间驱动（10fps）。多个观看端与"截帧"看到的内容一致 */
const frameIndex = () => Math.floor(Date.now() / 100);

/**
 * 生成动画视频帧（PNG，320×180）——Mock 的"视频源"，**按 deviceCode 区分画面**
 * （API 形态与真实后端一致：`/device/video/*?device_code=` 指定取哪台设备的流）：
 * - 天色/路面/警示牌色调、运动相位、OSD 设备号均随 deviceCode 派生 → 不同设备画面稳定不同
 * - 天色缓慢轮转（约 12 秒一轮）→ 同设备不同时刻帧颜色也不同
 * - 道路 + 向近处滚动的中央虚线 → 运动感；左右往返的三角警示牌 → 主体运动
 * - 左上角点阵 OSD 渲染 deviceCode → 肉眼可辨当前是哪台设备的流
 * 接真实视频源（车端摄像头/视频文件）后，替换本函数与 /device/video/* 即可。
 */
function makeFrame(fi = frameIndex(), w = 320, h = 180, deviceCode = 'TC-001') {
  const seed = hashStr(deviceCode);
  const raw = Buffer.alloc(h * (1 + w * 3));
  const [skyR, skyG, skyB] = hsvToRgb((((seed % 120) + (fi * 3) % 360) % 360) / 360, 0.45, 0.5);
  const roadTop = 96;
  const dashOffset = (fi * 6 + (seed >>> 8) % 48) % 48;
  // 三角警示牌：左右往返（周期约 16 秒；相位随设备错开）
  const span = w - 80;
  const tPos = (fi * 2 + (seed >>> 4) % span) % (2 * span);
  const signX = 40 + (tPos < span ? tPos : 2 * span - tPos);
  // 警示牌/路缘配色随设备派生（hue 偏移），肉眼区分更明显
  const [signR, signG, signB] = hsvToRgb(((seed >>> 16) % 360) / 360, 0.85, 1.0);
  const [kerbR, kerbG, kerbB] = hsvToRgb(((seed >>> 8) % 360) / 360, 0.18, 0.42);
  for (let y = 0; y < h; y++) {
    const row = y * (1 + w * 3);
    raw[row] = 0;
    for (let x = 0; x < w; x++) {
      const o = row + 1 + x * 3;
      let r;
      let g;
      let b;
      if (y < roadTop) {
        r = skyR; g = skyG; b = skyB;                 // 天空（轮转色，基点随设备）
      } else if (y < roadTop + 6) {
        r = kerbR; g = kerbG; b = kerbB;               // 路缘（随设备）
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
          r = signR; g = signG; b = signB;             // 牌面（随设备）
          if (Math.abs(x - signX) > half - 3 || roadTop - y < 3) { r = 255; g = 255; b = 255; } // 白边
        }
      }
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
    }
  }
  // 左上角 OSD：设备号（白字黑边，肉眼可辨是哪台设备的流）
  drawText5x7(raw, w, h, deviceCode, 7, 7, 2, [0, 0, 0]);
  drawText5x7(raw, w, h, deviceCode, 6, 6, 2, [255, 255, 255]);
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
  // 每次 JSON 响应都打印（含错误体），紧跟在对应的请求日志之后
  console.log(`${new Date().toLocaleTimeString('zh-CN')}  ← 响应 ${code}: ${body}`);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': CORS_ORIGIN,
  });
  res.end(body);
}
const sendError = (res, code, errCode, message, detail) =>
  sendJson(res, code, { error: { code: errCode, message, ...(detail ? { detail } : {}) } });

/**
 * 读完请求体（带上限）。
 *
 * 上限是必要的：原先无限制地把所有 chunk `Buffer.concat` 进内存，服务端一旦暴露到
 * 公网/局域网，一个超大 body 就能把它打爆。超限时 **pause 而不是 destroy** ——
 * destroy 会让客户端看到连接被重置而不是 413，且可能与已写出的响应竞态。
 */
function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    let size = 0;
    let settled = false;
    const settle = (v) => {
      if (settled) return;
      settled = true;
      resolve(v);
    };
    req.on('data', (c) => {
      if (settled) return;
      size += c.length;
      if (size > MAX_BODY_BYTES) {
        settle({ tooLarge: true });
        req.pause();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => settle({ raw: Buffer.concat(chunks) }));
    req.on('error', () => settle({ raw: Buffer.alloc(0) }));
  });
}

/**
 * 乱码守卫：文本里出现**替换字符 U+FFFD** = 请求体不是合法 UTF-8
 * （典型场景：Windows 控制台用 GBK 发中文，curl -d 时中文被转成 GBK 字节）。
 * 这种数据一旦入库，界面上就是「�」（且无法还原），故一律拒绝并给出正确姿势。
 */
function hasInvalidUtf8(value) {
  if (typeof value === 'string') return value.includes('�');
  if (Array.isArray(value)) return value.some(hasInvalidUtf8);
  if (value && typeof value === 'object') return Object.values(value).some(hasInvalidUtf8);
  return false;
}

const UTF8_HINT =
  '请求体不是合法 UTF-8（检测到乱码字节）：中文请改用 --data-binary @payload.json 传 UTF-8 文件，' +
  '或让 APP/客户端直接发 UTF-8 请求体';

/**
 * 媒体地址归一化（对外仍返回绝对地址，契约不变）：
 * - 相对路径（新数据存储形态，如 `/media/snap-0001.png`）→ 补全为**当前请求的 origin**
 * - 历史遗留的绝对地址 → 只重写 origin
 * 这样换端口 / 换访问地址（127.0.0.1 ↔ 局域网 IP ↔ adb reverse）后，旧数据里的图片依然能加载。
 */
function absolutizeMediaUrl(url, base) {
  if (!url) return url;
  if (/^https?:\/\//i.test(url)) return url.replace(/^https?:\/\/[^/]+/i, base);
  return base + (url.startsWith('/') ? url : `/${url}`);
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
  // CORS 预检必须在**读 body 之前**短路：原先全文件没有 OPTIONS 分支，
  // 预检请求会一路掉到末尾的 404。APP 走原生 HttpURLConnection 不受影响，
  // 但用浏览器/网页工具联调时会被挡在预检上。
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': CORS_ORIGIN,
      'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization, Idempotency-Key',
      'Access-Control-Max-Age': '86400',
    });
    return res.end();
  }

  const url = new URL(req.url, `http://x`);
  const path = url.pathname;
  const log = (extra) => console.log(`${new Date().toLocaleTimeString('zh-CN')}  ${req.method} ${path}${url.search}  ${extra || ''}`);

  const read = ['POST', 'PUT', 'PATCH'].includes(req.method) ? await readBody(req) : { raw: Buffer.alloc(0) };
  if (read.tooLarge) {
    log(`请求体超限（上限 ${MAX_BODY_BYTES} 字节）`);
    return sendError(res, 413, 'payload_too_large', `请求体超过上限 ${MAX_BODY_BYTES} 字节`);
  }
  const raw = read.raw;
  const isJson = (req.headers['content-type'] || '').includes('json');
  let body = {};
  if (raw.length && isJson) {
    try {
      body = JSON.parse(raw.toString('utf8'));
    } catch {
      log(`请求体(JSON 解析失败): ${raw.toString('utf8')}`);
      return sendError(res, 400, 'bad_request', 'JSON 解析失败');
    }
    // 乱码守卫：非法 UTF-8（Windows 控制台 GBK 中文）→ 拒绝入库，避免脏数据污染列表
    if (hasInvalidUtf8(body)) {
      log(`请求体(非法 UTF-8): ${raw.toString('utf8')}`);
      return sendError(res, 400, 'bad_request', UTF8_HINT);
    }
  }

  // 每次请求都打印请求体：JSON 原样打印；空体/二进制只标注类型与大小
  if (!raw.length) {
    log('请求体: (空)');
  } else if (isJson) {
    log(`请求体: ${JSON.stringify(body)}`);
  } else {
    log(`请求体: [${req.headers['content-type'] || 'unknown'} ${raw.length}B]`);
  }

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
    res.writeHead(204, { 'Access-Control-Allow-Origin': CORS_ORIGIN });
    return res.end();
  }

  // ── 设备台账（按登录用户过滤：设备信息根据用户登录状态获取）──
  if (path === '/api/v1/devices' && req.method === 'GET') {
    const user = currentUser(req);
    const mine = [...devices.values()].filter((d) => d.owner === user);
    log(`[设备] u=${user} 共 ${mine.length} 台`);
    return sendJson(res, 200, {
      devices: mine.map((d) => ({
        device_code: d.id, name: d.name, bound: true, online: true,
        started: d.started, available: d.available, batteryPercent: d.batteryPercent,
        deviceType: d.deviceType, fw: d.fw, lastSeenAt: Date.now(),
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
    res.writeHead(204, { 'Access-Control-Allow-Origin': CORS_ORIGIN });
    return res.end();
  }
  // ── 连接设备（连接即绑定）：username / password / device_code / binding_code / device_type ──
  // 设备编号字段统一为 device_code；binding_code 必填（Mock 宽松：非空即通过，
  // 正式服务端必须与设备登记信息比对并返回 400 invalid_binding_code）
  if (path === '/api/v1/devices/bind' && req.method === 'POST') {
    const id = String(body.device_code || '').trim();
    const bindingCode = String(body.binding_code || '').trim();
    const deviceType = String(body.device_type || DEVICE_TYPE_TRIANGLE).trim();
    if (!id || id.length > 32) {
      return sendError(res, 400, 'bad_request', '设备编号（device_code）不能为空且不超过 32 字符');
    }
    if (!bindingCode) {
      return sendError(res, 400, 'bad_request', '绑定码（binding_code）不能为空，请输入设备铭牌上的绑定码');
    }
    const user = currentUser(req);            // 有 token 以 token 为准；无 token 按默认用户（宽松）
    const loginName = String(body.username || user); // 登录账号（APP 登录后带入，仅记日志）
    const exists = devices.get(id);
    if (exists && exists.owner !== user) {
      return sendError(res, 409, 'already_bound', '该设备已被其他账号绑定');
    }
    const d = getDevice(id, user); // 未知编号自动注册到当前用户（Mock 便利行为，正式服务端应按 doc 返回 404）
    d.owner = user;
    d.deviceType = deviceType;
    d.bindingCode = bindingCode;
    log(
      `[设备] u=${user} 连接/绑定 ${id}（${d.custom ? '自动注册为自定义设备' : '内置设备'}）` +
        ` 账号=${loginName} 绑定码=已提供 类型=${deviceType}`,
    );
    return sendJson(res, 201, { device: { device_code: d.id, name: d.name, bound: true, deviceType: d.deviceType } });
  }
  let mDev = /^\/api\/v1\/devices\/([^/]+)$/.exec(path);
  if (mDev && req.method === 'GET') {
    const d = getDevice(decodeURIComponent(mDev[1]), currentUser(req));
    return sendJson(res, 200, {
      device_code: d.id, name: d.name, deviceType: d.deviceType, fw: d.fw, sn: 'ESP32-MOCK', boundAt: Date.now() - 86400000,
      lastSeenAt: Date.now(), online: true,
      capabilities: { gps: EMIT_GPS, camera: true, battery: d.batteryPercent !== null, tilt: false, obstacle: false },
    });
  }

  // ── 实时状态（APP 连接后读取一次，之后由用户手动刷新）──
  // 状态机由上方 setInterval 统一推进；本接口只读，不随查询次数改变车辆状态。
  if (path === '/api/v1/device/status' && req.method === 'GET') {
    const id = url.searchParams.get('device_code') || 'TC-001';
    const d = getDevice(id);
    // 可选的手机位置锚点（GCJ-02）：APP 定位可用时上报，服务端把"车端 GPS"锚定在作业人员附近
    const lat = Number(url.searchParams.get('lat'));
    const lng = Number(url.searchParams.get('lng'));
    const anchor = Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) > 1e-6 ? { lat, lng } : null;
    // 只在连接后/用户手动刷新时调用，频率低，可以放心打日志（原先 1Hz 轮询时刻意不打印以免刷屏）
    log(`[状态] u=${currentUser(req)} ${id}${anchor ? ` 锚点=${anchor.lat},${anchor.lng}` : ''}`);
    return sendJson(res, 200, statusJson(d, anchor));
  }

  // ── 控制命令 ──
  if (path === '/api/v1/device/command' && req.method === 'POST') {
    const id = String(body.device_code || '');
    if (!id) return sendError(res, 400, 'bad_request', '缺少 device_code');
    const d = getDevice(id);
    const r = applyCommand(d, body);
    log(`[cmd] u=${currentUser(req)} ${id} ${body.type}${body.dir ? '/' + body.dir : ''}${body.mode ? '/' + body.mode : ''}${body.segments ? ` (${body.segments.length} 段)` : ''}${body.lane ? ` 车道=${body.lane}` : ''} → ${r.accepted ? 'accepted' : 'rejected: ' + r.reason}`);
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
      device_code: body.device_code || 'TC-001',
      receivedAt: Date.now(),
    };
    workRecords.push(rec);
    log(`[记录] u=${currentUser(req)} ${rec.device_code} ${body.kind || '-'} 车道=${body.lane ?? '-'} ${body.deviceResult || '-'} 核验=${body.verifyResult || '-'} → ${rec.id}`);
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
    // multipart 的原始 body 是二进制，这里补充打印解析后的字段（JSON 请求体已由通用日志打印）
    if (!isJson) log(`请求体(multipart 字段): ${JSON.stringify(parsed.fields)}${parsed.image ? ` image=${parsed.image.buffer.length}B` : ''}`);
    const fields = parsed.fields;
    if (hasInvalidUtf8(fields)) return sendError(res, 400, 'bad_request', UTF8_HINT);
    const lane = normalizeLane(fields.lane);
    const snap = {
      id: `snap-${String(bizSeq++).padStart(4, '0')}`,
      device_code: fields.device_code || 'TC-001',
      taskName: fields.taskName || '',
      // 作业车道（第几车道）：随所属任务上报，登记到风险事件
      lane: Number.isInteger(lane) ? lane : null,
      eventLabel: fields.eventLabel || '现场抓拍',
      capturedAt: Number(fields.capturedAt) || Date.now(),
      note: fields.note || '',
      hasImage: true, // 服务端从视频流截帧（接真机后为车端抓帧/APP 抓帧）
    };
    snapshots.push(snap);
    // 快照图片：**从该设备视频流截帧** —— 取当前帧（时间驱动，与观看端该 deviceCode 流看到的一致）。
    // 客户端 multipart 里若带 image 暂不采用（联调约定；接真机后改为车端抓帧或 APP 抓帧，见 docs 第 9 节）。
    const frame = makeFrame(frameIndex(), 320, 180, snap.device_code);
    const fileName = `${snap.id}.png`;
    mediaStore.set(fileName, { buffer: frame, contentType: 'image/png' });
    persistMedia(fileName, frame); // 截帧落盘 → 重启后经 /media/{name} 仍可回放
    // 入库用**相对路径**（换端口/换访问地址后仍能加载）；响应里给绝对地址（契约不变）
    const mediaPath = `/media/${fileName}`;
    const snapUrl = `${baseUrl(req)}${mediaPath}`;
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
      lane: snap.lane,   // 作业车道（第几车道）：风险页/事故详情展示
      hasPhoto: true,
      hasVideo: false,
      photoUrls: [mediaPath],   // 相对路径入库；/risks 查询时按当前 origin 拼绝对地址
      videoUrl: null,
      source: 'worker', // worker=作业人员上传 / external=外部数据源
    };
    workerRisks.push(risk);
    log(
      `[快照] u=${currentUser(req)} ${snap.device_code} ${snap.eventLabel} 车道=${snap.lane ?? '-'} → ${snap.id}` +
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
    const lane = normalizeLane(body.lane);
    const w = {
      id: `warn-${String(bizSeq++).padStart(4, '0')}`,
      publishedAt: Date.now(),
      channels: ['nav_event'],
      // 作业车道（第几车道）：随预警信息下发（发给过往司机），未带则 null
      lane: Number.isInteger(lane) ? lane : null,
    };
    log(`[预警] ${body.eventLabel || '-'}「${body.title || '-'}」车道=${w.lane ?? '-'} → ${w.id}`);
    return sendJson(res, 201, w);
  }

  // ── 道路风险（围绕请求点生成演示数据；坐标为 GCJ-02）──
  if (path === '/api/v1/risks' && req.method === 'GET') {
    const lat = Number(url.searchParams.get('lat') || 39.9928);
    const lng = Number(url.searchParams.get('lng') || 116.4004);
    const radiusKm = Number(url.searchParams.get('radiusKm') || 2);
    // lane = 事故/风险占用车道（第几车道，1~8）：演示数据**每条必带**（风险列表要显示「第 N 车道」）
    // minutesAgo/daysAgo = 演示发生时间：今天 6 条（默认「今天」筛选下可见）+ 历史 2 条（切「全部」可见）
    // 距离均 ≤ 2 公里（风险页搜索半径），方位各不相同，便于验证「近→远 / 新→旧」排序
    const seeds = [
      { m: 350, heading: 25, type: 'breakdown', title: '小车抛锚占用第 4 车道', desc: '一辆白色小轿车因发动机故障停在第 4 车道，未设置警示标志，后方车辆请注意避让。', photo: false, video: true, dev: 'TC-001', lane: 4, minutesAgo: 8, daysAgo: 0 },
      { m: 700, heading: 140, type: 'rear_end', title: '两车追尾，占用第 1、2 车道', desc: '高峰时段两辆小客车发生追尾，双方驾驶员无受伤，车辆停在第 1、2 车道，正在等待拖车。', photo: true, video: true, dev: 'TC-002', lane: 1, minutesAgo: 26, daysAgo: 0 },
      { m: 1100, heading: 210, type: 'construction', title: '路面养护施工，占用第 2 车道', desc: '路面沥青摊铺作业，施工区域长约 300 米、占用第 2 车道，已布设锥桶与警示灯，请提前变道。', photo: true, video: false, dev: 'TC-001', lane: 2, minutesAgo: 55, daysAgo: 0 },
      { m: 1400, heading: 75, type: 'rollover', title: '货车侧翻，占用第 2、3 车道', desc: '一辆轻型货车因雨天路滑发生侧翻，货物散落占用第 2、3 车道，现场正在清理。', photo: true, video: true, dev: 'TC-002', lane: 2, minutesAgo: 92, daysAgo: 0 },
      { m: 1700, heading: 300, type: 'debris', title: '第 3 车道内有散落物', desc: '第 3 车道内有较大面积散落物（疑似轮胎碎片），请减速通过并注意避让。', photo: false, video: false, dev: 'TC-001', lane: 3, minutesAgo: 130, daysAgo: 0 },
      { m: 1950, heading: 340, type: 'other', title: '路面油污，占用第 3 车道', desc: '前方路面有车辆漏油，油污带长约 50 米、占用第 3 车道，路面湿滑，请减速慢行、避免急刹。', photo: true, video: false, dev: 'TC-002', lane: 3, minutesAgo: 165, daysAgo: 0 },
      // ── 历史数据（默认「今天」筛选下不显示，切「全部」可见）──
      { m: 600, heading: 95, type: 'construction', title: '夜间施工围挡未撤，占用第 2 车道', desc: '施工围挡与锥桶未及时撤除，占用第 2 车道，请提前变道绕行。', photo: true, video: false, dev: 'TC-001', lane: 2, minutesAgo: 540, daysAgo: 1 },
      { m: 1500, heading: 190, type: 'rear_end', title: '早高峰追尾事故，占用第 1 车道', desc: '早高峰时段两车追尾，车辆停在第 1 车道等待处理，后方车辆请注意避让。', photo: true, video: false, dev: 'TC-002', lane: 1, minutesAgo: 420, daysAgo: 3 },
    ];
    const now = Date.now();
    // 北京时间当天 0 点（「今天」的时间基准；与 APP 端 TimeFormat 一致）
    const SH_OFFSET_MS = 8 * 60 * 60 * 1000;
    const shNow = new Date(now + SH_OFFSET_MS);
    const todayStart = Date.UTC(shNow.getUTCFullYear(), shNow.getUTCMonth(), shNow.getUTCDate()) - SH_OFFSET_MS;
    const occurredAtOf = (s, i) => {
      const back = now - s.minutesAgo * 60 * 1000 - s.daysAgo * 24 * 60 * 60 * 1000;
      // 今天的条目若倒推跨过零点，贴回当天 0 点之后（保证仍属「今天」筛选）
      return s.daysAgo === 0 && back < todayStart
        ? Math.min(todayStart + (i + 1) * 60 * 1000, now)
        : back;
    };
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
          occurredAt: occurredAtOf(s, i),
          description: s.desc,
          lane: s.lane ?? null,   // 占用车道（第几车道）；演示数据每条必带
          hasPhoto: s.photo,
          hasVideo: s.video,
          photoUrls: s.photo ? [`${base}/media/acc-${1000 + i}-1.png`] : [],
          // 事故视频指向事发设备自己的流（按 deviceCode 各自一路）
          videoUrl: s.video ? `${base}/api/v1/device/video/stream?device_code=${s.dev}` : null,
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
      .filter(Boolean)
      // 图片地址按**当前请求**的 origin 输出（新数据是相对路径；历史绝对地址只重写 origin）
      .map((r) => ({ ...r, photoUrls: (r.photoUrls || []).map((u) => absolutizeMediaUrl(u, base)) }));
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

  // ── 视频流（按 deviceCode 各自一路：实时视频/事故视频/快照截帧均以 deviceCode 区分；接真实视频源后替换本段）──
  if (path === '/api/v1/device/video/snapshot.jpg' && req.method === 'GET') {
    const dev = url.searchParams.get('device_code') || 'TC-001';
    const png = makeFrame(frameIndex(), 320, 180, dev); // 该设备当前帧（时间驱动，与观看端一致）
    res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length, 'Access-Control-Allow-Origin': CORS_ORIGIN });
    return res.end(png);
  }
  if (path === '/api/v1/device/video/stream' && req.method === 'GET') {
    const dev = url.searchParams.get('device_code') || 'TC-001';
    res.writeHead(200, { 'Content-Type': 'multipart/x-mixed-replace; boundary=frame', 'Access-Control-Allow-Origin': CORS_ORIGIN });
    log(`[视频流] 客户端接入 device_code=${dev}（10fps 动画帧）`);
    const timer = setInterval(() => {
      const png = makeFrame(frameIndex(), 320, 180, dev); // 10fps；帧内容由时间驱动
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
        'Access-Control-Allow-Origin': CORS_ORIGIN,
      });
      return res.end(stored.buffer);
    }
    if (path.endsWith('.jpg') || path.endsWith('.png')) {
      const png = makeFrame(0);
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length, 'Access-Control-Allow-Origin': CORS_ORIGIN });
      return res.end(png);
    }
    return sendError(res, 404, 'not_found', 'Mock 未提供该媒体文件（占位）');
  }

  log('→ 404');
  sendError(res, 404, 'not_found', `未知接口：${req.method} ${path}`);
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error(`\n  端口 ${PORT} 已被占用：换端口启动（node server.js 8081）或先停掉占用的进程。\n`);
    process.exit(1);
  }
  throw e;
});

server.listen(PORT, HOST, () => {
  console.log('');
  console.log('  三角警示牌 Mock 服务端已启动（Ctrl+C 停止）');
  console.log(`  本机访问:   http://127.0.0.1:${PORT}`);
  const lan = [];
  const global6 = [];
  for (const infos of Object.values(os.networkInterfaces())) {
    for (const info of infos || []) {
      if (info.internal) continue;
      if (info.family === 'IPv4') {
        lan.push(`http://${info.address}:${PORT}`);
      } else if (info.family === 'IPv6') {
        // fe80:: 链路本地地址对客户端没用，只报全局地址（公网/局域网可达的那种）
        if (/^fe80:/i.test(info.address)) continue;
        global6.push(`http://[${info.address}]:${PORT}`);
      }
    }
  }
  for (const u of lan) console.log(`  局域网访问: ${u}   ← 真机同 Wi-Fi / 同热点时用这个地址`);
  for (const u of global6) console.log(`  IPv6 访问:  ${u}   ← 带方括号；设备需有 IPv6 且链路可达`);
  console.log('  自检:       http://127.0.0.1:' + PORT + '/  （浏览器打开可见设备列表）');
  console.log('');
  console.log('  ⚠ 本服务端无鉴权（无 token 时按默认用户 demo 处理）、无 IP 白名单、无限流。');
  console.log('    局域网联调可直接用；暴露到公网前请先加访问控制（见 README「公网部署」）。');
  console.log('');
});
