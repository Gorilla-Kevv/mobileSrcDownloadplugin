package com.clipdown.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.app.ui.theme.BrandContainer
import com.clipdown.app.ui.theme.DangerContainer
import com.clipdown.app.ui.theme.DangerFg
import com.clipdown.app.ui.theme.InfoContainer
import com.clipdown.app.ui.theme.InfoFg
import com.clipdown.app.ui.theme.NeutralSurfaceAlt
import com.clipdown.app.ui.theme.OnBrandContainer
import com.clipdown.app.ui.theme.SuccessContainer
import com.clipdown.app.ui.theme.SuccessFg
import com.clipdown.app.ui.theme.TextSecondary
import com.clipdown.app.ui.theme.TextTertiary
import com.clipdown.app.ui.theme.WarningContainer
import com.clipdown.app.ui.theme.WarningFg

// ─────────────────────────────────────────────────────────────────────────────
// 语义色调：所有 pill / 提示块统一走这里，保证同一语义永远同一配色
// ─────────────────────────────────────────────────────────────────────────────
enum class Tone { Neutral, Brand, Success, Warning, Danger, Info }

private data class ToneColors(val fg: Color, val bg: Color)

@Composable
private fun toneOf(tone: Tone): ToneColors = when (tone) {
    Tone.Neutral -> ToneColors(TextSecondary, NeutralSurfaceAlt)
    Tone.Brand -> ToneColors(OnBrandContainer, BrandContainer)
    Tone.Success -> ToneColors(SuccessFg, SuccessContainer)
    Tone.Warning -> ToneColors(WarningFg, WarningContainer)
    Tone.Danger -> ToneColors(DangerFg, DangerContainer)
    Tone.Info -> ToneColors(InfoFg, InfoContainer)
}

// ─────────────────────────────────────────────────────────────────────────────
// 页面骨架
// ─────────────────────────────────────────────────────────────────────────────

/** 页面标题区：主标题 + 说明，统一字号与间距（各页只写内容，不各自定样式） */
@Composable
fun PageHeader(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(AppTheme.spacing.xs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 统一卡片：白底 + 1dp 描边 + 18dp 圆角 + 16dp 内边距（不用阴影，避免脏边） */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = AppTheme.spacing.lg,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppTheme.radius.card),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

/** 卡片头：标题 + 可选说明 + 可选右侧操作，保证所有卡片头部对齐一致 */
@Composable
fun CardHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

/** 卡片内分隔线（比 Divider 更细、更克制） */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 标签 / 状态
// ─────────────────────────────────────────────────────────────────────────────

/** 状态胶囊：下载状态、权限状态、平台登录态统一用它 */
@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val c = toneOf(tone)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppTheme.radius.pill))
            .background(c.bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = c.fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** 可点选胶囊（媒体形态选择、筛选等） */
@Composable
fun AppChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(AppTheme.radius.pill))
            .background(bg)
            .border(
                1.dp,
                if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(AppTheme.radius.pill)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** 键值行：左标签右值，两列对齐（信息型卡片的统一排版） */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueTone: Tone = Tone.Neutral
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp)
        )
        Spacer(Modifier.width(AppTheme.spacing.md))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (valueTone == Tone.Neutral) MaterialTheme.colorScheme.onSurface else toneOf(valueTone).fg,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 提示条：错误 / 警告 / 成功信息，统一圆角与内边距 */
@Composable
fun NoticeBar(text: String, tone: Tone = Tone.Danger, modifier: Modifier = Modifier) {
    val c = toneOf(tone)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AppTheme.radius.control))
            .background(c.bg)
            .padding(horizontal = AppTheme.spacing.md, vertical = 10.dp)
    ) {
        Text(text, color = c.fg, style = MaterialTheme.typography.bodySmall)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 控件
// ─────────────────────────────────────────────────────────────────────────────

/** 主行动按钮（实心品牌色） */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(AppTheme.radius.control),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 次级按钮（描边） */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(AppTheme.radius.control),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 设置项开关行：标题 + 说明 + 右侧开关，行高与间距全页统一 */
@Composable
fun SettingSwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(AppTheme.spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

/** 空态：图标 + 标题 + 说明，居中 */
@Composable
fun EmptyState(
    title: String,
    desc: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(AppTheme.radius.card))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(AppTheme.spacing.lg))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (!desc.isNullOrBlank()) {
            Spacer(Modifier.height(AppTheme.spacing.xs))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** 小标题（区块内分组用，如"必要权限""支持的平台"） */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(bottom = AppTheme.spacing.sm),
        style = MaterialTheme.typography.labelMedium,
        color = TextTertiary
    )
}

/** 竖向间距快捷方式，避免各页各写 Spacer(Modifier.height(x.dp)) */
@Composable
fun VSpace(space: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(space))

/** 横向间距 */
@Composable
fun HSpace(space: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(space))

/** 统一的列表项纵向节奏 */
@Composable
fun RowGap() = Spacer(Modifier.height(AppTheme.spacing.sm))

/** 水平等距容器 */
@Composable
fun HGroup(
    space: androidx.compose.ui.unit.Dp = AppTheme.spacing.sm,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(space), verticalAlignment = Alignment.CenterVertically, content = content)
}
