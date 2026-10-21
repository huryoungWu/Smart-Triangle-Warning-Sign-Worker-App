# APP 接口请求响应 JSON 文档 — UDP 实时视频流变更版

> **版本**：2026-10-21 / v1.0
> **适用对象**：后端开发（实现 APP ↔ 服务端接口）
> **文档性质**：本文是《APP接口请求响应JSON文档.md》的**增量变更文档**。
> 只列出本次「实时视频流 HTTP → UDP」改造涉及或受影响的接口；
> 登录、设备、状态、命令、任务、预警等未列出的接口**没有变化**，仍以原文档为准。
>
> **一句话结论**：实时视频流仍然使用逻辑接口
> `GET /api/v1/device/video/stream?device_code=...`，
> 但传输从 HTTP MJPEG 改为 **UDP**（与 HTTP 同端口号）；
> 单帧抓拍、快照上传、媒体回放仍走 HTTP。

---

## 目录

1. [本次变更摘要](#1-本次变更摘要)
2. [通用约定](#2-通用约定)
3. [实时视频流（UDP）](#3-实时视频流udp)
4. [道路风险列表（videoUrl 变更）](#4-道路风险列表videourl-变更)
5. [未变但相关接口](#5-未变但相关接口)
6. [兼容性与迁移](#6-兼容性与迁移)
7. [后端实现与验收清单](#7-后端实现与验收清单)
8. [附录：帧头字段与常量](#8-附录帧头字段与常量)

---

## 1. 本次变更摘要

| 接口 | 变更类型 | 说明 |
|---|---|---|
| `GET /api/v1/device/video/stream?device_code=` | **协议变更** | HTTP `multipart/x-mixed-replace` → **UDP**；逻辑 path/query/`device_code` 语义/10fps/错误码不变；原 HTTP 流端点已删除 |
| `GET /api/v1/risks?lat=&lng=&radiusKm=` | **字段值变更** | 响应 `risks[].videoUrl` 的实时流地址由 `http://...` 改为 **`udp://...`**；字段名/类型/含义不变 |
| `GET /api/v1/device/video/snapshot.jpg?device_code=` | 未变 | 仍为 HTTP 单帧；**不要改成 UDP** |
| `POST /api/v1/snapshots` | 未变 | 仍为 HTTP multipart；服务端仍从该设备视频流截当前帧 |
| `GET /api/v1/snapshots`、`GET /media/{name}` | 未变 | 仍为 HTTP 列表/媒体回放 |

> 旧版 APP 如果仍请求 HTTP `/api/v1/device/video/stream`，会收到 **404**；需随 APP 升级。

---

## 2. 通用约定

| 项 | 值 |
|---|---|
| HTTP Base URL | `http://<server>:<port>/api/v1` |
| 实时视频 UDP 地址 | `udp://<server>:<port>/api/v1/device/video/stream?...` |
| UDP 端口 | **与 HTTP 同号**（默认 `8080`；TCP 8080 与 UDP 8080 可同时监听） |
| 时间 | epoch 毫秒（Long，13 位） |
| `device_code` | 设备编号；所有设备相关接口统一使用；视频按 `device_code` 各自一路 |
| HTTP 鉴权 | `Authorization: Bearer <accessToken>` |
| UDP 鉴权 | UDP 没有 Header，`token` 放在订阅 `target` 的 query 里（短时效流 token 建议 5 分钟） |
| 错误格式 | HTTP 沿用原 `{ "error": { "code", "message", "detail?" } }`；UDP 用 `err` 报文承载同样的 `status` + `error` |
| 坐标系 | 风险/事故为 **GCJ-02（高德）**；车端 GPS 为 **WGS-84**；两者不可混用 |
| 字段完整性 | 必填字段缺失时返回 400，不补默认值；数值缺失用 `0`，非数值缺失用 `null` |

> Mock 服务端不校验 `token`；正式服务端应校验 `target` query 里的 `token`，失效返回
> `err` status 401。

---

## 3. 实时视频流（UDP）

### 3.1 逻辑接口（与原 HTTP 完全一致）

```
GET /api/v1/device/video/stream?device_code=TC-001[&token=][&w=640][&fps=10]
```

**接口含义**：获取指定设备的实时视频流；APP 任务执行页「实时视频」使用。

**调用时机**：进入任务执行页并显示视频卡片时订阅；离开页面发 `unsub` / 直接断开。

**传输**：UDP 单播；APP 用 `udp://<server>:<port>` + 原 request-target 订阅。
端口与 HTTP 相同，**不需要新增端口配置**。

### 3.2 请求参数（与原 query 完全一致）

| 参数 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 是 | 要观看的设备编号 |
| `token` | string | 否 | 短时效流 token；放在 `target` query；Mock 不校验 |
| `w` | int | 否 | 期望宽度；Mock **接受但忽略**（固定 320×180）；正式服务端可降采样 |
| `fps` | int | 否 | 期望帧率；Mock **接受但忽略**（固定 10fps）；正式服务端可降帧 |

无请求体。

### 3.3 会话时序

```
APP（UDP 客户端）                                服务端（UDP 与 HTTP 同端口）
      | -- sub {cid, target} ---------------------------> |
      | <-- ack {sid, status, format, fps, w} ----------- |
      | <-- FRAME 分片 × N（10fps，每帧 1~N 个 datagram） |
      | -- hb {sid} -------------------------------------> |  每 2s
      | -- hb {sid} -------------------------------------> |
      | -- unsub {sid} ----------------------------------> |  离开页面/取消
      |                                                   |  6s 无 hb 清理会话
```

### 3.4 订阅报文（客户端 → 服务端）

单个 UDP datagram，UTF-8 JSON：

```json
{
  "v": 1,
  "t": "sub",
  "cid": "a1b2c3d4",
  "target": "/api/v1/device/video/stream?device_code=TC-001&fps=10"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `v` | int | 是 | 协议版本，当前 `1` |
| `t` | string | 是 | `sub` |
| `cid` | string | 是 | 客户端随机串（建议 8~32 字符） |
| `target` | string | 是 | 原 HTTP request-target（path + query）；服务端用它校验 `device_code`/`token` |

- 同一 `(客户端地址:端口, cid)` 重复 `sub` 是**幂等**的：服务端只回 `ack`、刷新心跳，
  不重建流；用于 UDP 丢包后的订阅重试。
- 同地址:端口换 `cid` = 重新订阅，旧 session 被替换。

### 3.5 成功响应 ack（服务端 → 客户端）

单个 UDP datagram，UTF-8 JSON：

```json
{
  "v": 1,
  "t": "ack",
  "cid": "a1b2c3d4",
  "sid": 123456,
  "status": 200,
  "format": "image/png",
  "fps": 10,
  "w": 320
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `v` | int | 协议版本 `1` |
| `t` | string | `ack` |
| `cid` | string | 回显订阅的 cid |
| `sid` | int (u32) | 本次会话 id；后续帧头/心跳/退订都用它 |
| `status` | int | 200 成功 |
| `format` | string | `image/png` 或 `image/jpeg`（APP 用 `BitmapFactory` 自动识别） |
| `fps` | int | 实际帧率（Mock 为 10；正式服务端可回报实际值） |
| `w` | int | 实际宽度（Mock 为 320；正式服务端可回报实际值） |

### 3.6 失败响应 err（服务端 → 客户端）

单个 UDP datagram，UTF-8 JSON；`status`/`error` 与 HTTP 语义一致：

```json
{
  "v": 1,
  "t": "err",
  "cid": "a1b2c3d4",
  "status": 400,
  "error": {
    "code": "bad_request",
    "message": "缺少 device_code 查询参数"
  }
}
```

| status | code（示例） | 场景 | APP 行为 |
|---:|---|---|---|
| 400 | `bad_request` | `target` 非法 / 缺少 `device_code` | 停止，显示错误 |
| 401 | `token_expired` / `unauthorized` | 流 token 失效（正式服务端） | 停止，重新登录/刷新 token |
| 403 | `forbidden` | 无权观看该设备 | 停止，显示错误 |
| 404 | `not_found` | 未知接口 / 设备不存在 / 无摄像头 | 停止，显示错误 |
| 503 | `server_busy` | 会话数达到上限 | 1s 退避后重试 |
| 503 | `device_offline` | 车端/摄像头离线 | 1s 退避后重试 |

> 生产服务端不要在设备离线时返回 200 + 黑帧；应返回 `err` 或停止该设备的 UDP 发送。

### 3.7 帧数据（服务端 → 客户端，二进制）

24 字节头 + payload，单 datagram **≤ 1200 字节**（避免 IP 分片）；
**所有多字节字段均为大端（网络字节序）**：

| 偏移 | 长度 | 字段 | 说明 |
|---:|---:|---|---|
| 0 | 4 | `magic` | `"SPV1"`（ASCII） |
| 4 | 1 | `version` | `1` |
| 5 | 1 | `type` | `1` = FRAME |
| 6 | 4 | `sid` | session id（u32） |
| 10 | 4 | `frameId` | 每 deviceStream 递增（u32，模 2^32） |
| 14 | 4 | `frameLen` | 整帧字节数（u32，≤256KiB） |
| 18 | 2 | `fragIndex` | 分片下标，从 0 开始 |
| 20 | 2 | `fragCount` | 分片总数（≤223） |
| 22 | 2 | `payloadLen` | 本片 payload 长度 |
| 24 | N | `payload` | PNG/JPEG 原始字节，N ≤ 1176 |

**不变量：**

```
fragCount = ceil(frameLen / 1176)
除最后一片外，payloadLen = 1176
最后一片 payloadLen = frameLen - (fragCount - 1) * 1176
frameLen <= 256 KiB
单 datagram = 24 + payloadLen <= 1200
```

- `frameId` 是每台设备视频流自己的递增计数器，**不要用 `Date.now()/100` 等时间戳**
  （会超过 u32）；客户端用模 2^32 的有符号差判断新旧。
- 同一帧的分片可能乱序/重复；客户端只保留 1 个未完成帧，新 `frameId` 到来时丢弃旧帧，
  缺片则丢弃整帧（不做 NACK/重传）。
- 客户端收到 `ack` 之前丢弃所有二进制帧；`ack` 丢失时靠 1s 重发 `sub` 恢复。
- 帧格式由 `ack.format` 标识；Mock 是 PNG，生产可为 JPEG；APP 端自动识别。

### 3.8 心跳与退订（客户端 → 服务端）

```json
{"v":1,"t":"hb","sid":123456}
{"v":1,"t":"unsub","sid":123456}
```

| 报文 | 时机 | 服务端行为 |
|---|---|---|
| `hb` | `ack` 之后每 2s | 刷新该 session 的 `lastSeen` |
| `unsub` | 离开页面/取消 | 立即清理 session；最后一个观看端离开时停止该设备定时器 |

服务端 **6s** 收不到 `hb` 自动清理 session（Mock 可用 `UDP_SESSION_IDLE_MS` 覆盖）。
APP 侧 **3s** 收不到完整帧会关闭 socket、1s 后重连（换新 `cid`/新本地端口）。

### 3.9 服务端会话与扇出规则

- **每台设备一个 100ms 定时器**：只生成帧一次，再扇出给该设备的所有观看端
  （分片结果可复用，仅 `sid`/帧头不同）；不要每个观看端重复解码/编码。
- 同一设备多个观看端：各自一个 session；帧内容相同，帧头 `sid` 不同。
- 会话上限建议：总 session ≤64、同一客户端地址 ≤8（Mock 默认值），防止单机刷爆。
- `sid` 建议用随机 u32（不要用自增计数器），避免服务端重启后新旧 session 的
  `sid`/`frameId` 碰撞。
- 日志只打印 `device_code`、`sid`、订阅/退订/错误；**不要打印完整 `target`**
  （其中可能包含 `token`）。
- 控制报文上限 4KB；超限/JSON 解析失败直接忽略或回 400。

### 3.10 完整示例（一帧小于 1176 字节）

```
1) APP → 服务端  {"v":1,"t":"sub","cid":"a1b2c3d4",
                  "target":"/api/v1/device/video/stream?device_code=TC-001&fps=10"}
2) 服务端 → APP  {"v":1,"t":"ack","cid":"a1b2c3d4","sid":123456,"status":200,
                  "format":"image/png","fps":10,"w":320}
3) 服务端 → APP  24 字节头 + PNG：
                  magic "SPV1", version=1, type=1, sid=123456, frameId=1,
                  frameLen=1000, fragIndex=0, fragCount=1, payloadLen=1000
                  → 单 datagram 1024 字节
4) APP → 服务端  {"v":1,"t":"hb","sid":123456}          （每 2s）
5) APP → 服务端  {"v":1,"t":"unsub","sid":123456}       （离开页面）
```

若帧为 3000 字节：`fragCount=3`，前两片 `payloadLen=1176`，最后一片 648；
三个 datagram 的 `frameLen=3000`、`frameId` 相同，`fragIndex` 分别为 0/1/2。

---

## 4. 道路风险列表（videoUrl 变更）

### 4.1 接口

```
GET /api/v1/risks?lat=39.9088&lng=116.3975&radiusKm=2
Authorization: Bearer <accessToken>
```

**接口含义**：查询用户周边指定半径内的道路风险/事故；APP 风险页展示列表与地图点位。

**调用时机**：进入风险页、切换搜索半径/筛选/排序时。

### 4.2 请求参数（query，未变）

```json
{
  "lat": 39.9088,
  "lng": 116.3975,
  "radiusKm": 2
}
```

| 参数 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `lat` | double | 是 | 查询中心纬度，**GCJ-02**（通常是手机位置） |
| `lng` | double | 是 | 查询中心经度，**GCJ-02** |
| `radiusKm` | double | 是 | 搜索半径，单位公里；风险页常用 0.5 / 1 / 2 |

无请求体。

### 4.3 响应 200（只展示与本次变更相关的字段）

```json
{
  "risks": [
    {
      "id": "r-1000",
      "title": "小车抛锚占用第 4 车道",
      "type": "breakdown",
      "lat": 39.9102,
      "lng": 116.3981,
      "occurredAt": 1760083000000,
      "description": "一辆白色小轿车因发动机故障停在第 4 车道……",
      "lane": 4,
      "hasPhoto": false,
      "hasVideo": true,
      "photoUrls": [],
      "videoUrl": "udp://127.0.0.1:8080/api/v1/device/video/stream?device_code=TC-001"
    },
    {
      "id": "r-snap-0007",
      "title": "作业人员上报：任务开始（自动抓拍）",
      "type": "other",
      "lat": 39.9089,
      "lng": 116.3976,
      "occurredAt": 1760083200000,
      "description": "自动布置（任务启动自动抓拍）",
      "lane": 2,
      "hasPhoto": true,
      "hasVideo": false,
      "photoUrls": [
        "http://127.0.0.1:8080/media/snap-0007.png"
      ],
      "videoUrl": null,
      "source": "worker"
    }
  ]
}
```

### 4.4 字段说明（完整列表，未变字段一并列出）

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `risks` | array | 风险/事故列表 |
| `risks[].id` | string | 风险事件 id |
| `risks[].title` | string | 标题（列表主文案） |
| `risks[].type` | string | `breakdown` 抛锚 / `rear_end` 追尾 / `construction` 施工 / `rollover` 侧翻 / `debris` 散落物 / `other` 其他 |
| `risks[].lat` | double | 纬度，**GCJ-02** |
| `risks[].lng` | double | 经度，**GCJ-02** |
| `risks[].occurredAt` | long | 事故发生时间（毫秒） |
| `risks[].description` | string | 详细描述 |
| `risks[].lane` | int \| null | 占用车道（1~8）；`null` = 未标注 |
| `risks[].hasPhoto` | bool | 是否有照片 |
| `risks[].hasVideo` | bool | 是否有视频 |
| `risks[].photoUrls` | string[] | 照片 URL 列表；可为空数组；仍为 **HTTP** |
| `risks[].videoUrl` | string \| null | **实时流为 `udp://<server>:<port>/api/v1/device/video/stream?device_code=...`**；已录制录像可为 `http(s)://...mp4/.m3u8`；无视频为 `null` |
| `risks[].source` | string? | 仅作业人员上报的风险带此字段，值为 `"worker"`；外部数据无此字段 |

**`videoUrl` 语义要点：**

- 实时流：`udp://` + 与 HTTP 相同的 host/port + 原 path/query；
- 录像：`http(s)://...`（APP 用 ExoPlayer 播放）；
- **不要**再返回 `http://.../api/v1/device/video/stream`（该 HTTP 流已删除，会 404）；
- APP 事故详情页当前不展示视频，但字段保留；后端按上述格式返回即可。

---

## 5. 未变但相关接口

以下接口**本次没有修改**，后端不要因为视频改 UDP 而一起改：

### 5.1 单帧抓拍（HTTP）

```
GET /api/v1/device/video/snapshot.jpg?device_code=TC-001[&token=]
→ 200 Content-Type: image/jpeg
```

- 返回该设备视频流的**当前帧**（与 `POST /snapshots` 截帧同源）；
- Mock 实际返回 PNG，APP 自动识别；
- 缺少 `device_code` → 400 JSON 错误。

### 5.2 快照上传（HTTP）

```
POST /api/v1/snapshots
Authorization: Bearer <accessToken>
Content-Type: multipart/form-data
Idempotency-Key: <客户端快照 id>
```

- APP 当前只传元数据（`id`、`device_code`、`taskName`、`lane`、`eventLabel`、
  `capturedAt`、`note`，可选 `lat/lng`，图片部分预留）；
- 服务端仍从该 `device_code` 的视频流截当前帧，落盘并通过 `url` 回放；
- 仍自动登记为 `source=worker` 风险事件。

### 5.3 媒体回放（HTTP）

```
GET /media/{name}
→ 200 image/png | image/jpeg
```

- 快照/风险照片仍通过 HTTP 回放；与 UDP 视频流无关。

### 5.4 快照列表（HTTP）

```
GET /api/v1/snapshots?device_code=&from=&to=&page=1&size=20
→ { "items": [...], "page", "size", "total" }
```

- 未变。

---

## 6. 兼容性与迁移

| 场景 | 影响 | 处理 |
|---|---|---|
| 旧版 APP 请求 HTTP `/api/v1/device/video/stream` | 404（HTTP 流已删除） | 升级 APP；新版用 UDP |
| 旧 `videoUrl` 返回 `http://.../video/stream` | APP 不再使用；HTTP 地址 404 | `/risks` 改为返回 `udp://...` |
| `GET /device/video/snapshot.jpg` | 不受影响 | 继续用 HTTP |
| `POST /snapshots`、`/media` | 不受影响 | 继续用 HTTP |
| Android 17（targetSdk 37） | 局域网 UDP/HTTP 需要 `ACCESS_LOCAL_NETWORK` 运行时权限 | APP 已处理；后端无需改 |
| 模拟器 / USB 真机 | `adb reverse` 只转 TCP，UDP 不生效 | 模拟器用 `10.0.2.2`，真机用局域网 IP |
| Windows/Linux 防火墙 | 只放行 TCP 会看到「HTTP 正常、视频不可用」 | 同时放行 **UDP 同号端口** |

---

## 7. 后端实现与验收清单

### 7.1 实现要点

1. **端口**：在 HTTP 同号端口上额外监听 UDP（如 HTTP 8080 → UDP 8080）；
   IPv4/IPv6 双栈建议同时绑定 `0.0.0.0` 与 `[::]`（`ipv6Only`）。
2. **订阅**：解析 `sub.target`（按 URL/查询参数解析），要求 path 为
   `/api/v1/device/video/stream`，`device_code` 必填；正式服务端校验 `token`。
3. **ack**：返回随机 `sid`、`status=200`、`format`、实际 `fps/w`。
4. **帧源**：每台设备一路真实摄像头；服务端解码/转封装后按设备扇出，避免每个观看端重复编码。
5. **分片**：单 datagram ≤1200 字节；24 字节头；`fragCount = ceil(frameLen/1176)`。
6. **会话**：客户端每 2s `hb`；服务端 6s 无心跳清理；同一 `(地址:端口, cid)` 重复 `sub` 幂等。
7. **错误**：用 `err` 返回与 HTTP 相同的 `status`/`error.code`；设备离线/无摄像头返回 404 或 503，
   不要返回 200 空流/黑帧。
8. **上限**：限制总 session 与单地址 session；发送失败连续多次时移除该 session。
9. **日志**：只记录 `device_code`/`sid`/错误；不要记录完整 `target`（可能含 token）。
10. **旧路由**：删除 HTTP `/api/v1/device/video/stream`；单帧/快照/媒体接口保持 HTTP。

### 7.2 验收命令（Mock）

```bash
# 1) 取一帧（UDP），应写出一张 PNG
node tools/mock-server/udp-probe.js 127.0.0.1 8080 TC-001 tc001.png
node tools/mock-server/udp-probe.js 127.0.0.1 8080 TC-002 tc002.png
# 两张图应不同（不同设备各自一路）

# 2) 自动验收：协议、分片、心跳超时、HTTP 回归
node tools/mock-server/test-video-udp.js

# 3) 单帧仍为 HTTP
curl -o frame.png \
  "http://127.0.0.1:8080/api/v1/device/video/snapshot.jpg?device_code=TC-001"

# 4) 旧 HTTP 流应 404
curl -i "http://127.0.0.1:8080/api/v1/device/video/stream?device_code=TC-001"
```

> `curl` 不能验 UDP 流；请用 `udp-probe.js` 或真实 APP。

### 7.3 验收标准

- [ ] UDP 与 HTTP 同号端口同时监听；
- [ ] App 任务执行页能持续显示该设备画面（10fps 左右）；
- [ ] 换设备连接后画面/OSD 跟随变化；
- [ ] 停掉服务端 3s 内显示「视频流不可用」，重启后自动恢复；
- [ ] 离开页面后服务端 session 被清理；
- [ ] `GET /risks` 的 `videoUrl` 为 `udp://...`（有实时视频时）；
- [ ] `snapshot.jpg`、`POST /snapshots`、`/media` 仍为 HTTP 且可用；
- [ ] 旧 HTTP `/video/stream` 返回 404。

---

## 8. 附录：帧头字段与常量

### 8.1 帧头（24 字节，大端）

```
+--------+---------+--------+--------+----------+----------+-----------+-----------+------------+
| magic  | version | type   | sid    | frameId  | frameLen | fragIndex | fragCount | payloadLen |
| 4B     | 1B      | 1B     | 4B     | 4B       | 4B       | 2B        | 2B        | 2B         |
| "SPV1" | 1       | 1      | u32    | u32      | u32      | u16       | u16       | u16        |
+--------+---------+--------+--------+----------+----------+-----------+-----------+------------+
| payload（PNG/JPEG，N ≤ 1176 字节）                                                          |
+--------------------------------------------------------------------------------------------+
```

### 8.2 常量

| 常量 | 值 | 说明 |
|---|---:|---|
| 协议版本 | 1 | `v=1` |
| 帧头长度 | 24 | 固定 |
| 单 datagram 上限 | 1200 | 避免 IP 分片 |
| 单片 payload 上限 | 1176 | `1200 - 24` |
| 单帧上限 | 256 KiB | 超过则丢弃/记日志 |
| 最大分片数 | 223 | `ceil(256KiB / 1176)` |
| 默认帧率 | 10fps | Mock 固定；生产可支持 `fps` |
| 默认分辨率 | 320×180 | Mock 固定；生产可支持 `w` |
| 客户端心跳 | 每 2s | `hb` |
| 服务端会话空闲 | 6s | 无 `hb` 清理 |
| 客户端帧看门狗 | 3s | 无完整帧重连 |
| 客户端重连退避 | 1s | 换新 `cid` |

### 8.3 APP 侧行为（供后端对齐）

- 收到 `ack` 前丢弃所有二进制帧；`sub` 每 1s 重发直到 `ack`；
- `ack` 后每 2s 发 `hb`；3s 无完整帧关闭 socket，1s 后重连；
- 只保留 1 个未完成帧；新 `frameId` 丢弃旧帧；缺片丢整帧；
- `BitmapFactory` 自动识别 PNG/JPEG；
- 离开页面时发 `unsub` 并关闭 socket；
- UI 状态：「连接视频流…」→ 首帧后显示画面；无首帧/失败显示「视频流不可用」。

---

## 附：与原始文档的对应关系

| 本文 | 原《APP接口请求响应JSON文档.md》 |
|---|---|
| §2 通用约定 | 文档开头「通用字段约定」+ Base URL/鉴权 |
| §3 实时视频流（UDP） | §11 实时视频流（UDP） |
| §4 道路风险列表 | §10 道路风险列表（仅 `videoUrl` 字段值变化） |
| §5.1 单帧抓拍 | §21 单帧抓拍（未变） |
| §5.2 快照上传 | §9 快照上传（未变） |
| §5.4 快照列表 | §18 快照列表（未变） |

> 未在本表/本文列出的接口，本次均未修改。
