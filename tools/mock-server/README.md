# Mock 服务端（联调用）

按《docs/后端接口设计文档.md》实现全部接口形状的**零依赖 Node 服务端**，内置小车状态机模拟
（分段路线推进 / 急停 / 取消 / 回收 / 800ms 遥控看门狗），让 APP 在正式服务端就绪前即可
**按 REAL 链路跑通全流程**。

## 启动

```bash
node tools/mock-server/server.js          # 默认 8080 端口
node tools/mock-server/server.js 9000     # 或指定端口
```

启动后控制台会打印：

```
本机访问:   http://127.0.0.1:8080
局域网访问: http://192.168.x.x:8080   ← 真机同 Wi-Fi 时用这个地址
自检:       http://127.0.0.1:8080/    （浏览器打开可见设备列表）
```

## 默认调试账号

| 账号 | 密码 | 说明 |
|---|---|---|
| `demo` | `123456` | **默认账号**：绑定 TC-001 / TC-002 两台演示设备（APP 登录页已预填） |
| 任意新用户名 | 任意 | 自动建号，**设备列表为空**（演示「设备信息按登录用户获取」；绑定后才可见） |

未登录 / 跳过登录 / 未知 token 一律按默认账号 `demo` 处理（宽松模式，方便调试）。
生产服务端应强制 Bearer 鉴权。

## 连接设备 · 绑定码

APP 连接页「连接设备」会向 `POST /api/v1/devices/bind` 上报 5 个字段：
`username`、`password`（登录后自动带入）、`device_code`（设备编号）、**`binding_code`（用户在连接页输入）**、
`device_type`（默认常量 `triangle`）。

| 账号/密码 | 设备编号 device_code | 绑定码 binding_code | 结果 |
|---|---|---|---|
| 已登录（demo/123456） | `TC-001` | 任意非空（如 `123456`） | ✅ 201 连接成功 |
| 任意 | `TC-001` | **留空** | ❌ 400 `bad_request`（APP 侧按钮同时置灰） |
| 其他账号 | `demo` 已绑定的设备 | 任意非空 | ❌ 409 `already_bound` |

> Mock 只校验「绑定码非空」，**不比对真实绑定码**（宽松模式，便于联调）；
> 正式服务端必须与设备登记信息比对，不符返回 400 `invalid_binding_code`（见对接文档 §4.2）。

## APP 侧三步接通

1. **切开关**（`di/ServiceLocator.kt`，只改一行）：

   ```kotlin
   val TRANSPORT: DeviceTransport = DeviceTransport.SERVER
   ```

   切到 SERVER 后：登录（`/auth/login`）、设备信息按登录用户获取（`/devices`）、状态/命令
   （`/device/status`、`/device/command`）、记录上传（`/tasks`）、快照上传（`/snapshots`，**上传后
   在风险页展示**）、风险数据（`/risks`）全部走服务端；切回 `FAKE` 即恢复纯演示模式。

2. **配地址**（`ServiceLocator.SERVER_BASE_URL`；**当前项目值 = `http://127.0.0.1:8080`，即模拟器/USB 模式**）：

   | 运行环境 | 地址 | 额外操作 |
   |---|---|---|
   | 模拟器 / USB 真机（当前） | `http://127.0.0.1:8080` | 执行一次 `adb reverse tcp:8080 tcp:8080` |
   | 真机同 Wi-Fi | 改成 `http://<电脑局域网IP>:8080`（Mock 启动时打印） | 把该 IP 加进 `res/xml/network_security_config.xml` 白名单（192.168.43.120 已加） |

3. **重装运行**：登录（默认账号 `demo/123456` 已预填，可跳过）→ 连接页选设备（按登录账号获取）
   → **输入绑定码**（任意非空即可，如 `123456`）→ 连接 → 发布任务（**填写作业车道 1~8**）
   → 执行页看进度/视频占位/快照 → 核验通过自动上传 → 记录页看上传状态；
   **快照上传后可到「风险」页看到「作业人员上报：…」条目**（统一：上传后才能在风险中展示），
   该条目与发布任务时的作业车道一致（风险页/事故详情页显示「第 N 车道」）。
   服务端控制台会实时打印每条请求与命令（含 `u=账号`、`车道=`）。

