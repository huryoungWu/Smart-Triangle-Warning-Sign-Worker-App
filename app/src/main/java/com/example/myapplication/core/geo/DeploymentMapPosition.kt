package com.example.myapplication.core.geo

import com.example.myapplication.core.model.deployment.DeploymentEvent

/**
 * 锥桶地图估算点。
 *
 * 当前部署接口只返回每只桶的 `traveledM`（自身已行进距离），没有逐桶 GPS；
 * 与三角架无车端 GPS 时一样，以发布/启动时的手机位置为锚点、默认正北方向
 * 直线投影 `traveledM` 米，仅用于现场示意，**不是 GPS 定位**。
 */
data class DeploymentConeMapPin(
    val deviceCode: String,
    val point: GeoPoint,
    val order: Int,
    val isMaster: Boolean,
    val traveledM: Double,
    val targetM: Double,
    val arrived: Boolean,
    val skipped: Boolean,
)

fun resolveDeploymentConePins(
    event: DeploymentEvent,
    anchor: GeoPoint?,
    headingDeg: Double = 0.0,
): List<DeploymentConeMapPin> {
    if (anchor == null) return emptyList()
    return event.devices.map { device ->
        DeploymentConeMapPin(
            deviceCode = device.deviceCode,
            point = CoordTransform.projectGcj02(
                start = anchor,
                headingDeg = headingDeg,
                distanceM = device.traveledM.coerceAtLeast(0.0),
            ),
            order = device.order,
            isMaster = device.isMaster,
            traveledM = device.traveledM,
            targetM = device.targetM,
            arrived = device.arrived,
            skipped = device.skipped,
        )
    }
}
