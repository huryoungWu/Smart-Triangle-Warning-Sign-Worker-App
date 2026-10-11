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

/**
 * 简易手动 DI（单模块不引入 Hilt/Koin）。
 * 通信层接口化：默认 Fake 模拟设备（无真机可跑通全流程），接真机切 HTTP。
 */
class ServiceLocator(context: Context) {

    val settingsRepository = SettingsRepository(context)

    val workRecordRepository = WorkRecordRepository(context)

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

        fun get(context: Context): ServiceLocator =
            (context.applicationContext as MyAppApplication).serviceLocator
    }
}
