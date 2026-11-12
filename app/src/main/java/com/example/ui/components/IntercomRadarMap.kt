package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.IntercomPeer
import com.example.model.UserLocation
import com.example.model.formatBearing
import com.example.model.formatDistance
import com.example.ui.theme.Amber500
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Indigo400
import com.example.ui.theme.Rose500
import com.example.ui.theme.Teal400
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun IntercomRadarMap(
    myLocation: UserLocation?,
    peers: List<IntercomPeer>,
    radarRangeMeters: Float,
    selectedPeer: IntercomPeer?,
    onRangeSelected: (Float) -> Unit,
    onPeerSelected: (IntercomPeer?) -> Unit,
    modifier: Modifier = Modifier
) {
    val nonSelfPeers = remember(peers) { peers.filter { !it.isSelf } }
    val textMeasurer = rememberTextMeasurer()

    // Smooth sweeping radar beam animation
    val infiniteTransition = rememberInfiniteTransition(label = "radar_sweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep_angle"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Radar Viewport & HUD Overlay
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                val onSurfaceColor = MaterialTheme.colorScheme.onSurface

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(nonSelfPeers, radarRangeMeters) {
                            detectTapGestures { tapOffset ->
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val maxRadius = min(size.width, size.height) / 2f - 24.dp.toPx()

                                // Check if user tapped near any peer blip
                                val tapped = nonSelfPeers.firstOrNull { peer ->
                                    val dist = peer.distanceMeters ?: return@firstOrNull false
                                    val bearing = peer.bearingDegrees ?: 0f
                                    val rad = Math.toRadians((bearing - 90.0))
                                    val fraction = (dist / radarRangeMeters).coerceIn(0f, 1f)
                                    val px = center.x + (fraction * maxRadius * cos(rad)).toFloat()
                                    val py = center.y + (fraction * maxRadius * sin(rad)).toFloat()

                                    val dx = tapOffset.x - px
                                    val dy = tapOffset.y - py
                                    (dx * dx + dy * dy) <= (28.dp.toPx() * 28.dp.toPx())
                                }
                                onPeerSelected(if (tapped == selectedPeer) null else tapped)
                            }
                        }
                ) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = min(size.width, size.height) / 2f - 24.dp.toPx()

                    // Background Tactical Radar Rings
                    val ringFractions = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f)
                    ringFractions.forEach { frac ->
                        val r = maxRadius * frac
                        drawCircle(
                            color = outlineColor,
                            radius = r,
                            center = center,
                            style = Stroke(
                                width = 1.2.dp.toPx(),
                                pathEffect = if (frac == 1.0f) null else PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )
                        )

                        // Distance label along the north axis
                        val rangeAtRing = (radarRangeMeters * frac).toInt()
                        val label = if (rangeAtRing >= 1000) "${rangeAtRing / 1000}km" else "${rangeAtRing}m"
                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            topLeft = Offset(center.x + 4.dp.toPx(), center.y - r + 2.dp.toPx()),
                            style = TextStyle(color = outlineColor.copy(alpha = 0.8f), fontSize = 9.sp)
                        )
                    }

                    // Crosshair Axes (N-S and E-W)
                    drawLine(
                        color = outlineColor,
                        start = Offset(center.x, center.y - maxRadius),
                        end = Offset(center.x, center.y + maxRadius),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = outlineColor,
                        start = Offset(center.x - maxRadius, center.y),
                        end = Offset(center.x + maxRadius, center.y),
                        strokeWidth = 1.dp.toPx()
                    )

                    // Diagonal 45-degree guide markers
                    val diag = maxRadius * 0.7071f
                    drawLine(
                        color = outlineColor.copy(alpha = 0.15f),
                        start = Offset(center.x - diag, center.y - diag),
                        end = Offset(center.x + diag, center.y + diag),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = outlineColor.copy(alpha = 0.15f),
                        start = Offset(center.x - diag, center.y + diag),
                        end = Offset(center.x + diag, center.y - diag),
                        strokeWidth = 1.dp.toPx()
                    )

                    // Cardinal Direction Labels
                    val cardinals = listOf(
                        Pair("N", Offset(center.x - 6.dp.toPx(), center.y - maxRadius - 18.dp.toPx())),
                        Pair("S", Offset(center.x - 5.dp.toPx(), center.y + maxRadius + 4.dp.toPx())),
                        Pair("E", Offset(center.x + maxRadius + 6.dp.toPx(), center.y - 8.dp.toPx())),
                        Pair("W", Offset(center.x - maxRadius - 20.dp.toPx(), center.y - 8.dp.toPx()))
                    )
                    cardinals.forEach { (text, pos) ->
                        drawText(
                            textMeasurer = textMeasurer,
                            text = text,
                            topLeft = pos,
                            style = TextStyle(
                                color = if (text == "N") primaryColor else onSurfaceColor.copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                    }

                    // Sweeping Radar Line
                    val sweepRad = Math.toRadians((sweepAngle - 90.0))
                    val sweepEnd = Offset(
                        center.x + (maxRadius * cos(sweepRad)).toFloat(),
                        center.y + (maxRadius * sin(sweepRad)).toFloat()
                    )
                    drawLine(
                        brush = Brush.radialGradient(
                            colors = listOf(primaryColor.copy(alpha = 0.45f), Color.Transparent),
                            center = center,
                            radius = maxRadius
                        ),
                        start = center,
                        end = sweepEnd,
                        strokeWidth = 2.dp.toPx()
                    )

                    // Draw Connected Peer Blips
                    nonSelfPeers.forEach { peer ->
                        val dist = peer.distanceMeters ?: return@forEach
                        val bearing = peer.bearingDegrees ?: 0f
                        val rad = Math.toRadians((bearing - 90.0))
                        val fraction = (dist / radarRangeMeters).coerceIn(0.08f, 1f)
                        val px = center.x + (fraction * maxRadius * cos(rad)).toFloat()
                        val py = center.y + (fraction * maxRadius * sin(rad)).toFloat()

                        val isTalking = peer.isTransmitting
                        val isSelected = peer.id == selectedPeer?.id

                        // Outer ring if talking or selected
                        if (isTalking || isSelected) {
                            drawCircle(
                                color = if (isTalking) Emerald400.copy(alpha = 0.35f) else primaryColor.copy(alpha = 0.35f),
                                radius = 16.dp.toPx(),
                                center = Offset(px, py)
                            )
                        }

                        // Connecting tether line to user
                        drawLine(
                            color = if (isTalking) Emerald400.copy(alpha = 0.5f) else primaryColor.copy(alpha = 0.2f),
                            start = center,
                            end = Offset(px, py),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )

                        // Peer Dot
                        val dotColor = if (isTalking) Emerald400 else if (peer.isHost) Indigo400 else primaryColor
                        drawCircle(
                            color = dotColor,
                            radius = if (isSelected) 8.dp.toPx() else 6.dp.toPx(),
                            center = Offset(px, py)
                        )

                        // Peer Callsign & Distance Tag
                        val peerTag = "${peer.callsign} (${formatDistance(dist)})"
                        drawText(
                            textMeasurer = textMeasurer,
                            text = peerTag,
                            topLeft = Offset(px + 8.dp.toPx(), py - 8.dp.toPx()),
                            style = TextStyle(
                                color = if (isTalking) Emerald400 else onSurfaceColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }

                    // User Center Marker ("YOU")
                    drawCircle(
                        color = primaryColor.copy(alpha = 0.2f),
                        radius = 14.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = primaryColor,
                        radius = 5.dp.toPx(),
                        center = center
                    )
                }

                // Top-Left HUD Info: Current GPS & Compass Coordinates
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                        Text(
                            text = if (myLocation != null) "GPS ACTIVE" else "ACQUIRING FIX...",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (myLocation != null) MaterialTheme.colorScheme.primary else Amber500,
                            fontSize = 10.sp
                        )
                    }
                    if (myLocation != null) {
                        Text(
                            text = String.format("%.5f, %.5f", myLocation.latitude, myLocation.longitude),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp
                        )
                        Text(
                            text = "Acc: ±${myLocation.accuracy.toInt()}m • Offline BT Mesh",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp
                        )
                    }
                }

                // Top-Right Range Scale Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "RANGE: ${formatDistance(radarRangeMeters)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // 2. Radar Zoom / Range Selector Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Radar Scale",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val ranges = listOf(25f, 50f, 100f, 250f, 500f, 1000f, 5000f)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(ranges) { range ->
                    FilterChip(
                        selected = radarRangeMeters == range,
                        onClick = { onRangeSelected(range) },
                        label = {
                            Text(if (range >= 1000f) "${(range / 1000).toInt()}km" else "${range.toInt()}m")
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("radar_range_${range.toInt()}")
                    )
                }
            }
        }

        // 3. Selected Peer Focused Telemetry Card
        selectedPeer?.let { peer ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Navigation,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .size(22.dp)
                                    .rotate(peer.bearingDegrees ?: 0f)
                            )
                        }
                        Column {
                            Text(
                                text = "Tracking: ${peer.callsign}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Bearing: ${formatBearing(peer.bearingDegrees)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = formatDistance(peer.distanceMeters),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Distance",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 4. Connected Peers Proximity HUD List
        Text(
            text = "Peer Distance & Bearings (${nonSelfPeers.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        if (nonSelfPeers.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "No other users connected in this channel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "When peers join via Bluetooth, their live distance and directional vector will display here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                nonSelfPeers.sortedBy { it.distanceMeters ?: Float.MAX_VALUE }.forEach { peer ->
                    PeerDistanceCard(
                        peer = peer,
                        isSelected = peer.id == selectedPeer?.id,
                        onClick = { onPeerSelected(if (peer.id == selectedPeer?.id) null else peer) }
                    )
                }
            }
        }
    }
}

@Composable
fun PeerDistanceCard(
    peer: IntercomPeer,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isTalking = peer.isTransmitting
    val distance = peer.distanceMeters
    val bearing = peer.bearingDegrees

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .testTag("peer_distance_card_${peer.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTalking) {
                Emerald400.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Directional Compass Arrow
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isTalking) Emerald400 else if (peer.isHost) Indigo400 else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (bearing != null) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = "Bearing arrow",
                            tint = if (isTalking || peer.isHost) Color.White else MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(bearing)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = if (isTalking || peer.isHost) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = peer.callsign,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (peer.isHost) {
                            Text(
                                text = "HOST",
                                style = MaterialTheme.typography.labelSmall,
                                color = Indigo400,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        }
                    }
                    Text(
                        text = if (bearing != null) "Heading: ${formatBearing(bearing)}" else "Calculating vector...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatDistance(distance),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isTalking) Emerald400 else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (isTalking) "TALKING" else "ONLINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isTalking) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
