package com.tilelauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tilelauncher.ui.theme.LocalMetro

/** First-run screen: what this is, and the two things that make it work best. */
@Composable
fun WelcomeScreen(onMakeDefault: () -> Unit, onChoosePermissions: () -> Unit, onDone: () -> Unit) {
    val metro = LocalMetro.current
    Column(
        Modifier
            .fillMaxSize()
            .background(metro.background)
            // Swallow touches so the Start screen underneath can't be used yet.
            .clickable(enabled = true, onClick = {})
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("hello", fontSize = 76.sp, fontWeight = FontWeight.Light)
        Text(
            "Tile Launcher puts live tiles on your home screen. Two things help it work best.",
            fontSize = 19.sp,
        )
        Spacer(Modifier.padding(4.dp))
        Text("Make it your home screen so the home button opens it.", fontSize = 16.sp, color = metro.subtle)
        MetroButton("set as home screen", onMakeDefault)
        Text(
            "Live tiles can show unread counts, appointments, photos, contacts and the weather. You choose which.",
            fontSize = 16.sp,
            color = metro.subtle,
        )
        MetroButton("choose live tile permissions", onChoosePermissions)
        Spacer(Modifier.weight(1f))
        MetroButton("get started", onDone, filled = true)
    }
}
