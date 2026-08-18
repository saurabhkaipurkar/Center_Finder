package com.myworkshopy.centerfinder

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

data class DetectedShape(
    val name: String,
    val confidence: Float,
    val centerX: Float,
    val centerY: Float,
    val radius: Float? = null,
    val points: List<Pair<Float, Float>> = emptyList()
)

data class FrameDetection(
    val primary: DetectedShape?,
    val extras: List<DetectedShape> = emptyList(),
    val imageWidth: Int,
    val imageHeight: Int
)

private data class ScoredShape(
    val shape: DetectedShape,
    val score: Double,
    val area: Double
)

/**
 * Finds the object you are pointing at, traces its border, and marks the center.
 *
 * Classification (square / circle / hexagon / honeycomb) is optional. If the
 * object is not a clean primitive we still return it as "Object" so the outline
 * and crosshair always appear.
 */
class ShapeDetector(
    private val minAreaFraction: Float = 0.004f,
    private val maxCandidates: Int = 8
) {

    fun detect(inputRgba: Mat, rotationDegrees: Int): FrameDetection {
        val rotated = rotate(inputRgba, rotationDegrees)
        val imgW = rotated.cols()
        val imgH = rotated.rows()
        val empty = FrameDetection(null, emptyList(), imgW, imgH)
        return try {
            detectUnsafe(rotated, imgW, imgH)
        } catch (t: Throwable) {
            empty
        } finally {
            if (rotated !== inputRgba) rotated.release()
        }
    }

    private fun detectUnsafe(rotated: Mat, imgW: Int, imgH: Int): FrameDetection {
        val frameArea = (imgW * imgH).toDouble()
        val minArea = frameArea * minAreaFraction

        val gray = Mat()
        Imgproc.cvtColor(rotated, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val binaries = buildBinaries(gray)
        val all = ArrayList<ScoredShape>()
        for (binary in binaries) {
            collectFromBinary(binary, imgW, imgH, minArea, frameArea, all)
        }

        gray.release()
        binaries.forEach { it.release() }

        val unique = dedupe(all)
        val hexes = unique.map { it.shape }.filter { it.name == "Hexagon" }
        val honeycomb = maybeHoneycomb(hexes)
        if (honeycomb != null) {
            return FrameDetection(honeycomb.first, honeycomb.second, imgW, imgH)
        }
        if (unique.isEmpty()) {
            return FrameDetection(null, emptyList(), imgW, imgH)
        }
        val ranked = unique.sortedByDescending { it.score }
        return FrameDetection(ranked.first().shape, ranked.drop(1).take(3).map { it.shape }, imgW, imgH)
    }

    private fun buildBinaries(blurred: Mat): List<Mat> {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        val out = ArrayList<Mat>(3)

        val otsu = Mat()
        Imgproc.threshold(blurred, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_CLOSE, kernel)
        out += otsu

        val otsuFlip = Mat()
        Core.bitwise_not(otsu, otsuFlip)
        out += otsuFlip

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 25.0, 80.0)
        Imgproc.dilate(edges, edges, kernel)
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
        out += edges

        kernel.release()
        return out
    }

    private fun collectFromBinary(
        binary: Mat,
        imgW: Int,
        imgH: Int,
        minArea: Double,
        frameArea: Double,
        sink: MutableList<ScoredShape>
    ) {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        for (contour in contours) {
            val area = abs(Geometry.contourArea(contour))
            if (area < minArea) continue
            if (isLikelyFrameBorder(contour, imgW, imgH, area, frameArea)) continue
            val scored = classifyOrObject(contour, area, imgW, imgH) ?: continue
            sink += scored
        }
        contours.forEach { it.release() }
        hierarchy.release()
    }

    /**
     * Always returns the contour as a drawable object. Name is a known shape
     * when the fit is good, otherwise "Object".
     */
    private fun classifyOrObject(contour: MatOfPoint, area: Double, imgW: Int, imgH: Int): ScoredShape? {
        val raw = contour.toArray()
        if (raw.size < 3) return null
        val contour2f = MatOfPoint2f(*raw)
        val perimeter = Geometry.arcLength(contour2f, true)
        if (perimeter < 24.0) {
            contour2f.release()
            return null
        }

        val outline = simplifyOutline(contour2f, perimeter)
        val moments = Geometry.moments(contour)
        val cx = if (moments.m00 != 0.0) (moments.m10 / moments.m00) else outline.map { it.x }.average()
        val cy = if (moments.m00 != 0.0) (moments.m01 / moments.m00) else outline.map { it.y }.average()

        val circularity = ShapeMath.circularity(area, perimeter)
        val rotRect = Geometry.minAreaRect(contour2f)
        val boxW = rotRect.size.width
        val boxH = rotRect.size.height
        val boxArea = (boxW * boxH).coerceAtLeast(1.0)
        val rectFill = (area / boxArea).coerceIn(0.0, 1.2)
        val boxAspect = ShapeMath.aspect(boxW, boxH)
        val boxCorners = Array(4) { Point() }
        rotRect.points(boxCorners)

        val approx = MatOfPoint2f()
        Geometry.approxPolyDP(contour2f, approx, 0.03 * perimeter, true)
        val approxPts = approx.toArray()
        approx.release()

        val encCenter = Point()
        val encRadius = FloatArray(1)
        Geometry.minEnclosingCircle(contour2f, encCenter, encRadius)
        val encR = encRadius[0].toDouble().coerceAtLeast(1.0)
        val circleFill = (area / (PI * encR * encR)).coerceIn(0.0, 1.2)
        contour2f.release()

        val quadVecs = boxCorners.map { Vec2(it.x, it.y) }
        val hexVecs = if (approxPts.size == 6) approxPts.map { Vec2(it.x, it.y) } else null
        val rectQuality = ShapeMath.quadScore(quadVecs) * rectFill.coerceAtMost(1.0)
        val hexQuality = hexVecs?.let { ShapeMath.hexagonScore(it) } ?: 0.0

        val outlinePairs = outline.map { it.x.toFloat() to it.y.toFloat() }
        val shape: DetectedShape = when {
            circularity > 0.84 && circleFill > 0.72 && boxAspect <= 1.22 -> {
                DetectedShape("Circle", circularity.toFloat().coerceIn(0.6f, 0.99f), cx.toFloat(), cy.toFloat(), encR.toFloat(), outlinePairs)
            }
            circularity > 0.70 && boxAspect > 1.22 -> {
                DetectedShape("Oval", (circularity * 0.9).toFloat().coerceIn(0.5f, 0.95f), cx.toFloat(), cy.toFloat(), points = outlinePairs)
            }
            approxPts.size == 4 && rectQuality > 0.42 && rectFill > 0.55 -> {
                val name = if (ShapeMath.isSquareAspect(boxAspect)) "Square" else "Rectangle"
                DetectedShape(name, rectQuality.toFloat().coerceIn(0.5f, 0.98f), rotRect.center.x.toFloat(), rotRect.center.y.toFloat(), points = boxCorners.map { it.x.toFloat() to it.y.toFloat() })
            }
            approxPts.size in 4..5 && rectQuality > 0.50 && rectFill > 0.60 -> {
                val name = if (ShapeMath.isSquareAspect(boxAspect)) "Square" else "Rectangle"
                DetectedShape(name, rectQuality.toFloat().coerceIn(0.48f, 0.95f), rotRect.center.x.toFloat(), rotRect.center.y.toFloat(), points = boxCorners.map { it.x.toFloat() to it.y.toFloat() })
            }
            hexQuality > 0.48 && hexVecs != null -> {
                DetectedShape("Hexagon", hexQuality.toFloat().coerceIn(0.48f, 0.96f), cx.toFloat(), cy.toFloat(), points = hexVecs.map { it.toPair() })
            }
            else -> {
                DetectedShape("Object", 0.55f, cx.toFloat(), cy.toFloat(), points = outlinePairs)
            }
        }

        val aim = ShapeMath.aimScore(shape.centerX.toDouble(), shape.centerY.toDouble(), imgW, imgH)
        return ScoredShape(shape, area * aim * (0.55 + 0.45 * shape.confidence), area)
    }

    private fun simplifyOutline(contour2f: MatOfPoint2f, perimeter: Double): Array<Point> {
        val approx = MatOfPoint2f()
        var eps = 0.006 * perimeter
        var pts = emptyArray<Point>()
        for (i in 0 until 6) {
            Geometry.approxPolyDP(contour2f, approx, eps, true)
            pts = approx.toArray()
            if (pts.size <= 72) break
            eps *= 1.45
        }
        approx.release()
        return if (pts.size >= 3) pts else contour2f.toArray()
    }

    private fun maybeHoneycomb(hexes: List<DetectedShape>): Pair<DetectedShape, List<DetectedShape>>? {
        if (hexes.size < 3) return null
        val areas = hexes.map { hexArea(it) }
        val median = areas.sorted()[areas.size / 2].coerceAtLeast(1.0)
        val similar = hexes.filter { ShapeMath.ratio(hexArea(it), median) > 0.45 }
        if (similar.size < 3) return null

        val cx = similar.map { it.centerX }.average().toFloat()
        val cy = similar.map { it.centerY }.average().toFloat()
        val hull = convexHullPairs(similar.flatMap { it.points.ifEmpty { ringAround(it) } })
        val conf = similar.map { it.confidence }.average().toFloat()
        val honeycomb = DetectedShape(
            name = "Honeycomb",
            confidence = conf.coerceIn(0.55f, 0.98f),
            centerX = cx,
            centerY = cy,
            points = hull
        )
        return honeycomb to similar
    }

    private fun convexHullPoints(contour: MatOfPoint): Array<Point> {
        val idx = MatOfInt()
        Geometry.convexHull(contour, idx)
        val pts = contour.toArray()
        val ids = idx.toArray()
        idx.release()
        if (ids.isEmpty()) return pts
        return Array(ids.size) { pts[ids[it].coerceIn(0, pts.lastIndex)] }
    }

    private fun isLikelyFrameBorder(
        contour: MatOfPoint,
        imgW: Int,
        imgH: Int,
        area: Double,
        frameArea: Double
    ): Boolean {
        if (area < frameArea * 0.55) return false
        val r = Geometry.boundingRect(contour)
        val touches = listOf(
            r.x <= 2,
            r.y <= 2,
            r.x + r.width >= imgW - 2,
            r.y + r.height >= imgH - 2
        ).count { it }
        return touches >= 3
    }

    private fun dedupe(items: List<ScoredShape>): List<ScoredShape> {
        val sorted = items.sortedByDescending { it.score }
        val kept = ArrayList<ScoredShape>()
        for (item in sorted) {
            val dup = kept.any { other ->
                val d = hypot(
                    (other.shape.centerX - item.shape.centerX).toDouble(),
                    (other.shape.centerY - item.shape.centerY).toDouble()
                )
                val size = (equivalentSize(other.shape) + equivalentSize(item.shape)) / 2.0
                d < 0.28 * size
            }
            if (!dup) kept += item
        }
        return kept.take(maxCandidates)
    }

    private fun equivalentSize(shape: DetectedShape): Double {
        shape.radius?.let { return it * 2.0 }
        if (shape.points.size >= 2) {
            val xs = shape.points.map { it.first }
            val ys = shape.points.map { it.second }
            return hypot((xs.max() - xs.min()).toDouble(), (ys.max() - ys.min()).toDouble())
        }
        return 40.0
    }

    private fun hexArea(shape: DetectedShape): Double {
        val r = shape.radius
        if (r != null) return PI * r * r
        if (shape.points.size < 3) return 1.0
        val pts = shape.points
        var sum = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.first * b.second - b.first * a.second
        }
        return abs(sum) / 2.0
    }

    private fun ringAround(shape: DetectedShape): List<Pair<Float, Float>> {
        val r = 18.0
        return ShapeMath.regularPolygon(Vec2(shape.centerX.toDouble(), shape.centerY.toDouble()), r, 6, 0.0)
            .map { it.toPair() }
    }

    private fun convexHullPairs(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (pts.size < 3) return pts
        val mat = MatOfPoint(*pts.map { Point(it.first.toDouble(), it.second.toDouble()) }.toTypedArray())
        val hull = convexHullPoints(mat).map { it.x.toFloat() to it.y.toFloat() }
        mat.release()
        return hull
    }

    private fun rotate(src: Mat, degrees: Int): Mat {
        return when (((degrees % 360) + 360) % 360) {
            90 -> Mat().also { Core.rotate(src, it, Core.ROTATE_90_CLOCKWISE) }
            180 -> Mat().also { Core.rotate(src, it, Core.ROTATE_180) }
            270 -> Mat().also { Core.rotate(src, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
            else -> src
        }
    }
}

class DetectionSmoother(
    private val windowSize: Int = 7,
    private val requiredAgreement: Int = 3,
    private val geometryAlpha: Float = 0.45f
) {
    private val recent = ArrayDeque<String?>()
    private var stable: String? = null
    private var lastFrame: FrameDetection? = null
    private var missCount = 0

    fun stabilizeName(name: String?): String? {
        recent.addLast(name)
        if (recent.size > windowSize) recent.removeFirst()

        val counts = recent.filterNotNull().groupingBy { it }.eachCount()
        val best = counts.maxByOrNull { it.value }
        if (best != null && best.value >= requiredAgreement) {
            stable = best.key
        } else if (recent.count { it == null } > windowSize / 2) {
            stable = null
        }
        return name ?: stable
    }

    fun stabilizeFrame(raw: FrameDetection): FrameDetection {
        val prev = lastFrame
        val primary = blendShape(prev?.primary, raw.primary)
        val extras = if (raw.primary?.name == "Honeycomb" && prev?.extras?.isNotEmpty() == true) {
            blendExtras(prev.extras, raw.extras)
        } else {
            raw.extras
        }
        val out = raw.copy(primary = primary, extras = extras)
        lastFrame = out
        return out
    }

    private fun blendShape(prev: DetectedShape?, next: DetectedShape?): DetectedShape? {
        if (next == null) {
            missCount++
            return if (prev != null && missCount < 10) prev else null
        }
        missCount = 0
        val sameTarget = prev != null && hypot(
            (prev.centerX - next.centerX).toDouble(),
            (prev.centerY - next.centerY).toDouble()
        ) < 80.0
        if (prev == null || (!sameTarget && prev.name != next.name)) return next
        val a = geometryAlpha
        val points = if (prev.points.size == next.points.size && prev.points.isNotEmpty()) {
            ShapeMath.blendPoints(prev.points, next.points, a)
        } else {
            next.points
        }
        val radius = if (prev.radius != null && next.radius != null) {
            ShapeMath.lerp(prev.radius, next.radius, a)
        } else {
            next.radius
        }
        return next.copy(
            centerX = ShapeMath.lerp(prev.centerX, next.centerX, a),
            centerY = ShapeMath.lerp(prev.centerY, next.centerY, a),
            radius = radius,
            points = points,
            confidence = ShapeMath.lerp(prev.confidence, next.confidence, 0.35f)
        )
    }

    private fun blendExtras(prev: List<DetectedShape>, next: List<DetectedShape>): List<DetectedShape> {
        return next.map { cell ->
            val match = prev.minByOrNull {
                hypot((it.centerX - cell.centerX).toDouble(), (it.centerY - cell.centerY).toDouble())
            }
            val close = match != null && hypot(
                (match.centerX - cell.centerX).toDouble(),
                (match.centerY - cell.centerY).toDouble()
            ) < 40.0
            if (close) blendShape(match, cell) ?: cell else cell
        }
    }

    fun reset() {
        recent.clear()
        stable = null
        lastFrame = null
        missCount = 0
    }
}

internal fun imageProxyToMat(
    width: Int,
    height: Int,
    rowStride: Int,
    buffer: java.nio.ByteBuffer
): Mat? {
    return try {
        val mat = Mat(height, width, CvType.CV_8UC4)
        if (rowStride == width * 4) {
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            mat.put(0, 0, data)
        } else {
            val data = ByteArray(width * height * 4)
            val rowData = ByteArray(rowStride)
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                val bytesToRead = if (row == height - 1) min(rowStride, buffer.remaining()) else rowStride
                buffer.get(rowData, 0, bytesToRead)
                System.arraycopy(rowData, 0, data, row * width * 4, width * 4)
            }
            mat.put(0, 0, data)
        }
        mat
    } catch (_: Exception) {
        null
    }
}
