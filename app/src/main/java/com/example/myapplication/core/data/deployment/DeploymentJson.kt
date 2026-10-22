package com.example.myapplication.core.data.deployment

import com.example.myapplication.core.model.deployment.CommandResultState
import com.example.myapplication.core.model.deployment.DeploymentAnomaly
import com.example.myapplication.core.model.deployment.DeploymentCommandState
import com.example.myapplication.core.model.deployment.DeploymentDeviceState
import com.example.myapplication.core.model.deployment.DeploymentDeviceSummary
import com.example.myapplication.core.model.deployment.DeploymentEvent
import com.example.myapplication.core.model.deployment.DeploymentNextExpected
import com.example.myapplication.core.model.deployment.DeploymentParams
import com.example.myapplication.core.model.deployment.DeploymentPhase
import com.example.myapplication.core.model.deployment.DeploymentPose
import com.example.myapplication.core.model.deployment.DeploymentRecordSummary
import com.example.myapplication.core.model.deployment.DeploymentReportState
import com.example.myapplication.core.model.deployment.DeploymentResult
import com.example.myapplication.core.model.deployment.DeploymentDeviceType
import com.example.myapplication.core.model.deployment.DeploymentLimits
import com.example.myapplication.core.model.deployment.DeploymentSkip
import com.example.myapplication.core.model.deployment.DeploymentState
import com.example.myapplication.core.model.deployment.DeploymentStep
import com.example.myapplication.core.model.deployment.DeploymentTriggerState
import com.example.myapplication.core.model.deployment.DeploymentTurn
import com.example.myapplication.core.model.deployment.DeploymentTurnDir
import com.example.myapplication.core.model.deployment.DeploymentVerifyState
import com.example.myapplication.core.model.deployment.DeviceSet
import com.example.myapplication.core.model.deployment.ReportResultState
import com.example.myapplication.core.model.deployment.SkipDisposition
import com.example.myapplication.core.model.deployment.VerifyResultState
import org.json.JSONArray
import org.json.JSONObject

/** 布设事件 JSON 解析（服务端字段见 docs/锥桶协同布设功能需求规格说明书.md 第 9 章） */
object DeploymentJson {

    /**
     * 服务端未下发时的默认电量：70~80 随机。
     * 按 device_code 记住，避免每次刷新设备列表时数值跳变。
     */
    private val defaultBatteryByDevice = mutableMapOf<String, Int>()

    private fun defaultBatteryPercent(deviceCode: String): Int =
        defaultBatteryByDevice.getOrPut(deviceCode) { (70..80).random() }

    /**
     * 服务端未下发时的默认套装号：70~80 随机。
     * 进程内固定，保证同一批缺省设备仍能归到同一个“套装”分组。
     */
    private val defaultSetId: String by lazy { "SET-${(70..80).random()}" }

    fun parseDeviceSummary(o: JSONObject): DeploymentDeviceSummary {
        val deviceCode = o.optString("device_code", "")
        return DeploymentDeviceSummary(
            deviceCode = deviceCode,
            name = o.optString("name", ""),
            deviceType = DeploymentDeviceType.fromToken(o.optString("device_type")),
            bindingCode = o.optString("binding_code").takeIf { it.isNotBlank() && it != "null" },
            setId = o.optString("set_id").takeIf { it.isNotBlank() && it != "null" } ?: defaultSetId,
            masterCode = o.optString("master_code").takeIf { it.isNotBlank() && it != "null" },
            online = !o.has("online") || o.optBoolean("online", true),
            available = !o.has("available") || o.optBoolean("available", true),
            occupied = o.optBoolean("occupied", false),
            occupiedByEvent = o.optString("occupiedByEvent").takeIf { it.isNotBlank() && it != "null" },
            activeTaskId = o.optString("activeTaskId").takeIf { it.isNotBlank() && it != "null" },
            estopLocked = o.optBoolean("estopLocked", false),
            batteryPercent = o.optInt("batteryPercent", -1)
                .takeIf { it in 0..100 }
                ?: defaultBatteryPercent(deviceCode),
        )
    }

