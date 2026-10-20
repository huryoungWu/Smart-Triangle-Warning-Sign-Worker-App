package com.example.myapplication.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.core.ui.NewRiskAlertDialog
import com.example.myapplication.core.ui.permission.rememberLocationPermissionState
import com.example.myapplication.feature.risk.RiskAlertViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapplication.core.ui.AppDialogRenderer
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.feature.connect.ConnectScreen
import com.example.myapplication.feature.deployment.DeploymentCreateScreen
import com.example.myapplication.feature.deployment.DeploymentExecScreen
import com.example.myapplication.feature.deployment.DeploymentRecordsScreen
import com.example.myapplication.feature.deployment.DeploymentViewModel
import com.example.myapplication.feature.login.LoginScreen
import com.example.myapplication.feature.debug.DebugScreen
import com.example.myapplication.feature.home.HomeScreen
import com.example.myapplication.feature.map.MapScreen
import com.example.myapplication.feature.map.MapViewModel
import com.example.myapplication.feature.record.RecordsScreen
import com.example.myapplication.feature.risk.AccidentDetailScreen
import com.example.myapplication.feature.risk.RiskScreen
import com.example.myapplication.feature.settings.AboutScreen
import com.example.myapplication.feature.settings.DeviceInfoScreen
import com.example.myapplication.feature.settings.ServerConfigScreen
import com.example.myapplication.feature.settings.SettingsScreen
import com.example.myapplication.feature.task.TaskExecScreen
import com.example.myapplication.feature.task.TaskPublishScreen
import com.example.myapplication.feature.vehicle.VehicleViewModel
import kotlinx.coroutines.awaitCancellation

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabItems = listOf(
    TabItem(Routes.HOME, "作业", Icons.Default.Home),
    TabItem(Routes.RISK, "风险", Icons.Default.Warning),
    TabItem(Routes.RECORDS, "记录", Icons.Default.List),
    TabItem(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

/**
 * 顶层导航。
 *
 * 主流程：HOME（连接门）→ TASK_PUBLISH（发布表单）→ TASK_EXEC（执行，无启动按钮）。
 * 发布时 `popUpTo(TASK_PUBLISH){inclusive=true}`，表单出栈防重复发布。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val vm: VehicleViewModel = viewModel(factory = VehicleViewModel.Factory)
    val mapVm: MapViewModel = viewModel(factory = MapViewModel.Factory)
    val deploymentVm: DeploymentViewModel = viewModel(factory = DeploymentViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 新风险轮询（Activity 级，跨页面共享；必须在 NavHost 外创建）
    val riskAlertVm: RiskAlertViewModel = viewModel(
        factory = RiskAlertViewModel.factory {
            mapVm.phoneFix.value?.point ?: RiskAlertViewModel.FALLBACK_CENTER
        },
    )
    val pendingAlert by riskAlertVm.pendingAlert.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 事故详情传参（简单场景用内存传递，避免序列化）
    var pendingAccident by remember { mutableStateOf<com.example.myapplication.core.model.risk.Accident?>(null) }

    // 仅前台轮询：STARTED 启动，进入后台停止
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, riskAlertVm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            riskAlertVm.start()
            try {
                awaitCancellation()
            } finally {
                riskAlertVm.stop()
            }
        }
    }

    // 隐私同意 + 定位权限就绪后全局启动定位（与 MapPanel 同一门控），
    // 保证用户停在非地图页面时，新风险查询也有真实中心点
    val mapState by mapVm.uiState.collectAsStateWithLifecycle()
    val locationPermission = rememberLocationPermissionState()
    LaunchedEffect(mapState.privacyAccepted, locationPermission.granted) {
        if (mapState.privacyAccepted && locationPermission.granted) {
            mapVm.startLocating()
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in Routes.bottomBarPages) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    onSelect = { route -> navController.navigateToTab(route) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LOGIN,
            modifier = Modifier.padding(innerPadding),
        ) {
            // ⓪ 登录页：接入服务端登录（默认账号 demo/123456 已预填），可跳过
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoggedIn = {
                        // 设备信息按登录用户获取：登录成功后刷新设备列表
                        vm.reloadDevices()
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    onSkip = {
                        vm.reloadDevices()
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                )
            }

            // ① 设备连接页：独立页，连接成功才放行
            composable(Routes.CONNECT) {
                ConnectScreen(
                    vm = vm,
                    onEnterWork = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.CONNECT) { inclusive = true }
                        }
                    },
                    onOpenDeployment = {
                        navController.navigate(Routes.DEPLOYMENT_CREATE)
                    },
                )
            }

            // ② 开始作业页：单一入口「开始新任务」（定距布置 · 全自动驾驶）
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    deploymentVm = deploymentVm,
                    onOpenPublishFixed = {
                        navController.navigate("${Routes.TASK_PUBLISH}?${Routes.ARG_KIND}=${Routes.KIND_FIXED}")
                    },
                    onBackToConnect = {
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    },
                    onOpenExec = { navController.navigate(Routes.TASK_EXEC) },
                    onOpenDeployment = { navController.navigate(Routes.DEPLOYMENT_CREATE) },
                    onOpenDeploymentExec = { id ->
                        deploymentVm.openEvent(id)
                        navController.navigate(Routes.DEPLOYMENT_EXEC)
                    },
                )
            }

            // 风险提示（新增 Tab）：半径可设 + 高德地图 + 距离排序事故列表
            composable(Routes.RISK) {
                RiskScreen(
                    vm = vm,
                    mapVm = mapVm,
                    onOpenDetail = { accident ->
                        pendingAccident = accident
                        navController.navigate(Routes.ACCIDENT_DETAIL)
                    },
                )
            }

            composable(Routes.ACCIDENT_DETAIL) {
                val acc = pendingAccident
                if (acc != null) {
                    AccidentDetailScreen(
                        accident = acc,
                        onBack = { navController.popBackStack() },
                    )
                } else {
                    navController.popBackStack()
                }
            }

            composable(Routes.RECORDS) {
                RecordsScreen(
                    vm = vm,
                    onOpenDeployments = { navController.navigate(Routes.DEPLOYMENT_RECORDS) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    onOpenDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) },
                    onOpenDebug = { navController.navigate(Routes.DEBUG) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenServerConfig = { navController.navigate(Routes.SERVER_CONFIG) },
                    onBackToLogin = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.SERVER_CONFIG) {
                ServerConfigScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    // 地址已切换 → 旧会话作废，回登录页重新登录（同「切换账号」的写法）
                    onSaved = {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable(
                route = "${Routes.TASK_PUBLISH}?${Routes.ARG_KIND}={${Routes.ARG_KIND}}",
                arguments = listOf(navArgument(Routes.ARG_KIND) {
                    type = NavType.StringType
                    defaultValue = Routes.KIND_FIXED
                }),
            ) { entry ->
                val kindToken = entry.arguments?.getString(Routes.ARG_KIND) ?: Routes.KIND_FIXED
                val kind = if (kindToken == Routes.KIND_MANUAL) TaskKind.MANUAL else TaskKind.FIXED_DISTANCE
                TaskPublishScreen(
                    vm = vm,
                    mapVm = mapVm,
                    kind = kind,
                    onBack = { navController.popBackStack() },
                    onPublished = {
                        // 发布即执行：表单出栈，防重复发布
                        navController.navigate(Routes.TASK_EXEC) {
                            popUpTo(Routes.TASK_PUBLISH) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.TASK_EXEC) {
                TaskExecScreen(
                    vm = vm,
                    mapVm = mapVm,
                    onBack = { navController.popBackStack() },
                    onOpenMap = { navController.navigate(Routes.MAP) },
                    onRepublish = {
                        navController.navigate("${Routes.TASK_PUBLISH}?${Routes.ARG_KIND}=${Routes.KIND_FIXED}")
                    },
                )
            }

            // ── 锥桶协同布设：向导 → 执行 → 记录 ──
            composable(Routes.DEPLOYMENT_CREATE) {
                DeploymentCreateScreen(
                    vm = deploymentVm,
                    onBack = { navController.popBackStack() },
                    onStarted = {
                        navController.navigate(Routes.DEPLOYMENT_EXEC) {
                            popUpTo(Routes.DEPLOYMENT_CREATE) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.DEPLOYMENT_EXEC) {
                DeploymentExecScreen(
                    vm = deploymentVm,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.DEPLOYMENT_RECORDS) {
                DeploymentRecordsScreen(
                    vm = deploymentVm,
                    onBack = { navController.popBackStack() },
                    onOpen = { id ->
                        deploymentVm.openEvent(id)
                        navController.navigate(Routes.DEPLOYMENT_EXEC)
                    },
                )
            }

            composable(Routes.DEBUG) {
                DebugScreen(vm = vm, onBack = { navController.popBackStack() })
            }

            composable(Routes.DEVICE_INFO) {
                DeviceInfoScreen(vm = vm, onBack = { navController.popBackStack() })
            }

            composable(Routes.MAP) {
                MapScreen(vm = vm, mapVm = mapVm, onBack = { navController.popBackStack() })
            }

            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }
        }
    }

    state.dialog?.let { dialog ->
        AppDialogRenderer(dialog = dialog, onDismiss = vm::dismissDialog)
    }

    // 新风险弹窗：安全弹窗优先，互不叠加
    if (state.dialog == null) {
        pendingAlert?.let { alert ->
            NewRiskAlertDialog(
                accident = alert.accident,
                extraCount = alert.extraCount,
                onOpenDetail = {
                    riskAlertVm.onAlertClosed()   // 先同步清弹窗，再导航
                    pendingAccident = alert.accident
                    navController.navigate(Routes.ACCIDENT_DETAIL)
                },
                onDismiss = riskAlertVm::onAlertClosed,
            )
        }
    }

}

@Composable
private fun AppBottomBar(
    currentRoute: String?,
    onSelect: (String) -> Unit,
) {
    NavigationBar {
        tabItems.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onSelect(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(Routes.HOME) { saveState = true }
        restoreState = true
    }
}
