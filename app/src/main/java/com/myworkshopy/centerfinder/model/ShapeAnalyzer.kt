package com.myworkshopy.centerfinder.model

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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