    fun parseDeviceList(o: JSONObject): List<DeploymentDeviceSummary> {
        val arr = o.optJSONArray("devices") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(parseDeviceSummary(it)) }
            }
        }
    }

    fun parseDeviceSet(o: JSONObject): DeviceSet = DeviceSet(
        setId = o.optString("set_id").takeIf { it.isNotBlank() && it != "null" } ?: defaultSetId,
        master = o.optJSONObject("master")?.let(::parseDeviceSummary),
        slaves = o.optJSONArray("slaves").let { arr ->
            buildList {
                if (arr != null) {
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(parseDeviceSummary(it)) }
                }
            }
        },
    )

    fun parseEvent(o: JSONObject): DeploymentEvent {
        val params = o.optJSONObject("params")
        val chain = o.optJSONObject("chain")
        return DeploymentEvent(
            deploymentId = o.optString("deploymentId", o.optString("deployment_id", "")),
            eventId = o.optString("eventId").takeIf { it.isNotBlank() && it != "null" },
            name = o.optString("name", "锥桶协同布设"),
            lane = if (o.isNull("lane")) null else o.optInt("lane", 0).takeIf { it in 1..8 },
            state = DeploymentState.fromToken(o.optString("state")),
            result = DeploymentResult.fromToken(o.optString("result")),
            params = DeploymentParams(
                L = params?.optDouble("L", 0.0) ?: 0.0,
                d = params?.optDouble("d", 0.0) ?: 0.0,
                N = params?.optInt("N", 0) ?: 0,
                turn = parseTurn(params?.optJSONObject("turn")),
            ),
            devices = o.optJSONArray("devices").let { arr ->
                buildList {
                    if (arr != null) {
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(parseDeviceState(it)) }
                    }
                }
            },
            nextExpected = chain?.optJSONObject("nextExpected")?.let(::parseNextExpected),
            steps = chain?.optJSONArray("steps").let { arr -> parseSteps(arr) },
            anomalies = parseAnomalies(o.optJSONArray("anomalies")),
            startedAt = o.optLongOrNull("startedAt"),
            updatedAt = o.optLong("updatedAt", 0L),
            completedAt = o.optLongOrNull("completedAt"),
        )
    }

    /** 解析 `params.turn`；缺失/null/非法一律按直行（null）处理，兼容旧服务端 */
    private fun parseTurn(o: JSONObject?): DeploymentTurn? {
        if (o == null) return null
        val dir = DeploymentTurnDir.fromToken(o.optString("dir")) ?: return null
        val atM = o.optDouble("atM", Double.NaN)
        if (!atM.isFinite() || atM <= 0.0) return null
        val deg = o.optDouble("deg", DeploymentLimits.TURN_DEG)
            .takeIf { it.isFinite() && it > 0.0 }
            ?: DeploymentLimits.TURN_DEG
        return DeploymentTurn(dir = dir, atM = atM, deg = deg)
    }

    fun parseDeviceState(o: JSONObject): DeploymentDeviceState {
        val phaseToken = o.optString("phase").ifBlank { o.optString("startState") }
        val start = o.optJSONObject("startCommand")
        val report = o.optJSONObject("report")
        val trigger = o.optJSONObject("trigger")
        val skip = o.optJSONObject("skip")
        val verify = o.optJSONObject("verify")
        val anomaly = o.optJSONObject("anomaly")
        val pos = o.optJSONObject("pos")
        return DeploymentDeviceState(
            deviceCode = o.optString("device_code", ""),
            name = o.optString("name", ""),
            deviceType = DeploymentDeviceType.fromToken(o.optString("device_type")),
            bindingCode = o.optString("binding_code").takeIf { it.isNotBlank() && it != "null" },
            order = o.optInt("order", 0),
            online = !o.has("online") || o.optBoolean("online", true),
            targetM = o.optDouble("targetM", 0.0),
            traveledM = o.optDouble("traveledM", 0.0),
            phase = DeploymentPhase.fromToken(phaseToken),
            startCommand = DeploymentCommandState(
                state = CommandResultState.fromToken(start?.optString("state")),
                commandId = start?.optString("commandId")?.takeIf { it.isNotBlank() && it != "null" },
                sentAt = start?.optLongOrNull("sentAt"),
                ackedAt = start?.optLongOrNull("ackedAt"),
                reason = start?.optString("reason")?.takeIf { it.isNotBlank() && it != "null" },
            ),
            report = DeploymentReportState(
                state = ReportResultState.fromToken(report?.optString("state")),
                seq = report?.optInt("seq", 0) ?: 0,
                sentAt = report?.optLongOrNull("sentAt"),
                ackedAt = report?.optLongOrNull("ackedAt"),
            ),
            trigger = trigger?.let {
                DeploymentTriggerState(
                    to = it.optString("to").takeIf { v -> v.isNotBlank() && v != "null" },
                    seq = it.optInt("seq", 0),
                    state = it.optString("state", ""),
                    commandId = it.optString("commandId").takeIf { v -> v.isNotBlank() && v != "null" },
                )
            },
            reachedInterval = o.optBoolean("reachedInterval", false),
            arrived = o.optBoolean("arrived", false),
            skip = skip?.let {
                DeploymentSkip(
                    skipped = it.optBoolean("skipped", true),
                    reason = it.optString("reason", ""),
                    disposition = SkipDisposition.fromToken(it.optString("disposition")),
                    note = it.optString("note", ""),
                    skippedAt = it.optLongOrNull("skippedAt"),
                    skippedBy = it.optString("skippedBy").takeIf { v -> v.isNotBlank() && v != "null" },
                    stopConfirmed = if (it.has("stopConfirmed") && !it.isNull("stopConfirmed")) it.optBoolean("stopConfirmed") else null,
                )
            },
            verify = DeploymentVerifyState(
                state = VerifyResultState.fromToken(verify?.optString("state")),
                note = verify?.optString("note")?.takeIf { it.isNotBlank() && it != "null" },
                verifiedAt = verify?.optLongOrNull("verifiedAt"),
            ),
            anomaly = anomaly?.let {
                DeploymentAnomaly(
                    code = it.optString("code", ""),
                    message = it.optString("message", ""),
                    deviceCode = o.optString("device_code", ""),
                    detectedAt = it.optLong("detectedAt", 0L),
                )
            },
            pos = pos?.let { DeploymentPose(it.optDouble("x", 0.0), it.optDouble("y", 0.0)) },
            headingDeg = o.optDouble("headingDeg", 0.0),
            manualActive = o.optBoolean("manualActive", false),
        )
    }

    private fun parseNextExpected(o: JSONObject) = DeploymentNextExpected(
        type = o.optString("type", ""),
        deviceCode = o.optString("device_code", ""),
        thresholdM = o.optDouble("thresholdM", 0.0),
        afterReportTrigger = o.optString("after_report_trigger").takeIf { it.isNotBlank() && it != "null" },
        state = o.optString("state", ""),
    )

    private fun parseSteps(arr: JSONArray?): List<DeploymentStep> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                DeploymentStep(
                    seq = o.optInt("seq", i + 1),
                    type = o.optString("type", ""),
                    from = o.optString("from").takeIf { it.isNotBlank() && it != "null" },
                    to = o.optString("to").takeIf { it.isNotBlank() && it != "null" },
                    cause = o.optString("cause").takeIf { it.isNotBlank() && it != "null" },
                    causeReportId = o.optString("causeReportId").takeIf { it.isNotBlank() && it != "null" },
                    commandId = o.optString("commandId").takeIf { it.isNotBlank() && it != "null" },
                    reportId = o.optString("reportId").takeIf { it.isNotBlank() && it != "null" },
                    state = o.optString("state", ""),
                    at = o.optLong("at", 0L),
                ),
            )
        }
    }

    private fun parseAnomalies(arr: JSONArray?): List<DeploymentAnomaly> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(
                DeploymentAnomaly(
                    code = o.optString("code", ""),
                    message = o.optString("message", ""),
                    deviceCode = o.optString("device_code", "").takeIf { it.isNotBlank() && it != "null" },
                    detectedAt = o.optLong("detectedAt", 0L),
                ),
            )
        }
    }

    fun parseRecordSummary(o: JSONObject): DeploymentRecordSummary = DeploymentRecordSummary(
        deploymentId = o.optString("deploymentId", o.optString("deployment_id", "")),
        name = o.optString("name", "锥桶协同布设"),
        lane = if (o.isNull("lane")) null else o.optInt("lane", 0).takeIf { it in 1..8 },
        state = DeploymentState.fromToken(o.optString("state")),
        result = DeploymentResult.fromToken(o.optString("result")),
        L = o.optDouble("L", 0.0),
        d = o.optDouble("d", 0.0),
        N = o.optInt("N", 0),
        masterCode = o.optString("master_code").takeIf { it.isNotBlank() && it != "null" },
        createdAt = o.optLong("createdAt", 0L),
        startedAt = o.optLongOrNull("startedAt"),
        completedAt = o.optLongOrNull("completedAt"),
        hasSkip = o.optBoolean("hasSkip", false),
    )

    fun parseRecordList(o: JSONObject): List<DeploymentRecordSummary> {
        val arr = o.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(parseRecordSummary(it)) }
        }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}
