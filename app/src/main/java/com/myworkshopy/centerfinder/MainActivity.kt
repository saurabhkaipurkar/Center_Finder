package com.myworkshopy.centerfinder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.myworkshopy.centerfinder.ui.theme.CenterFinderTheme
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val openCvReady = OpenCVLoader.initLocal()

        setContent {
            CenterFinderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var hasCameraPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission()
                    ) { granted -> hasCameraPermission = granted }

                    LaunchedEffect(Unit) {
                        if (!hasCameraPermission) {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }

                    when {
                        !openCvReady -> {
                            Box(Modifier.fillMaxSize()) {
                                Text(
                                    "OpenCV failed to initialize",
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                        hasCameraPermission -> CameraFinderScreen()
                        else -> {
                            Box(Modifier.fillMaxSize()) {
                                Text(
                                    "Camera permission is required",
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/*------------------------------------------*/

/**
 * Result of analyzing a single camera frame.
 * All coordinates are normalized (0..1) relative to [frameWidth] x [frameHeight],
 * in the *display-oriented* frame (i.e. after correcting for sensor rotation).
 */
data class DetectionResult(
    val shapeName: String,
    val contourPoints: List<Pair<Float, Float>>,
    val centroid: Pair<Float, Float>,
    val frameWidth: Int,
    val frameHeight: Int
)

/**
 * CameraX analyzer that finds the largest object's outline in each frame,
 * classifies its rough shape, and reports its centroid.
 *
 * Runs on the Y-plane only (grayscale) - fast, and all we need for edge detection.
 */
class ShapeAnalyzer(
    private val onResult: (DetectionResult?) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val gray = imageProxyToGrayMat(image)
            val rotated = rotateMat(gray, image.imageInfo.rotationDegrees)
            gray.release()

            val result = detectLargestShape(rotated)
            rotated.release()
            onResult(result)
        } catch (e: Exception) {
            onResult(null)
        } finally {
            image.close()
        }
    }

    private fun imageProxyToGrayMat(image: ImageProxy): Mat {
        // ImageAnalysis default output is YUV_420_888; the Y plane alone is a
        // valid grayscale image and is all we need here.
        val yPlane = image.planes[0]
        val yBuffer: ByteBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val width = image.width
        val height = image.height

        val mat = Mat(height, width, CvType.CV_8UC1)
        if (rowStride == width) {
            val bytes = ByteArray(yBuffer.remaining())
            yBuffer.get(bytes)
            mat.put(0, 0, bytes)
        } else {
            val rowBytes = ByteArray(rowStride)
            for (row in 0 until height) {
                yBuffer.position(row * rowStride)
                yBuffer.get(rowBytes, 0, rowStride)
                mat.put(row, 0, rowBytes.copyOfRange(0, width))
            }
        }
        return mat
    }

    private fun rotateMat(src: Mat, rotationDegrees: Int): Mat {
        if (rotationDegrees == 0) return src.clone()
        val dst = Mat()
        when (rotationDegrees) {
            90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, dst, Core.ROTATE_180)
            270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return src.clone()
        }
        return dst
    }

    private fun detectLargestShape(gray: Mat): DetectionResult? {
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 50.0, 150.0)
        Imgproc.dilate(edges, edges, Mat())
        blurred.release()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        edges.release()
        hierarchy.release()

        // Ignore tiny noise contours - require at least 1% of frame area.
        val minArea = gray.rows() * gray.cols() * 0.01
        var best: MatOfPoint? = null
        var bestArea = 0.0
        for (c in contours) {
            val area = Geometry.contourArea(c)
            if (area > bestArea && area > minArea) {
                bestArea = area
                best = c
            }
        }
        val bestContour = best ?: return null

        val moments = Geometry.moments(bestContour)
        if (moments.m00 == 0.0) return null

        val contour2f = MatOfPoint2f(*bestContour.toArray())
        val approx = MatOfPoint2f()
        val peri = Geometry.arcLength(contour2f, true)
        Geometry.approxPolyDP(contour2f, approx, 0.02 * peri, true)
        val vertexCount = approx.toArray().size
        contour2f.release()
        approx.release()

        val shapeName = classifyShape(vertexCount, bestContour)

        val w = gray.cols().toFloat()
        val h = gray.rows().toFloat()
        val cx = (moments.m10 / moments.m00).toFloat()
        val cy = (moments.m01 / moments.m00).toFloat()

        val normalizedContour = bestContour.toArray().map { p ->
            Pair((p.x.toFloat() / w).coerceIn(0f, 1f), (p.y.toFloat() / h).coerceIn(0f, 1f))
        }

        return DetectionResult(
            shapeName = shapeName,
            contourPoints = normalizedContour,
            centroid = Pair((cx / w).coerceIn(0f, 1f), (cy / h).coerceIn(0f, 1f)),
            frameWidth = gray.cols(),
            frameHeight = gray.rows()
        )
    }

    private fun classifyShape(vertexCount: Int, contour: MatOfPoint): String {
        return when (vertexCount) {
            3 -> "Triangle"
            4 -> {
                val rect: Rect = Geometry.boundingRect(contour)
                val ratio = rect.width.toFloat() / rect.height.toFloat()
                if (abs(ratio - 1.0f) < 0.08f) "Square" else "Rectangle"
            }
            5 -> "Pentagon"
            6 -> "Hexagon"
            7, 8 -> "Octagon"
            else -> {
                val area = Geometry.contourArea(contour)
                val contour2f = MatOfPoint2f(*contour.toArray())
                val peri = Geometry.arcLength(contour2f, true)
                contour2f.release()
                val circularity = if (peri > 0) (4 * Math.PI * area) / (peri * peri) else 0.0
                if (circularity > 0.75) "Circle" else "Object"
            }
        }
    }
}


@Composable
fun CameraFinderScreen() {
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