package com.example.gmeter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gmeter.ui.theme.GMeterTheme
import java.io.OutputStream
import kotlin.math.*

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        enableEdgeToEdge()
        setContent {
            GMeterTheme {
                GForceApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GForceApp() {
    val context = LocalContext.current
    val textMeasurer = rememberTextMeasurer()
    
    var currentLanguage by remember { mutableStateOf(getSavedLanguage(context)) }
    fun t(zh: String, en: String): String = if (currentLanguage == "zh") zh else en

    var displayGX by remember { mutableFloatStateOf(0f) }
    var displayGY by remember { mutableFloatStateOf(0f) }
    val historyMaxG = remember { mutableStateListOf<Float>().apply { repeat(360) { add(0f) } } }
    
    val axisRight = remember { mutableStateOf(floatArrayOf(1f, 0f, 0f)) }
    val axisForward = remember { mutableStateOf(floatArrayOf(0f, 1f, 0f)) }
    val latestGravity = remember { floatArrayOf(0f, 0f, 9.81f) }

    val sensorEventListener = remember {
        object : SensorEventListener {
            private val smoothingFactor = 0.12f 
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> System.arraycopy(event.values, 0, latestGravity, 0, 3)
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        val lx = event.values[0]; val ly = event.values[1]; val lz = event.values[2]
                        val r = axisRight.value; val f = axisForward.value
                        val rawGX = (lx * r[0] + ly * r[1] + lz * r[2]) / 9.81f
                        val rawGY = (lx * f[0] + ly * f[1] + lz * f[2]) / 9.81f
                        displayGX = displayGX * (1 - smoothingFactor) + rawGX * smoothingFactor
                        displayGY = displayGY * (1 - smoothingFactor) + rawGY * smoothingFactor
                        val mag = sqrt(displayGX * displayGX + displayGY * displayGY)
                        if (mag > 0.05f) {
                            val angleRad = atan2(displayGY, displayGX)
                            var angleDeg = Math.toDegrees(angleRad.toDouble()).toInt()
                            angleDeg = (angleDeg + 360) % 360
                            if (mag > historyMaxG[angleDeg]) historyMaxG[angleDeg] = mag
                        }
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.registerListener(sensorEventListener, sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), SensorManager.SENSOR_DELAY_GAME)
        sm.registerListener(sensorEventListener, sm.getDefaultSensor(Sensor.TYPE_GRAVITY), SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(sensorEventListener) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(t("G力表", "G-Meter")) },
                actions = {
                    TextButton(onClick = {
                        currentLanguage = if (currentLanguage == "zh") "en" else "zh"
                        saveLanguage(context, currentLanguage)
                    }) {
                        Text(if (currentLanguage == "zh") "English" else "中文")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Box(
                modifier = Modifier
                    .size(320.dp)
                    .background(Color.Black, shape = CircleShape)
                    .border(2.dp, Color.DarkGray, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                val maxDisplayG = 2.0f
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val radius = size.width / 2
                    drawLine(Color.DarkGray, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y))
                    drawLine(Color.DarkGray, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius))
                    for (g in listOf(0.5f, 1.0f, 1.5f, 2.0f)) {
                        val r = radius * (g / maxDisplayG)
                        drawCircle(Color.Gray.copy(alpha = 0.2f), radius = r, style = Stroke(1f))
                        drawText(
                            textMeasurer = textMeasurer,
                            text = "${g}G",
                            style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp),
                            topLeft = Offset(center.x + 6f, center.y - r + 4f)
                        )
                    }
                    val fitted = computeFittedHistory(historyMaxG)
                    val finalPath = Path()
                    var first = true
                    for (i in 0 until 360) {
                        val gVal = fitted[i]
                        if (gVal > 0.05f) {
                            val ang = Math.toRadians(i.toDouble()).toFloat()
                            val px = center.x + (gVal / maxDisplayG * radius) * cos(ang)
                            val py = center.y + (gVal / maxDisplayG * radius) * sin(ang)
                            if (first) { finalPath.moveTo(px, py); first = false }
                            else { finalPath.lineTo(px, py) }
                        }
                    }
                    if (!first) { 
                        finalPath.close()
                        drawPath(finalPath, Color.Yellow.copy(alpha = 0.15f))
                        drawPath(finalPath, Color.Yellow.copy(alpha = 0.6f), style = Stroke(6f))
                    }
                }
                val dotX = (displayGX / maxDisplayG * 160f).coerceIn(-160f, 160f)
                val dotY = (displayGY / maxDisplayG * 160f).coerceIn(-160f, 160f)
                Box(modifier = Modifier.offset(x = dotX.dp, y = dotY.dp).size(20.dp).background(Color.Red, shape = CircleShape).border(2.dp, Color.White, shape = CircleShape))
            }

            val displayMag = sqrt(displayGX * displayGX + displayGY * displayGY)
            Text("${"%.2f".format(displayMag)} G", style = MaterialTheme.typography.displayMedium)
            
            // 第一行：校准和重置 (功能按钮)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                Button(
                    onClick = {
                        val nx = latestGravity[0]; val ny = latestGravity[1]; val nz = latestGravity[2]
                        val nMag = sqrt(nx*nx + ny*ny + nz*nz)
                        val n = floatArrayOf(nx/nMag, ny/nMag, nz/nMag)
                        var tx = 0f; var ty = 1f; var tz = 0f
                        if (abs(n[1]) > 0.8f) { tx = 0f; ty = 0f; tz = -1f }
                        val dotTN = tx*n[0] + ty*n[1] + tz*n[2]
                        val fx = tx - dotTN * n[0]; val fy = ty - dotTN * n[1]; val fz = tz - dotTN * n[2]
                        val fMag = sqrt(fx*fx + fy*fy + fz*fz)
                        axisForward.value = floatArrayOf(fx/fMag, fy/fMag, fz/fMag)
                        val fv = axisForward.value
                        axisRight.value = floatArrayOf(fv[1] * n[2] - fv[2] * n[1], fv[2] * n[0] - fv[0] * n[2], fv[0] * n[1] - fv[1] * n[0])
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(t("校准", "Calibrate"), fontSize = 12.sp)
                }
                
                OutlinedButton(
                    onClick = { for(i in 0 until 360) historyMaxG[i] = 0f },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(t("重置", "Reset"), fontSize = 12.sp)
                }
            }

            Text(
                text = t("提示：手机可任意角度固定。请在水平地面且车辆静止时，点击“校准”以设定当前姿态为基准。", 
                         "Tip: The phone can be fixed at any angle. Park on level ground and click 'Calibrate' while stationary to set the baseline."),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray.copy(alpha = 0.8f),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 第二行：保存图片 (结果按钮，更醒目)
            Button(
                onClick = { 
                    saveGDiagramToGallery(context, historyMaxG.toList(), currentLanguage)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(t("保存图片", "Save Image"), fontSize = 14.sp)
            }
        }
    }
}

fun getSavedLanguage(context: Context): String {
    val prefs = context.getSharedPreferences("gmeter_prefs", Context.MODE_PRIVATE)
    return prefs.getString("language", "en") ?: "en"
}

fun saveLanguage(context: Context, lang: String) {
    val prefs = context.getSharedPreferences("gmeter_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("language", lang).apply()
}

fun computeFittedHistory(historyMaxG: List<Float>): FloatArray {
    val maxFiltered = FloatArray(360)
    val maxWin = 15
    for (i in 0 until 360) {
        var m = 0f
        for (dw in -maxWin..maxWin) { m = max(m, historyMaxG[(i + dw + 360) % 360]) }
        maxFiltered[i] = m
    }
    val fitted = FloatArray(360)
    val smoothWin = 10
    for (i in 0 until 360) {
        var sum = 0f
        for (dw in -smoothWin..smoothWin) { sum += maxFiltered[(i + dw + 360) % 360] }
        fitted[i] = sum / (2 * smoothWin + 1)
    }
    return fitted
}

fun saveGDiagramToGallery(context: Context, history: List<Float>, lang: String) {
    val size = 1024
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    canvas.drawColor(android.graphics.Color.BLACK)
    
    val centerHorizontal = size / 2f
    val centerVertical = size / 2f
    val radius = size * 0.36f
    val maxG = 2.0f

    paint.color = android.graphics.Color.DKGRAY
    paint.strokeWidth = 2f
    val axisLimit = radius
    canvas.drawLine(centerHorizontal - axisLimit, centerVertical, centerHorizontal + axisLimit, centerVertical, paint)
    canvas.drawLine(centerHorizontal, centerVertical - axisLimit, centerHorizontal, centerVertical + axisLimit, paint)

    paint.style = Paint.Style.STROKE
    paint.textSize = 30f
    for (g in listOf(0.5f, 1.0f, 1.5f, 2.0f)) {
        val r = radius * (g / maxG)
        canvas.drawCircle(centerHorizontal, centerVertical, r, paint)
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.LTGRAY
        canvas.drawText("${g}G", centerHorizontal + 10f, centerVertical - r + 35f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = android.graphics.Color.DKGRAY
    }

    val fitted = computeFittedHistory(history)
    val path = android.graphics.Path()
    var first = true
    for (i in 0 until 360) {
        val gVal = fitted[i]
        if (gVal > 0.05f) {
            val ang = Math.toRadians(i.toDouble())
            val px = centerHorizontal + (gVal / maxG * radius) * cos(ang).toFloat()
            val py = centerVertical + (gVal / maxG * radius) * sin(ang).toFloat()
            if (first) { path.moveTo(px, py); first = false }
            else { path.lineTo(px, py) }
        }
    }
    if (!first) {
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = android.graphics.Color.argb(45, 255, 255, 0)
        canvas.drawPath(path, paint)
        
        paint.style = Paint.Style.STROKE
        paint.color = android.graphics.Color.YELLOW
        paint.strokeWidth = 8f
        canvas.drawPath(path, paint)
    }

    paint.style = Paint.Style.FILL
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 50f
    paint.textAlign = Paint.Align.CENTER
    val title = if (lang == "zh") "G力表性能包络图" else "G-Meter Performance Envelope"
    canvas.drawText(title, centerHorizontal, 90f, paint)

    val overallMaxG = history.maxOrNull() ?: 0f
    paint.color = android.graphics.Color.YELLOW
    paint.textSize = 65f
    val maxText = if (lang == "zh") "最大峰值: ${"%.2f".format(overallMaxG)} G" else "MAX PEAK: ${"%.2f".format(overallMaxG)} G"
    canvas.drawText(maxText, centerHorizontal, size - 70f, paint)

    val filename = "GMeter_${System.currentTimeMillis()}.png"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/G-Meter")
        }
    }

    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    try {
        uri?.let {
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(it)
            outputStream?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            val successMsg = if (lang == "zh") "图片已保存至相册" else "Image saved to gallery"
            Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        val failMsg = if (lang == "zh") "保存失败" else "Save failed"
        Toast.makeText(context, "$failMsg: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
