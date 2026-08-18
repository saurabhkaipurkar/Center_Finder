package com.myworkshopy.centerfinder

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencv.core.Mat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ShapeViewModel : ViewModel() {
    private val detector = ShapeDetector()
    private val smoother = DetectionSmoother()
    private val _cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    val cameraExecutor: ExecutorService get() = _cameraExecutor

    private val _detectionFrame = MutableStateFlow<FrameDetection?>(null)
    val detectionFrame: StateFlow<FrameDetection?> = _detectionFrame.asStateFlow()

    private val _stableName = MutableStateFlow<String?>(null)
    val stableName: StateFlow<String?> = _stableName.asStateFlow()

    fun onFrameAnalyzed(mat: Mat, rotationDegrees: Int) {
        try {
            val result = detector.detect(mat, rotationDegrees)
            val smoothed = smoother.stabilizeFrame(result)
            val label = smoother.stabilizeName(smoothed.primary?.name)
            _detectionFrame.value = smoothed
            _stableName.value = label
        } catch (_: Throwable) {
            // keep last frame rather than crashing the analyzer thread
        } finally {
            mat.release()
        }
    }

    override fun onCleared() {
        super.onCleared()
        _cameraExecutor.shutdown()
    }
}
