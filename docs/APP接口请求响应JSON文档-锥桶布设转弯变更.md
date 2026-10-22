# APP 接口请求响应 JSON 文档 — 锥桶布设转弯变更版（单次 90° 左/右转）

> **版本**：2026-10-22 / v1.0（对应原文档）
> **注意**：本文的单转弯 `turn` 契约仍兼容，但**多转弯已由
> [《APP接口请求响应JSON文档-锥桶布设多转弯变更.md》](APP接口请求响应JSON文档-锥桶布设多转弯变更.md)
> 扩展**（`turns` 数组，最多 5 处）。新实现请优先阅读多转弯文档。
> **适用对象**：后端开发（实现 APP ↔ 服务端接口）
> **文档性质**：本文是《APP接口请求响应JSON文档.md》的**增量变更文档**。
> 只列出本次「锥桶布设自动转弯」变更涉及或受影响的接口；未列出的接口没有变化，仍以原文档为准。
>
> **本次变更**：创建布设事件 `POST /api/v1/deployments` 新增**可选** `turn` 字段；
> 创建/聚合响应 `params` 增加 `turn`；单次 90° 左/右转由服务端沿两段路线模拟并体现在
> `devices[].pos/headingDeg`。**不新增端点，不改变 `L/d/lane` 的既有语义。**
>
> **一句话结论**：`L` 仍是主桶沿路线累计总行程；`turn.atM` 是起点到拐角的累计距离
> （`0 < atM < L`）；服务端在 `atM` 处把 heading 从第一段方向转到左 270°/右 90°，
> 第二段继续走到各自 `targetM`。不传 `turn` 的旧客户端/旧事件完全走原直行逻辑。

---

## 目录

