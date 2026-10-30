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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amrakshay.atvremote.discovery.DiscoveredTv

private val KEYPAD = listOf(
    "1", "2", "3", "A",
    "4", "5", "6", "B",
    "7", "8", "9", "C",
    "D", "0", "E", "F",
)

@Composable
fun PairingScreen(
    tv: DiscoveredTv,
    code: String,
    busy: Boolean,
    error: String?,
    onCodeChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AtvColors.Background)
            .padding(horizontal = 20.dp)
            .padding(top = 56.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column {
            Text(
                "Enter the code",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = AtvColors.Text,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "${tv.serviceName} is showing six characters. They are letters and digits, " +
                    "not just numbers.",
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = AtvColors.Muted,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            repeat(6) { index ->
                val filled = index < code.length
                val isNext = index == code.length
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(62.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isNext) Color(0xFF1B1B29) else AtvColors.Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (filled) code[index].toString() else "",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = AtvColors.Text,
                    )
                }
            }
        }

        Text(
            error ?: "A wrong code is caught here, before the TV is asked.",
            fontSize = 12.5.sp,
            color = if (error != null) AtvColors.Danger else AtvColors.Faint,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KEYPAD.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { label ->
                        val isLetter = label.first().isLetter()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(
                                    if (isLetter) AtvColors.SurfaceBright else AtvColors.Surface,
                                )
                                .clickable(enabled = !busy) { onCodeChanged(code + label) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isLetter) AtvColors.Muted else AtvColors.Text,
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .width(92.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(AtvColors.Surface)
                    .clickable(enabled = !busy) { onCodeChanged(code.dropLast(1)) },
                contentAlignment = Alignment.Center,
            ) {
                Text("⌫", fontSize = 18.sp, color = AtvColors.Muted)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        if (code.length == 6 && !busy) AtvColors.Accent else AtvColors.SurfaceBright,
                    )
                    .clickable(enabled = code.length == 6 && !busy) { onSubmit() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "Pairing…" else "Pair",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (code.length == 6 && !busy) AtvColors.OnAccent else AtvColors.Muted,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Text(
            "Back",
            fontSize = 14.sp,
            color = AtvColors.Muted,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(12.dp),
        )
    }
}
