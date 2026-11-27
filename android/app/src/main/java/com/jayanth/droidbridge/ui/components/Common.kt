package com.jayanth.droidbridge.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppBackdrop(content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            colors.background,
                            colors.surfaceContainerLowest,
                        ),
                        startY = 0f,
                        endY = size.height,
                    ),
                )

                val topGlow = Offset(size.width * 0.92f, size.height * 0.02f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.primary.copy(alpha = 0.26f), Color.Transparent),
                        center = topGlow,
                        radius = size.width * 0.9f,
                    ),
                    radius = size.width * 0.9f,
                    center = topGlow,
                )

                val bottomGlow = Offset(size.width * 0.02f, size.height * 0.92f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colors.tertiary.copy(alpha = 0.16f), Color.Transparent),
                        center = bottomGlow,
                        radius = size.width * 0.8f,
                    ),
                    radius = size.width * 0.8f,
                    center = bottomGlow,
                )
            }
            content()
        }
    }
}

@Composable
fun BrandMark(
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(
                Brush.linearGradient(
                    listOf(colors.primary, colors.tertiary),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Devices,
            contentDescription = null,
            modifier = Modifier.size(size * 0.52f),
            tint = colors.onPrimary,
        )
    }
}

@Composable
fun Wordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 42.dp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandMark(size = markSize)
        Column {
            Text(
                text = "DroidBridge",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Local screen bridge",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            } else {
                Box(Modifier.size(6.dp).background(color, CircleShape))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(size * 0.46f),
            tint = tint,
        )
    }
}

@Composable
fun AppFooter(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DroidBridge v2.0.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.secondary, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Local only",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
fun DeviceBridgeGraphic(
    connected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "bridge")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "bridgePulse",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceTile(
            icon = Icons.Rounded.LaptopMac,
            label = "Mac",
            size = 88.dp,
            active = connected,
        )
        Canvas(
            modifier = Modifier
                .padding(horizontal = 10.dp)
                .width(64.dp)
                .height(28.dp),
        ) {
            val dotRadius = 2.4.dp.toPx()
            val centerY = size.height / 2f
            val count = 5
            repeat(count) { index ->
                val distance = kotlin.math.abs(phase - index / (count - 1f))
                val alpha = (1f - distance * 1.6f).coerceIn(0.25f, 1f)
                drawCircle(
                    color = if (connected) {
                        colors.secondary.copy(alpha = if (connected) alpha else 0.45f)
                    } else {
                        colors.outline.copy(alpha = 0.55f)
                    },
                    radius = dotRadius,
                    center = Offset(
                        x = size.width * (index + 0.5f) / count,
                        y = centerY,
                    ),
                )
            }
            drawCircle(
                color = if (connected) colors.secondary else colors.outline.copy(alpha = 0.6f),
                radius = 4.2.dp.toPx(),
                center = Offset(size.width, centerY),
            )
        }
        DeviceTile(
            icon = Icons.Rounded.PhoneAndroid,
            label = "Phone",
            size = 66.dp,
            active = connected,
        )
    }
}

@Composable
private fun DeviceTile(
    icon: ImageVector,
    label: String,
    size: Dp,
    active: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.28f))
                .background(colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(size * 0.44f),
                tint = if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
fun PairingCode(
    code: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        code.forEach { digit ->
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = digit.toString(),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 30.sp,
                    ),
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun StepNumber(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}
