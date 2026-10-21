package com.example.myapplication.core.ui.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
 * Android 17（API 37）本地网络权限状态（模式同 [rememberCameraPermissionState]）。
 *
 * Android 17 起，targetSdk 37 的 App 访问局域网 TCP/UDP（包括 HTTP、UDP 视频流）
 * 需要运行时权限 `android.permission.ACCESS_LOCAL_NETWORK`（NEARBY_DEVICES 组）。
 * - API < 37：系统没有该权限限制，恒为 granted；
 * - API 37+：未授权时请求；拒绝后可通过 [openAppSettings] 去系统设置。
 *
 * 这里用字符串常量而不是 Manifest.permission.ACCESS_LOCAL_NETWORK，避免不同
 * compileSdk 预览版缺少该字段导致编译失败。
 */
class LocalNetworkPermissionState internal constructor(
    private val context: Context,
) {
    var granted by mutableStateOf(check(context))
        internal set

    var launchRequest: ((String) -> Unit)? = null

    /** 一次性授权结果回调（授权后可继续原操作） */
    var onRequestResult: ((Boolean) -> Unit)? = null

    fun request() {
        if (Build.VERSION.SDK_INT >= API_37) {
            launchRequest?.invoke(PERMISSION)
        }
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
        Build.VERSION.SDK_INT < API_37 ||
            ContextCompat.checkSelfPermission(ctx, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val API_37 = 37
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}

@Composable
fun rememberLocalNetworkPermissionState(): LocalNetworkPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { LocalNetworkPermissionState(context) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        state.refresh()
        val callback = state.onRequestResult
        state.onRequestResult = null
        callback?.invoke(state.granted)
    }

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
