package com.example.myapplication.di

import android.content.Context
import com.example.myapplication.MyAppApplication
import com.example.myapplication.core.data.device.DeviceRepository
import com.example.myapplication.core.data.device.FakeDeviceRepository
import com.example.myapplication.core.data.device.HttpDeviceRepository
import com.example.myapplication.core.data.location.AMapLocationRepository
import com.example.myapplication.core.data.location.FakeLocationRepository
import com.example.myapplication.core.data.location.LocationRepository
import com.example.myapplication.core.data.prefs.SettingsRepository
import com.example.myapplication.core.data.record.WorkRecordRepository

/** 设备传输方式（M01）：验收真机时把 [ServiceLocator.TRANSPORT] 切成 [DeviceTransport.HTTP] */
enum class DeviceTransport { FAKE, HTTP }

/** 手机定位来源：FAKE = 演示模拟轨迹；AMAP = 真实高德定位（需定位权限+隐私同意） */
enum class LocationSource { FAKE, AMAP }

/** 任务上传方式：STUB = 演示占位（无服务器）；HTTP = 真实服务器 */
enum class UploadMode { STUB, HTTP }

/**
 * 简易手动 DI（单模块不引入 Hilt/Koin）。
 * 通信层接口化：默认 Fake 模拟设备（无真机可跑通全流程），接真机切 HTTP。
 */
class ServiceLocator(context: Context) {

    val settingsRepository = SettingsRepository(context)

    val workRecordRepository = WorkRecordRepository(context)

    /** 道路风险数据源（演示数据，接真实数据源时切 HttpRiskRepository） */
    val riskRepository: com.example.myapplication.core.data.risk.RiskRepository =
        com.example.myapplication.core.data.risk.FakeRiskRepository()

    /** 任务上传（服务器尚未接入 → 占位实现） */
    val taskUploadRepository: com.example.myapplication.core.data.upload.TaskUploadRepository =
        when (UPLOAD_MODE) {
            UploadMode.STUB -> com.example.myapplication.core.data.upload.StubTaskUploadRepository()
            UploadMode.HTTP -> com.example.myapplication.core.data.upload.HttpTaskUploadRepository()
        }

    val deviceRepository: DeviceRepository = when (TRANSPORT) {
        DeviceTransport.FAKE -> FakeDeviceRepository()
        DeviceTransport.HTTP -> HttpDeviceRepository()
    }

    val transport: DeviceTransport get() = TRANSPORT

    /** 注意：AMapLocationRepository 构造函数不实例化定位 SDK，隐私合规见该类 KDoc */
    val locationRepository: LocationRepository = when (LOCATION_SOURCE) {
        LocationSource.FAKE -> FakeLocationRepository()
        LocationSource.AMAP -> AMapLocationRepository(context)
    }

    val locationSource: LocationSource get() = LOCATION_SOURCE

    companion object {
        /** 一行切换：FAKE = 模拟设备演示；HTTP = 真实 192.168.4.1 */
        val TRANSPORT: DeviceTransport = DeviceTransport.FAKE

        /** 一行切换：FAKE = 演示模拟轨迹（北京基准点）；AMAP = 真实高德定位（显示手机实际位置） */
        val LOCATION_SOURCE: LocationSource = LocationSource.AMAP

        /** 一行切换：STUB = 演示占位上传；HTTP = 真实服务器（未接入） */
        val UPLOAD_MODE: UploadMode = UploadMode.STUB

        fun get(context: Context): ServiceLocator =
            (context.applicationContext as MyAppApplication).serviceLocator
    }
}
