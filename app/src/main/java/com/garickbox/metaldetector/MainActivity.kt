package com.garickbox.metaldetector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { MetalDetectorService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askAndStart()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }

    private fun askAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            MetalDetectorService.start(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MetalDetectorService.stop(this)
    }
}

@Composable
fun Screen() {
    val ctx: Context = LocalContext.current
    val reading by MetalDetectorService.reading.collectAsStateWithLifecycle()
    val isRunning by MetalDetectorService.isRunning.collectAsStateWithLifecycle()

    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(reading) {
        reading?.let {
            history.add(it.delta)
            if (history.size > 200) history.removeAt(0)
        }
    }

    val delta = reading?.delta ?: 0f
    val color = when {
        delta > 50f -> Color(0xFFFF3333)
        delta > 20f -> Color(0xFFFFAA00)
        delta > 5f  -> Color(0xFFFFEB3B)
        else        -> Color(0xFF4CAF50)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("METAL DETECTOR", color = Color.White, fontSize = 14.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        Spacer(Modifier.height(16.dp))

        Text("|B|", color = Color.Gray, fontSize = 12.sp)
        Text("${reading?.smoothed?.toInt() ?: 0} µT", color = color,
            fontSize = 64.sp, fontWeight = FontWeight.Bold)
        Text("Δ ${"%.1f".format(delta)} µT", color = color, fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold)
        Text("baseline ${reading?.baseline?.toInt() ?: 0} µT",
            color = Color.Gray, fontSize = 12.sp)

        Spacer(Modifier.height(24.dp))

        Box(
            Modifier.fillMaxWidth().height(20.dp)
                .background(Color(0xFF222222), RoundedCornerShape(10.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(min(1f, delta / 50f)).fillMaxHeight()
                    .background(color, RoundedCornerShape(10.dp))
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", color = Color.Gray, fontSize = 10.sp)
            Text("25 µT", color = Color.Gray, fontSize = 10.sp)
            Text("50+ µT", color = Color.Gray, fontSize = 10.sp)
        }

        Spacer(Modifier.height(24.dp))

        Text("SIGNAL (последние 200)", color = Color.Gray, fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width
            val h = size.height
            for (i in 1..4) {
                val y = h * i / 5
                drawLine(Color(0xFF333333), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            if (history.size > 1) {
                val maxVal = maxOf(50f, history.maxOrNull() ?: 50f)
                var prev: Offset? = null
                history.forEachIndexed { i, v ->
                    val x = w * i / 199f
                    val y = h - (v / maxVal) * h
                    val pt = Offset(x, y)
                    prev?.let { drawLine(color, it, pt, strokeWidth = 3f) }
                    prev = pt
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (isRunning) MetalDetectorService.stop(ctx)
                else MetalDetectorService.start(ctx)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFD32F2F) else Color(0xFF388E3C)
            )
        ) {
            Text(if (isRunning) "STOP" else "START")
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Detects: Fe, Ni, Co, steel, magnets\n" +
            "Does NOT detect: Al, Cu, Au, brass\n" +
            "Tip: перезапусти вдали от металла",
            color = Color(0xFF888888), fontSize = 11.sp, textAlign = TextAlign.Center
        )
    }
}