## 自检命令（可选）

```bash
curl -X POST http://127.0.0.1:8080/api/v1/auth/login -H "Content-Type: application/json" \
     -d '{"username":"demo","password":"123456"}'          # → token
curl http://127.0.0.1:8080/api/v1/devices
curl -X POST http://127.0.0.1:8080/api/v1/devices/bind -H "Content-Type: application/json" \
     -d '{"username":"demo","password":"123456","device_code":"TC-001","binding_code":"123456","device_type":"triangle"}'   # 连接设备（绑定码）
curl "http://127.0.0.1:8080/api/v1/device/status?deviceId=TC-001"
curl -X POST http://127.0.0.1:8080/api/v1/device/command -H "Content-Type: application/json" \
     -d '{"deviceId":"TC-001","type":"task_start","kind":"fixed_distance","targetM":2,"lane":2}'   # lane = 作业车道（1~8）
curl -X POST http://127.0.0.1:8080/api/v1/snapshots -H "Content-Type: application/json" \
     -d '{"id":"t1","deviceId":"TC-001","taskName":"自动布置","lane":2,"eventLabel":"任务开始（自动抓拍）","capturedAt":1791980000000}'
curl -X POST http://127.0.0.1:8080/api/v1/tasks -H "Content-Type: application/json" -H "Idempotency-Key: t1" \
     -d '{"id":"t1","deviceId":"TC-001","kind":"fixed_distance","lane":2,"deviceResult":"done","verifyResult":"pass","targetM":20}'   # 记录上传（核验通过自动上传的同一接口）
curl "http://127.0.0.1:8080/api/v1/tasks"        # 记录列表（重启后仍在 —— 已落盘保留）
curl "http://127.0.0.1:8080/api/v1/risks?lat=39.9928&lng=116.4004&radiusKm=2"   # 演示 8 条（今天 6 + 历史 2，均带 lane 车道）+「作业人员上报：…」条目
curl -o frame.png "http://127.0.0.1:8080/api/v1/device/video/snapshot.jpg?deviceId=TC-001"   # 该设备视频流当前帧（快照即取此帧）
curl -N "http://127.0.0.1:8080/api/v1/device/video/stream?deviceId=TC-001" | head -c 2000   # TC-001 的 MJPEG 流（Ctrl+C 结束；换 deviceId 得不同画面）
```

> **Windows 下 curl 命令行直接传中文会因控制台编码（GBK）变成乱码字节**——Mock 现在会**直接拒绝**
> 这类请求体（`400 bad_request`，提示请求体不是合法 UTF-8），不会再往列表里写入乱码数据。
> 发中文请把请求体存成 **UTF-8 文件**后用 `--data-binary @payload.json` 传；APP 端天然是 UTF-8 请求体，不受影响。

## Mock 行为说明（与正式服务端的差异）

