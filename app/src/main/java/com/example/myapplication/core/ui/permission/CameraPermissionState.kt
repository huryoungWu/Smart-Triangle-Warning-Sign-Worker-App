package com.example.myapplication.core.ui.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 * 相机权限状态容器（扫码添加设备用，模式同 [rememberLocationPermissionState]，不引 Accompanist）。
 *
 * 约定：
 * - **申请时机**：点「扫码」才请求，不启动即弹
 * - **拒绝降级**：仍可「输入设备编号」添加设备，扫码入口提示先授权
 * - **申请结果回调**：[onRequestResult] 为一次性回调（授权后自动继续原操作，如直接打开扫码相机），触发后自动清空
 * - 从系统设置返回时经 ON_RESUME 自动 [refresh]
 */
class CameraPermissionState internal constructor(
    private val context: Context,
) {
    var granted by mutableStateOf(check(context))
        internal set

    var launchRequest: ((String) -> Unit)? = null

    /** 一次性的授权结果回调：`request()` 弹窗结束后回调一次并清空（true=已授权） */
    var onRequestResult: ((Boolean) -> Unit)? = null

    fun request() {
        launchRequest?.invoke(Manifest.permission.CAMERA)
    }

    fun refresh() {
        granted = check(context)
    }

    private fun check(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}

@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { CameraPermissionState(context) }

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
