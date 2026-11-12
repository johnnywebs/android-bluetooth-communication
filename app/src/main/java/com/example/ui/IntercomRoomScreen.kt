package com.example.ui

import android.view.MotionEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VoiceChat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.IntercomPeer
import com.example.model.IntercomUiState
import com.example.model.formatDistance
import com.example.ui.components.AudioSettingsSheet
import com.example.ui.components.IntercomRadarMap
import com.example.ui.components.VuMeter
import com.example.ui.theme.Emerald400
import com.example.ui.theme.Emerald500
import com.example.ui.theme.Indigo400
import com.example.ui.theme.Teal400
import com.example.viewmodel.IntercomViewModel

@OptIn(ExperimentalComposeUiApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun IntercomRoomScreen(
    uiState: IntercomUiState,
    viewModel: IntercomViewModel
) {
    var showAudioSettings by remember { mutableStateOf(false) }
    var isMapTabSelected by remember { mutableStateOf(false) }
    val room = uiState.currentRoom

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 3.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = room?.name ?: "Intercom Channel",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(
                                            text = "PIN: ${room?.pin ?: "----"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${uiState.peers.size} Connected • ${if (room?.isHost == true) "Host Station" else "Station Relay"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = { showAudioSettings = true },
                                modifier = Modifier.testTag("room_settings_button")
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = "Audio Settings")
                            }
                            FilledTonalIconButton(
                                onClick = { viewModel.leaveRoom() },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier.testTag("leave_room_button")
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = "Leave Channel")
                            }
                        }
                    }

                    // Navigation Tabs: Voice Hub vs Radar & Distance Map
                    PrimaryTabRow(
                        selectedTabIndex = if (isMapTabSelected) 1 else 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = !isMapTabSelected,
                            onClick = { isMapTabSelected = false },
                            text = { Text("Voice Hub", fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("tab_voice_hub")
                        )
                        Tab(
                            selected = isMapTabSelected,
                            onClick = { isMapTabSelected = true },
                            text = { Text("Radar & Map", fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.Explore, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("tab_radar_map")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (isMapTabSelected) {
            // ---------------- MAP & RADAR VIEW ----------------
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        IntercomRadarMap(
                            myLocation = uiState.myLocation,
                            peers = uiState.peers,
                            radarRangeMeters = uiState.radarRangeMeters,
                            selectedPeer = uiState.selectedPeerForTracking,
                            onRangeSelected = { viewModel.setRadarRange(it) },
                            onPeerSelected = { viewModel.selectPeerForTracking(it) }
                        )
                    }
                }

                // Persistent Floating PTT & Handsfree Bar on Map Screen
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FilterChip(
                            selected = uiState.settings.isHandsfreeLocked,
                            onClick = { viewModel.toggleHandsfreeLock() },
                            label = { Text(if (uiState.settings.isHandsfreeLocked) "Handsfree: ON" else "Handsfree") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (uiState.settings.isHandsfreeLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Emerald400.copy(alpha = 0.25f),
                                selectedLabelColor = Emerald400
                            )
                        )

                        // Compact Map PTT Button
                        Button(
                            onClick = {},
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .pointerInteropFilter { motionEvent ->
                                    when (motionEvent.action) {
                                        MotionEvent.ACTION_DOWN -> {
                                            viewModel.setPttPressed(true)
                                            true
                                        }
                                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                            viewModel.setPttPressed(false)
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .testTag("map_push_to_talk_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.isTransmitting) Emerald500 else MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (uiState.isTransmitting) Icons.Default.Mic else Icons.Default.MicOff,
                                    contentDescription = null
                                )
                                Text(
                                    text = if (uiState.isTransmitting) "TRANSMITTING" else "HOLD TO TALK",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // ---------------- VOICE HUB VIEW ----------------
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. Participant Cards Grid
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Channel Members (${uiState.peers.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .clickable { isMapTabSelected = true },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.Explore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Text(
                                text = "View on Radar Map",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(uiState.peers) { peer ->
                            ParticipantCard(peer = peer)
                        }
                    }

                    if (uiState.peers.size <= 1) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column {
                                    Text(
                                        text = "Waiting for nearby peers...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Nearby devices must scan and enter PIN ${room?.pin ?: ""} to connect.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Telemetry VU Meter & Live Transmission Indicator
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (uiState.isTransmitting) Emerald400 else Color.Gray)
                                )
                                Text(
                                    text = if (uiState.isTransmitting) "TRANSMITTING (LIVE VOICE)" else "LISTENING / MUTED",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.isTransmitting) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Text(
                                text = if (uiState.settings.isHandsfreeLocked) "HANDSFREE LOCKED" else if (uiState.settings.isVoxEnabled) "VOX ACTIVE" else "PTT READY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        VuMeter(
                            level = uiState.localAudioLevel,
                            threshold = uiState.settings.noiseGateThreshold,
                            isTransmitting = uiState.isTransmitting
                        )
                    }
                }

                // 3. Handsfree & VOX Quick Toggles
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = uiState.settings.isHandsfreeLocked,
                        onClick = { viewModel.toggleHandsfreeLock() },
                        label = {
                            Text(if (uiState.settings.isHandsfreeLocked) "Handsfree: LOCKED" else "Handsfree: OFF")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.settings.isHandsfreeLocked) Icons.Default.LockOpen else Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f).testTag("handsfree_toggle_chip"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Emerald400.copy(alpha = 0.25f),
                            selectedLabelColor = Emerald400
                        )
                    )

                    FilterChip(
                        selected = uiState.settings.isVoxEnabled,
                        onClick = { viewModel.toggleVox() },
                        label = {
                            Text(if (uiState.settings.isVoxEnabled) "VOX: ON" else "VOX: OFF")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.weight(1f).testTag("vox_toggle_chip"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                // 4. Primary Central PTT (Push-To-Talk) Hub
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val isPttActive = uiState.isTransmitting

                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = if (isPttActive) 1.15f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(650, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulse_scale"
                    )

                    // Pulsing ring when transmitting
                    if (isPttActive) {
                        Box(
                            modifier = Modifier
                                .size(136.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(Emerald400.copy(alpha = 0.25f))
                        )
                    }

                    // Main PTT Circle Button
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPttActive) Emerald500 else MaterialTheme.colorScheme.primary
                            )
                            .border(
                                width = 4.dp,
                                color = if (isPttActive) Emerald400 else MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape
                            )
                            .pointerInteropFilter { motionEvent ->
                                when (motionEvent.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        viewModel.setPttPressed(true)
                                        true
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        viewModel.setPttPressed(false)
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .testTag("push_to_talk_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isPttActive) Icons.Default.Mic else Icons.Default.MicOff,
                                contentDescription = "Push to Talk",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isPttActive) "TALKING" else "HOLD TO TALK",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAudioSettings) {
        AudioSettingsSheet(
            settings = uiState.settings,
            availableInputs = uiState.availableInputDevices,
            availableOutputs = uiState.availableOutputDevices,
            localAudioLevel = uiState.localAudioLevel,
            isLoopbackActive = uiState.isAudioTestActive,
            onDismiss = { showAudioSettings = false },
            onGainChanged = { viewModel.setMicGain(it) },
            onNoiseGateChanged = { viewModel.setNoiseGateThreshold(it) },
            onHardwareNsToggled = { viewModel.toggleHardwareNoiseSuppression(it) },
            onHardwareAecToggled = { viewModel.toggleHardwareEchoCancellation(it) },
            onSelectInput = { viewModel.selectInputDevice(it) },
            onSelectOutput = { viewModel.selectOutputDevice(it) },
            onToggleLoopback = { viewModel.toggleAudioLoopbackTest() }
        )
    }
}

@Composable
fun ParticipantCard(peer: IntercomPeer) {
    val isTalking = peer.isTransmitting
    val borderColor by animateColorAsState(
        targetValue = if (isTalking) Emerald400 else Color.Transparent,
        label = "border_color"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, RoundedCornerShape(16.dp))
            .testTag("peer_card_${peer.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTalking) {
                Emerald400.copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (isTalking) Emerald400 else if (peer.isHost) Indigo400 else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isTalking) Icons.Default.RecordVoiceOver else Icons.Default.Person,
                    contentDescription = null,
                    tint = if (isTalking || peer.isHost) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = peer.callsign,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    if (peer.isSelf) {
                        Text(
                            text = "(You)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (peer.isHost) {
                        Text(
                            text = "HOST",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Indigo400,
                            fontSize = 9.sp
                        )
                    }
                    Text(
                        text = if (isTalking) "TALKING" else "LISTENING",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isTalking) Emerald400 else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        fontWeight = if (isTalking) FontWeight.Bold else FontWeight.Normal
                    )
                }

                // Live Distance Telemetry Badge
                if (!peer.isSelf) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.NearMe,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(11.dp)
                                .rotate(peer.bearingDegrees ?: 0f)
                        )
                        Text(
                            text = formatDistance(peer.distanceMeters),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}
