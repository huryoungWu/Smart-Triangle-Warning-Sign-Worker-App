package com.example.myapplication.core.ui.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 定位权限状态容器（**不引入 Accompanist**，用系统 ActivityResult API）。
 *
 * 约定：
 * - **申请时机**：进入地图页或设置页点按钮才请求，不启动即弹
 * - **拒绝降级**：地图瓦片照出，仅无定位点；车端点位不受影响
 * - 从系统设置返回时经 ON_RESUME 自动 [refresh]
 */
class LocationPermissionState internal constructor(
    private val context: Context,
) {
    var granted by mutableStateOf(check(context))
        internal set

    /** 是否为"仅大致位置"（Android 12+ 用户可选）—— 已授权但精度受限 */
    val coarseOnly: Boolean
        get() = granted && ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) != PackageManager.PERMISSION_GRANTED

    var launchRequest: ((String) -> Unit)? = null

    fun request() {
        launchRequest?.invoke(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun refresh() {
        granted = check(context)
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun check(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
fun rememberLocationPermissionState(): LocationPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { LocationPermissionState(context) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { state.refresh() }

    DisposableEffect(lifecycleOwner) {
        state.launchRequest = { perm -> launcher.launch(perm) }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            state.launchRequest = null
        }
    }

    return state
}
