package com.example.myapplication.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapplication.core.model.vehicle.TaskKind
import com.example.myapplication.core.ui.AppDialogRenderer
import com.example.myapplication.core.ui.NewRiskAlertDialog
import com.example.myapplication.core.ui.permission.rememberLocalNetworkPermissionState
import com.example.myapplication.core.ui.permission.rememberLocationPermissionState
import com.example.myapplication.di.DeviceTransport
import com.example.myapplication.di.ServiceLocator
import com.example.myapplication.feature.connect.ConnectScreen
import com.example.myapplication.feature.deployment.ConeConnectScreen
import com.example.myapplication.feature.deployment.ConeHomeScreen
import com.example.myapplication.feature.deployment.DeploymentExecScreen
import com.example.myapplication.feature.deployment.DeploymentPublishScreen
import com.example.myapplication.feature.deployment.DeploymentRecordsScreen
import com.example.myapplication.feature.deployment.DeploymentViewModel
import com.example.myapplication.feature.home.HomeScreen
import com.example.myapplication.feature.login.LoginScreen
import com.example.myapplication.feature.map.MapScreen
import com.example.myapplication.feature.map.MapViewModel
import com.example.myapplication.feature.record.RecordsScreen
import com.example.myapplication.feature.risk.AccidentDetailScreen
import com.example.myapplication.feature.risk.RiskAlertViewModel
import com.example.myapplication.feature.risk.RiskScreen
import com.example.myapplication.feature.settings.AboutScreen
import com.example.myapplication.feature.settings.DeviceInfoScreen
import com.example.myapplication.feature.settings.ServerConfigScreen
import com.example.myapplication.feature.settings.SettingsScreen
import com.example.myapplication.feature.task.TaskExecScreen
import com.example.myapplication.feature.task.TaskPublishScreen
import com.example.myapplication.feature.vehicle.VehicleViewModel
import com.example.myapplication.feature.worktype.WorkTypeSelectScreen
import kotlinx.coroutines.awaitCancellation

/** 底栏项：route = null 表示「作业」动态项，目标由当前 WorkType 决定。 */
private data class TabItem(val route: String?, val label: String, val icon: ImageVector)

