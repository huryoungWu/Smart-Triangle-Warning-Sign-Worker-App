# APP 接口请求响应 JSON 文档 — 锥桶布设变更版（单桶手动干预）

> **版本**：2026-10-21 / v1.0（对应原文档 v2.2）
> **适用对象**：后端开发（实现 APP ↔ 服务端接口）
> **文档性质**：本文是《APP接口请求响应JSON文档.md》的**增量变更文档**。
> 只列出本次「单桶手动干预（v2.2）」变更涉及或受影响的接口；
> 未列出的接口没有变化，仍以原文档为准。
>
> **本次变更**：新增单桶手动干预 `manual/move`、`manual/stop`；聚合状态 `devices[]`
> 增加 `pos` / `headingDeg` / `manualActive`；并配套放宽 `pause` 的调用时机。
>
> **一句话结论**：手动干预只在事件 `paused` 时可用，走独立接口而不是三角架
> `/device/command`，按住 300ms 保活、800ms 看门狗，手动移动计入该桶
> `traveledM` 并更新 `pos/headingDeg`。

---

## 目录

1. [本次变更摘要](#1-本次变更摘要)
2. [通用约定](#2-通用约定)
3. [单桶手动干预](#3-单桶手动干预)
4. [未变但相关接口](#4-未变但相关接口)
5. [兼容性与迁移](#5-兼容性与迁移)
6. [后端实现与验收清单](#6-后端实现与验收清单)
7. [附录：常量与字段速查](#7-附录常量与字段速查)

---

## 1. 本次变更摘要

| 接口 | 变更类型 | 说明 |
|---|---|---|
| `POST /api/v1/deployments/{id}/pause` | **语义放宽** | 任意非终止态均可调用；仅 `running→paused`，其余只停残余运动/手动，幂等；终止态 no-op |
| `POST /api/v1/deployments/{id}/manual/move` | **新增** | 单桶四向手动点动，仅 `paused`；`sessionId + seq` 防迟到包 |
| `POST /api/v1/deployments/{id}/manual/stop` | **新增** | 单桶手动停止，幂等；旧 session 的迟到 stop 不影响新按住 |
| `GET /api/v1/deployments/{id}` | **additive 字段** | `devices[]` 增加 `pos {x,y}`、`headingDeg`、`manualActive` |
| `POST /api/v1/device/command` | **行为收紧** | 对布设中的设备，`move` / `manual_stop` 返回 `{accepted:false, reason:"deployment_managed"}` |

> 旧版 APP 不调用 `manual/*`，也不依赖新增字段；旧版服务端没有这些接口时，
> 新版 APP 的手动干预不可用。

---

## 2. 通用约定

| 项 | 值 |
|---|---|
| Base URL | `http://<server>:<port>/api/v1` |
| 鉴权 | `Authorization: Bearer <accessToken>` |
| 时间 | epoch 毫秒（Long，13 位） |
| 事件与设备 | 事件 `deploymentId`；设备统一用 `device_code`、`device_type` |
| 设备类型 | `master_cone` / `slave_cone` |
| 错误格式 | `{ "error": { "code", "message", "detail?" } }` |
| 位置单位 | 米；`pos.x` = 东向、`pos.y` = 北向，均相对**部署起始线** |
| 朝向 | `headingDeg`：`0` = 正北，`90` = 正东，顺时针递增 |
| 手动保活 | APP 每 **300ms** 发一次；服务端 **800ms** 无保活自动停车 |
| 手动接口超时 | APP 对 `manual/*` 使用 **700ms** 读超时，服务端需快速返回 |

---

## 3. 单桶手动干预

### 3.1 前置流程与 pause 语义

APP 的操作顺序：

```
RUNNING → 点击「单桶手动干预」→ 二次确认 → 调 pause 暂停全部
        → 轮询到 PAUSED → 选择一只桶 → 按住方向键点动
        → 松手发 manual/stop → 关闭弹窗（事件保持 PAUSED）
        → 用户显式点「继续布设」才 resume
```

PAUSED 状态下直接进入手动干预，不再调用 pause。

**pause 语义调整**：`pause` 在**任意非终止状态**都可调用。

| 调用时状态 | 服务端行为 | 调用后状态 |
|---|---|---|
| `running` | 停止全部运动，清空手动状态，事件置暂停 | `paused` |
| `paused` | 幂等；停止残余运动/手动状态 | `paused` |
| `starting` | 只停止残余运动/手动状态，**不改变事件状态** | `starting` |
| `pending_verify` / `verify_failed` | 同上 | 不变 |
| `completed` / `aborted` / `failed` | no-op | 不变 |

```http
POST /api/v1/deployments/{deploymentId}/pause
{ "commandId": "uuid", "reason": "manual_intervention" }

→ 202 { "deploymentId": "dep-7f3a", "state": "paused" }
```

> 终止态也返回 `202` + 当前状态，不返回 409。

### 3.2 点动：`POST /manual/move`

```http
POST /api/v1/deployments/{deploymentId}/manual/move
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "commandId": "uuid",
  "sessionId": "hold-uuid",
  "seq": 1,
  "device_code": "CONE-M-001",
  "device_type": "master_cone",
  "dir": "fwd"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `commandId` | string | 是 | 本次请求 id（审计/幂等） |
| `sessionId` | string | 是 | 一次“按住”生成一次；同一次按住的保活包复用 |
| `seq` | int | 是 | 本次按住内递增（建议从 1 开始）；服务端拒绝迟到的低序号包 |
| `device_code` | string | 是 | 要控制的桶，必须在事件内 |
| `device_type` | string | 是 | `master_cone` / `slave_cone`，必须与事件记录一致 |
| `dir` | string | 是 | `fwd` / `back` / `left` / `right` |

**APP 发送节奏**：

- 按下立即发第一条，随后每 **300ms** 发一条保活包（`seq` 递增）；
- 松手立即发 `manual/stop`；
- 服务端 **800ms** 收不到保活自动停车（`manual_timeout`）。

**方向语义**：

- `fwd` / `back`：沿当前 `headingDeg` 前进/后退，更新 `traveledM` 与 `pos`；
- `left` / `right`：只改变 `headingDeg`（原地转向），不改变 `pos`；
- `traveledM` 限制在 `[0, targetM]`；前进到目标或后退到 0 时自动停止。

**响应 202**：

```json
{
  "deploymentId": "dep-7f3a",
  "state": "paused",
  "device_code": "CONE-M-001",
  "traveledM": 3.4,
  "targetM": 20.0,
  "phase": "stopped",
  "headingDeg": 90.0,
  "pos": { "x": 3.4, "y": 0.0 },
  "ignored": false
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `deploymentId` | string | 事件 id |
| `state` | string | 事件当前状态（手动期间应为 `paused`） |
| `device_code` | string | 被控制的桶 |
| `traveledM` | double | 该桶当前累计行程（已计入本次手动移动） |
| `targetM` | double | 该桶目标行程 |
| `phase` | string | 该桶阶段，手动期间保持 `stopped` |
| `headingDeg` | double | 当前朝向（0=正北） |
| `pos` | object | 相对起始线的位置，`x`=东、`y`=北，单位米 |
| `ignored` | bool | 本次请求未产生动作时可为 `true`（如旧 session 的迟到 stop）；APP 不依赖该字段做安全判断 |

**`sessionId + seq` 规则**：

- 同一 `sessionId` 内，`seq <= 当前 seq` 的保活包视为重复/乱序，HTTP 仍返回 202，但**不得重复推进**；
- `manual/stop` 之后，同一 `sessionId` 的迟到保活不得重新启动运动；
- 同一只桶开始新的按住（新 `sessionId`）可以接管旧 session；旧 session 的迟到 stop 不能停掉新按住；
- `ignored` 用于标识本次请求未产生动作（例如旧 session 的迟到 stop），Mock 对重复保活包可能仍返回 `false`，APP 不依赖该字段做安全判断。

### 3.3 停止：`POST /manual/stop`

```http
POST /api/v1/deployments/{deploymentId}/manual/stop
```

```json
{
  "commandId": "uuid",
  "sessionId": "hold-uuid",
  "seq": 12,
  "device_code": "CONE-M-001",
  "device_type": "master_cone"
}
```

- 响应 `202`，结构同 `manual/move`；
- **幂等**：没有 active 手动时直接成功；
- 旧 session 的迟到 stop 不会停掉新的按住（可返回 `ignored: true`）；
- 停止后该桶 `phase` 保持 `stopped`、`manualActive=false`，事件仍为 `paused`。

### 3.4 聚合状态新增字段：`GET /deployments/{id}`

`devices[]` 增加三个 additive 字段（旧客户端会忽略）：

```json
{
  "device_code": "CONE-M-001",
  "device_type": "master_cone",
  "order": 0,
  "online": true,
  "targetM": 20.0,
  "traveledM": 3.4,
  "pos": { "x": 3.4, "y": 0.0 },
  "headingDeg": 90.0,
  "manualActive": true,
  "phase": "stopped",
  "arrived": false
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `pos` | object | 相对**部署起始线**的米坐标：`x`=东、`y`=北 |
| `pos.x` | double | 东向偏移（米） |
| `pos.y` | double | 北向偏移（米） |
| `headingDeg` | double | 朝向，`0`=正北，`90`=正东，顺时针递增 |
| `manualActive` | bool | 该桶当前是否处于手动点动状态 |

> 旧服务端不返回 `pos` 时，APP 会退化为“按 `traveledM` 沿正北投影”；
> 要实现手动转向后的正确轨迹，后端必须返回 `pos` 与 `headingDeg`。

### 3.5 服务端状态机与约束

| 约束 | 说明 |
|---|---|
| 仅 `paused` | 事件不是 `paused` → `409 deployment_not_paused` |
| 设备必须属于事件 | 不存在/不在事件内 → `404 not_found` |
| 在线 | 离线 → `409 device_offline` |
| 未急停 | `estopLocked` / `safe_stop` → `409 estop_locked` |
| 阶段必须 `stopped` | 未启动/已到位/失败等 → `409 device_not_jogging` |
| 未跳过 | `skipped_manual` / `skip.skipped` → `409 device_skipped` |
| 未到目标 | `traveledM >= targetM` → `409 device_at_target` |
| 单桶互斥 | 同一事件已有另一只桶在手动 → `409 manual_busy` |

**手动期间**：

- 只影响所选桶，其他桶保持 `stopped`；
- 不触发下一只从桶、不写 report、不调用 `checkComplete`；
- 手动到 `targetM` 时保持 `phase=stopped`、`arrived=false`；
- `resume` 后按当前 `traveledM` 正常判定到达/触发后继，进入待核验；
- 审计步骤增加 `manual_move`（按住开始）、`manual_stop`（松手/停止）、`manual_timeout`（断保活）。

### 3.6 与三角架命令接口的隔离

布设中的设备只能走 `deployments/{id}/manual/*`。对布设设备的三角架命令：

```http
POST /api/v1/device/command
{ "device_code": "CONE-M-001", "type": "move", "dir": "fwd" }
→ 200 { "accepted": false, "reason": "deployment_managed" }
```

`move` 和 `manual_stop` 都要返回该拒绝原因；否则会出现“三角架接口静默无效”。

### 3.7 错误码汇总

| HTTP | code | 场景 |
|---:|---|---|
| 400 | `invalid_direction` | `dir` 不是 `fwd/back/left/right` |
| 400 | `invalid_device_type` | `device_type` 与事件设备不一致 |
| 400 | `bad_request` | 缺少 `sessionId` / `seq` 非法 |
| 404 | `not_found` / `deployment_not_found` | 事件/设备不存在或不在事件内 |
| 409 | `deployment_not_paused` | 事件未暂停（APP 应先暂停全部） |
| 409 | `device_not_jogging` | 设备不是 `stopped` |
| 409 | `device_skipped` | 已人工跳过 |
| 409 | `device_offline` | 设备离线 |
| 409 | `estop_locked` | 设备急停锁定/安全停止 |
| 409 | `manual_busy` | 同一事件已有另一只桶在手动 |
| 409 | `device_at_target` | 已到目标，不能继续前进 |

### 3.8 一次按住的完整时序

```
APP                                             服务端
 | -- manual/move {S, seq=1, dir=fwd} -----------> |  开始手动，返回 202
 | -- manual/move {S, seq=2, dir=fwd} -----------> |  每 300ms 保活
 | -- manual/move {S, seq=3, dir=fwd} -----------> |
 | -- manual/stop {S, seq=4} --------------------> |  立即停车
 | <-- 202 { manualActive=false } -----------------|
 |                                                |
 |  若 800ms 没有新保活：                          |
 |                                                |  manual_timeout，自动停车
```

---

## 4. 未变但相关接口

以下接口本次**没有修改**：

- `POST /api/v1/deployments/{id}/start`
- `POST /api/v1/deployments/{id}/resume`
- `POST /api/v1/deployments/{id}/abort`
- `POST /api/v1/deployments/{id}/retry`
- `POST /api/v1/deployments/{id}/skip`
- `POST /api/v1/deployments/{id}/verify`
- `POST /api/v1/deployments/{id}/complete`
- `GET /api/v1/deployments`（列表不返回 `devices[]`，因此没有新增字段）
- 设备台账、套装、绑定/解绑接口

> 自动布设路线仍然**不含转向段**：`left/right` 只是手动干预期间的原地转向，
> 不是自动路线的一部分。

---

## 5. 兼容性与迁移

| 场景 | 影响 | 处理 |
|---|---|---|
| 旧服务端 `pause` 只在 `running` 可用 | 从 `starting/pending_verify/verify_failed` 暂停会 409 | 按 §3.1 改为任意非终止态可用 |
| 旧服务端没有 `manual/*` | APP 手动干预不可用（404） | 实现 §3.2/§3.3 |
| 旧服务端不返回 `pos/headingDeg/manualActive` | APP 回退正北投影，手动转向后轨迹不准 | 返回新增字段 |
| 旧 APP 不支持手动干预 | 不会调用新接口 | 新接口对旧 APP 无影响 |
| 旧 APP 忽略新增字段 | 无影响 | `devices[]` 为 additive 扩展 |
| 三角架 APP 对布设设备发 `move` | 可能被静默接受或无效 | 返回 `deployment_managed` |

---

## 6. 后端实现与验收清单

### 6.1 实现要点

1. **pause**：任意非终止态可调用；只有 `running→paused`；`starting/pending_verify/verify_failed` 只清运动/手动；终止态 no-op。
2. **manual/move**：仅 `paused`；校验事件内、在线、`stopped`、未到位、未跳过、未急停；同一事件单桶互斥。
3. **session/seq**：同一 session 的低序号包忽略；stop 后的迟到包不得重启；新 session 可接管旧 session。
4. **看门狗**：800ms 无保活自动停车并记录 `manual_timeout`；APP 每 300ms 保活。
5. **位置/朝向**：`fwd/back` 更新 `traveledM`、`pos`；`left/right` 更新 `headingDeg`；`pos` 相对起始线，x=东、y=北。
6. **审计**：`manual_move` / `manual_stop` / `manual_timeout` 写入 `steps`。
7. **命令隔离**：`/api/v1/device/command` 对布设设备的 `move`/`manual_stop` 返回 `deployment_managed`。
8. **响应速度**：手动接口应在 700ms 内返回，避免 APP 读超时；不要阻塞在设备长指令上。

### 6.2 验收命令（Mock）

```bash
# 先创建一个布设事件并启动，或使用现有的进行中事件

# 暂停全部
curl -s -X POST http://127.0.0.1:8080/api/v1/deployments/<id>/pause \
  -H 'Content-Type: application/json' -d '{"reason":"manual"}'

# 手动点动
curl -s -X POST http://127.0.0.1:8080/api/v1/deployments/<id>/manual/move \
  -H 'Content-Type: application/json' \
  -d '{"commandId":"m1","sessionId":"hold-1","seq":1,
       "device_code":"CONE-M-001","device_type":"master_cone","dir":"fwd"}'

# 手动停止
curl -s -X POST http://127.0.0.1:8080/api/v1/deployments/<id>/manual/stop \
  -H 'Content-Type: application/json' \
  -d '{"commandId":"m2","sessionId":"hold-1","seq":2,
       "device_code":"CONE-M-001","device_type":"master_cone"}'

# 自动化验收（覆盖四向点动、保活、看门狗、暂停不触发后继）
node tools/mock-server/test-deployment.js
```

### 6.3 验收标准

- [ ] `pause` 在 `running/paused/starting/pending_verify/verify_failed/终态` 均不报 409；
- [ ] 非 `paused` 状态调用 `manual/move` 返回 `409 deployment_not_paused`；
- [ ] 四向点动均生效：`fwd/back` 改变 `traveledM/pos`，`left/right` 改变 `headingDeg`；
- [ ] 第二只桶同时手动返回 `409 manual_busy`；
- [ ] `manual/stop` 后同 session 的迟到保活不会重新启动；
- [ ] 800ms 无保活自动停车并记录 `manual_timeout`；
- [ ] 暂停期间手动越过 `d` 不触发后继；`resume` 后按当前行程正常处理；
- [ ] 聚合状态返回 `pos/headingDeg/manualActive`；
- [ ] 三角架 `/device/command` 对布设设备返回 `deployment_managed`；
- [ ] N>=1 的原有布设流程回归通过。

---

## 7. 附录：常量与字段速查

### 7.1 常量

| 常量 | 值 | 说明 |
|---|---:|---|
| 手动保活间隔 | 300ms | APP 发送 |
| 手动看门狗 | 800ms | 服务端无保活自动停 |
| 手动接口读超时 | 700ms | APP 侧 |
| 方向 token | `fwd/back/left/right` | 四向 |
| 朝向零点 | `0` = 正北 | `90` = 正东 |
| 位置单位 | 米 | `x`=东、`y`=北 |

### 7.2 与原文档的对应关系

| 本文 | 原《APP接口请求响应JSON文档.md》 |
|---|---|
| §3.1 pause 语义 | §27 暂停 / 继续 / 终止 |
| §3.2 / §3.3 手动点动与停止 | §32 单桶手动干预 |
| §3.4 新增设备字段 | §26 聚合状态 |
| §3.5 服务端状态机 | §32.4 审计与后续 |
| §3.6 三角架接口隔离 | §7 命令接口（行为收紧） |

> 未在本文列出的接口与字段，本次均未修改。
