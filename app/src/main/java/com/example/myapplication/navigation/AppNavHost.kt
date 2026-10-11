package com.example.myapplication.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.myapplication.core.ui.ConfirmDialog
import com.example.myapplication.core.ui.EstopBar
import com.example.myapplication.feature.debug.DebugScreen
import com.example.myapplication.feature.deploy.DeployScreen
import com.example.myapplication.feature.home.HomeScreen
import com.example.myapplication.feature.manual.ManualScreen
import com.example.myapplication.feature.map.MapScreen
import com.example.myapplication.feature.map.MapViewModel
import com.example.myapplication.feature.onetouch.OneTouchScreen
import com.example.myapplication.feature.precheck.PrecheckScreen
import com.example.myapplication.feature.record.RecordsScreen
import com.example.myapplication.feature.settings.DeviceInfoScreen
import com.example.myapplication.feature.settings.SettingsScreen
import com.example.myapplication.feature.vehicle.VehicleViewModel

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

private val tabItems = listOf(
    TabItem(Routes.HOME, "作业", Icons.Default.Home),
    TabItem(Routes.RECORDS, "记录", Icons.Default.List),
    TabItem(Routes.SETTINGS, "设置", Icons.Default.Settings),
)

/**
 * 顶层导航：作业首页 / 作业记录 / 设置（Tab）+ 手动遥控 / 预检 / 布置 / 调试 / 设备信息（压栈）。
 *
 * `VehicleViewModel` 在此创建（Activity 作用域）并下传各页，全应用共享同一状态机。
 * 急停条（M08）在首页与各运动页底部常驻。
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val vm: VehicleViewModel = viewModel(factory = VehicleViewModel.Factory)
    val mapVm: MapViewModel = viewModel(factory = MapViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            Column {
                if (currentRoute in Routes.estopPages) {
                    EstopBar(
                        locked = state.snapshot?.estopLocked == true || state.snapshot?.mode == com.example.myapplication.core.model.vehicle.VehicleMode.SAFE_STOP,
                        stopUnconfirmed = state.stopUnconfirmed,
                        onEstop = vm::estop,
                        onResume = vm::requestResume,
                    )
                }
                if (currentRoute in Routes.tabs) {
                    AppBottomBar(
                        currentRoute = currentRoute,
                        onSelect = { route -> navController.navigateToTab(route) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    mapVm = mapVm,
                    onOpenManual = { navController.navigate(Routes.MANUAL) },
                    onOpenPrecheckDeploy = { navController.navigate("${Routes.PRECHECK}?${Routes.ARG_KIND}=${Routes.KIND_DEPLOY}") },
                    onOpenPrecheckOneTouch = { navController.navigate("${Routes.PRECHECK}?${Routes.ARG_KIND}=${Routes.KIND_ONETOUCH}") },
                    onOpenDebug = { navController.navigate(Routes.DEBUG) },
                    onOpenDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) },
                    onOpenMap = { navController.navigate(Routes.MAP) },
                )
            }

            composable(Routes.RECORDS) { RecordsScreen(vm = vm) }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    onOpenDeviceInfo = { navController.navigate(Routes.DEVICE_INFO) },
                    onOpenDebug = { navController.navigate(Routes.DEBUG) },
                )
            }

            composable(Routes.MANUAL) {
                ManualScreen(vm = vm, mapVm = mapVm, onBack = { navController.popBackStack() })
            }

            composable(
                route = "${Routes.PRECHECK}?${Routes.ARG_KIND}={${Routes.ARG_KIND}}",
                arguments = listOf(navArgument(Routes.ARG_KIND) {
                    type = NavType.StringType
                    defaultValue = Routes.KIND_DEPLOY
                }),
            ) { entry ->
                val kind = entry.arguments?.getString(Routes.ARG_KIND) ?: Routes.KIND_DEPLOY
                PrecheckScreen(
                    vm = vm,
                    kind = kind,
                    onBack = { navController.popBackStack() },
                    onPassed = {
                        val target = if (kind == Routes.KIND_ONETOUCH) Routes.ONETOUCH else Routes.DEPLOY
                        navController.navigate(target) {
                            popUpTo(Routes.PRECHECK) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.DEPLOY) {
                DeployScreen(vm = vm, mapVm = mapVm, onBack = { navController.popBackStack() })
            }

            composable(Routes.ONETOUCH) {
                OneTouchScreen(vm = vm, mapVm = mapVm, onBack = { navController.popBackStack() })
            }

            composable(Routes.DEBUG) {
                DebugScreen(vm = vm, onBack = { navController.popBackStack() })
            }

            composable(Routes.DEVICE_INFO) {
                DeviceInfoScreen(vm = vm, onBack = { navController.popBackStack() })
            }

            composable(Routes.MAP) {
                MapScreen(
                    vm = vm,
                    mapVm = mapVm,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

    // 全局一次性弹窗（拒绝原因/告警/结果未知，规格第 4 节）
    state.dialog?.let { dialog ->
        AppDialogRenderer(dialog = dialog, onDismiss = vm::dismissDialog)
    }

    // 恢复待命前的人工现场核查确认（M08）
    if (state.pendingResumeConfirm) {
        ConfirmDialog(
            title = "恢复前请现场核查",
            message = "恢复至待命前，请确认：\n\n" +
                "1. 设备与车辆周围无人员、无障碍\n" +
                "2. 现场风险已排除或有人看护\n" +
                "3. 确需继续作业时请重新发起任务\n\n" +
                "恢复后设备回到待命，不会自动续跑之前的任务。",
            confirmText = "确认恢复",
            onConfirm = vm::confirmResume,
            onDismiss = vm::cancelResumeConfirm,
        )
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
