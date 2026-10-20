package com.example.myapplication.di

import android.content.Context
import com.example.myapplication.MyAppApplication
import com.example.myapplication.core.data.api.AuthRepository
import com.example.myapplication.core.data.api.DeviceRepository
import com.example.myapplication.core.data.api.LocationRepository
import com.example.myapplication.core.data.api.RiskRepository
import com.example.myapplication.core.data.api.SnapshotUploadRepository
import com.example.myapplication.core.data.api.TaskUploadRepository
import com.example.myapplication.core.data.api.WarningUploadRepository
import com.example.myapplication.core.data.device.FakeDeviceRepository
import com.example.myapplication.core.data.device.HttpDeviceRepository
import com.example.myapplication.core.data.device.ServerDeviceRepository
import com.example.myapplication.core.data.location.AMapLocationRepository
import com.example.myapplication.core.data.location.FakeLocationRepository
import com.example.myapplication.core.data.prefs.SettingsRepository
import com.example.myapplication.core.data.record.WorkRecordRepository

/**
 * 设备传输方式（M01）：
 * - [FAKE] 模拟设备（默认，演示用）
 * - [HTTP] 手机直连真机 192.168.4.1（旧方案）
 * - [SERVER] 三层架构：走服务端（协议见 docs/后端接口设计文档.md，联调用 tools/mock-server）
 */
enum class DeviceTransport { FAKE, HTTP, SERVER }

/** 手机定位来源：FAKE = 演示模拟轨迹；AMAP = 真实高德定位（需定位权限+隐私同意） */
enum class LocationSource { FAKE, AMAP }

/** 任务上传方式：STUB = 演示占位（无服务器）；HTTP = 真实服务器 */
enum class UploadMode { STUB, HTTP }

/** 道路风险数据源：FAKE = 演示数据；SERVER = 服务端 GET /api/v1/risks */
enum class RiskSource { FAKE, SERVER }

/** [ServiceLocator.applyServerBaseUrl] 的结果 */
enum class ServerUrlApplyResult {
    /** 地址非法，未做任何改动 */
    INVALID,

    /** 规范化后与当前地址相同（例如只换了个等价写法）—— 不登出、不断连 */
    UNCHANGED,

    /** 确实换了服务端：旧会话已作废，调用方应引导重新登录 */
    CHANGED,
}

/**
 * 简易手动 DI（单模块不引入 Hilt/Koin）。
 * 通信层接口化：默认 Fake 模拟设备（无真机可跑通全流程），接真机切 HTTP。
 */
class ServiceLocator(context: Context) {

    val settingsRepository = SettingsRepository(context)

    init {
        // 启动期同步灌一次地址（DataStore 是异步的，而下面的仓库要能同步取到地址）。
        // 唯一一次阻塞主线程，量级 1–50 ms；详见 SettingsRepository.hydrateServerBaseUrlBlocking。
        settingsRepository.hydrateServerBaseUrlBlocking()
    }

    /**
     * 各 HTTP 仓库的服务端地址提供者：**每次请求时才解析**。
     *
     * 这样换地址不需要重建 ServiceLocator / ViewModel —— ViewModel 在构造时就把仓库实例
     * 捕获成 val（见 AppNavHost 的 Factory），重建传不过去；provider 让对象标识保持不变。
     */
    private val baseUrlProvider: () -> String = { settingsRepository.currentServerBaseUrl }

    /** 当前生效的服务端地址（同步读；界面请订阅 `settingsRepository.serverBaseUrl` 以跟随变化） */
    val serverBaseUrl: String get() = settingsRepository.currentServerBaseUrl

    /**
     * 账号（登录/登出）。设备信息按登录用户获取：各 HTTP 仓库经 [AuthRepository.currentToken]
     * 携带 `Authorization`；未登录（含跳过登录）时不带，Mock 服务端按默认用户 demo 处理。
     * 调试默认账号：demo / 123456（登录页已预填）。
     */
    val authRepository: AuthRepository =
        com.example.myapplication.core.data.auth.HttpAuthRepository(baseUrlProvider)

    val workRecordRepository = WorkRecordRepository(context)

    /** 用户添加设备登记（连接页「添加设备」：手动编号 / 扫码） */
    val deviceRegistryRepository =
        com.example.myapplication.core.data.device.DeviceRegistryRepository(context)

    /** 任务快照本地存储（小车运行中抓拍） */
    val snapshotRepository: com.example.myapplication.core.data.media.SnapshotRepository =
        com.example.myapplication.core.data.media.LocalSnapshotRepository(context)

    /**
     * 快照上传（仅 SERVER 模式启用；演示模式为 null 仅存本机）。
     * 统一约定：上传后服务端将其登记为风险事件（风险页 source=worker 可见）。
     */
    val snapshotUploadRepository: SnapshotUploadRepository? = when (TRANSPORT) {
        DeviceTransport.SERVER -> com.example.myapplication.core.data.media.HttpSnapshotUploadRepository(
            baseUrlProvider,
            tokenProvider = { authRepository.currentToken },
        )
        else -> null
    }

    /** 预警信息上传（**接口预留，暂不实现**） */
    val warningUploadRepository: WarningUploadRepository =
        com.example.myapplication.core.data.warning.StubWarningUploadRepository()

    /** 新风险弹窗的本地已读表（仅本机，不上传服务端） */
    val riskReadStore = com.example.myapplication.core.data.risk.RiskReadStore(context)

