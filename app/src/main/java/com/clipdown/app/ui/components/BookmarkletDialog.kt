package com.clipdown.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.parser.model.Platform

object BookmarkletUtil {

    /**
     * 生成适配移动端浏览器的书签脚本 (Bookmarklet)。
     * 在浏览器地址栏或书签运行该脚本时，自动读取 document.cookie 并通过 DeepLink
     * 回传至 ClipDown (clipdown://cookie?platform=...&data=...)。
     */
    fun generate(platform: Platform): String {
        val platformId = platform.id
        val checkToken = when (platform) {
            Platform.INSTAGRAM -> "sessionid"
            Platform.XIAOHONGSHU -> "web_session"
            Platform.FACEBOOK -> "c_user"
            else -> ""
        }
        val guardCondition = if (checkToken.isNotBlank()) {
            "if(!c||c.indexOf('$checkToken')===-1){alert('【提示】当前页面未检测到有效登录态($checkToken)，请先登录账号后再点击书签！');return;}"
        } else {
            "if(!c){alert('【提示】当前页面未检测到 Cookie，请先登录账号！');return;}"
        }

        // 双重保障：
        // 1. 尝试直接写入剪贴板（避免某些浏览器对自定义 scheme 跳转拦截）；
        // 2. 触发 location.href='clipdown://cookie?...' 瞬时唤起应用自动保存。
        return "javascript:(function(){var c=document.cookie;$guardCondition try{navigator.clipboard.writeText(c);}catch(e){}location.href='clipdown://cookie?platform=$platformId&data='+encodeURIComponent(c);})();"
    }

    fun getPlatformWebUrl(platform: Platform): String = when (platform) {
        Platform.INSTAGRAM -> "https://www.instagram.com/"
        Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/"
        Platform.FACEBOOK -> "https://m.facebook.com/"
        else -> "https://www.instagram.com/"
    }
}

/**
 * 手机浏览器「一键书签脚本」免电脑导入指引弹窗。
 * 彻底解决 Android WebView 被 Meta 识别并陷入 2FA 验证死循环的硬伤。
 */
@Composable
fun BookmarkletGuideDialog(
    platform: Platform,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scriptCode = remember(platform) { BookmarkletUtil.generate(platform) }
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "手机浏览器一键导入 Cookie",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "推荐 · 100% 避开内置浏览器的风控与验证死循环",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "由于 Meta 对应用内嵌 WebView 实施了严格的指纹风控，使用手机自带浏览器（Chrome / 夸克 / Edge）登录是最稳妥的方式。\n\n" +
                        "通过本功能，只需在浏览器中点一下「书签」，即可 1 秒将登录态回传至 ClipDown，免找电脑 F12，免手动选词复制！",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(AppTheme.spacing.md))

                // 极简三步法卡片
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RectangleShape,
                    modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                ) {
                    Column(modifier = Modifier.padding(AppTheme.spacing.md)) {
                        Text(
                            text = "极简操作 3 步走：",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(AppTheme.spacing.xs))
                        StepRow(
                            step = "1",
                            desc = "点击下方按钮复制「书签导入脚本代码」"
                        )
                        Spacer(Modifier.height(AppTheme.spacing.xs))
                        StepRow(
                            step = "2",
                            desc = "在手机自带浏览器中登录 ${platform.displayName}（支持点击下方按钮直接唤起）"
                        )
                        Spacer(Modifier.height(AppTheme.spacing.xs))
                        StepRow(
                            step = "3",
                            desc = "在浏览器中将该网页添加为书签，网址替换为刚复制的代码（名称设为【导入ClipDown】）。登录后在地址栏点击该书签，自动跳回本应用完成保存！"
                        )
                    }
                }

                Spacer(Modifier.height(AppTheme.spacing.md))

                // 复制按钮
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(scriptCode))
                        copied = true
                        Toast.makeText(context, "已复制书签脚本代码！请在浏览器书签中粘贴使用", Toast.LENGTH_SHORT).show()
                    },
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (copied) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) "✓ 代码已复制到剪贴板" else "复制书签导入脚本代码")
                }

                Spacer(Modifier.height(AppTheme.spacing.xs))

                // 唤起浏览器按钮
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BookmarkletUtil.getPlatformWebUrl(platform))).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }.onFailure {
                            Toast.makeText(context, "无法打开系统浏览器", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RectangleShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("在手机浏览器中打开 ${platform.displayName}")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = RectangleShape
            ) {
                Text("我知道了")
            }
        }
    )
}

@Composable
private fun StepRow(step: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(MaterialTheme.colorScheme.primary, RectangleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 16.sp
        )
    }
}
