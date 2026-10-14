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

/**
 * 简易手动 DI（单模块不引入 Hilt/Koin）。
 * 通信层接口化：默认 Fake 模拟设备（无真机可跑通全流程），接真机切 HTTP。
 */
class ServiceLocator(context: Context) {

    val settingsRepository = SettingsRepository(context)

    /**
     * 账号（登录/登出）。设备信息按登录用户获取：各 HTTP 仓库经 [AuthRepository.currentToken]
     * 携带 `Authorization`；未登录（含跳过登录）时不带，Mock 服务端按默认用户 demo 处理。
     * 调试默认账号：demo / 123456（登录页已预填）。
     */
    val authRepository: AuthRepository = com.example.myapplication.core.data.auth.HttpAuthRepository(SERVER_BASE_URL)

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
            SERVER_BASE_URL,
            tokenProvider = { authRepository.currentToken },
        )
        else -> null
    }

    /** 预警信息上传（**接口预留，暂不实现**） */
    val warningUploadRepository: WarningUploadRepository =
        com.example.myapplication.core.data.warning.StubWarningUploadRepository()

    /** 道路风险数据源（SERVER 模式自动走服务端 /api/v1/risks；含作业人员上传登记的风险事件） */
    val riskRepository: RiskRepository =
        when (RISK_SOURCE) {
            RiskSource.FAKE -> com.example.myapplication.core.data.risk.FakeRiskRepository()
            RiskSource.SERVER -> com.example.myapplication.core.data.risk.HttpRiskRepository(
                SERVER_BASE_URL,
                tokenProvider = { authRepository.currentToken },
            )
        }

    /** 任务上传（SERVER 模式自动走服务端 POST /api/v1/tasks） */
    val taskUploadRepository: TaskUploadRepository =
        when (UPLOAD_MODE) {
            UploadMode.STUB -> com.example.myapplication.core.data.upload.StubTaskUploadRepository()
            UploadMode.HTTP -> com.example.myapplication.core.data.upload.HttpTaskUploadRepository(
                SERVER_BASE_URL,
                tokenProvider = { authRepository.currentToken },
            )
        }

    val deviceRepository: DeviceRepository = when (TRANSPORT) {
        DeviceTransport.FAKE -> FakeDeviceRepository()
        DeviceTransport.HTTP -> HttpDeviceRepository()
        DeviceTransport.SERVER -> ServerDeviceRepository(
            SERVER_BASE_URL,
            tokenProvider = { authRepository.currentToken },
        )
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
         *                            （配套地址 [SERVER_BASE_URL]，步骤见 tools/mock-server/README.md）
         * - [DeviceTransport.HTTP]   旧方案：手机直连真机 192.168.4.1
         */
        val TRANSPORT: DeviceTransport = DeviceTransport.SERVER

        /**
         * 联调服务端地址（TRANSPORT = SERVER 时生效）。**当前 = 模拟器 / USB 调试模式**：
         * - 模拟器 / USB 真机：保持 "http://127.0.0.1:8080"，执行一次 `adb reverse tcp:8080 tcp:8080`
         *   （走 adb 隧道，不依赖设备自身的网络配置）
         * - 真机同 Wi-Fi：改成电脑局域网 IP（Mock 启动时会打印），
         *   并把该地址加进 res/xml/network_security_config.xml 白名单（192.168.43.120 已加，可直接用）
         */
        const val SERVER_BASE_URL = "http://127.0.0.1:8080"

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
