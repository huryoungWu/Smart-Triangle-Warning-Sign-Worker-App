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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.example.myapplication.feature.settings.SettingsScreen
import com.example.myapplication.feature.task.TaskExecScreen
import com.example.myapplication.feature.task.TaskPublishScreen
import com.example.myapplication.feature.vehicle.VehicleViewModel

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
    val state by vm.uiState.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 事故详情传参（简单场景用内存传递，避免序列化）
    var pendingAccident by remember { mutableStateOf<com.example.myapplication.core.model.risk.Accident?>(null) }

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
            // ⓪ 登录页：演示状态，可跳过
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoggedIn = {
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    },
                    onSkip = {
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
                )
            }

            // ② 开始作业页：单一入口「开始新任务」（定距布置 · 全自动驾驶）
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    onOpenPublishFixed = {
                        navController.navigate("${Routes.TASK_PUBLISH}?${Routes.ARG_KIND}=${Routes.KIND_FIXED}")
                    },
                    onBackToConnect = {
                        navController.navigate(Routes.CONNECT) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    },
                    onOpenExec = { navController.navigate(Routes.TASK_EXEC) },
                )
            }

            // 风险提示（新增 Tab）：半径可设 + 高德地图 + 距离排序事故列表
            composable(Routes.RISK) {
                RiskScreen(
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

            composable(Routes.RECORDS) { RecordsScreen(vm = vm) }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    onOpenDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) },
                    onOpenDebug = { navController.navigate(Routes.DEBUG) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onBackToLogin = {
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
