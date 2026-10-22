# APP 接口请求响应 JSON 文档 — 锥桶布设多转弯变更版（最多 5 处 90° 转弯）

> **版本**：2026-10-22 / v1.0（对应原文档与单转弯变更文档）
> **适用对象**：后端开发（实现 APP ↔ 服务端接口）
> **文档性质**：本文是《APP接口请求响应JSON文档.md》的**增量变更文档**。
> 只列出本次「锥桶布设多处自动转弯」变更涉及或受影响的接口；未列出的接口没有变化，仍以原文档为准。
>
> **本次变更**：创建布设事件在单转弯 `turn` 的基础上新增可选数组 `turns`（最多 5 处，
> 每处固定 90°）；响应 `params.turns` 始终返回，`params.turn` 保留为单转弯兼容别名。
> **不新增端点，不改变 `L/d/targetM/触发/到达/核验` 语义。**
>
> **一句话结论**：`turns = [{ dir, atM }, ...]`，`atM` 严格递增且 `0 < atM < L`；
> 服务端按 atM 顺序在每处转 90°，多段折线由 `pos/headingDeg` 体现。

---

## 目录

1. [本次变更摘要](#1-本次变更摘要)
2. [通用约定](#2-通用约定)
3. [创建布设事件（新增 turns）](#3-创建布设事件新增-turns)
4. [聚合状态中的 params.turns](#4-聚合状态中的-paramsturns)
5. [自动运动模型](#5-自动运动模型)
6. [兼容性与迁移](#6-兼容性与迁移)
7. [服务端 / 设备侧实现要求](#7-服务端--设备侧实现要求)
8. [验收清单](#8-验收清单)
9. [附录：常量与字段速查](#9-附录常量与字段速查)

---

## 1. 本次变更摘要

| 接口 | 变更类型 | 说明 |
|---|---|---|
| `POST /api/v1/deployments` | **additive 可选字段** | 新增 `turns: [{dir, atM}, ...]`；最多 5 处；旧字段 `turn` 仍接受 |
| `POST /api/v1/deployments` 响应 | **additive 字段** | `params.turns` 始终返回（直行 `[]`）；`params.turn` 仅单转弯时非 null |
| `GET /api/v1/deployments/{id}` | **additive 字段** | `params.turns` / `params.turn` 回显 |
| `GET /api/v1/deployments/{id}` 的 `devices[]` | **行为变化（有 turns 时）** | `pos/headingDeg` 沿多段折线变化；字段本身不变 |
| `POST /api/v1/deployments/{id}/start` 等控制接口 | **无变化** | 转弯计划在创建时确定 |
| `GET /api/v1/deployments` 列表 | **无变化** | 列表不返回 `params` |

> 旧版 App 不发送 `turns`；单转弯仍发送旧字段 `turn`，新服务端会包装成单元素 `turns`
> 并同时回显 `params.turn`，保证旧 App 与单转弯服务端双兼容。多转弯是新增能力，
> 旧服务端忽略 `turns` 时 App 会通过回显校验取消 READY 事件。

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
| 转弯角度 | 每处固定 **90°**；响应回显 `deg: 90`，请求不传角度 |
| 转弯点 | `turns[i].atM` 为从起点沿路线累计到第 i 个拐角的距离，不是地图直线距离 |
| 转弯数量 | 1～5 处（`turns` 为空 = 直行） |
| 顺序 | `0 < atM1 < atM2 < … < atMn < L`，严格递增，0.1 m 精度 |

**方向定义**：`dir: "left"` / `"right"` 是相对于**计划中的前一段朝向**的左/右。
Mock 第一段固定为正北（`headingDeg=0`）；真实后端若第一段不是正北，应以其实际第一段朝向为基准。
到每个转弯点时，服务端把设备朝向设为预计算的**绝对 heading**（见 §5.1）。

---

## 3. 创建布设事件（新增 turns）

```http
POST /api/v1/deployments
```

### 3.1 请求

多转弯（推荐）：

```json
{
  "clientRequestId": "uuid-multi-turn",
  "name": "锥桶协同布设-两处转弯",
  "lane": 2,
  "master": {
    "device_code": "CONE-M-001",
    "device_type": "master_cone",
    "binding_code": "123456"
  },
  "slaves": [
    { "device_code": "CONE-S-001", "device_type": "slave_cone", "binding_code": "123456", "order": 1 },
    { "device_code": "CONE-S-002", "device_type": "slave_cone", "binding_code": "123456", "order": 2 }
  ],
  "L": 12.0,
  "d": 3.0,
  "note": "",
  "turns": [
    { "dir": "left",  "atM": 4.0 },
    { "dir": "right", "atM": 8.0 }
  ]
}
```

单转弯（兼容旧字段，仍可用）：

```json
"turn": { "dir": "left", "atM": 30.0 }
```

直行：省略 `turns` / 传 `null` / 传空数组 `[]`。

### 3.2 字段说明

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `turns` | array/null | 否 | 转弯序列；缺省/null/`[]` = 直行 |
| `turns[].dir` | string | 是 | `left` / `right`，大小写不敏感（建议小写） |
| `turns[].atM` | number | 是 | 沿路线累计到该拐角的距离（m），0.1 m 精度，严格递增，`0 < atM < L` |
| `turns[].deg` | number | 否 | **v1 固定 90，禁止自定义**；显式非 90 返回 `invalid_turn` |
| `turn` | object/null | 否 | **旧单转弯字段**，等价于 `turns:[turn]`；不能与非空 `turns` 同时出现 |

规则细节：

- `turns` 最多 **5** 处；超过返回 `invalid_turn`。
- `turns: []` 与 `null` 等价于直行；若同时给了非空 `turn`，以 `turn` 为准。
- 非空 `turns` 与非空 `turn` 同时出现 → `400 invalid_turn`，避免语义歧义。
- `atM` 必须严格递增；重复或倒序 → `invalid_turn`。
- `targetM <= atM` 的设备不会经过该转弯点，也不会应用它及后续转弯。
- `d` 与 `atM` 无关；`atM == d` 时二者在同一 tick 发生，触发链必须不受影响。

### 3.3 校验规则

| 规则 | 失败错误码 |
|---|---|
| `turns` 必须是数组 | `invalid_turn` |
| 数量 1～5（空数组 = 直行） | `invalid_turn` |
| 每项必须是对象 | `invalid_turn` |
| `dir` 必须是 `left` / `right` | `invalid_turn` |
| `atM` 必须是有限数字 | `invalid_turn` |
| `atM` 精度最多 0.1 m | `invalid_turn` |
| `atM > 0` 且 `atM < L` | `invalid_turn` |
| `atM` 严格递增 | `invalid_turn` |
| 显式 `deg != 90` | `invalid_turn` |
| `turn` 与非空 `turns` 同时出现 | `invalid_turn` |

错误示例：

```json
400 { "error": { "code": "invalid_turn", "message": "第 2 个转弯的 atM 必须大于前一个转弯点（4 m）" } }
400 { "error": { "code": "invalid_turn", "message": "转弯不能超过 5 处" } }
400 { "error": { "code": "invalid_turn", "message": "turn 与 turns 不能同时出现" } }
```

### 3.4 响应 `201`

多转弯：

```json
{
  "deploymentId": "dep-7f3a",
  "eventId": null,
  "name": "锥桶协同布设-两处转弯",
  "lane": 2,
  "state": "ready",
  "result": null,
  "params": {
    "L": 12.0,
    "d": 3.0,
    "N": 2,
    "unit": "m",
    "turns": [
      { "dir": "left",  "atM": 4.0, "deg": 90.0 },
      { "dir": "right", "atM": 8.0, "deg": 90.0 }
    ],
    "turn": null
  },
  "devices": [
    { "device_code": "CONE-M-001", "device_type": "master_cone", "order": 0, "targetM": 12.0, "traveledM": 0, "phase": "not_started", "arrived": false },
    { "device_code": "CONE-S-001", "device_type": "slave_cone", "order": 1, "targetM": 9.0, "traveledM": 0, "phase": "not_started", "arrived": false },
    { "device_code": "CONE-S-002", "device_type": "slave_cone", "order": 2, "targetM": 6.0, "traveledM": 0, "phase": "not_started", "arrived": false }
  ]
}
```

单转弯：

```json
"params": {
  "L": 8.0, "d": 2.0, "N": 1, "unit": "m",
  "turns": [{ "dir": "left", "atM": 4.0, "deg": 90.0 }],
  "turn":  { "dir": "left", "atM": 4.0, "deg": 90.0 }
}
```

直行：

```json
"params": {
  "L": 8.0, "d": 2.0, "N": 1, "unit": "m",
  "turns": [],
  "turn": null
}
```

> **回显规则**：
> - `params.turns` 始终存在；
> - `params.turn` **仅当恰好 1 处转弯时**返回该对象，0 处或 ≥2 处为 `null`；
> - App 用该回显判断服务端是否真正支持转弯；缺失/不一致会取消 READY 事件。

---

## 4. 聚合状态中的 `params.turns`

```http
GET /api/v1/deployments/{deploymentId}
```

`params` 与创建响应一致。`devices[].pos/headingDeg` 由服务端按多段折线计算。

S 形示例 `L=12, d=3, N=2, turns=[left@4, right@8]` 全部到位后：

```json
"params": {
  "L": 12.0, "d": 3.0, "N": 2, "unit": "m",
  "turns": [
    { "dir": "left",  "atM": 4.0, "deg": 90.0 },
    { "dir": "right", "atM": 8.0, "deg": 90.0 }
  ],
  "turn": null
},
"devices": [
  { "order": 0, "targetM": 12.0, "pos": { "x": -4.0, "y": 8.0 }, "headingDeg": 0.0,   "arrived": true },
  { "order": 1, "targetM": 9.0,  "pos": { "x": -4.0, "y": 5.0 }, "headingDeg": 0.0,   "arrived": true },
  { "order": 2, "targetM": 6.0,  "pos": { "x": -2.0, "y": 4.0 }, "headingDeg": 270.0, "arrived": true }
]
```

连续两个左转示例 `L=8, turns=[left@2, left@4]`：

```json
"turns": [
  { "dir": "left", "atM": 2.0, "deg": 90.0 },
  { "dir": "left", "atM": 4.0, "deg": 90.0 }
],
"devices": [
  { "order": 0, "targetM": 8.0, "pos": { "x": -2.0, "y": -2.0 }, "headingDeg": 180.0 }
]
```

> App 不重新计算路线几何，直接按 `pos` 画点；服务端必须保证 `pos/headingDeg`
> 与 `params.turns` 一致。`pos` 坐标系与含义不变（东/北、相对起始线）。

---

## 5. 自动运动模型

### 5.1 计划朝向序列

第一段朝向 `H0 = 0`（Mock 为正北）。对第 i 处转弯：

```
H_i = (H_{i-1} + (dir == "left" ? -90 : +90) + 360) % 360
```

到第 i 个转弯点时，设备朝向设为 **H_i**（绝对朝向），然后沿 H_i 走下一段。
因此：

- 单转弯从正北出发：左转 H=270°、右转 H=90°（与单转弯版本一致）；
- 左转后右转：H 依次为 270°、0°；
- 连续两个左转：H 依次为 270°、180°。

> 手动 `left/right` 造成的实际 heading 偏差，会在下一个计划转弯点被纠正为 H_i；
> 这与单转弯版本“到拐角按计划绝对朝向”的约定一致。

### 5.2 位置与目标

以累计行程 `traveledM` 为自变量：

```
第 0 段：从 0 到 atM1，沿 H0
第 i 段：从 atMi 到 atM(i+1)，沿 H_i
最后一段：从 atMn 到 targetM，沿 H_n
```

- `targetM` **不变**：主桶 `L`，从桶 `L - k·d`；它是沿整条路线的累计行程。
- `d` **不变**：仍按每台设备自身 `traveledM >= d` 的边沿触发后继，与 `atM` 无关。
- `targetM <= atM_i` 的设备不经过该转弯点，也不会应用它及后续转弯；
  `targetM == atM_i` 时停在拐角处，`headingDeg` 保持上一段朝向。
- 转弯本身**不消耗** `traveledM`。
- 到位判定仍为 `traveledM >= targetM`。

### 5.3 一步跨过多个转弯点

服务端按 tick 推进，一次 `step` 可能跨过多个 `atM`。实现要求：

1. 先处理“手动已越过”的待转点（`traveledM >= atM` 且 `targetM > atM`），按顺序应用；
2. 再沿当前段前进，遇到下一个 `atM` 就先走到该点、应用 H_i、`nextTurnIndex++`；
3. 剩余距离继续下一段，循环直到走完或到达目标；
4. 每台设备维护**单向** `nextTurnIndex`（已应用转弯数），持久化；
5. 不能用 `traveledM` 直接重算 `pos`，必须增量位移，避免手动干预后瞬移。

Mock 每个转弯写一条 `type:"turn"` 审计步骤：

```json
{ "seq": 12, "type": "turn", "from": "CONE-M-001", "to": null,
  "turnIndex": 1, "dir": "left", "atM": 4.0, "deg": 90,
  "headingDeg": 270, "state": "done", "at": 1760083200000 }
```

---

## 6. 兼容性与迁移

| 客户端 / 服务端 | 直行 | 单转弯 | 多转弯 |
|---|---|---|---|
| 旧 App / 新服务端 | 正常 | 正常（旧 App 发 `turn`，新服务端回显 `params.turn`） | 旧 App 可查看：地图按 `pos/headingDeg` 画出所有拐弯；忽略 `turns`，副标题可能不显示 |
| 新 App / 旧单转弯服务端 | 正常 | 正常（新 App 对单转弯继续发 `turn`） | 旧服务端忽略 `turns`；App 回显校验失败 → `DELETE` READY 并提示升级 |
| 新 App / 新服务端 | 正常 | 正常（新旧字段均兼容） | 正常（`turns`） |

迁移与持久化：

- 旧落盘事件只有 `turn` / `turnApplied`：加载时分别迁移为 `turns:[turn]` / `nextTurnIndex`；
- 旧事件没有转弯：`turns=[]`，按直行处理；
- 多转弯事件的回滚到旧服务端不受支持；旧服务端会把 `turns` 当作未知字段忽略；
- 新 App 对回显做严格校验：`turns` 数组必须结构合法且数量/顺序/方向/atM 完全一致，
  单转弯时新旧字段若同时出现必须一致。

---

## 7. 服务端 / 设备侧实现要求

### 7.1 服务端

1. 创建时校验并保存 `turns`（最多 5、严格递增、0.1 m 精度、`0<atM<L`）。
2. `params.turns` 在创建响应、聚合状态中始终返回；单转弯同时返回 `params.turn` 别名。
3. 启动时每台设备从 `(0,0)`、第一段朝向、`nextTurnIndex=0` 开始。
4. 自动 tick 按 §5.3 增量推进；一次 tick 可跨多个转弯点。
5. `targetM/d/触发/到达/暂停/继续/终止/重试/跳过/核验` 语义全部不变。
6. 手动 `fwd/back` 继续更新 `traveledM` 和 `pos`；`left/right` 继续只改 heading。
7. 旧数据迁移：`turn` → `turns`，`turnApplied` → `nextTurnIndex`。

### 7.2 设备中继（`deploy_start`）

Mock 只模拟位置；**真实后端必须把完整转弯计划下发给设备**。建议在现有
`deploy_start` 载荷上追加与创建接口同构的 `turns` 数组：

```json
{
  "type": "deploy_start",
  "deploymentId": "dep-7f3a",
  "commandId": "cmd-2",
  "device_code": "CONE-S-001",
  "device_type": "slave_cone",
  "order": 1,
  "targetM": 9.0,
  "intervalM": 3.0,
  "reportTo": "CONE-M-001",
  "successor": { "device_code": "CONE-S-002", "order": 2 },
  "noMove": false,
  "cause": "report",
  "turns": [
    { "dir": "left",  "atM": 4.0, "deg": 90.0 },
    { "dir": "right", "atM": 8.0, "deg": 90.0 }
  ]
}
```

要求：

- 主桶启动、主桶触发从桶、上报后触发下一只、重试 `deploy_start` 时都必须携带**同一份完整 turns**；
- 建议对每台设备过滤：只下发 `atM < targetM - eps` 的转弯，避免设备在目标点后继续转；
- `dir` 相对第一段实际朝向；设备内部转成绝对 heading；
- 转向本身不增加 `traveledM`；
- 若设备协议使用 `segments`，注意现有单设备协议对段数（≤10）和 fwd 最小长度（1 m）的限制；
  最多 5 处转弯会产生 6 段前进，建议优先使用原生 `turns` 字段；
- 旧固件只懂 `turn`：后端应对多转弯事件拒绝/提示固件升级，不能静默让设备直行。

---

## 8. 验收清单

### 8.1 Mock 自动化

```bash
node tools/mock-server/test-deployment.js
```

覆盖：

- 单转弯旧字段 `turn`：响应同时有 `params.turns`（1 项）与 `params.turn`；
- 非法多转弯：非数组、坏 dir、非递增、重复、超过 5 处、`atM>=L`、`turn`+`turns` 同时出现；
- S 形 `L=12,d=3,N=2,turns=[left@4,right@8]`：
  - 主桶 `(-4,8)/0`、从1 `(-4,5)/0`、从2 `(-2,4)/270`；
  - 从2 的目标 6 m 在第二个转弯点 8 m 之前，因此不过第二弯；
- 连续两个左转 `L=8,turns=[left@2,left@4]`：主桶 `(-2,-2)/180`；
- 一步跨多处转弯（纯函数测试）：不丢转、不重复；
- `targetM == atM`：停在拐角不转；
- 手动越过多个转弯后 resume：只补转一次、不瞬移。

### 8.2 手工 curl

```bash
node tools/mock-server/server.js 8080

curl -s -X POST http://127.0.0.1:8080/api/v1/deployments \
  -H 'Content-Type: application/json' \
  -d '{
    "clientRequestId":"curl-multi-1","name":"curl-multi","lane":2,
    "master":{"device_code":"CONE-M-001","device_type":"master_cone","binding_code":"123456"},
    "slaves":[{"device_code":"CONE-S-001","device_type":"slave_cone","binding_code":"123456","order":1},
              {"device_code":"CONE-S-002","device_type":"slave_cone","binding_code":"123456","order":2}],
    "L":12,"d":3,
    "turns":[{"dir":"left","atM":4},{"dir":"right","atM":8}]
  }'

curl -s -X POST http://127.0.0.1:8080/api/v1/deployments/<deploymentId>/start \
  -H 'Content-Type: application/json' -d '{"confirmed":true}'
curl -s http://127.0.0.1:8080/api/v1/deployments/<deploymentId>
```

验收标准：

1. 创建响应 `params.turns` 为 2 项、`params.turn=null`；
2. 到位后主桶 `pos≈(-4,8)`、`headingDeg=0`；
3. 从2 `pos≈(-2,4)`、`headingDeg=270`（未过第二弯）；
4. 单转弯请求仍返回 `params.turn` 非 null；
5. 直行请求 `params.turns=[]`、`params.turn=null`。

---

## 9. 附录：常量与字段速查

| 项 | 值 |
|---|---|
| 最大转弯数 | `5` |
| 每处转弯角度 | `deg = 90`（固定） |
| 方向 | `dir = left / right`（相对计划中的前一段） |
| 左转 heading 变化 | `H_i = H_{i-1} - 90°` |
| 右转 heading 变化 | `H_i = H_{i-1} + 90°` |
| `atM` 范围 | `(0, L)`，0.1 m 精度，严格递增 |
| 不转弯条件 | `targetM <= atM` |
| `d` 触发 | 仍按各设备 `traveledM >= d` 边沿，与 `atM` 无关 |
| 请求字段 | `turns: [{dir, atM}]`；旧 `turn: {dir, atM}` |
| 响应字段 | `params.turns: [...]`；`params.turn: 单转弯别名或 null` |
| 错误码 | `invalid_turn` |
