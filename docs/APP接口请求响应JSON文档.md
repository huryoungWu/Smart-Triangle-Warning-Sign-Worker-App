# APP ↔ 服务端接口文档（请求 / 响应 JSON）

> 本文件按接口列出：**接口含义、请求 JSON、响应 JSON、字段含义与取值**，不展开协议设计背景。
>
> - Base URL：`http://<server>:<port>/api/v1`
> - 鉴权：登录、版本检查、刷新 token 不需要 Bearer；其余接口建议带
>   `Authorization: Bearer <token>`；视频流允许 `?token=`（播放器不能自定义 Header 时使用）
> - JSON 请求头：`Content-Type: application/json; charset=utf-8`
> - 时间均为毫秒时间戳
> - 字段必须显式上报：数值缺失用 `0`，非数值缺失用 `null`（服务端不补默认值）
> - 错误统一格式：
>
> ```json
> {
>   "error": {
>     "code": "bad_request",
>     "message": "可读错误原因",
>     "detail": null
>   }
> }
> ```
>
> `detail` 可省略。

## 通用字段约定

| 字段 / 写法 | 含义 |
|---|---|
| `device_code` | 设备编号（设备码）。所有设备相关接口统一使用；APP 连接页选中、扫码或手动添加的设备编号 |
| `ts` | 客户端毫秒时间戳，便于服务端测延迟；Mock 当前不解析 |
| `null` | 该字段无值 / 无来源。请求中仍需显式出现，服务端不补默认值 |
| `0` | 数值型字段无值时的占位；例如 `lane=0` 表示未标注车道 |
| 时间字段 | 均为 Unix 毫秒时间戳（13 位） |
| 坐标 | 状态接口的 `gps.lat/lng` 为 **WGS-84**；手机锚点 `lat/lng`、风险 `lat/lng` 为 **GCJ-02** |
| 枚举值 | 对外统一小写 token；APP 解析大小写不敏感 |
| 错误 `detail` | 可选，补充信息；无则为 `null` 或省略 |

---

# 一、APP 实际调用的接口

## 1. 登录

`POST /auth/login`（无需 Bearer）

**接口含义**：登录并获取访问 token 与用户信息；APP 登录页使用，登录成功后进入设备连接页。

**调用时机**：用户点击「登录」；或设置页切换账号后重新登录。

### 请求

