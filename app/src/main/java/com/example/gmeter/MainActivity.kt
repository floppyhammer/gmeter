package com.example.gmeter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.gmeter.ui.theme.GMeterTheme
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GMeterTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GForceApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun GForceApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var gX by remember { mutableFloatStateOf(0f) }
    var gY by remember { mutableFloatStateOf(0f) }
    
    // 存储校准时的重力向量（基准向量）
    val gravityBaseline = remember { mutableStateOf(floatArrayOf(0f, 0f, 9.81f)) }
    
    // 存储最新的传感器原始值，用于点击校准按钮时捕获
    val latestRaw = remember { floatArrayOf(0f, 0f, 9.81f) }

    val sensorEventListener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                    val rx = event.values[0]
                    val ry = event.values[1]
                    val rz = event.values[2]
                    
                    latestRaw[0] = rx
                    latestRaw[1] = ry
                    latestRaw[2] = rz

                    val baseline = gravityBaseline.value
                    val bx = baseline[0]
                    val by = baseline[1]
                    val bz = baseline[2]
                    
                    val bMag = sqrt(bx * bx + by * by + bz * bz)
                    if (bMag < 0.1f) return

                    // 1. 单位法向量 n (代表“下”方向)
                    val nx = bx / bMag
                    val ny = by / bMag
                    val nz = bz / bMag

                    // 2. 计算线性加速度向量 = 原始向量 - (原始向量在 n 方向的投影) * n
                    // 这样可以消除重力分量，得到水平面内的加速度
                    val dot = rx * nx + ry * ny + rz * nz
                    val lx = rx - dot * nx
                    val ly = ry - dot * ny
                    val lz = rz - dot * nz

                    // 3. 定义 UI 的“向前”方向 (v)：手机 Y 轴在水平面上的投影
                    var vx = 0f - ny * nx
                    var vy = 1f - ny * ny
                    var vz = 0f - ny * nz
                    var vMag = sqrt(vx * vx + vy * vy + vz * vz)
                    
                    if (vMag < 0.01f) {
                        // 如果手机水平放置（Y 轴与重力平行），改用手机 Z 轴作为“向前”方向
                        vx = 0f - nz * nx
                        vy = 0f - nz * ny
                        vz = 1f - nz * nz
                        vMag = sqrt(vx * vx + vy * vy + vz * vz)
                    }

                    if (vMag > 0.01f) {
                        vx /= vMag
                        vy /= vMag
                        vz /= vMag
                    }

                    // 4. 定义 UI 的“向右”方向 (u)：v 与 n 的叉积
                    val ux = vy * nz - vz * ny
                    val uy = vz * nx - vx * nz
                    val uz = vx * ny - vy * nx

                    // 5. 将线性加速度投射到 u 和 v 轴上，并除以 9.81 转换为 G 值
                    gX = (lx * ux + ly * uy + lz * uz) / 9.81f
                    gY = (lx * vx + ly * vy + lz * vz) / 9.81f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    // 生命周期管理：注册和注销传感器
    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorManager.registerListener(sensorEventListener, accel, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(sensorEventListener)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "G-Meter",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(32.dp))

        // 圆形显示区域
        Box(
            modifier = Modifier
                .size(300.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outline, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.width / 2
                
                // 十字准星
                drawLine(Color.Gray.copy(alpha = 0.5f), Offset(0f, center.y), Offset(size.width, center.y))
                drawLine(Color.Gray.copy(alpha = 0.5f), Offset(center.x, 0f), Offset(center.x, size.height))
                
                // 刻度圆圈 (0.5G, 1.0G, 1.5G)
                drawCircle(Color.Gray.copy(alpha = 0.3f), radius = radius * 0.25f, style = Stroke(1f))
                drawCircle(Color.Gray.copy(alpha = 0.3f), radius = radius * 0.5f, style = Stroke(1f))
                drawCircle(Color.Gray.copy(alpha = 0.3f), radius = radius * 0.75f, style = Stroke(1f))
            }

            // G力小红点
            // 比例映射：2.0G 对应圆圈边缘 (150dp)
            val maxDisplayG = 2.0f
            val dotX = (gX / maxDisplayG * 150f).coerceIn(-150f, 150f)
            val dotY = (-gY / maxDisplayG * 150f).coerceIn(-150f, 150f)

            Box(
                modifier = Modifier
                    .offset(x = dotX.dp, y = dotY.dp)
                    .size(24.dp)
                    .background(Color.Red, shape = CircleShape)
                    .border(2.dp, Color.White, shape = CircleShape)
            )
        }

        Spacer(modifier = Modifier.height(40.dp))

        // 数值显示
        val totalG = sqrt(gX * gX + gY * gY)
        Text(
            text = "${"%.2f".format(totalG)} G",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "横向: ${"%.2f".format(gX)} G", style = MaterialTheme.typography.bodyLarge)
            Text(text = "纵向: ${"%.2f".format(gY)} G", style = MaterialTheme.typography.bodyLarge)
        }

        Spacer(modifier = Modifier.height(48.dp))

        // 校准按钮
        Button(
            onClick = {
                gravityBaseline.value = latestRaw.copyOf()
            },
            shape = MaterialTheme.shapes.medium
        ) {
            Text("点击校准 (Calibrate)")
        }
        
        Text(
            text = "请在车辆静止于水平面时点击，以设定零位",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