1. [本次变更摘要](#1-本次变更摘要)
2. [通用约定](#2-通用约定)
3. [创建布设事件（新增 turn）](#3-创建布设事件新增-turn)
4. [聚合状态中的 params.turn](#4-聚合状态中的-paramsturn)
5. [自动运动模型](#5-自动运动模型)
6. [兼容性与迁移](#6-兼容性与迁移)
7. [服务端 / 设备侧实现要求](#7-服务端--设备侧实现要求)
8. [验收清单](#8-验收清单)
9. [附录：常量与字段速查](#9-附录常量与字段速查)

---

## 1. 本次变更摘要

| 接口 | 变更类型 | 说明 |
|---|---|---|
| `POST /api/v1/deployments` | **additive 可选字段** | 新增 `turn: { dir, atM }`；缺省/null = 直行 |
| `POST /api/v1/deployments` 响应 | **additive 字段** | `params.turn` 回显；直行为 `null` |
| `GET /api/v1/deployments/{id}` | **additive 字段** | `params.turn` 回显转弯计划 |
| `GET /api/v1/deployments/{id}` 的 `devices[]` | **行为变化（有 turn 时）** | `pos/headingDeg` 沿两段路线变化；字段本身不变 |
| `POST /api/v1/deployments/{id}/start` | **无变化** | 请求/响应保持原样；转弯计划在创建时确定 |
| `manual/move`、`pause`、`resume`、`abort` 等 | **无变化** | 手动干预仍只在 `paused` 可用 |
| `GET /api/v1/deployments` 列表 | **无变化** | 列表不返回 `params`，不要求带 turn |

> 旧版 APP 不发送 `turn`，服务端按直行处理；旧版服务端忽略未知字段 `turn`，
> 新版 APP 会通过 `params.turn` 回显校验发现并取消这次创建（见 §6）。

---

## 2. 通用约定

| 项 | 值 |
|---|---|
| Base URL | `http://<server>:<port>/api/v1` |
| 鉴权 | `Authorization: Bearer <accessToken>` |
| 事件与设备 | 事件 `deploymentId`；设备统一用 `device_code`、`device_type` |
| 距离单位 | 米（m），0.1 m 精度 |
| 位置 | `devices[].pos.x` = 东向、`pos.y` = 北向，均相对**部署起始线** |
| 朝向 | `headingDeg`：`0` = 正北，`90` = 正东，顺时针递增 |
| 转弯角度 | v1 固定 **90°**；响应回显 `deg: 90`，请求不传角度 |
| 转弯点 | `turn.atM` 为从起点沿路线累计到拐角的距离，不是地图直线距离 |
| 路线 | 起点 → 第一段直线（累计 0 → `atM`）→ 转向 → 第二段直线（累计 `atM` → 各自 `targetM`） |

**方向定义**：`dir: "left"` / `"right"` 是相对于**第一段前进方向**的左/右。
Mock 中第一段固定为正北（`headingDeg=0`），因此左转后 `headingDeg=270`、右转后 `headingDeg=90`。
真实后端若第一段不是正北，应以设备的实际第一段朝向为基准解释 `left/right`，但返回的
`headingDeg` 仍必须是绝对朝向（0=正北）。

---

## 3. 创建布设事件（新增 turn）

```http
POST /api/v1/deployments
```

### 3.1 请求

在原有请求体上**追加**可选字段：

```json
{
  "clientRequestId": "uuid-left-turn",
  "name": "锥桶协同布设-左转",
  "lane": 2,
  "master": {
    "device_code": "CONE-M-001",
    "device_type": "master_cone",
    "binding_code": "123456"
  },
  "slaves": [
    { "device_code": "CONE-S-001", "device_type": "slave_cone", "binding_code": "123456", "order": 1 },
    { "device_code": "CONE-S-002", "device_type": "slave_cone", "binding_code": "123456", "order": 2 },
    { "device_code": "CONE-S-003", "device_type": "slave_cone", "binding_code": "123456", "order": 3 }
  ],
  "L": 60.0,
  "d": 10.0,
  "note": "",
  "turn": { "dir": "left", "atM": 30.0 }
}
```

右转只改方向：

```json
"turn": { "dir": "right", "atM": 30.0 }
```

直行（兼容旧行为）：**省略 `turn`** 或显式传 `null`：

```json
"turn": null
```

### 3.2 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `turn` | object/null | 否 | 可选单次转弯；省略/`null` = 直行 |
| `turn.dir` | string | 有 `turn` 时必填 | `left` / `right`，大小写不敏感（建议小写） |
| `turn.atM` | number | 有 `turn` 时必填 | 沿路线累计到拐角的距离（m），0.1 m 精度，`0 < atM < L` |
| `turn.deg` | number | 否 | **v1 固定 90，禁止自定义**；服务端可忽略，但建议对非 90 值返回 `invalid_turn` |

说明：

- `atM` 是**每台设备共同的路线距离**。所有设备从同一起点、同一第一段方向出发，
  因此同一个 `atM` 对每台设备都是同一个物理拐角。
- `targetM` 仍按原规则计算：主桶 `L`，从桶 k `L - k·d`。
- `targetM <= atM` 的设备不会过弯（在拐角前或拐角处停车）；`targetM == atM`
  时停在拐角处，不执行转向。
- `d` 仍是启动间隔，与 `atM` 无关；两者可以在同一位置/同一 tick 发生，触发链不受影响。

### 3.3 校验规则

| 规则 | 失败错误码 |
|---|---|
| `turn` 必须是对象（不能是数组/标量） | `invalid_turn` |
| `dir` 必须是 `left` 或 `right` | `invalid_turn` |
| `atM` 必须是有限数字 | `invalid_turn` |
| `atM` 精度最多 0.1 m | `invalid_turn` |
| `atM > 0` | `invalid_turn` |
| `atM < L` | `invalid_turn` |
| 显式 `deg != 90` | `invalid_turn` |

```json
400 { "error": { "code": "invalid_turn", "message": "turn.dir 必须为 left 或 right" } }
400 { "error": { "code": "invalid_turn", "message": "turn.atM 精度最多 0.1 m" } }
400 { "error": { "code": "invalid_turn", "message": "turn.atM 必须大于 0" } }
400 { "error": { "code": "invalid_turn", "message": "turn.atM 必须小于 L（60 ≥ 60）" } }
```

### 3.4 响应 `201`

带转弯时：

```json
{
  "deploymentId": "dep-7f3a",
  "eventId": null,
  "name": "锥桶协同布设-左转",
  "lane": 2,
  "state": "ready",
  "result": null,
  "params": {
    "L": 60.0,
    "d": 10.0,
    "N": 3,
    "unit": "m",
    "turn": { "dir": "left", "atM": 30.0, "deg": 90.0 }
  },
  "devices": [
    { "device_code": "CONE-M-001", "device_type": "master_cone", "order": 0, "targetM": 60.0, "traveledM": 0, "phase": "not_started", "arrived": false },
    { "device_code": "CONE-S-001", "device_type": "slave_cone", "order": 1, "targetM": 50.0, "traveledM": 0, "phase": "not_started", "arrived": false }
  ]
}
```

直行时：

```json
"params": {
  "L": 60.0,
  "d": 10.0,
  "N": 3,
  "unit": "m",
  "turn": null
}
```

> `params.turn` 必须原样回显创建时的转弯计划（`deg` 由服务端补 90）。
> APP 依赖该回显判断服务端是否真正支持转弯；缺失/不一致会导致 APP 取消这次 READY 事件。

---

## 4. 聚合状态中的 `params.turn`

```http
GET /api/v1/deployments/{deploymentId}
```

`params` 与创建响应一致：带转弯时回显 `{ dir, atM, deg }`，直行为 `null`。

转弯生效后，`devices[]` 的 `pos` / `headingDeg` 由服务端按两段路线计算。例如
`L=8, d=2, N=3, atM=4, dir=left` 全部到位后：

```json
"params": { "L": 8.0, "d": 2.0, "N": 3, "unit": "m",
            "turn": { "dir": "left", "atM": 4.0, "deg": 90.0 } },
"devices": [
  { "device_code": "CONE-M-001", "order": 0, "targetM": 8.0, "traveledM": 8.0, "pos": { "x": -4.0, "y": 4.0 }, "headingDeg": 270.0, "arrived": true },
  { "device_code": "CONE-S-001", "order": 1, "targetM": 6.0, "traveledM": 6.0, "pos": { "x": -2.0, "y": 4.0 }, "headingDeg": 270.0, "arrived": true },
  { "device_code": "CONE-S-002", "order": 2, "targetM": 4.0, "traveledM": 4.0, "pos": { "x": 0.0,  "y": 4.0 }, "headingDeg": 0.0,   "arrived": true },
  { "device_code": "CONE-S-003", "order": 3, "targetM": 2.0, "traveledM": 2.0, "pos": { "x": 0.0,  "y": 2.0 }, "headingDeg": 0.0,   "arrived": true }
]
```

右转示例 `L=6, d=2, N=1, atM=2, dir=right`：

```json
"turn": { "dir": "right", "atM": 2.0, "deg": 90.0 }
"devices": [
  { "order": 0, "targetM": 6.0, "pos": { "x": 4.0, "y": 2.0 }, "headingDeg": 90.0 },
  { "order": 1, "targetM": 4.0, "pos": { "x": 2.0, "y": 2.0 }, "headingDeg": 90.0 }
]
```

> APP 不重新计算路线几何，直接按 `pos` 画点；因此服务端必须保证 `pos/headingDeg`
> 与 `params.turn` 一致。`pos` 的坐标系与含义不变（东/北、相对起始线）。

---

## 5. 自动运动模型

### 5.1 两段路线

以起点 `(0,0)`、第一段朝向 `heading0` 为基准：

```
第一段：从累计 0 到 atM，沿 heading0 直行
拐角：  在累计 atM 处转向（左 = heading0 - 90°，右 = heading0 + 90°）
第二段：从累计 atM 到 targetM，沿新朝向直行
```

Mock 中 `heading0 = 0`（正北），所以：

- 左转：`heading = 270°`，第二段 `pos.x` 递减；
- 右转：`heading = 90°`，第二段 `pos.x` 递增；
- 第一段 `pos = (0, traveledM)`；
- 第二段 `pos = (0, atM) + (traveledM - atM) · (sin(heading), cos(heading))`。

### 5.2 与 `targetM` / `d` / 到位判定的关系

- `targetM` **不变**：主桶 `L`，从桶 `L - k·d`。它是沿路线的累计行程，包含两段。
- `d` **不变**：仍按每台设备自身 `traveledM` 达到 `d` 的边沿触发后继，
  与转弯点无关；`atM == d` 时两者在同一 tick 发生，不能因此漏触发或重复触发。
- 到位判定仍为 `traveledM >= targetM`；转弯本身**不消耗** `traveledM`。
- `targetM <= atM`：设备在第一段停车，不转向；`targetM == atM` 时停在拐角处，
  `headingDeg` 保持第一段朝向（Mock 为 0）。
- 超程异常（overshoot）仍按 `targetM + 2m` 处理；第二段继续沿新朝向。

### 5.3 一步跨过拐角

服务端按 tick 推进，可能在一次 tick 内跨过 `atM`。实现时必须把这一步拆成两段：

1. 先沿第一段走到 `atM`（本 tick 剩余距离不够时只走剩余）；
2. 到达/越过 `atM` 后把 heading 切到左/右转朝向；
3. 剩余距离沿第二段走完。

不能只用 `traveledM` 直接套公式重算 `pos`，否则手动干预造成的偏移会被“瞬移”回计划线。
Mock 采用**增量位移 + 每设备一次性 `turnApplied` 标记**：

- `turnApplied` 单向，持久化；
- 手动 `back` 退过 `atM` 不会重新转弯/反转；
- 手动 `fwd` 在暂停期间跨过 `atM`，恢复自动后的第一个 tick 补上转弯；
- 手动 `left/right` 改变过 heading 的，恢复自动时保留第一段实际 heading，
  到 `atM` 后按计划绝对朝向纠正。
- 手动干预造成的实际拐角与名义拐角有偏差，属于现场干预的预期代价，需在验收时知晓。

### 5.4 审计

每次设备执行转弯时，建议在 `chain.steps` 增加一条：

```json
{ "seq": 12, "type": "turn", "from": "CONE-M-001", "to": null,
  "dir": "left", "atM": 30.0, "deg": 90, "state": "done", "at": 1760083200000 }
```

Mock 已按该结构写入；正式后端可沿用或使用等价审计字段。

---

## 6. 兼容性与迁移

| 场景 | 行为 | 处理 |
|---|---|---|
| 旧 APP（不传 `turn`） | 服务端按直行处理，`params.turn = null` | 无影响 |
| 新 APP + 旧服务端 | 旧服务端忽略未知 `turn`，实际按直行执行 | APP 在创建后校验 `params.turn`；不一致会 `DELETE` 该 READY 事件并提示升级服务端 |
| 旧服务端响应没有 `params.turn` | APP 解析为 `null` | 同上，创建转弯事件时会被回显校验拦截 |
| 旧落盘事件没有 `turn` | 按直行处理 | 服务端加载时补 `turn = null`、`turnApplied = false` |
| 旧落盘事件有非法 `turn` | 丢弃并按直行 | 加载时做范围/方向校验 |
| 列表接口 `GET /deployments` | 不返回 `params` | 不要求新增字段；记录页不展示转弯 |

> **重要**：新 APP 对服务端回显做严格校验。后端如果实现了 `turn`，必须把
> `params.turn` 原样返回；只“接受字段但不回显”会被 APP 判定为不支持并取消创建。

---

## 7. 服务端 / 设备侧实现要求

### 7.1 服务端

1. 创建时校验并保存 `turn`，所有 `targetM/d` 计算保持原公式。
2. 启动时每台设备从 `(0,0)`、第一段朝向、`turnApplied=false` 开始。
3. 自动 tick 按 §5.3 增量推进，保证 `pos/headingDeg` 在 `atM` 后沿第二段变化。
4. `params.turn` 在创建响应、聚合状态中回显（`deg` 固定 90）。
5. 暂停/继续/终止/重试/跳过/核验/完成的既有语义不变。
6. 手动 `fwd/back` 继续更新 `traveledM` 和 `pos`；`left/right` 继续只改 heading。
7. 设备离线、超程、上报丢失等异常逻辑不变。

### 7.2 设备中继（`deploy_start`）

Mock 只模拟位置；**真实后端必须把转弯计划下发给设备**，否则物理锥桶仍会直行。
建议在现有 `deploy_start` 载荷上追加与创建接口同构的可选字段：

```json
{
  "type": "deploy_start",
  "deploymentId": "dep-7f3a",
  "commandId": "cmd-2",
  "device_code": "CONE-S-001",
  "device_type": "slave_cone",
  "order": 1,
  "targetM": 50.0,
  "intervalM": 10.0,
  "reportTo": "CONE-M-001",
  "successor": { "device_code": "CONE-S-002", "order": 2 },
  "noMove": false,
  "cause": "report",
  "turn": { "dir": "left", "atM": 30.0, "deg": 90.0 }
}
```

要求：

- 主桶启动、主桶触发从桶、从桶上报后触发下一只、重试 `deploy_start` 时，
  **都必须携带同一个 `turn`**，不能只在首次启动时下发。
- `targetM <= atM` 的设备建议不下发 `turn`（或下发 `null`），由服务端/设备双重保证不转向。
- 方向以第一段实际朝向为基准；设备内部应把 `dir/deg` 转成绝对 heading。
- 转向本身不增加 `traveledM`；转向耗时是否计入由设备侧决定，但不得改变 `targetM` 语义。
- 若设备不支持转向，建议后端在创建/启动时拒绝带 `turn` 的事件，而不是静默直行；
  当前协议没有硬件能力协商字段，可后续以设备能力表补充。

---

## 8. 验收清单

### 8.1 Mock 自动化

```bash
node tools/mock-server/test-deployment.js
```

应覆盖：

- 非法 `turn`（方向/缺少 atM/≤0/≥L/精度/自定义 deg）全部 `400 invalid_turn`；
- 直行事件 `params.turn === null`，原有 target/触发/完成断言不变；
- 左转 `L=8,d=2,N=3,atM=4`：最终 `pos/headingDeg`
  `[(-4,4)/270, (-2,4)/270, (0,4)/0, (0,2)/0]`；
- 右转 `L=6,d=2,N=1,atM=2`（`atM == d`）：`[(4,2)/90, (2,2)/90]`，触发链正常；
- 暂停中手动越过 `atM` 后继续：只转一次、不瞬移回名义拐角；
- 手动退过 `atM` 后继续：不反转、不重复转弯。

### 8.2 手工 curl

```bash
# 启动 Mock（端口按需）
node tools/mock-server/server.js 8080

# 创建左转事件
curl -s -X POST http://127.0.0.1:8080/api/v1/deployments \
  -H 'Content-Type: application/json' \
  -d '{
    "clientRequestId":"curl-left-1","name":"curl-left","lane":2,
    "master":{"device_code":"CONE-M-001","device_type":"master_cone","binding_code":"123456"},
    "slaves":[{"device_code":"CONE-S-001","device_type":"slave_cone","binding_code":"123456","order":1}],
    "L":8,"d":2,"turn":{"dir":"left","atM":4}
  }'

# 启动
curl -s -X POST http://127.0.0.1:8080/api/v1/deployments/<deploymentId>/start \
  -H 'Content-Type: application/json' -d '{"confirmed":true}'

# 轮询
curl -s http://127.0.0.1:8080/api/v1/deployments/<deploymentId>
```

验收标准：

1. 创建响应 `params.turn = {dir,atM,deg:90}`；
2. 轮询到位后主桶 `pos ≈ (-4,4)`、`headingDeg = 270`；
3. `targetM == atM` 的从桶 `headingDeg = 0`（不转弯）；
4. 不带 `turn` 的事件 `params.turn = null`，位置仍在 `x=0` 直线上。

---

## 9. 附录：常量与字段速查

| 项 | 值 |
|---|---|
| 转弯角度 | `deg = 90`（固定） |
| 方向 | `dir = left / right`（相对第一段前进方向） |
| 左转后 heading | 第一段为 0 时 = `270°` |
| 右转后 heading | 第一段为 0 时 = `90°` |
| `atM` 范围 | `(0, L)`，精度 0.1 m |
| 左转第二段位置 | `x = -(traveledM - atM)`，`y = atM` |
| 右转第二段位置 | `x = +(traveledM - atM)`，`y = atM` |
| 不转弯条件 | `targetM <= atM` |
| `d` 触发 | 仍按各设备 `traveledM >= d` 边沿，与 `atM` 无关 |
| 错误码 | `invalid_turn` |
| 响应回显 | `params.turn = {dir, atM, deg} | null` |
