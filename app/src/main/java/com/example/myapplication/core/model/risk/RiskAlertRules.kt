package com.example.myapplication.core.model.risk

/**
 * 新风险弹窗的纯判定逻辑（与 UI 解耦，便于单测）。
 *
 * 口径与风险页「事故列表」的默认视图完全一致：
 * `lane != null` + 今天（北京时间）+ 按距离近→远。
 */
object RiskAlertRules {

    /** App 前台轮询间隔。 */
    const val POLL_INTERVAL_MS = 60_000L

    /**
     * 列表默认视图中的风险（已按距离从近到远排序）。
     *
     * 直接复用 [applyRiskView]，保证弹窗口径与列表不会漂移。
     * 丢弃空白 id（无法记录已读）并对重复 id 去重（保留排序后最近的一条）。
     */
    fun matchingRisks(
        fetched: List<Accident>,
        now: Long = System.currentTimeMillis(),
    ): List<Accident> =
        fetched.applyRiskView(RiskTimeScope.TODAY, RiskSort.DISTANCE, now)
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }

    /** 未读的新风险；[List.first] 即本次应弹窗展示的最近一条。 */
    fun pickNewRisks(
        fetched: List<Accident>,
        readIds: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): List<Accident> =
        matchingRisks(fetched, now).filter { it.id !in readIds }

    /**
     * 是否应建立首次静默基线。
     *
     * 只用**完整 fetched** 是否为空来判断：空列表可能是网络失败（[HttpRiskRepository]
     * 把异常降级为空列表），不能当基线；而非空列表即使没有任何"今天"条目，也应建立基线，
     * 否则之后出现的第一条今天的风险会被基线吞掉。
     */
    fun shouldEstablishBaseline(baselined: Boolean, fetched: List<Accident>): Boolean =
        !baselined && fetched.isNotEmpty()
}
