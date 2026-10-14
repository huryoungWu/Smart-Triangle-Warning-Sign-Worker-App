package com.example.myapplication.core.data.api

import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.model.risk.Accident

/**
 * 道路风险数据源（对外接口契约）——前方道路风险提示。
 *
 * 实现分布（本文件为纯接口，不含实现）：
 * - [com.example.myapplication.core.data.risk.FakeRiskRepository] 演示数据（无真实事故源时）
 * - [com.example.myapplication.core.data.risk.HttpRiskRepository] 服务端 `GET /api/v1/risks`（见 docs/后端接口设计文档.md 第 12 节）
 * 装配与切换见 [com.example.myapplication.di.ServiceLocator.RISK_SOURCE]。
 */
interface RiskRepository {
    /** 拉取用户周围 [radiusKm] 公里内的风险事件 */
    suspend fun getAccidents(center: GeoPoint, radiusKm: Double): List<Accident>
}
