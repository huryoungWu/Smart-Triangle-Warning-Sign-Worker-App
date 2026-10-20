package com.example.myapplication.feature.risk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myapplication.MyAppApplication
import com.example.myapplication.core.data.api.RiskRepository
import com.example.myapplication.core.data.risk.RiskReadStore
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.model.risk.RiskAlertRules
import com.example.myapplication.core.model.risk.RiskRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 新风险弹窗的 Activity 级 ViewModel（`AppNavHost` 根部创建，跨页面共享）。
 *
 * 前台期间每 [RiskAlertRules.POLL_INTERVAL_MS] 拉一次风险列表，与本地已读表比对：
 * - 首次成功拉到非空列表 → 静默基线，不弹；
 * - 之后出现"今天 + 带车道"的未读事故 → 弹一条（最近）+ 其余条数；
 * - 关闭弹窗 → 本批全部记为已读。
 *
 * 已读状态只存本机，不上传服务端。
 */
class RiskAlertViewModel(
    private val repository: RiskRepository,
    private val readStore: RiskReadStore,
    /** 每次轮询时现取，避免捕获旧定位；无定位时用 [FALLBACK_CENTER]。 */
    private val centerProvider: () -> GeoPoint,
) : ViewModel() {

    data class PendingAlert(
        val accident: Accident,
        /** 同批其余未读条数，用于「另有 N 条新风险」 */
        val extraCount: Int,
    )

    private val _pendingAlert = MutableStateFlow<PendingAlert?>(null)
    val pendingAlert: StateFlow<PendingAlert?> = _pendingAlert.asStateFlow()

    private var pollJob: Job? = null
    private val pollMutex = Mutex()

    /** 当前弹窗对应的整批风险 id；关闭弹窗时整批标读。 */
    private var pendingBatchIds: Set<String> = emptySet()

    /** 已关闭但 DataStore 尚未写完的 id，防止同进程下一轮误重复弹。 */
    private val sessionReadIds = mutableSetOf<String>()

    /** 前台开始轮询（幂等）：立即查一次，之后每 60s 一次。 */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    pollMutex.withLock { pollOnce() }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // 静默：网络/解析异常由仓库降级为空列表；下一轮重试
                }
                delay(RiskAlertRules.POLL_INTERVAL_MS)
            }
        }
    }

    /** 进入后台时停止轮询。 */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 关闭弹窗（「查看详情」「知道了」、返回键、点遮罩均走这里）：整批记为已读。 */
    fun onAlertClosed() {
        val ids = pendingBatchIds
        if (ids.isEmpty() && _pendingAlert.value == null) return
        pendingBatchIds = emptySet()
        sessionReadIds += ids
        _pendingAlert.value = null
        if (ids.isNotEmpty()) {
            viewModelScope.launch {
                runCatching { readStore.markRead(ids) }
            }
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    private suspend fun pollOnce() {
        // 弹窗未关闭：本轮完全跳过（不请求；关闭后的下一轮再处理这段时间新增的风险）。
        if (_pendingAlert.value != null) return

        val fetched = repository.getAccidents(
            center = centerProvider(),
            radiusKm = RiskRadius.DEFAULT_KM.toDouble(),
        )
        // HttpRiskRepository 的 runCatching 会吞掉 CancellationException，
        // 这里补一刀：切后台后不再发布弹窗。
        currentCoroutineContext().ensureActive()

        val stored = readStore.snapshot() ?: return

        if (RiskAlertRules.shouldEstablishBaseline(stored.baselined, fetched)) {
            val ids = fetched.map { it.id }.filter { it.isNotBlank() }
            runCatching { readStore.markBaseline(ids) }
            return
        }
        if (!stored.baselined) return

        val fresh = RiskAlertRules.pickNewRisks(
            fetched = fetched,
            readIds = stored.readIds + sessionReadIds,
            now = System.currentTimeMillis(),
        )
        if (fresh.isEmpty()) return

        pendingBatchIds = fresh.mapTo(mutableSetOf()) { it.id }
        _pendingAlert.value = PendingAlert(
            accident = fresh.first(),   // matchingRisks 已按距离升序
            extraCount = fresh.size - 1,
        )
    }

    companion object {
        /** 与风险页一致的演示基准点（无定位时使用）。 */
        val FALLBACK_CENTER = GeoPoint(39.9928, 116.4004)

        fun factory(centerProvider: () -> GeoPoint): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyAppApplication
                val sl = app.serviceLocator
                RiskAlertViewModel(
                    repository = sl.riskRepository,
                    readStore = sl.riskReadStore,
                    centerProvider = centerProvider,
                )
            }
        }
    }
}