    /** 道路风险数据源（SERVER 模式自动走服务端 /api/v1/risks；含作业人员上传登记的风险事件） */
    val riskRepository: RiskRepository =
        when (RISK_SOURCE) {
            RiskSource.FAKE -> com.example.myapplication.core.data.risk.FakeRiskRepository()
            RiskSource.SERVER -> com.example.myapplication.core.data.risk.HttpRiskRepository(
                baseUrlProvider,
                tokenProvider = { authRepository.currentToken },
            )
        }

    /** 任务上传（SERVER 模式自动走服务端 POST /api/v1/tasks） */
    val taskUploadRepository: TaskUploadRepository =
        when (UPLOAD_MODE) {
            UploadMode.STUB -> com.example.myapplication.core.data.upload.StubTaskUploadRepository()
            UploadMode.HTTP -> com.example.myapplication.core.data.upload.HttpTaskUploadRepository(
                baseUrlProvider,
                tokenProvider = { authRepository.currentToken },
            )
        }

    val deviceRepository: DeviceRepository = when (TRANSPORT) {
        DeviceTransport.FAKE -> FakeDeviceRepository()
        DeviceTransport.HTTP -> HttpDeviceRepository()
        DeviceTransport.SERVER -> ServerDeviceRepository(
            baseUrlProvider,
            tokenProvider = { authRepository.currentToken },
        )
    }

    /**
     * 应用新的服务端地址（**唯一写入入口**）。顺序不可调换：
     *
     * 1. 落库 + 更新内存缓存 → 此后所有仓库立即指向新地址
     * 2. 作废旧会话（`clearSession`，**不是** `logout` —— 后者会把旧 token 发给新服务端）
     * 3. 复位设备层中"属于旧服务端"的状态
     *
     * 调用方在返回 [ServerUrlApplyResult.CHANGED] 后，还需通知 `VehicleViewModel.onServerChanged()`
     * 停掉状态刷新与遥控保持包，并拉取新服务端的设备列表。
     */
    suspend fun applyServerBaseUrl(raw: String): ServerUrlApplyResult {
        val canonical = com.example.myapplication.core.util.normalizeServerUrl(raw)
            ?: return ServerUrlApplyResult.INVALID
        val changed = canonical != settingsRepository.currentServerBaseUrl
        if (!settingsRepository.saveServerBaseUrl(canonical)) return ServerUrlApplyResult.INVALID
        if (changed) {
            authRepository.clearSession()
            (deviceRepository as? ServerDeviceRepository)?.resetForNewServer()
            // 不同服务端的风险 id 会重复（如 r-1000…），旧的已读表会让新服务端的新事故被误判为已读
            riskReadStore.clear()
        }
        return if (changed) ServerUrlApplyResult.CHANGED else ServerUrlApplyResult.UNCHANGED
    }

    /** 清除用户覆盖，回到编译期默认地址（语义同 [applyServerBaseUrl]） */
    suspend fun resetServerBaseUrl(): ServerUrlApplyResult {
        val before = settingsRepository.currentServerBaseUrl
        val restored = settingsRepository.resetServerBaseUrl()
        if (restored == before) return ServerUrlApplyResult.UNCHANGED
        authRepository.clearSession()
        (deviceRepository as? ServerDeviceRepository)?.resetForNewServer()
        riskReadStore.clear()
        return ServerUrlApplyResult.CHANGED
    }

    val transport: DeviceTransport get() = TRANSPORT

    /** 注意：AMapLocationRepository 构造函数不实例化定位 SDK，隐私合规见该类 KDoc */
    val locationRepository: LocationRepository = when (LOCATION_SOURCE) {
        LocationSource.FAKE -> FakeLocationRepository()
        LocationSource.AMAP -> AMapLocationRepository(context)
    }

    val locationSource: LocationSource get() = LOCATION_SOURCE

    companion object {
        /**
         * 一行切换（联调只改这一行）：
         * - [DeviceTransport.FAKE]   模拟设备演示（默认，无服务端也能跑全流程）
         * - [DeviceTransport.SERVER] 三层架构联调：状态/命令/记录上传/风险数据全部走服务端
         *                            （服务端地址见 SettingsRepository.serverBaseUrl，步骤见 tools/mock-server/README.md）
         * - [DeviceTransport.HTTP]   旧方案：手机直连真机 192.168.4.1
         */
        val TRANSPORT: DeviceTransport = DeviceTransport.SERVER

        /** 上传通道：SERVER 模式自动切真实服务端（需独立演示占位时可改回 STUB） */
        val UPLOAD_MODE: UploadMode
            get() = if (TRANSPORT == DeviceTransport.SERVER) UploadMode.HTTP else UploadMode.STUB

        /** 风险数据源：SERVER 模式自动切服务端 /api/v1/risks */
        val RISK_SOURCE: RiskSource
            get() = if (TRANSPORT == DeviceTransport.SERVER) RiskSource.SERVER else RiskSource.FAKE

        /** 一行切换：FAKE = 演示模拟轨迹（北京基准点）；AMAP = 真实高德定位（显示手机实际位置） */
        val LOCATION_SOURCE: LocationSource = LocationSource.AMAP

        fun get(context: Context): ServiceLocator =
            (context.applicationContext as MyAppApplication).serviceLocator
    }
}