```json
{
  "username": "demo",
  "password": "123456",
  "client": "android",
  "appVersion": "1.0"
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `username` | string | 是 | 登录账号；调试默认账号 `demo` |
| `password` | string | 是 | 登录密码；调试默认密码 `123456` |
| `client` | string | 否 | 客户端类型，APP 固定传 `"android"`；服务端当前不解析 |
| `appVersion` | string | 否 | APP 版本名（如 `"1.0"`）；服务端当前不解析 |

### 响应 200

```json
{
  "token": "mock-xxxx",
  "refreshToken": "mock-refresh-xxxx",
  "expiresIn": 7200,
  "user": {
    "id": "u-1001",
    "name": "演示用户",
    "role": "operator",
    "phone": "138****0000"
  }
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `token` | string | Bearer 访问 token；APP 后续请求放在 `Authorization: Bearer <token>` |
| `refreshToken` | string | 刷新 token；APP 当前未使用 |
| `expiresIn` | int | 访问 token 有效期，单位秒（Mock 为 7200） |
| `user.id` | string | 用户唯一标识 |
| `user.name` | string | 用户显示名 |
| `user.role` | string | 用户角色，Mock 固定 `"operator"`（作业人员） |
| `user.phone` | string | 手机号，Mock 为脱敏值 `"138****0000"` |

### 失败 401

```json
{
  "error": {
    "code": "bad_credentials",
    "message": "用户名或密码错误"
  }
}
```

---

## 2. 登出

`POST /auth/logout`（Bearer）

**接口含义**：通知服务端使当前 token 失效；APP 同时清空本地会话并返回登录页。

**调用时机**：设置页「退出登录」或「切换账号」。APP 先清本地会话，再尽力通知服务端。

### 请求

无请求体。

| 位置 | 字段 | 类型 | 含义 |
|---|---|---|---|
| Header | `Authorization` | string | `Bearer <token>`；无 token 时接口也会返回 204 |

### 响应 204

无响应体。HTTP 204 即表示成功；服务端不返回 JSON。

---

## 3. 版本检查

`GET /app/version`（无需 Bearer）

**接口含义**：获取服务端最新版本信息；APP 也用它做「测试连接」（判断服务端是否可达且是本项目 API）。

**调用时机**：设置 → 服务器地址 →「测试连接」；未来「检查更新」也可使用。

### 请求

无请求体，无查询参数。

### 响应 200

```json
{
  "latestVersion": "1.0",
  "versionCode": 1,
  "url": "",
  "forceUpdate": false,
  "releaseNote": "Mock 服务端：当前已是最新版本"
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `latestVersion` | string | 最新版本名；APP 的「测试连接」会读取该字段确认接口可用 |
| `versionCode` | int | 最新版本号（整数），用于比较版本新旧 |
| `url` | string | APK 下载地址；Mock 为空字符串 |
| `forceUpdate` | bool | 是否强制更新；`true` 时必须更新 |
| `releaseNote` | string | 版本更新说明 |

---

## 4. 设备列表

`GET /devices`（Bearer）

**接口含义**：获取当前登录账号名下的设备台账；APP 连接页据此展示可选设备下拉。

**调用时机**：登录/跳过登录完成、切换服务端后、`reloadDevices()`；APP 会再把本机手动添加的设备合并进列表。

### 请求

无请求体，无查询参数。

### 响应 200

```json
{
  "devices": [
    {
      "device_code": "TC-001",
      "name": "三角警示牌一号车",
      "bound": true,
      "online": true,
      "started": true,
      "available": true,
      "batteryPercent": 86,
      "deviceType": "triangle",
      "fw": "0.3.1",
      "lastSeenAt": 1760083200000
    },
    {
      "device_code": "TC-002",
      "name": "三角警示牌二号车",
      "bound": true,
      "online": true,
      "started": false,
      "available": false,
      "batteryPercent": null,
      "deviceType": "triangle",
      "fw": "0.3.1",
      "lastSeenAt": 1760083200000
    }
  ]
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `devices` | array | 当前账号可用的设备列表 |
| `devices[].device_code` | string | 设备编号；连接、状态、命令、上传全部使用该值 |
| `devices[].name` | string | 设备显示名称 |
| `devices[].bound` | bool | 是否已绑定；Mock 返回 `true` |
| `devices[].online` | bool | 设备是否在线 |
| `devices[].started` | bool | 设备是否已上电/启动 |
| `devices[].available` | bool | 设备是否可用于作业 |
| `devices[].batteryPercent` | int \| null | 电量百分比；`null` = 无来源，APP 显示「未知」 |
| `devices[].deviceType` | string | 设备类型；当前固定 `"triangle"`（三角警示牌小车） |
| `devices[].fw` | string | 固件版本 |
| `devices[].lastSeenAt` | long | 服务端最后见到设备的时间（毫秒） |

---

## 5. 连接设备（连接即绑定）

`POST /devices/bind`（Bearer 可选）

**接口含义**：把设备绑定到当前账号；APP 连接页的「连接设备」。成功后才会读取一次状态，并开始后续状态轮询。

**调用时机**：用户在连接页选择设备、输入绑定码后点击「连接设备」。

### 请求

```json
{
  "username": "demo",
  "password": "123456",
  "device_code": "TC-001",
  "binding_code": "123456",
  "device_type": "triangle"
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `username` | string \| null | 是（可 null） | 登录账号；APP 登录后自动带入；跳过登录时显式传 `null`，仅用于服务端日志 |
| `password` | string \| null | 是（可 null） | 登录密码；同上；Mock 不校验 |
| `device_code` | string | 是 | 设备编号；非空，≤32 字符 |
| `binding_code` | string | 是 | 绑定码；设备铭牌上的绑定码；空字符串会被拒绝 |
| `device_type` | string | 是 | 设备类型；当前唯一取值 `"triangle"` |

### 响应 201

```json
{
  "device": {
    "device_code": "TC-001",
    "name": "三角警示牌一号车",
    "bound": true,
    "deviceType": "triangle"
  }
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `device.device_code` | string | 绑定后的设备编号；APP 以该值为准，后续状态/命令都用它 |
| `device.name` | string | 设备名称 |
| `device.bound` | bool | 是否已绑定；成功为 `true` |
| `device.deviceType` | string | 设备类型，回显请求中的 `device_type` |

### 失败示例

```json
{
  "error": {
    "code": "already_bound",
    "message": "该设备已被其他账号绑定"
  }
}
```

---

## 6. 设备实时状态

`GET /device/status?device_code=TC-001&lat=39.9088&lng=116.3975`（Bearer）

**接口含义**：读取小车当前状态、任务进度、里程、GPS 和位置；这是 APP 判断「已连接 / 任务完成 / 小车位置」的唯一事实源。

**调用时机**：

- 连接（bind）成功后读取一次；
- 任务执行页 / 地图页可见时 **1Hz 轮询**；
- 首页、连接页等由用户点击「刷新状态」读取一次。

### 请求参数（query）

```json
{
  "device_code": "TC-001",
  "lat": 39.9088,
  "lng": 116.3975
}
```

| 参数 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 是 | 要查询的设备编号 |
| `lat` | double | 否 | 手机当前纬度，**GCJ-02**；服务端把它作为小车位置的锚点 A |
| `lng` | double | 否 | 手机当前经度，**GCJ-02**；与 `lat` 同时上报 |

- 无请求体。
- `lat/lng` 只传一个时按「未上报」处理。

### 响应 200

```json
{
  "device_code": "TC-001",
  "fw": "0.3.1",
  "online": true,
  "mode": "standby",
  "motion": "idle",
  "estopLocked": false,
  "stopSource": "none",
  "light": "off",
  "task": {
    "id": "t-1a2b3c",
    "kind": "fixed_distance",
    "source": "app",
    "targetM": 20,
    "traveledM": 12.4,
    "state": "running",
    "stage": "moving",
    "resultNote": null,
    "heading": 25,
    "lane": 2,
    "segments": [
      {
        "action": "fwd",
        "value": 20,
        "state": "running",
        "progress": 0.4
      }
    ]
  },
  "odo": {
    "leftPulse": 6200,
    "rightPulse": 6180,
    "traveledM": 12.4,
    "speedMps": 1.0,
    "diffPulse": 20
  },
  "batteryPercent": 86,
  "tilt": null,
  "obstacle": null,
  "ts": 1760083200123,
  "gps": {
    "lat": 39.9073988,
    "lng": 116.3912564,
    "acc": 5.0,
    "speed": 1.0,
    "heading": 0,
    "state": "fixed",
    "at": 1760083200123
  }
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `device_code` | string | 设备编号 |
| `fw` | string | 固件版本 |
| `online` | bool | 设备是否在线；Mock 固定 `true` |
| `mode` | string | 车辆模式：`standby` 待命 / `manual` 手动 / `auto` 自动驾驶中 / `safe_stop` 安全停止 |
| `motion` | string | 运动状态：`idle` 静止 / `moving` 行驶中 / `fault` 故障 |
| `estopLocked` | bool | 急停是否锁定；`true` 时禁止运动类指令 |
| `stopSource` | string | 停车来源：`none` / `manual` 人工 / `estop` 急停 / `link_loss` 失联 / `encoder` 编码器异常 / `deviation` 左右偏差 / `timeout` 超时 |
| `light` | string | 灯光模式：`off` 关 / `on` 常亮 / `blink` 双闪 |
| `task` | object | 当前任务信息；无任务时字段仍存在，但 `id/kind` 可能省略 |
| `task.id` | string? | 任务唯一 id；无任务时省略 |
| `task.kind` | string? | 任务类型：`fixed_distance` 定距布置 / `manual` 手动遥控 / `one_touch` 一键布置 / `recover` 自动回收 |
| `task.source` | string | 任务来源：`app` 手机发起 / `button` 车身实体按钮发起 |
| `task.targetM` | double | 目标前进总里程（米）；多段路线为所有 `fwd` 段之和；回收任务为发起时的已行驶里程（返程距离） |
| `task.traveledM` | double | 当前任务已行驶里程（米，编码器估算）；回收任务中递减 |
| `task.state` | string | 任务状态：`idle` 无任务 / `running` 执行中 / `done` 已完成 / `failed` 失败 / `canceled` 已取消 |
| `task.stage` | string? | 一键布置阶段：`lighting` 灯光 / `countdown` 倒计时 / `moving` 行驶 / `parked` 停车；回收任务为 `recovering`；无则省略 |
| `task.resultNote` | string? | 设备结果备注；如 `route_done` / `recovered` / `user_confirmed` / `manual_stop` / `estop` / `canceled_by_app`；无则省略 |
| `task.heading` | double | 当前车头方向（度，0=正北，顺时针增加） |
| `task.lane` | int? | 作业车道（1~8）；未标注时省略或为 `null` |
| `task.segments` | array | 路线段列表；按执行顺序排列 |
| `task.segments[].action` | string | 段动作：`fwd` 前进 / `turn` 转向 |
| `task.segments[].value` | double | 段参数：`fwd` 为米；`turn` 为度（负=左，正=右） |
| `task.segments[].state` | string | 段状态：`pending` 待执行 / `waiting_confirm` 待确认 / `running` 执行中 / `done` 已完成 / `failed` 失败 / `skipped` 未执行 |
| `task.segments[].progress` | double | 段内进度，0.0 ~ 1.0 |
| `odo.leftPulse` | long | 左轮编码器累计脉冲 |
| `odo.rightPulse` | long | 右轮编码器累计脉冲 |
| `odo.traveledM` | double | 整车累计里程（米，编码器估算；不含 GPS 校准） |
| `odo.speedMps` | double | 当前速度（米/秒）；静止为 `0.0` |
| `odo.diffPulse` | long | 左右轮脉冲差；用于偏差诊断 |
| `batteryPercent` | int? | 电量百分比；`null` = 无来源 |
| `tilt` | bool? | 倾斜传感器；`null` = 无来源 |
| `obstacle` | bool? | 障碍物传感器；`null` = 无来源 |
| `ts` | long | 设备侧时间戳（毫秒） |
| `gps` | object? | 车端 GPS；无 GPS 模块或协议未带字段时整块省略 |
| `gps.lat` | double | 纬度，**WGS-84**；APP 负责转 GCJ-02 |
| `gps.lng` | double | 经度，**WGS-84** |
| `gps.acc` | double | 定位精度（米） |
| `gps.speed` | double | 车端速度（米/秒） |
| `gps.heading` | double | 车头方向（度，0=正北） |
| `gps.state` | string | 定位状态：`fixed` 已定位 / `no_fix` 未定位 / `absent` 无模块 |
| `gps.at` | long | GPS 采样时间（毫秒） |

字段取值：

```json
{
  "mode": ["standby", "manual", "auto", "safe_stop"],
  "motion": ["idle", "moving", "fault"],
  "stopSource": ["none", "manual", "estop", "link_loss", "encoder", "deviation", "timeout"],
  "light": ["off", "on", "blink"],
  "task.state": ["idle", "running", "done", "failed", "canceled"],
  "task.kind": ["fixed_distance", "manual", "one_touch", "recover"],
  "task.source": ["app", "button"],
  "segments[].action": ["fwd", "turn"],
  "segments[].state": ["pending", "waiting_confirm", "running", "done", "failed", "skipped"],
  "gps.state": ["fixed", "no_fix", "absent"]
}
```

可选 / 可省略字段：

- `task.stage`、`task.resultNote`、`task.lane` 可为 `null` 或省略。
- `task.id`、`task.kind` 无任务时可省略。
- `gps` 整块可省略（无 GPS 模块时）。
- `batteryPercent`、`tilt`、`obstacle` 可为 `null`。
- `gps` 坐标为 **WGS-84**；APP 自行转 GCJ-02。

---

## 7. 控制命令

`POST /device/command`（Bearer）

**接口含义**：所有设备控制命令的统一入口，包括手动遥控、停车/急停、灯光、任务启动/完成/取消、回收、里程清零。

**调用时机**：用户在任务执行页、手动干预弹窗、任务发布页等触发对应操作时。

所有命令共用以下字段：

```json
{
  "device_code": "TC-001",
  "type": "命令类型",
  "ts": 1760083200000
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 是 | 目标设备编号；缺失返回 HTTP 400 |
| `type` | string | 是 | 命令类型，见 7.1 表 |
| `ts` | long | 否 | 客户端毫秒时间戳；Mock 当前不解析 |

### 7.1 命令类型与附加字段

| type | 附加字段 | 含义 |
|---|---|---|
| `move` | `dir`: `fwd` / `back` / `left` / `right` | 按住方向键移动/转向；APP 每 300ms 发一次保持包；任务执行中也允许手动微调，手动期间自动推进暂停 |
| `manual_stop` | 无 | 结束手动微调：只停手动运动，**不取消任务**；自动任务继续 |
| `stop` | 无 | 普通停车；任务执行中会停止并**取消/熔断任务** |
| `estop` | 无 | 急停：进入安全停止并锁定，任务熔断 |
| `resume` | 无 | 急停解锁，恢复待命；**不续跑任务** |
| `light` | `mode`: `off` / `on` / `blink` | 灯光模式；与运动分离，可随时调整 |
| `task_start` | 见 7.3、7.4 | 启动任务（发布即执行） |
| `task_complete` | 无 | 用户现场确认任务完成：停止运动并置为 `done` |
| `task_cancel` | 无 | 取消当前任务；剩余路段标为未执行 |
| `segment_confirm` | 无 | 分段确认模式：确认执行当前等待段 |
| `recover` | 无 | 自动回收：小车返回作业人员位置 A |
| `odo_reset` | 无 | 里程清零；仅静止且无任务时允许 |

参数取值：

```json
{
  "dir": ["fwd", "back", "left", "right"],
  "mode": ["off", "on", "blink"]
}
```

### 7.2 简单命令请求示例

```json
{
  "device_code": "TC-001",
  "type": "move",
  "dir": "fwd",
  "ts": 1760083200000
}
```

```json
{
  "device_code": "TC-001",
  "type": "light",
  "mode": "blink",
  "ts": 1760083200000
}
```

无附加字段的命令，逐条请求体如下（每行就是一个完整的请求 JSON）：

```jsonc
{ "device_code": "TC-001", "type": "manual_stop",     "ts": 1760083200000 }
{ "device_code": "TC-001", "type": "stop",            "ts": 1760083200000 }
{ "device_code": "TC-001", "type": "estop",           "ts": 1760083200000 }
{ "device_code": "TC-001", "type": "resume",          "ts": 1760083200000 }
{ "device_code": "TC-001", "type": "task_complete",   "ts": 1760083200000 }
{ "device_code": "TC-001", "type": "task_cancel",     "ts": 1760083200000 }
{ "device_code": "TC-001", "type": "segment_confirm", "ts": 1760083200000 }
{ "device_code": "TC-001", "type": "recover",         "ts": 1760083200000 }
{ "device_code": "TC-001", "type": "odo_reset",       "ts": 1760083200000 }
```

语义区别：

- `stop`：任务执行中会停止并取消任务。
- `manual_stop`：只结束手动微调，不取消任务；自动任务继续。
- `task_complete`：用户现场确认任务完成，停止运动并置为 `done`。

### 7.3 task_start：单段快捷（形态 1）

```json
{
  "device_code": "TC-001",
  "type": "task_start",
  "kind": "fixed_distance",
  "targetM": 20,
  "mode": "auto",
  "light": "blink",
  "lane": 2,
  "segments": [],
  "ts": 1760083200000
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 是 | 目标设备编号 |
| `type` | string | 是 | 固定 `"task_start"` |
| `kind` | string | 是 | 任务类型：`fixed_distance` / `manual` / `one_touch` / `recover` |
| `targetM` | double | 是 | 单段目标距离（米），1 ~ 50 |
| `mode` | string | 是 | 执行模式：`auto` 全自动 / `segment_confirm` 分段确认 |
| `light` | string \| null | 是 | 任务灯光：`off` / `on` / `blink`；无值时显式 `null` |
| `lane` | int \| null | 是 | 作业车道 1~8；无值时显式 `null` |
| `segments` | array | 是 | 形态 1 必须显式传空数组 `[]` |
| `ts` | long | 否 | 客户端时间戳 |

### 7.4 task_start：多段路线（形态 2）

```json
{
  "device_code": "TC-001",
  "type": "task_start",
  "kind": "fixed_distance",
  "targetM": 40,
  "mode": "auto",
  "light": "blink",
  "lane": 2,
  "segments": [
    { "action": "fwd", "value": 20 },
    { "action": "turn", "value": 90 },
    { "action": "fwd", "value": 20 }
  ],
  "ts": 1760083200000
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 是 | 目标设备编号 |
| `type` | string | 是 | 固定 `"task_start"` |
| `kind` | string | 是 | 任务类型：`fixed_distance` / `manual` / `one_touch` / `recover` |
| `targetM` | double | 是 | 所有 `fwd` 段之和（米），由 APP 计算后上报；0 ~ 200 |
| `mode` | string | 是 | 执行模式：`auto` 全自动 / `segment_confirm` 分段确认 |
| `light` | string \| null | 是 | 任务灯光：`off` / `on` / `blink` |
| `lane` | int \| null | 是 | 作业车道 1~8；未填写时显式 `null` |
| `segments` | array | 是 | 路线段列表，最多 10 段 |
| `segments[].action` | string | 是 | `fwd` 前进 / `turn` 转向 |
| `segments[].value` | double | 是 | `fwd`：1 ~ 50 米；`turn`：绝对值 5 ~ 180 度（负=左，正=右） |
| `ts` | long | 否 | 客户端时间戳 |

### 7.5 响应

成功受理（HTTP 200）：

```json
{
  "accepted": true,
  "cmdId": "c-1a2b"
}
```

业务拒绝（HTTP 200）：

```json
{
  "accepted": false,
  "reason": "estop_locked"
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `accepted` | bool | 命令是否被接受；`true` 已受理，`false` 被拒绝或未执行 |
| `cmdId` | string? | 受理后的命令 id；仅 `accepted=true` 时返回 |
| `reason` | string? | 拒绝原因；仅 `accepted=false` 时返回，APP 原样展示 |

`reason` 取值：

| reason | 含义 |
|---|---|
| `estop_locked` | 急停锁定中 / 安全停止中，禁止运动类命令 |
| `task_running` | 已有任务运行中，禁止重复启动任务 |
| `param_out_of_range` | 参数超范围或缺失 |
| `segment_out_of_range` | 路线段参数超范围 |
| `too_many_segments` | 路线段数超过 10 |
| `not_locked` | 未处于急停锁定，`resume` 不接受 |
| `busy` | 行驶中/任务中，`odo_reset` 不允许 |
| `no_task` | 当前无运行中任务 |
| `not_waiting_confirm` | 当前没有待确认路段 |
| `already_home` | 小车已在作业人员位置，无需回收 |

缺少 `device_code`（HTTP 400）：

```json
{
  "error": {
    "code": "bad_request",
    "message": "缺少 device_code"
  }
}
```

---

## 8. 作业记录上传

`POST /tasks`（Bearer）

**接口含义**：把本机作业记录上传到服务端，形成云端记录；服务端按 `Idempotency-Key` 去重。

**调用时机**：用户点击「任务完成」后 APP 直接上传；核验通过后的自动上传；上传失败后的手动重试。

请求头：

```json
{
  "Content-Type": "application/json; charset=utf-8",
  "Idempotency-Key": "9f2a13cd"
}
```

### 请求

```json
{
  "device_code": "TC-001",
  "id": "9f2a13cd",
  "name": "自动布置",
  "kind": "fixed_distance",
  "mode": "auto",
  "lane": 2,
  "startedAt": 1760080000000,
  "finishedAt": 1760083200000,
  "segments": [
    { "action": "fwd", "value": 20 },
    { "action": "turn", "value": 90 },
    { "action": "fwd", "value": 20 }
  ],
  "targetM": 40,
  "traveledM": 39.8,
  "deviceResult": "done",
  "deviceResultNote": "route_done",
  "verifyResult": "pass",
  "verifyNote": "位置/朝向/灯光正常",
  "abnormalNote": null
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 是 | 任务所属设备编号 |
| `id` | string | 是 | 本地记录 id；同时作为 `clientId` / 幂等键 |
| `name` | string \| null | 是 | 任务名；无则显式 `null` |
| `kind` | string | 是 | 任务类型：`fixed_distance` / `manual` / `one_touch` / `recover` |
| `mode` | string \| null | 是 | `auto` / `segment_confirm`；无则显式 `null` |
| `lane` | int | 是 | 作业车道 1~8；未填写传 `0`（未标注） |
| `startedAt` | long | 是 | 任务开始时间（毫秒） |
| `finishedAt` | long | 是 | 任务结束时间（毫秒） |
| `segments` | array | 是 | 发布时的路线段；元素为 `{action, value}` |
| `segments[].action` | string | 是 | `fwd` / `turn` |
| `segments[].value` | double | 是 | `fwd` 为米；`turn` 为度 |
| `targetM` | double | 是 | 目标总前进里程（米） |
| `traveledM` | double | 是 | 实际行驶里程（米，编码器估算） |
| `deviceResult` | string | 是 | 设备结果：`done` / `failed` / `canceled` / `link_loss` / `unknown` |
| `deviceResultNote` | string \| null | 是 | 设备结果备注；无则 `null` |
| `verifyResult` | string | 是 | 人工核验：`pass` / `fail` / `not_done` |
| `verifyNote` | string \| null | 是 | 核验备注；无则 `null` |
| `abnormalNote` | string \| null | 是 | 异常备注；无则 `null` |

### 响应 201

```json
{
  "id": "srv-0007",
  "receivedAt": 1760083202000
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `id` | string | 服务端记录单号；APP 保存为「服务端单号」并在记录页展示 |
| `receivedAt` | long | 服务端接收时间（毫秒） |

幂等命中时返回同一结构。

---

## 9. 快照上传

`POST /snapshots`（Bearer）

**接口含义**：上传任务过程快照元数据；服务端从该设备视频流截取当前帧并登记为「作业人员上报」风险事件。

**调用时机**：任务开始/结束自动抓拍、用户手动点击「捕捉快照」。

请求头：

```json
{
  "Content-Type": "multipart/form-data; boundary=----ZT-xxxx"
}
```

### 请求字段

APP 以 multipart 表单发送，逻辑字段如下：

```json
{
  "id": "9f2a13cd",
  "device_code": "TC-001",
  "taskName": "自动布置",
  "lane": 2,
  "eventLabel": "任务开始（自动抓拍）",
  "capturedAt": 1760083200000,
  "note": "任务启动自动抓拍"
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `id` | string | 是 | 本地快照 id；用于关联/追踪（服务端会另生成 `snap-xxx`） |
| `device_code` | string | 是 | 快照所属设备编号 |
| `taskName` | string | 是 | 所属任务名；可为空字符串 |
| `lane` | string | 是 | 作业车道 1~8；未填写传 `"0"`，服务端按未标注处理 |
| `eventLabel` | string | 是 | 事件类型，如「任务开始（自动抓拍）」「任务结束（自动抓拍）」「现场抓拍」 |
| `capturedAt` | string | 是 | 抓拍时间（毫秒时间戳，字符串形式） |
| `note` | string | 是 | 备注；可为空字符串 |
| `lat` | string | 否 | 抓拍点纬度（可选，GCJ-02） |
| `lng` | string | 否 | 抓拍点经度（可选，GCJ-02） |
| `image` | file | 否 | 图片文件；当前 APP 不传，服务端从视频流截帧 |

- multipart 中所有值都是字符串。
- 服务端也兼容 `application/json` 形式的同名字段。

### 响应 201

```json
{
  "id": "snap-0007",
  "url": "http://127.0.0.1:8080/media/snap-0007.png",
  "placeholder": true,
  "riskId": "r-snap-0007"
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `id` | string | 服务端快照 id |
| `url` | string | 快照图片地址；Mock 为当前服务端 origin + `/media/...` |
| `placeholder` | bool | 是否占位图；Mock 为 `true` |
| `riskId` | string | 服务端登记的风险事件 id，可在风险页看到 |

---

## 10. 道路风险列表

`GET /risks?lat=39.9088&lng=116.3975&radiusKm=2`（Bearer）

**接口含义**：查询用户周边指定半径内的道路风险/事故；APP 风险页展示列表与地图点位，并在客户端按距离排序。

**调用时机**：进入风险页、切换搜索半径/筛选/排序时。

### 请求参数（query）

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

- 无请求体。

### 响应 200

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
      "videoUrl": "http://127.0.0.1:8080/api/v1/device/video/stream?device_code=TC-001"
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

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `risks` | array | 风险/事故列表 |
| `risks[].id` | string | 风险事件 id |
| `risks[].title` | string | 标题（列表主文案） |
| `risks[].type` | string | 类型：`breakdown` 抛锚 / `rear_end` 追尾 / `construction` 施工 / `rollover` 侧翻 / `debris` 散落物 / `other` 其他 |
| `risks[].lat` | double | 纬度，**GCJ-02** |
| `risks[].lng` | double | 经度，**GCJ-02** |
| `risks[].occurredAt` | long | 事故发生时间（毫秒） |
| `risks[].description` | string | 详细描述 |
| `risks[].lane` | int \| null | 占用车道（1~8）；`null` = 未标注 |
| `risks[].hasPhoto` | bool | 是否有照片 |
| `risks[].hasVideo` | bool | 是否有视频 |
| `risks[].photoUrls` | string[] | 照片 URL 列表；可为空数组 |
| `risks[].videoUrl` | string \| null | 视频/视频流 URL；无则为 `null` |
| `risks[].source` | string? | 仅作业人员上报的风险带此字段，值为 `"worker"`；外部数据无此字段 |

---

## 11. 实时视频流

`GET /device/video/stream?device_code=TC-001`（Bearer 或 `?token=`）

**接口含义**：获取指定设备的实时视频流；APP 任务执行页「实时视频」使用。

**调用时机**：进入任务执行页并显示视频卡片时建立长连接；离开页面断开。

### 请求参数（query）

```json
{
  "device_code": "TC-001",
  "token": "<accessToken>"
}
```

| 参数 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 是 | 要观看的设备编号 |
| `token` | string | 否 | 短时效流 token；播放器不能自定义 Header 时使用；Mock 不校验 |

- 无请求体。

### 响应

不是 JSON。

| 项 | 值 / 含义 |
|---|---|
| `Content-Type` | `multipart/x-mixed-replace; boundary=frame` |
| 响应体 | 连续 MJPEG 帧；Mock 的帧内容实际为 PNG，约 10fps |
| 失败 | 缺少 `device_code` 返回 400 JSON 错误；鉴权失败（正式服务端）返回 401 |

---

# 二、服务端已提供、APP 暂未调用的接口

## 12. 刷新 token

`POST /auth/refresh`

**接口含义**：用 refresh token 换取新的访问 token；APP 当前暂未调用。

**调用时机**：访问 token 过期时（预留）。

### 请求

```json
{
  "refreshToken": "mock-refresh-xxxx"
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `refreshToken` | string | 是 | 登录时返回的刷新 token；Mock 当前不解析 |

### 响应 200

```json
{
  "token": "mock-xxxx",
  "refreshToken": "mock-refresh-xxxx",
  "expiresIn": 7200
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `token` | string | 新的访问 token |
| `refreshToken` | string | 新的刷新 token |
| `expiresIn` | int | 新 token 有效期（秒） |

---

## 13. 当前用户

`GET /auth/profile`（Bearer）

**接口含义**：获取当前 token 对应的用户信息；APP 当前暂未调用。

**调用时机**：需要刷新用户资料时（预留）。

### 请求

无请求体。

### 响应 200

```json
{
  "id": "u-1001",
  "name": "演示用户",
  "role": "operator",
  "phone": "138****0000"
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `id` | string | 用户唯一标识 |
| `name` | string | 用户显示名 |
| `role` | string | 角色，Mock 固定 `"operator"` |
| `phone` | string | 手机号（脱敏） |

---

## 14. 设备详情

`GET /devices/{device_code}`（Bearer）

**接口含义**：获取单台设备的详情与能力；APP 当前设备信息页使用本地快照，暂未调用本接口。

**调用时机**：设备信息页/后台刷新设备能力时（预留）。

### 请求

无请求体；`device_code` 在路径中。

### 响应 200

```json
{
  "device_code": "TC-001",
  "name": "三角警示牌一号车",
  "deviceType": "triangle",
  "fw": "0.3.1",
  "sn": "ESP32-MOCK",
  "boundAt": 1760000000000,
  "lastSeenAt": 1760083200000,
  "online": true,
  "capabilities": {
    "gps": true,
    "camera": true,
    "battery": true,
    "tilt": false,
    "obstacle": false
  }
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `device_code` | string | 设备编号 |
| `name` | string | 设备名称 |
| `deviceType` | string | 设备类型，当前 `"triangle"` |
| `fw` | string | 固件版本 |
| `sn` | string | 设备序列号 |
| `boundAt` | long | 绑定时间（毫秒） |
| `lastSeenAt` | long | 最后在线时间（毫秒） |
| `online` | bool | 是否在线 |
| `capabilities.gps` | bool | 是否具备 GPS 能力 |
| `capabilities.camera` | bool | 是否具备摄像头 |
| `capabilities.battery` | bool | 是否有电量数据来源 |
| `capabilities.tilt` | bool | 是否具备倾斜传感器 |
| `capabilities.obstacle` | bool | 是否具备障碍物传感器 |

---

## 15. 解绑设备

`DELETE /devices/{device_code}/bind`（Bearer）

**接口含义**：解除设备与当前账号的绑定；APP 当前只删除本机添加的设备，暂未调用本接口。

**调用时机**：设备台账中删除/解绑设备（预留）。

### 请求

无请求体；`device_code` 在路径中。

| 位置 | 字段 | 类型 | 含义 |
|---|---|---|---|
| Path | `device_code` | string | 要解绑的设备编号 |

### 响应 204

无响应体。HTTP 204 表示解绑成功。

### 失败示例

```json
{
  "error": {
    "code": "not_found",
    "message": "设备不存在或不属于当前账号"
  }
}
```

```json
{
  "error": {
    "code": "cannot_unbind_builtin",
    "message": "内置演示设备不可删除"
  }
}
```

---

## 16. 作业记录列表

`GET /tasks?device_code=TC-001&from=1760000000000&to=1760083200000&page=1&size=20`（Bearer）

**接口含义**：查询云端作业记录列表；APP 记录页当前使用本机记录，暂未调用本接口。

**调用时机**：记录页「云端记录」功能启用后（预留）。

### 请求参数（query）

```json
{
  "device_code": "TC-001",
  "from": 1760000000000,
  "to": 1760083200000,
  "page": 1,
  "size": 20
}
```

| 参数 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 否 | 按设备过滤 |
| `from` | long | 否 | 起始时间（毫秒，含） |
| `to` | long | 否 | 结束时间（毫秒，含） |
| `page` | int | 否 | 页码，从 1 开始 |
| `size` | int | 否 | 每页条数 |

- Mock 当前忽略筛选参数，返回全部记录。

### 响应 200

```json
{
  "items": [
    {
      "id": "srv-0007",
      "clientId": "9f2a13cd",
      "device_code": "TC-001",
      "name": "自动布置",
      "kind": "fixed_distance",
      "mode": "auto",
      "lane": 2,
      "startedAt": 1760080000000,
      "finishedAt": 1760083200000,
      "segments": [
        { "action": "fwd", "value": 20 },
        { "action": "turn", "value": 90 },
        { "action": "fwd", "value": 20 }
      ],
      "targetM": 40,
      "traveledM": 39.8,
      "deviceResult": "done",
      "deviceResultNote": "route_done",
      "verifyResult": "pass",
      "verifyNote": "位置/朝向/灯光正常",
      "abnormalNote": null,
      "receivedAt": 1760083202000
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `items` | array | 作业记录列表 |
| `items[].id` | string | 服务端记录单号 |
| `items[].clientId` | string \| null | APP 本地记录 id（上传时 `id` / 幂等键） |
| `items[].device_code` | string | 所属设备编号 |
| `items[].name` | string \| null | 任务名 |
| `items[].kind` | string | 任务类型 |
| `items[].mode` | string \| null | `auto` / `segment_confirm` |
| `items[].lane` | int \| null | 作业车道 |
| `items[].startedAt` | long | 开始时间（毫秒） |
| `items[].finishedAt` | long | 结束时间（毫秒） |
| `items[].segments` | array | 路线段 `{action, value}` |
| `items[].segments[].action` | string | `fwd` / `turn` |
| `items[].segments[].value` | double | `fwd` 为米；`turn` 为度 |
| `items[].targetM` | double | 目标里程（米） |
| `items[].traveledM` | double | 实际里程（米） |
| `items[].deviceResult` | string | 设备结果 |
| `items[].deviceResultNote` | string \| null | 设备结果备注 |
| `items[].verifyResult` | string | 人工核验结果 |
| `items[].verifyNote` | string \| null | 核验备注 |
| `items[].abnormalNote` | string \| null | 异常备注 |
| `items[].receivedAt` | long | 服务端接收时间（毫秒） |
| `page` | int | 当前页码 |
| `size` | int | 每页条数 |
| `total` | int | 总条数 |

---

## 17. 作业记录详情

`GET /tasks/{id}`（Bearer）

**接口含义**：查询单条云端作业记录详情，并返回事件编号。

**调用时机**：记录页打开云端记录详情（预留）。

### 请求

无请求体；`id` 在路径中。

| 位置 | 字段 | 类型 | 含义 |
|---|---|---|---|
| Path | `id` | string | 服务端记录单号（`srv-xxxx`） |

### 响应 200

```json
{
  "id": "srv-0007",
  "clientId": "9f2a13cd",
  "device_code": "TC-001",
  "name": "自动布置",
  "kind": "fixed_distance",
  "mode": "auto",
  "lane": 2,
  "startedAt": 1760080000000,
  "finishedAt": 1760083200000,
  "segments": [
    { "action": "fwd", "value": 20 },
    { "action": "turn", "value": 90 },
    { "action": "fwd", "value": 20 }
  ],
  "targetM": 40,
  "traveledM": 39.8,
  "deviceResult": "done",
  "deviceResultNote": "route_done",
  "verifyResult": "pass",
  "verifyNote": "位置/朝向/灯光正常",
  "abnormalNote": null,
  "receivedAt": 1760083202000,
  "eventNo": "EV-20261019-0007"
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `eventNo` | string | 事件编号，格式 `EV-yyyyMMdd-<记录id后4位>` |
| 其余字段 | — | 与 §16 `items[]` 中的记录字段完全相同 |

---

## 18. 快照列表

`GET /snapshots?device_code=TC-001&from=&to=&page=1&size=20`（Bearer）

**接口含义**：查询云端快照记录列表；APP 当前快照只存本机+上传，暂未调用本接口。

**调用时机**：记录/快照云端列表功能启用后（预留）。

### 请求参数（query）

```json
{
  "device_code": "TC-001",
  "from": 1760000000000,
  "to": 1760083200000,
  "page": 1,
  "size": 20
}
```

| 参数 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 否 | 按设备过滤 |
| `from` | long | 否 | 起始时间（毫秒） |
| `to` | long | 否 | 结束时间（毫秒） |
| `page` | int | 否 | 页码，从 1 开始 |
| `size` | int | 否 | 每页条数 |

- Mock 当前忽略筛选参数。

### 响应 200

```json
{
  "items": [
    {
      "id": "snap-0007",
      "device_code": "TC-001",
      "taskName": "自动布置",
      "lane": 2,
      "eventLabel": "任务开始（自动抓拍）",
      "capturedAt": 1760083200000,
      "note": "任务启动自动抓拍",
      "hasImage": true
    }
  ],
  "page": 1,
  "size": 20,
  "total": 1
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `items` | array | 快照记录列表 |
| `items[].id` | string | 服务端快照 id（`snap-xxxx`） |
| `items[].device_code` | string | 所属设备编号 |
| `items[].taskName` | string | 所属任务名 |
| `items[].lane` | int \| null | 作业车道 1~8；`null` = 未标注 |
| `items[].eventLabel` | string | 事件类型（任务开始/结束/现场抓拍等） |
| `items[].capturedAt` | long | 抓拍时间（毫秒） |
| `items[].note` | string | 备注 |
| `items[].hasImage` | bool | 是否有图片 |
| `page` | int | 当前页码 |
| `size` | int | 每页条数 |
| `total` | int | 总条数 |

---

## 19. 预警信息上传（预留）

`POST /warnings`（Bearer）

**接口含义**：把作业现场信息作为预警发布给过往司机；APP 端当前为预留占位实现，暂未调用。

**调用时机**：预警功能启用后（预留）。

### 请求

```json
{
  "snapshotId": "snap-0007",
  "capturedAt": 1760083200000,
  "eventLabel": "任务开始（自动抓拍）",
  "title": "前方作业，请注意避让",
  "description": "三角警示牌已布置，请减速慢行。",
  "lane": 2,
  "lat": 39.9088,
  "lng": 116.3975
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `snapshotId` | string | 否 | 关联快照 id |
| `capturedAt` | long | 否 | 快照/事件时间（毫秒） |
| `eventLabel` | string | 是 | 事件类型；Mock 用于日志 |
| `title` | string | 是 | 预警标题；Mock 用于日志 |
| `description` | string | 否 | 预警详细描述 |
| `lane` | int \| null | 否 | 作业车道 1~8；Mock 会保存并返回 |
| `lat` | double | 否 | 位置纬度（GCJ-02） |
| `lng` | double | 否 | 位置经度（GCJ-02） |

- Mock 当前只使用 `eventLabel`、`title`、`lane`。

### 响应 201

```json
{
  "id": "warn-0007",
  "publishedAt": 1760083202000,
  "channels": ["nav_event"],
  "lane": 2
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `id` | string | 预警 id |
| `publishedAt` | long | 发布时间（毫秒） |
| `channels` | string[] | 下发渠道；Mock 固定 `["nav_event"]` |
| `lane` | int \| null | 作业车道 |

---

## 20. 反馈

`POST /feedback`

**接口含义**：提交用户反馈/建议；APP 当前设置页仅弹本地提示，暂未调用本接口。

**调用时机**：帮助与反馈功能接入服务端后（预留）。

### 请求

```json
{
  "content": "问题描述或建议"
}
```

| 字段 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `content` | string | 是 | 反馈内容 |

### 响应 201

```json
{
  "id": "fb-0007"
}
```

| 字段 | 类型 | 含义 / 取值 |
|---|---|---|
| `id` | string | 反馈单号 |

---

## 21. 单帧抓拍

`GET /device/video/snapshot.jpg?device_code=TC-001`（Bearer 或 `?token=`）

**接口含义**：获取指定设备当前帧图片；用于低带宽取帧或快照来源。APP 当前由服务端从视频流截帧，暂未直接调用本接口。

**调用时机**：需要单帧图片时（预留/调试）。

### 请求参数（query）

```json
{
  "device_code": "TC-001",
  "token": "<accessToken>"
}
```

| 参数 | 类型 | 必填 | 含义 / 取值 |
|---|---|---|---|
| `device_code` | string | 是 | 要取帧的设备编号 |
| `token` | string | 否 | 短时效流 token；Mock 不校验 |

- 无请求体。

### 响应

不是 JSON。

| 项 | 值 / 含义 |
|---|---|
| `Content-Type` | `image/png`（接口名虽为 `.jpg`，Mock 返回 PNG） |
| 响应体 | 该设备当前帧图片 |
| 失败 | 缺少 `device_code` 返回 400 JSON 错误 |

---

# 三、非业务接口说明

以下不是 APP 业务接口，未列入上面的请求/响应表：

- `GET /`：Mock 人工自检页。
- `GET /media/{name}`：快照图片回放。
- 文档中预留但 Mock 未实现的接口：`POST /tasks/register`、`GET /device/video/live.m3u8`、`GET /device/track`。
