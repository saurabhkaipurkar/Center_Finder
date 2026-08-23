package com.myworkshopy.centerfinder



import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.Rect as CvRect
import org.opencv.geometry.Geometry
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect as ComposeRect

/**
 * Lets the user drag a selection box over the live camera feed and get an
 * exact count of however many discrete items (pencils, coins, holes, screws...)
 * fall inside it. Reuses the same imageProxyToMat(...) helper as ShapeDetector.
 */
@Composable
fun ObjectCounterScreen() {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val scope = rememberCoroutineScope()

    val frameHolder = remember { FrameHolder() }

    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    var count by remember { mutableStateOf<Int?>(null) }
    var markersScreen by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var resultBoxScreen by remember { mutableStateOf<ComposeRect?>(null) }
    var isCounting by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            frameHolder.release()
        }
    }

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
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        android.util.Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()
                        )
                        .build()
                        .also { useCase ->
                            useCase.setAnalyzer(cameraExecutor) { imageProxy ->
                                try {
                                    val plane = imageProxy.planes[0]
                                    val mat = imageProxyToMat(
                                        imageProxy.width,
                                        imageProxy.height,
                                        plane.rowStride,
                                        plane.buffer
                                    )
                                    if (mat != null) {
                                        val upright = rotateMat(mat as Mat, imageProxy.imageInfo.rotationDegrees)
                                        if (upright !== mat) mat.release()
                                        frameHolder.update(upright)
                                        val w = upright.cols()
                                        val h = upright.rows()
                                        mainExecutor.execute {
                                            if (imageSize.width != w || imageSize.height != h) {
                                                imageSize = IntSize(w, h)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("ObjectCounter", "Frame capture error", e)
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
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { previewSize = it }
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragCurrent = offset
                            count = null
                            markersScreen = emptyList()
                            resultBoxScreen = null
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragCurrent = change.position
                        }
                    )
                }
        ) {
            val start = dragStart
            val current = dragCurrent
            if (start != null && current != null) {
                val topLeft = Offset(minOf(start.x, current.x), minOf(start.y, current.y))
                val boxSize = ComposeSize(abs(current.x - start.x), abs(current.y - start.y))
                drawRect(
                    color = Color(0xFF4FC3F7),
                    topLeft = topLeft,
                    size = boxSize,
                    style = Stroke(width = 3f)
                )
            }

            resultBoxScreen?.let { r ->
                drawRect(
                    color = Color(0xFF69F0AE),
                    topLeft = r.topLeft,
                    size = ComposeSize(r.width, r.height),
                    style = Stroke(width = 3f)
                )
            }

            for (marker in markersScreen) {
                drawCircle(color = Color(0xFFFF5252), radius = 5f, center = marker)
            }
        }

        Text(
            text = "Drag a box around the items to count",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        count?.let { c ->
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .wrapContentSize(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                )
            ) {
                Text(
                    text = "Count: $c",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = {
                    val start = dragStart
                    val current = dragCurrent
                    if (start == null || current == null) return@Button
                    val viewW = previewSize.width.toFloat()
                    val viewH = previewSize.height.toFloat()
                    val imgW = imageSize.width
                    val imgH = imageSize.height
                    if (viewW <= 0f || viewH <= 0f || imgW <= 0 || imgH <= 0) return@Button

                    val screenRect = ComposeRect(
                        left = minOf(start.x, current.x),
                        top = minOf(start.y, current.y),
                        right = maxOf(start.x, current.x),
                        bottom = maxOf(start.y, current.y)
                    )
                    if (screenRect.width < 12f || screenRect.height < 12f) return@Button

                    isCounting = true
                    scope.launch {
                        val mat = frameHolder.latestClone()
                        if (mat == null) {
                            isCounting = false
                            return@launch
                        }
                        val result = withContext(Dispatchers.Default) {
                            try {
                                val imageRoi = screenRectToImageRect(screenRect, viewW, viewH, imgW, imgH)
                                ClusterCounter.count(mat, imageRoi)
                            } finally {
                                mat.release()
                            }
                        }
                        count = result.count
                        markersScreen = result.centers.map { (x, y) ->
                            // centers are ROI-local; shift back to full-image coords, then to screen.
                            val imageRoi = screenRectToImageRect(screenRect, viewW, viewH, imgW, imgH)
                            imagePointToScreen(
                                imageRoi.x + x, imageRoi.y + y,
                                viewW, viewH, imgW, imgH
                            )
                        }
                        resultBoxScreen = screenRect
                        isCounting = false
                    }
                },
                enabled = !isCounting && dragStart != null && dragCurrent != null
            ) {
                Text(if (isCounting) "Counting…" else "Count items")
            }
        }
    }
}

/** Holds the most recent analyzed frame off the Compose state graph. */
private class FrameHolder {
    @Volatile private var mat: Mat? = null
    private val lock = Any()

    fun update(newMat: Mat) {
        synchronized(lock) {
            mat?.release()
            mat = newMat
        }
    }

    fun latestClone(): Mat? = synchronized(lock) { mat?.clone() }

    fun release() = synchronized(lock) {
        mat?.release()
        mat = null
    }
}

/**
 * Converts an RGBA_8888 ImageProxy plane into a CV_8UC4 Mat.
 * CameraX often pads each row, so [rowStride] may be larger than width * 4.
 */
private fun imageProxyToMat(
    width: Int,
    height: Int,
    rowStride: Int,
    buffer: ByteBuffer
): Mat? {
    if (width <= 0 || height <= 0) return null
    val channels = 4
    val rowBytes = width * channels
    val mat = Mat(height, width, CvType.CV_8UC4)
    val src = buffer.duplicate()
    src.rewind()
    return try {
        if (rowStride == rowBytes) {
            val data = ByteArray(rowBytes * height)
            src.get(data)
            mat.put(0, 0, data)
        } else {
            val data = ByteArray(rowBytes * height)
            val row = ByteArray(rowStride)
            var dstOffset = 0
            for (y in 0 until height) {
                val remaining = src.remaining()
                if (remaining <= 0) break
                val toRead = minOf(rowStride, remaining)
                src.get(row, 0, toRead)
                System.arraycopy(row, 0, data, dstOffset, minOf(rowBytes, toRead))
                dstOffset += rowBytes
            }
            mat.put(0, 0, data)
        }
        mat
    } catch (_: Exception) {
        mat.release()
        null
    }
}

private fun rotateMat(src: Mat, degrees: Int): Mat {
    return when (((degrees % 360) + 360) % 360) {
        90 -> Mat().also { org.opencv.core.Core.rotate(src, it, org.opencv.core.Core.ROTATE_90_CLOCKWISE) }
        180 -> Mat().also { org.opencv.core.Core.rotate(src, it, org.opencv.core.Core.ROTATE_180) }
        270 -> Mat().also { org.opencv.core.Core.rotate(src, it, org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE) }
        else -> src
    }
}

/** Screen (view) pixel rect -> image (Mat) pixel rect, inverse of the FILL_CENTER mapping. */
private fun screenRectToImageRect(
    screenRect: ComposeRect,
    viewW: Float,
    viewH: Float,
    imgW: Int,
    imgH: Int
): CvRect {
    val scale = max(viewW / imgW, viewH / imgH)
    val dx = (viewW - imgW * scale) / 2f
    val dy = (viewH - imgH * scale) / 2f
    val left = ((screenRect.left - dx) / scale).toInt().coerceIn(0, imgW - 1)
    val top = ((screenRect.top - dy) / scale).toInt().coerceIn(0, imgH - 1)
    val right = ((screenRect.right - dx) / scale).toInt().coerceIn(left + 1, imgW)
    val bottom = ((screenRect.bottom - dy) / scale).toInt().coerceIn(top + 1, imgH)
    return CvRect(left, top, right - left, bottom - top)
}

/** Image (Mat) pixel point -> screen (view) pixel point, matching PreviewView's FILL_CENTER. */
private fun imagePointToScreen(
    x: Float,
    y: Float,
    viewW: Float,
    viewH: Float,
    imgW: Int,
    imgH: Int
): Offset {
    val scale = max(viewW / imgW, viewH / imgH)
    val dx = (viewW - imgW * scale) / 2f
    val dy = (viewH - imgH * scale) / 2f
    return Offset(x * scale + dx, y * scale + dy)
}

/**
 * Counts individual roughly-circular/uniform objects inside a selected region,
 * even when they touch each other (pencil bundles, coin piles, drilled holes,
 * bolts in a tray, etc.).
 *
 * Why not just findContours + count?
 * When objects touch, their contours merge into one big blob and you get "1"
 * instead of "37". Instead this:
 *   1. Thresholds the region into foreground/background (auto polarity).
 *   2. Runs a distance transform, so each object's foreground pixels form a
 *      "hill" that peaks at its center and drops toward its edges/neighbors.
 *   3. Thresholds the distance map at a fraction of its peak -> isolates one
 *      small blob per object, splitting touching objects apart.
 *   4. Counts connected components of those peak blobs = object count, and
 *      returns their centroids.
 */
object ClusterCounter {

    data class CountResult(
        val count: Int,
        /** Centers in ROI-local pixel coordinates (origin = ROI top-left). */
        val centers: List<Pair<Float, Float>>,
        val roiWidth: Int,
        val roiHeight: Int
    )

    /**
     * @param source Full-frame RGBA Mat (already rotated to display orientation).
     * @param roi Region within [source], in source pixel coordinates.
     * @param objectsDarkerThanBackground Null = auto-detect from corner samples.
     * @param peakThresholdRatio Lower = more sensitive to weakly-separated objects
     *   but more prone to splitting one object into two. 0.35–0.55 is a good range.
     * @param minComponentArea Discards tiny noise blobs, in pixels.
     */
    fun count(
        source: Mat,
        roi: Rect,
        objectsDarkerThanBackground: Boolean? = null,
        peakThresholdRatio: Double = 0.45,
        minComponentArea: Double = 10.0
    ): CountResult {
        val safeRoi = clampRect(roi, source.cols(), source.rows())
        if (safeRoi.width <= 4 || safeRoi.height <= 4) {
            return CountResult(0, emptyList(), safeRoi.width, safeRoi.height)
        }

        val cropped = Mat(source, safeRoi)
        val gray = Mat()
        Imgproc.cvtColor(cropped, gray, Imgproc.COLOR_RGBA2GRAY)

        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val darker = objectsDarkerThanBackground ?: detectPolarity(blurred)

        val binary = Mat()
        val threshType = if (darker) {
            Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU
        } else {
            Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU
        }
        Imgproc.threshold(blurred, binary, 0.0, 255.0, threshType)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_OPEN, kernel, org.opencv.core.Point(-1.0, -1.0), 2)

        val dist = Mat()
        Imgproc.distanceTransform(binary, dist, Geometry.DIST_L2, 5)

        val maxVal = Core.minMaxLoc(dist).maxVal
        if (maxVal <= 0.0) {
            listOf(gray, blurred, binary, dist, cropped).forEach { it.release() }
            return CountResult(0, emptyList(), safeRoi.width, safeRoi.height)
        }

        val peaks = Mat()
        Imgproc.threshold(dist, peaks, peakThresholdRatio * maxVal, 255.0, Imgproc.THRESH_BINARY)
        val peaks8u = Mat()
        peaks.convertTo(peaks8u, CvType.CV_8U)

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val numLabels = Imgproc.connectedComponentsWithStats(peaks8u, labels, stats, centroids, 8, CvType.CV_32S)

        val centers = mutableListOf<Pair<Float, Float>>()
        for (label in 1 until numLabels) { // label 0 = background
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
            if (area < minComponentArea) continue
            val cx = centroids.get(label, 0)[0].toFloat()
            val cy = centroids.get(label, 1)[0].toFloat()
            centers.add(cx to cy)
        }

        listOf(gray, blurred, binary, dist, peaks, peaks8u, labels, stats, centroids, cropped)
            .forEach { it.release() }

        return CountResult(centers.size, centers, safeRoi.width, safeRoi.height)
    }

    /** Samples the four corners as a background estimate vs the overall mean. */
    private fun detectPolarity(gray: Mat): Boolean {
        val corner = minOf(8, gray.cols() / 4, gray.rows() / 4)
        if (corner < 2) return true
        val w = gray.cols()
        val h = gray.rows()
        val patches = listOf(
            Mat(gray, Rect(0, 0, corner, corner)),
            Mat(gray, Rect(w - corner, 0, corner, corner)),
            Mat(gray, Rect(0, h - corner, corner, corner)),
            Mat(gray, Rect(w - corner, h - corner, corner, corner))
        )
        val bgMean = patches.map { Core.mean(it).`val`[0] }.average()
        patches.forEach { it.release() }
        val overallMean = Core.mean(gray).`val`[0]
        // Corners brighter than the overall average -> background is the bright
        // part -> objects are the darker part.
        return bgMean > overallMean
    }

    private fun clampRect(r: Rect, maxW: Int, maxH: Int): Rect {
        val x = r.x.coerceIn(0, maxW - 1)
        val y = r.y.coerceIn(0, maxH - 1)
        val w = r.width.coerceIn(1, maxW - x)
        val h = r.height.coerceIn(1, maxH - y)
        return Rect(x, y, w, h)
    }
}