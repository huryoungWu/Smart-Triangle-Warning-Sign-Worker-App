'use strict';

/**
 * UDP 视频协议 v1（零依赖，服务端 / probe / 测试共用）
 *
 * 控制报文是 UTF-8 JSON（单 datagram）：
 *   sub   {"v":1,"t":"sub","cid":"...","target":"/api/v1/device/video/stream?device_code=..."}
 *   ack   {"v":1,"t":"ack","cid":"...","sid":123,"status":200,"format":"image/png","fps":10,"w":320}
 *   err   {"v":1,"t":"err","cid":"...","status":400,"error":{"code":"bad_request","message":"..."}}
 *   hb    {"v":1,"t":"hb","sid":123}
 *   unsub {"v":1,"t":"unsub","sid":123}
 *
 * 数据报文是 24 字节头 + payload（大端；单 datagram ≤ 1200 字节）：
 *   0  4  magic "SPV1"
 *   4  1  version = 1
 *   5  1  type = 1 (FRAME)
 *   6  4  sid          u32
 *   10 4  frameId      u32（每 deviceStream 递增，模 2^32）
 *   14 4  frameLen     u32（整帧字节数）
 *   18 2  fragIndex    u16
 *   20 2  fragCount    u16
 *   22 2  payloadLen   u16
 *   24 N  payload
 *
 * 不变量：
 *   fragCount = ceil(frameLen / 1176)
 *   除最后一片外 payloadLen = 1176
 *   最后一片 = frameLen - (fragCount - 1) * 1176
 *   frameLen <= 256 KiB
 *
 * APP 侧 app/src/main/java/com/example/myapplication/core/video/UdpVideoProtocol.kt
 * 必须与本文件保持同一字段定义。
 */

const MAGIC = Buffer.from('SPV1', 'ascii');
const VERSION = 1;
const TYPE_FRAME = 1;
const HEADER_SIZE = 24;
const MAX_PAYLOAD = 1176;
const MAX_FRAME_BYTES = 256 * 1024;
const MAX_FRAGS = Math.ceil(MAX_FRAME_BYTES / MAX_PAYLOAD);

/** 模 2^32 判断 a 是否比 b 新（差值 < 2^31 视为前进） */
function isNewerFrameId(a, b) {
  const diff = (a - b) >>> 0;
  return diff !== 0 && diff < 0x80000000;
}

function encodeHeader({ sid, frameId, frameLen, fragIndex, fragCount, payloadLen }) {
  const header = Buffer.allocUnsafe(HEADER_SIZE);
  MAGIC.copy(header, 0);
  header[4] = VERSION;
  header[5] = TYPE_FRAME;
  header.writeUInt32BE(sid >>> 0, 6);
  header.writeUInt32BE(frameId >>> 0, 10);
  header.writeUInt32BE(frameLen >>> 0, 14);
  header.writeUInt16BE(fragIndex, 18);
  header.writeUInt16BE(fragCount, 20);
  header.writeUInt16BE(payloadLen, 22);
  return header;
}

/** 把一整帧切片为若干 datagram；返回 Buffer[]（每片 ≤ 1200 字节） */
function fragmentFrame(frame, sid, frameId) {
  const data = Buffer.isBuffer(frame) ? frame : Buffer.from(frame);
  if (data.length < 1) throw new RangeError('frame 不能为空');
  if (data.length > MAX_FRAME_BYTES) throw new RangeError(`frame 超过 ${MAX_FRAME_BYTES} 字节`);
  const fragCount = Math.ceil(data.length / MAX_PAYLOAD);
  if (fragCount > MAX_FRAGS) throw new RangeError(`fragCount 超过 ${MAX_FRAGS}`);
  const packets = new Array(fragCount);
  for (let i = 0; i < fragCount; i++) {
    const start = i * MAX_PAYLOAD;
    const end = Math.min(start + MAX_PAYLOAD, data.length);
    const payloadLen = end - start;
    const header = encodeHeader({
      sid,
      frameId,
      frameLen: data.length,
      fragIndex: i,
      fragCount,
      payloadLen,
    });
    packets[i] = Buffer.concat([header, data.subarray(start, end)], HEADER_SIZE + payloadLen);
  }
  return packets;
}