| 项 | Mock 行为 | 正式服务端应按文档 |
|---|---|---|
| 鉴权 | 宽松：无 token 按默认用户 `demo`；任意账号均可登录（新账号自动建号） | Bearer token，401 刷新 |
| 车端 GPS | 默认发送 `gps` 块（**WGS-84**）：位置 = 手机锚点（APP 随状态上报 `&lat=&lng=`）/默认点，沿航向投影 odo 里程，**随 1Hz 状态实时更新** — APP 端转 GCJ-02 后映射到高德地图。置 `EMIT_GPS=false` 可模拟无 GPS（APP 自动走行程估算降级） | 真实车端 GPS 模块（同为 WGS-84） |
| 设备列表 | **按登录用户过滤**（demo 有 2 台；新账号为空） | 同左（按绑定关系） |
| 连接设备（bind） | **只校验绑定码非空**（任意非空都算通过）；账号/密码仅记日志 | 必须比对设备登记的绑定码，不符 → 400 `invalid_binding_code`；账号与 Bearer 身份一致 |
| 绑定未知设备 | **自动注册为「自定义设备」**并归属当前用户（方便扫码演示） | 404 `unknown_device` |
| 内置设备解绑 | TC-001/TC-002 返回 400 `cannot_unbind_builtin` | 同左 |
| 快照上传 | 仅元数据；**图片由服务端从视频流截帧**（当前帧）并**落盘** `tools/mock-server/data/media/`，经 `/media/{id}.png` 回放（**重启后仍可回放**）；**自动登记为风险事件**（风险页 `source=worker`）。未带坐标时按查询点就近放置（演示友好）；带 `lat/lng` 时按真实坐标、半径过滤 | 存盘返回 url；同左（按上报真实坐标过滤） |
| 视频 | **按 `deviceId` 各自一路 MJPEG 流**（`/api/v1/device/video/stream?deviceId=`，10fps 时间驱动动画帧：天色轮转/道路虚线/往返三角牌；色调/相位/左上角 OSD 设备号均随 deviceId 派生，**不同设备画面稳定不同**）；`videoUrl` 指向事发设备那一路。视频帧**实时生成、不落盘**；仅"截帧成快照"的那一帧落盘（见上行） | 中继车端真实视频（MJPEG/HLS） |
| 数据持久化 | **任务记录（/tasks 上传）+ 快照媒体 + 快照登记记录 + 风险登记**落盘 `tools/mock-server/data/`（`media/*.png` + `state.json`），重启自动恢复（启动日志打印恢复条数）；其余演示数据（预警/设备状态）仍在内存，重启清空。目录已 gitignore | 数据库 |
| 图片地址 | 入库存**相对路径**（`/media/xxx.png`），`/risks` 查询时按**当前请求的 origin** 拼绝对地址（历史绝对地址只重写 origin）——换端口 / 换访问地址（127.0.0.1 ↔ 局域网 IP ↔ adb reverse）后旧数据图片仍可加载 | 对象存储/CDN 固定域名 |
| 风险数据（`/risks`） | 演示 **8 条**（**今天 6 条** + 历史 2 条），**每条都带 `lane` 占用车道**，距离均 ≤ 2 公里、方位各异 —— 便于验证 APP 风险页「默认今天 / 按距离近→远 / 按时间新→旧」。**无车道信息的条目 APP 不展示**（列表每行都要有「第 N 车道」） | 真实外部数据源（交警/平台），每条带占用车道 |
| 请求体编码 | 文本字段含替换字符 U+FFFD（非法 UTF-8，如 Windows 控制台 GBK 中文）→ `400 bad_request` 拒绝入库 | 同左（应校验编码） |
| 状态推进 | 由 `/status` 调用节奏驱动（与 APP 1Hz 轮询一致） | 车端 1Hz 上报 |

## 接口清单

认证 `auth/login|refresh|profile|logout` ｜ 设备 `devices`、**`devices/bind`（连接设备：账号+绑定码）**、`devices/:id/bind`、`devices/:id`
｜ 状态 `device/status`（含 `task.lane` 回显） ｜ 命令 `device/command`（move/stop/estop/resume/light/task_start[+lane]/segment_confirm/task_cancel/recover/odo_reset）
｜ 记录 `tasks`（POST/GET，支持 `Idempotency-Key`，含 `lane`） ｜ 快照 `snapshots`（multipart/JSON，含 `lane`） ｜ 预警 `warnings`（含 `lane`）
｜ 风险 `risks`（含 `lane` 占用车道） ｜ 版本 `app/version` ｜ 反馈 `feedback` ｜ 视频 `device/video/snapshot.jpg`、`device/video/stream`（均按 `?deviceId=` 区分设备）

字段与错误码详见 `docs/后端接口设计文档.md`。**正式服务端开发（后端同学）请看 [`docs/后端开发对接文档.md`](../../docs/后端开发对接文档.md)**——按 APP 实际解析口径整理，含 Mock 禁止照搬清单（视频必须接真实摄像头）与验收清单。
