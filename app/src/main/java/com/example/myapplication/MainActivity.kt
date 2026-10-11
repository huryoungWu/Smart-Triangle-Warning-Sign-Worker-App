package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.example.myapplication.navigation.AppNavHost
import com.example.myapplication.ui.theme.MyApplicationTheme

/**
 * 三角警示牌作业人员 APP 入口。
 * 首版不依赖账号/云端（M01），无登录门控，直接进入作业首页。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    AppNavHost()
}
