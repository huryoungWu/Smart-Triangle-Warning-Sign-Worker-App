package com.example.myapplication.core.data.api

import com.example.myapplication.core.model.deployment.DeploymentDeviceSummary
import com.example.myapplication.core.model.deployment.DeploymentDraft
import com.example.myapplication.core.model.deployment.DeploymentEvent
import com.example.myapplication.core.model.deployment.DeploymentRecordSummary
import com.example.myapplication.core.model.deployment.DeploymentRole
import com.example.myapplication.core.model.deployment.DeviceSet
import com.example.myapplication.core.model.deployment.SkipDisposition

/**
 * 锥桶协同布设通信接口 —— APP 只认服务端协议。
 *
 * 实现：
 * - [com.example.myapplication.core.data.deployment.ServerDeploymentRepository]：真实/Mock 服务端
 * - [com.example.myapplication.core.data.deployment.FakeDeploymentRepository]：无服务端演示
 *
 * 失败语义沿用现有 DeviceRepository：Result.failure 携带可读原因；服务端 409 冲突原样展示。
 */
interface DeploymentRepository {
    /** 设备台账（可选按角色过滤） */
    suspend fun listDevices(role: DeploymentRole? = null): Result<List<DeploymentDeviceSummary>>

    /** 主桶所属套装的从桶（仅默认候选，不限制加入其他主桶） */
    suspend fun getDeviceSet(masterCode: String): Result<DeviceSet>

    /** 扫码/编号绑定（device_type=cone；绑定≠加入事件） */
    suspend fun bindDevice(deviceCode: String, bindingCode: String): Result<DeploymentDeviceSummary>

    /** 解绑（执行中/被占用时服务端拒绝） */
    suspend fun unbindDevice(deviceCode: String): Result<Unit>

    /** 当前作业人员的非终态事件（一个作业人员同时只允许一个） */
    suspend fun activeDeployment(): Result<DeploymentEvent?>

    /** 事件列表（记录页） */
    suspend fun listDeployments(state: String? = null, page: Int = 1, size: Int = 20): Result<List<DeploymentRecordSummary>>

    /** 创建（READY，不启动） */
    suspend fun createDeployment(draft: DeploymentDraft, clientRequestId: String): Result<DeploymentEvent>

    /** 启动（服务端原子占用设备；成功返回最新事件） */
    suspend fun startDeployment(deploymentId: String, commandId: String): Result<DeploymentEvent>

    /** 聚合状态（执行页 1Hz） */
    suspend fun getDeployment(deploymentId: String): Result<DeploymentEvent>

    suspend fun pause(deploymentId: String, reason: String): Result<Unit>
    suspend fun resume(deploymentId: String): Result<Unit>
    suspend fun abort(deploymentId: String, reason: String): Result<Unit>

    /** 重试当前阻断步骤：start_command 复用原指令 ID；report 重发同一 reportId */
    suspend fun retry(
        deploymentId: String,
        type: String,
        deviceCode: String,
        originalCommandId: String? = null,
        reportId: String? = null,
    ): Result<Unit>

    /** 受控人工跳过（仅异常阻断时、仅从桶） */
    suspend fun skip(
        deploymentId: String,
        deviceCode: String,
        reason: String,
        disposition: SkipDisposition,
        note: String = "",
    ): Result<Unit>

    suspend fun verify(deploymentId: String, deviceCode: String, pass: Boolean, note: String): Result<Unit>
    suspend fun complete(deploymentId: String): Result<Unit>
}
