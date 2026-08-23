package com.myworkshopy.centerfinder.cemera_model

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.myworkshopy.centerfinder.model.DetectionResult
import com.myworkshopy.centerfinder.model.ShapeAnalyzer

@Composable
fun CameraFinderScreen(
    onProfileClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var detection by remember { mutableStateOf<DetectionResult?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(
                                ContextCompat.getMainExecutor(ctx),
                                ShapeAnalyzer { result -> detection = result }
                            )
                        }

                    val selector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, selector, preview, analysis
                        )
                    } catch (e: Exception) {
                        Log.e("CameraFinderScreen", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // Overlay: contour outline + centroid crosshair.
        // detection coordinates are normalized against the analysis frame; the
        // PreviewView uses FIT_CENTER, so we letterbox-map the same way here.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val result = detection ?: return@Canvas
            val frameAspect = result.frameWidth.toFloat() / result.frameHeight.toFloat()
            val viewAspect = size.width / size.height

            val scaledW: Float
            val scaledH: Float
            if (frameAspect > viewAspect) {
                scaledW = size.width
                scaledH = size.width / frameAspect
            } else {
                scaledH = size.height
                scaledW = size.height * frameAspect
            }
            val offsetX = (size.width - scaledW) / 2f
            val offsetY = (size.height - scaledH) / 2f

            fun toCanvas(p: Pair<Float, Float>) = Offset(
                offsetX + p.first * scaledW,
                offsetY + p.second * scaledH
            )

            if (result.contourPoints.size > 1) {
                val path = Path().apply {
                    val first = toCanvas(result.contourPoints.first())
                    moveTo(first.x, first.y)
                    result.contourPoints.drop(1).forEach { pt ->
                        val c = toCanvas(pt)
                        lineTo(c.x, c.y)
                    }
                    close()
                }
                drawPath(path, color = Color(0xFF00E676), style = Stroke(width = 4f))
            }

            val center = toCanvas(result.centroid)
            val crossSize = 24f
            drawLine(
                color = Color.Red,
                start = Offset(center.x - crossSize, center.y),
                end = Offset(center.x + crossSize, center.y),
                strokeWidth = 4f
            )
            drawLine(
                color = Color.Red,
                start = Offset(center.x, center.y - crossSize),
                end = Offset(center.x, center.y + crossSize),
                strokeWidth = 4f
            )
        }

        Text(
            text = detection?.shapeName ?: "Searching…",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}