/** 解析一个数据 datagram；非法返回 null */
function parseFragment(buf) {
  if (!Buffer.isBuffer(buf) || buf.length < HEADER_SIZE) return null;
  if (!buf.subarray(0, 4).equals(MAGIC)) return null;
  if (buf[4] !== VERSION || buf[5] !== TYPE_FRAME) return null;

  const sid = buf.readUInt32BE(6);
  const frameId = buf.readUInt32BE(10);
  const frameLen = buf.readUInt32BE(14);
  const fragIndex = buf.readUInt16BE(18);
  const fragCount = buf.readUInt16BE(20);
  const payloadLen = buf.readUInt16BE(22);

  if (frameLen < 1 || frameLen > MAX_FRAME_BYTES) return null;
  if (fragCount < 1 || fragCount > MAX_FRAGS) return null;
  if (fragCount !== Math.ceil(frameLen / MAX_PAYLOAD)) return null;
  if (fragIndex >= fragCount) return null;
  const expected = Math.min(MAX_PAYLOAD, frameLen - fragIndex * MAX_PAYLOAD);
  if (payloadLen !== expected) return null;
  if (buf.length !== HEADER_SIZE + payloadLen) return null;

  return {
    sid,
    frameId,
    frameLen,
    fragIndex,
    fragCount,
    payloadLen,
    payload: Buffer.from(buf.subarray(HEADER_SIZE)),
  };
}

/**
 * 单帧重组器（probe/test 用；与 APP 的 FrameAssembler 逻辑一致）：
 * - 同一时刻只保留 1 个未完成帧；
 * - 新 frameId 丢弃旧帧；乱序/重复片忽略；
 * - 完整时返回整帧 Buffer，否则 null。
 */
class FrameAssembler {
  constructor() {
    this.reset();
  }

  reset() {
    this.frame = null;
    this.lastSid = null;
    this.lastFrameId = null;
  }

  accept(fragment) {
    if (!fragment) return null;

    const sameFrame = this.frame &&
      this.frame.sid === fragment.sid &&
      this.frame.frameId === fragment.frameId;

    if (!sameFrame) {
      // 同一 sid 的旧帧：丢弃
      if (this.frame && this.frame.sid === fragment.sid &&
          !isNewerFrameId(fragment.frameId, this.frame.frameId)) {
        return null;
      }
      if (this.lastSid === fragment.sid && this.lastFrameId !== null &&
          !isNewerFrameId(fragment.frameId, this.lastFrameId)) {
        return null;
      }
      this.frame = {
        sid: fragment.sid,
        frameId: fragment.frameId,
        frameLen: fragment.frameLen,
        fragCount: fragment.fragCount,
        received: new Uint8Array(fragment.fragCount),
        got: 0,
        buffer: Buffer.allocUnsafe(fragment.frameLen),
      };
    }

    const f = this.frame;
    if (f.received[fragment.fragIndex]) return null; // 重复片
    fragment.payload.copy(f.buffer, fragment.fragIndex * MAX_PAYLOAD);
    f.received[fragment.fragIndex] = 1;
    f.got += 1;
    if (f.got !== f.fragCount) return null;

    const completed = f.buffer;
    this.lastSid = f.sid;
    this.lastFrameId = f.frameId;
    this.frame = null;
    return completed;
  }
}

module.exports = {
  MAGIC,
  VERSION,
  TYPE_FRAME,
  HEADER_SIZE,
  MAX_PAYLOAD,
  MAX_FRAME_BYTES,
  MAX_FRAGS,
  isNewerFrameId,
  encodeHeader,
  fragmentFrame,
  parseFragment,
  FrameAssembler,
};
