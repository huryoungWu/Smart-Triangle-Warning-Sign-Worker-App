package com.example.myapplication.core.geo

import com.example.myapplication.core.model.deployment.DeploymentEvent

/**
 * 锥桶地图估算点。
 *
 * 优先使用服务端返回的 `pos {x,y}`（相对起始线，米，东/北）与 `headingDeg`，
 * 这样手动转向后点位/轨迹能反映朝向变化；
 * 旧服务端/旧事件没有 `pos` 时，回退到按 `traveledM` 沿正北投影。
 * 仍然只是服务端行程/朝向的估算，**不是 GPS 定位**。
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
    val headingDeg: Double = 0.0,
)

fun resolveDeploymentConePins(
    event: DeploymentEvent,
    anchor: GeoPoint?,
    headingDeg: Double = 0.0,
): List<DeploymentConeMapPin> {
    if (anchor == null) return emptyList()
    return event.devices.map { device ->
        val pos = device.pos
        val point = if (pos != null) {
            offsetGcj02(anchor, pos.x, pos.y)
        } else {
            CoordTransform.projectGcj02(
                start = anchor,
                headingDeg = headingDeg,
                distanceM = device.traveledM.coerceAtLeast(0.0),
            )
        }
        DeploymentConeMapPin(
            deviceCode = device.deviceCode,
            point = point,
            order = device.order,
            isMaster = device.isMaster,
            traveledM = device.traveledM,
            targetM = device.targetM,
            arrived = device.arrived,
            skipped = device.skipped,
            headingDeg = device.headingDeg,
        )
    }
}

/** 以锚点为原点，按东/北偏移（米）得到 GCJ-02 点位 */
fun offsetGcj02(anchor: GeoPoint, eastM: Double, northM: Double): GeoPoint {
    val north = CoordTransform.projectGcj02(anchor, 0.0, northM)
    return CoordTransform.projectGcj02(north, 90.0, eastM)
}
