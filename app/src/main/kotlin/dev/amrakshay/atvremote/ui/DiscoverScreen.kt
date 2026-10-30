package dev.amrakshay.atvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amrakshay.atvremote.data.SavedTv
import dev.amrakshay.atvremote.discovery.DiscoveredTv

@Composable
fun DiscoverScreen(
    tvs: List<DiscoveredTv>,
    paired: List<SavedTv>,
    error: String?,
    onChoose: (DiscoveredTv) -> Unit,
    onConnect: (SavedTv) -> Unit,
    onManualConnect: (String) -> Unit,
) {
    var manualAddress by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtvColors.Background)
            .padding(horizontal = 20.dp)
            .padding(top = 56.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            Text(
                "Find your TV",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = AtvColors.Text,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Your phone and TV must be on the same Wi-Fi. Nothing is installed on the TV.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = AtvColors.Muted,
            )
        }

        if (paired.isNotEmpty()) {
            SectionLabel("ALREADY PAIRED")
            paired.forEach { tv ->
                TvRow(
                    title = tv.serviceName,
                    subtitle = tv.host,
                    highlighted = false,
                    onClick = { onConnect(tv) },
                )
            }
        }

        SectionLabel("FOUND ON YOUR NETWORK")

        if (tvs.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AtvColors.SurfaceDim)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = AtvColors.Accent,
                    trackColor = AtvColors.Track,
                )
                Spacer(Modifier.width(12.dp))
                Text("Still looking…", fontSize = 13.sp, color = AtvColors.Muted)
            }
        } else {
            tvs.forEach { tv ->
                TvRow(
                    title = tv.serviceName,
                    subtitle = "${tv.host}:${tv.port}",
                    highlighted = true,
                    onClick = { onChoose(tv) },
                )
            }
        }

        Spacer(Modifier.weight(1f))

        if (error != null) {
            Text(error, fontSize = 13.sp, color = AtvColors.Danger)
        }

        Text(
            "Some routers block the discovery messages. If your TV never appears, check it " +
                "is awake — it leaves the network completely when asleep — or type its " +
                "address, from Settings → Network on the TV.",
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = AtvColors.Faint,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("192.168.0.236", color = AtvColors.Faint) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AtvColors.Text,
                    unfocusedTextColor = AtvColors.Text,
                    focusedBorderColor = AtvColors.Accent,
                    unfocusedBorderColor = AtvColors.Track,
                    cursorColor = AtvColors.Accent,
                ),
            )
            Box(
                modifier = Modifier
                    .height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (manualAddress.isBlank()) AtvColors.SurfaceBright else AtvColors.Accent,
                    )
                    .clickable(enabled = manualAddress.isNotBlank()) {
                        onManualConnect(manualAddress)
                    }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Connect",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (manualAddress.isBlank()) AtvColors.Muted else AtvColors.OnAccent,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = AtvColors.Muted,
    )
}

@Composable
private fun TvRow(
    title: String,
    subtitle: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (highlighted) AtvColors.Surface else AtvColors.SurfaceDim)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(AtvColors.SurfaceBright),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Tv,
                contentDescription = null,
                tint = if (highlighted) AtvColors.Accent else AtvColors.Muted,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AtvColors.Text)
            Spacer(Modifier.size(3.dp))
            Text(subtitle, fontSize = 12.5.sp, color = AtvColors.Muted)
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = AtvColors.Faint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun CircleBadge(size: Int, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}
