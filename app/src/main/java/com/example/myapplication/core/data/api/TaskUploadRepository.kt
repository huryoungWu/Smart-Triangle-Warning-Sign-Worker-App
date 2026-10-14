package com.example.myapplication.core.data.api

import com.example.myapplication.core.model.vehicle.WorkRecord

/** 上传结果（服务器返回的单号与时间）——契约数据类 */
data class UploadResult(val serverId: String, val uploadedAt: Long)

/**
 * 任务上传接口（第 ⑤ 步闭环）——作业记录上传至服务端。
 *
 * 实现分布（本文件为纯接口，不含实现）：
 * - [com.example.myapplication.core.data.upload.StubTaskUploadRepository] 演示占位（无服务器）
 * - [com.example.myapplication.core.data.upload.HttpTaskUploadRepository] 服务端 `POST /api/v1/tasks`（见 docs/后端接口设计文档.md 第 8 节）
 * 装配与切换见 [com.example.myapplication.di.ServiceLocator.UPLOAD_MODE]；上传载荷契约见 [com.example.myapplication.core.data.upload.TaskUploadJson]。
 *
 * ⚠️ 数据真实性：占位模式下界面**不得宣称「已上传平台」**，只说「已模拟上传」。
 */
interface TaskUploadRepository {
    suspend fun upload(record: WorkRecord): Result<UploadResult>
}
