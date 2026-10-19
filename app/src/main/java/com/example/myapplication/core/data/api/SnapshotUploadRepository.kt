package com.example.myapplication.core.data.api

import com.example.myapplication.core.model.media.TaskSnapshot

/**
 * 快照上传接口（对外接口契约）。
 *
 * **统一约定（与风险页联动）**：快照上传成功后，服务端将其登记为**风险事件**
 * （`GET /api/v1/risks` 中 `source=worker`），风险页即可展示 —— 即"上传后才能在风险中展示"。
 * 详见 docs/后端接口设计文档.md 第 9/12 节。
 *
 * 实现分布（本文件为纯接口，不含实现）：
 * - [com.example.myapplication.core.data.media.HttpSnapshotUploadRepository] 服务端 `POST /api/v1/snapshots`（multipart）
 *
 * 本地存储另见 [com.example.myapplication.core.data.media.SnapshotRepository]；
 * 演示模式（TRANSPORT=FAKE）下不配置上传实现（为 null），快照仅存本机。
 */
interface SnapshotUploadRepository {
    /** 上传快照（成功返回服务端快照 id） */
    suspend fun upload(snapshot: TaskSnapshot, deviceCode: String): Result<String>
}