private val tabItems = listOf(
    TabItem(null, "作业", Icons.Default.Home),
    TabItem(Routes.RISK, "风险", Icons.Default.Warning),
    TabItem(Routes.RECORDS, "记录", Icons.AutoMirrored.Filled.List),
    TabItem(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

/**
 * 顶层导航。
 *
 * 登录后先进入「作业类型选择」：
 * - 三脚架布设：CONNECT → HOME → TASK_PUBLISH → TASK_EXEC
 * - 锥桶协同布设：CONE_CONNECT → CONE_HOME → DEPLOYMENT_PUBLISH → DEPLOYMENT_EXEC
 *
 * 两条流程的设备连接与任务发布互不依赖；RISK / RECORDS / SETTINGS 为共享页面。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val vm: VehicleViewModel = viewModel(factory = VehicleViewModel.Factory)
    val mapVm: MapViewModel = viewModel(factory = MapViewModel.Factory)
    val deploymentVm: DeploymentViewModel = viewModel(factory = DeploymentViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 作业类型：会话级选择；配置变更/进程重建可恢复，登出/换服务器/回登录页时清空
    var workTypeToken by rememberSaveable { mutableStateOf<String?>(null) }
    val workType = WorkType.fromToken(workTypeToken)
    val workLanding = workType?.landingRoute ?: Routes.WORK_TYPE

    // 新风险轮询（Activity 级，跨页面共享；必须在 NavHost 外创建）
    val riskAlertVm: RiskAlertViewModel = viewModel(
        factory = RiskAlertViewModel.factory {
            mapVm.phoneFix.value?.point ?: RiskAlertViewModel.FALLBACK_CENTER
        },
    )
    val pendingAlert by riskAlertVm.pendingAlert.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    // destination.route 会带查询参数模式（如 task_publish?kind={kind}），统一归一化
    val currentRoute = backStackEntry?.destination?.route?.substringBefore('?')

    // 事故详情传参（简单场景用内存传递，避免序列化）
    var pendingAccident by remember { mutableStateOf<com.example.myapplication.core.model.risk.Accident?>(null) }

    // 返回「作业类型选择」：流程主页都在选择页之上，直接回退即可
    val switchWorkType: () -> Unit = {
        if (!navController.popBackStack(Routes.WORK_TYPE, inclusive = false)) {
            navController.navigate(Routes.WORK_TYPE) { launchSingleTop = true }
        }
    }

    // 登录成功入口：两个业务的会话状态都复位，再进入类型选择
    val onSessionReady: () -> Unit = {
        vm.onSessionStarted()
        deploymentVm.onSessionStarted()
        workTypeToken = null
        navController.navigate(Routes.WORK_TYPE) {
            popUpTo(Routes.LOGIN) { inclusive = true }
        }
    }

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

    // Android 17（API 37）本地网络权限：登录/状态/命令/视频都要访问局域网，
    // 在首次进入 SERVER 模式时请求一次；拒绝后视频卡片会给出可读提示。
    val appContext = LocalContext.current
    val localNetworkPermission = rememberLocalNetworkPermissionState()
    var localNetworkPrompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val transport = ServiceLocator.get(appContext).transport
        if (!localNetworkPrompted && transport == DeviceTransport.SERVER && !localNetworkPermission.granted) {
            localNetworkPrompted = true
            localNetworkPermission.request()
        }
    }

    Scaffold(
        bottomBar = {
            if (Routes.showsBottomBar(currentRoute)) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    workLanding = workLanding,
                    workType = workType,
                    onSelect = { route ->
                        if (route == null) {
                            navController.navigateToWorkLanding(workLanding)
                        } else {
                            navController.navigateToTab(route, workLanding)
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LOGIN,
            modifier = Modifier.padding(innerPadding),
        ) {
            // ⓪ 登录页
            composable(Routes.LOGIN) {
                LaunchedEffect(Unit) { workTypeToken = null }
                LoginScreen(onLoggedIn = onSessionReady)
            }

            // ① 作业类型选择：三角警示牌 / 锥桶协同
            composable(Routes.WORK_TYPE) {
                WorkTypeSelectScreen(
                    current = workType,
                    onSelect = { type ->
                        workTypeToken = type.token
                        navController.navigate(type.startRoute) {
                            launchSingleTop = true
                        }
                    },
                )
            }

            // ── 三角警示牌流程 ──
            composable(Routes.CONNECT) {
                ConnectScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onEnterWork = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.CONNECT) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    onOpenPublishFixed = {
                        navController.navigate("${Routes.TASK_PUBLISH}?${Routes.ARG_KIND}=${Routes.KIND_FIXED}")
                    },
                    onBackToConnect = { navController.navigate(Routes.CONNECT) },
                    onOpenExec = { navController.navigate(Routes.TASK_EXEC) },
                    onSwitchWorkType = switchWorkType,
                )
            }

            // ── 锥桶协同布设流程 ──
            composable(Routes.CONE_CONNECT) {
                ConeConnectScreen(
                    vm = deploymentVm,
                    onBack = { navController.popBackStack() },
                    onDevicesReady = {
                        navController.navigate(Routes.CONE_HOME) {
                            popUpTo(Routes.CONE_CONNECT) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenActiveDeployment = { id ->
                        deploymentVm.openEvent(id)
                        navController.navigate(Routes.DEPLOYMENT_EXEC)
                    },
                )
            }

            composable(Routes.CONE_HOME) {
                ConeHomeScreen(
                    vm = deploymentVm,
                    onOpenDeviceConnect = { navController.navigate(Routes.CONE_CONNECT) },
                    onOpenCreate = { navController.navigate(Routes.DEPLOYMENT_PUBLISH) },
                    onOpenExec = { id ->
                        deploymentVm.openEvent(id)
                        navController.navigate(Routes.DEPLOYMENT_EXEC)
                    },
                    onSwitchWorkType = switchWorkType,
                )
            }

            composable(Routes.DEPLOYMENT_PUBLISH) {
                DeploymentPublishScreen(
                    vm = deploymentVm,
                    mapVm = mapVm,
                    onBack = { navController.popBackStack() },
                    onEditDevices = {
                        // 回到设备连接页时移除发布页，避免「完成设备连接」后重复叠页
                        navController.navigate(Routes.CONE_CONNECT) {
                            popUpTo(Routes.DEPLOYMENT_PUBLISH) { inclusive = true }
                        }
                    },
                    onStarted = {
                        navController.navigate(Routes.DEPLOYMENT_EXEC) {
                            popUpTo(Routes.DEPLOYMENT_PUBLISH) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.DEPLOYMENT_EXEC) {
                DeploymentExecScreen(
                    vm = deploymentVm,
                    mapVm = mapVm,
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

            // ── 共享页面 ──
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
                    deploymentVm = deploymentVm,
                    onOpenDeploymentExec = { id ->
                        deploymentVm.openEvent(id)
                        navController.navigate(Routes.DEPLOYMENT_EXEC)
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    onOpenDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenServerConfig = { navController.navigate(Routes.SERVER_CONFIG) },
                    onBackToLogin = {
                        workTypeToken = null
                        vm.onLoggedOut()
                        deploymentVm.clearSession()
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.SERVER_CONFIG) {
                ServerConfigScreen(
                    vm = vm,
                    deploymentVm = deploymentVm,
                    onBack = { navController.popBackStack() },
                    // 地址已切换 → 旧会话作废，回登录页重新登录（同「切换账号」的写法）
                    onSaved = {
                        workTypeToken = null
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
    workLanding: String,
    workType: WorkType?,
    onSelect: (String?) -> Unit,
) {
    NavigationBar {
        tabItems.forEach { tab ->
            val selected = if (tab.route == null) {
                currentRoute == workLanding ||
                    currentRoute == Routes.WORK_TYPE ||
                    workType?.owns(currentRoute) == true
            } else {
                currentRoute == tab.route
            }
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab.route) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}

/** 「作业」Tab：回到当前作业类型的主页；未选择类型时回到选择页。 */
private fun NavHostController.navigateToWorkLanding(landing: String) {
    navigate(landing) {
        launchSingleTop = true
        popUpTo(Routes.WORK_TYPE) { inclusive = false }
    }
}

/** 其他共享 Tab：保留各 Tab 的返回栈状态。 */
private fun NavHostController.navigateToTab(route: String, workLanding: String) {
    navigate(route) {
        launchSingleTop = true
        popUpTo(workLanding) { saveState = true }
        restoreState = true
    }
}
