package com.myworkshopy.centerfinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.myworkshopy.centerfinder.ui.theme.CenterFinderTheme
import org.opencv.android.OpenCVLoader
import kotlin.math.max
import android.util.Size as AndroidSize

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        cameraGranted.value = isGranted
        if (!isGranted) {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val cameraGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "OpenCV failed to load")
            Toast.makeText(this, "OpenCV failed to load", Toast.LENGTH_LONG).show()
        }

        cameraGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted.value) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            CenterFinderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val granted by cameraGranted
                    if (granted) {
                        ShapeDetectScreen()
                    } else {
                        PermissionPrompt(
                            onRequest = { requestPermissionLauncher.launch(Manifest.permission.CAMERA) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Camera access is needed to detect shapes in real time.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRequest) {
            Text("Allow camera")
        }
    }
}

@Composable
fun ShapeDetectScreen(viewModel: ShapeViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    val frame by viewModel.detectionFrame.collectAsState()
    val stableName by viewModel.stableName.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageRotationEnabled(true)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        AndroidSize(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()
                        )
                        .build()
                        .also { useCase ->
                            useCase.setAnalyzer(viewModel.cameraExecutor) { imageProxy ->
                                try {
                                    val plane = imageProxy.planes[0]
                                    val mat = imageProxyToMat(
                                        imageProxy.width,
                                        imageProxy.height,
                                        plane.rowStride,
                                        plane.buffer
                                    )
                                    if (mat != null) {
                                        viewModel.onFrameAnalyzed(
                                            mat,
                                            imageProxy.imageInfo.rotationDegrees
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("OpenCV", "Processing error", e)
                                } finally {
                                    imageProxy.close()
                                }
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (e: Exception) {
                        Log.e("CameraX", "Use case binding failed", e)
                    }
                }, mainExecutor)

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        ShapeOverlay(
            detection = frame,
            modifier = Modifier.fillMaxSize()
        )

        Text(
            text = "Point the camera at an object",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        ResultCard(
            detection = frame,
            stableName = stableName,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}

@Composable
private fun ResultCard(
    detection: FrameDetection?,
    stableName: String?,
    modifier: Modifier = Modifier
) {
    val shape = detection?.primary
    val title = shape?.name ?: stableName ?: "Looking for a shape…"
    val extras = detection?.extras.orEmpty()

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (shape != null) {
                val percent = (shape.confidence * 100).toInt()
                val center = "(${shape.centerX.toInt()}, ${shape.centerY.toInt()})"
                val detail = buildString {
                    append("$percent%  ·  center $center")
                    if (title == "Honeycomb" && extras.isNotEmpty()) {
                        append("  ·  ${extras.size} cells")
                    }
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            } else {
                Text(
                    text = "Aim at the object — its border and center will lock on",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ShapeOverlay(
    detection: FrameDetection?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val viewW = size.width
        val viewH = size.height

        val reticle = Color.White.copy(alpha = 0.35f)
        val cx = viewW / 2f
        val cy = viewH / 2f
        drawCircle(reticle, radius = 28f, center = Offset(cx, cy), style = Stroke(width = 2f))
        drawLine(reticle, Offset(cx - 18f, cy), Offset(cx + 18f, cy), strokeWidth = 2f)
        drawLine(reticle, Offset(cx, cy - 18f), Offset(cx, cy + 18f), strokeWidth = 2f)

        val frame = detection ?: return@Canvas
        val primary = frame.primary ?: return@Canvas
        val imgW = frame.imageWidth
        val imgH = frame.imageHeight
        if (imgW <= 0 || imgH <= 0) return@Canvas

        val scale = max(viewW / imgW, viewH / imgH)
        val dx = (viewW - imgW * scale) / 2f
        val dy = (viewH - imgH * scale) / 2f
        fun map(x: Float, y: Float) = Offset(x * scale + dx, y * scale + dy)

        val accent = when (primary.name) {
            "Honeycomb", "Hexagon" -> Color(0xFFFFC107)
            "Circle", "Oval" -> Color(0xFF4FC3F7)
            "Object" -> Color(0xFFB2FF59)
            else -> Color(0xFF69F0AE)
        }

        fun drawOutline(shape: DetectedShape, stroke: Float, color: Color, corners: Boolean) {
            if (shape.points.size >= 3) {
                val path = Path().apply {
                    val first = map(shape.points[0].first, shape.points[0].second)
                    moveTo(first.x, first.y)
                    for (i in 1 until shape.points.size) {
                        val p = map(shape.points[i].first, shape.points[i].second)
                        lineTo(p.x, p.y)
                    }
                    close()
                }
                drawPath(path, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round))
                if (corners) {
                    for (pt in shape.points) {
                        drawCircle(Color.White, radius = 5f, center = map(pt.first, pt.second))
                    }
                }
            } else {
                val r = shape.radius
                if (r != null) {
                    drawCircle(
                        color = color,
                        radius = r * scale,
                        center = map(shape.centerX, shape.centerY),
                        style = Stroke(width = stroke)
                    )
                }
            }
        }

        fun drawCenter(shape: DetectedShape) {
            val center = map(shape.centerX, shape.centerY)
            drawCircle(Color(0xFFFF5252), radius = 5f, center = center)
            drawLine(Color(0xFFFF5252), Offset(center.x - 16f, center.y), Offset(center.x + 16f, center.y), 3.5f)
            drawLine(Color(0xFFFF5252), Offset(center.x, center.y - 16f), Offset(center.x, center.y + 16f), 3.5f)
        }

        for (extra in frame.extras) {
            drawOutline(extra, 3.5f, accent.copy(alpha = 0.75f), corners = false)
            drawCenter(extra)
        }
        drawOutline(primary, 7f, accent, corners = primary.name == "Square" || primary.name == "Rectangle")
        drawCenter(primary)
    }
}
