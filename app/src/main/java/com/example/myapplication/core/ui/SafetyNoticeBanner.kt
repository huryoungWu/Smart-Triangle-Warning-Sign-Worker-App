package com.example.myapplication.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.R
import com.example.myapplication.ui.theme.NeutralGrey
import com.example.myapplication.ui.theme.NeutralGreyBg
import com.example.myapplication.ui.theme.WarnOrange
import com.example.myapplication.ui.theme.WarnOrangeBg
import com.example.myapplication.ui.theme.WarningRed
import com.example.myapplication.ui.theme.WarningRedBg

enum class BannerVariant { CRITICAL, INFO }

/**
 * 通用提示条。CRITICAL 用于失联/停止未确认等红色警示；INFO 用于"仅本机保存"等中性提示。
 */
@Composable
fun SafetyNoticeBanner(
    modifier: Modifier = Modifier,
    variant: BannerVariant = BannerVariant.CRITICAL,
    text: String = stringResource(R.string.safety_notice_scope),
) {
    val (bg, fg) = when (variant) {
        BannerVariant.CRITICAL -> WarningRedBg to WarningRed
        BannerVariant.INFO -> NeutralGreyBg to NeutralGrey
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (variant == BannerVariant.CRITICAL) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(
                text = text,
                color = if (variant == BannerVariant.CRITICAL) fg else WarnOrange,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 作业范围安全提示（第 1 节：样机能力边界） */
@Composable
fun ScopeSafetyBanner(modifier: Modifier = Modifier) {
    SafetyNoticeBanner(
        modifier = modifier,
        variant = BannerVariant.INFO,
        text = stringResource(R.string.safety_notice_scope),
    )
}
