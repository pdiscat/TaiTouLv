package com.example.taitoulv

import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executor

/** 一帧里检测到的一张脸：坐标已映射到 PreviewView 坐标系，同时带上做统计需要的量。 */
data class FaceObservation(
    val trackingId: Int?,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pitchDegrees: Float,
    /** 左右眼「睁开概率」的较小值；没开分类模式或侧脸过头时为 null */
    val eyesOpenProbability: Float?,
    /** 在「送进检测器的那张图」里的宽度（像素）——用来判断机位是否太远 */
    val analysisWidthPx: Int
)

data class DetectionResult(
    val observations: List<FaceObservation> = emptyList(),
    val boxesMapped: Boolean = false
)

/**
 * 抬头率检测：ML Kit 人脸检测（含关键点 + 睁眼/微笑分类）+ 俯仰角判定。
 *
 * 坐标换算交给 CameraX 官方的 [MlKitAnalyzer]（`COORDINATE_SYSTEM_VIEW_REFERENCED`），
 * 用裸 ImageAnalysis 时 CameraX 不会自动喂矩阵，需要外部调 [updateTransform]
 * 传入 `PreviewView.getSensorToViewTransform()`——`CameraController` 内部就是这么做的。
 *
 * 这里只负责「检测 + 出原始观测」；状态机（低头事件、趴桌、窗口统计）在 [AttentionTracker] 里。
 */
class FaceAnalyzer(
    private val onResult: (DetectionResult) -> Unit,
    executor: Executor,
    /** 每秒最多送多少帧给 ML Kit；统计用 2Hz 采样就够，限帧能明显降功耗和发热 */
    maxFps: Int = DEFAULT_MAX_FPS
) {
    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            // 允许更小的人脸，教室后排的脸在画面里占比很小
            .setMinFaceSize(0.05f)
            .enableTracking()
            // 关键点 + 分类（微笑/睁眼概率）：趴桌判定与 PERCLOS 疲劳指标要用
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    private val mlKitAnalyzer: MlKitAnalyzer = MlKitAnalyzer(
        listOf(detector),
        ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
        executor
    ) { result -> handle(result) }

    private val minFrameIntervalMs = if (maxFps <= 0) 0L else 1000L / maxFps
    private var lastProcessedMs = 0L

    @Volatile
    private var transformReady = false

    /** 交给 `ImageAnalysis.setAnalyzer()` 用（外面套一层限帧） */
    val analyzer: ImageAnalysis.Analyzer = object : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastProcessedMs >= minFrameIntervalMs) {
                lastProcessedMs = now
                mlKitAnalyzer.analyze(imageProxy)
            } else {
                // 超过帧率上限的帧直接丢掉：统计用 2Hz 采样就够，限帧能显著降功耗与发热
                imageProxy.close()
            }
        }

        // 这几个必须转发给 MlKitAnalyzer，否则 CameraX 拿不到 VIEW_REFERENCED 坐标系
        override fun getTargetCoordinateSystem(): Int = mlKitAnalyzer.targetCoordinateSystem

        override fun getDefaultTargetResolution(): Size? = mlKitAnalyzer.defaultTargetResolution

        override fun updateTransform(matrix: Matrix?) = mlKitAnalyzer.updateTransform(matrix)
    }

    /** 预览就绪 / 尺寸变化（含横竖屏切换）后调用，传入 `PreviewView.sensorToViewTransform` */
    fun updateTransform(matrix: Matrix?) {
        transformReady = matrix != null
        mlKitAnalyzer.updateTransform(matrix)
    }

    private fun handle(result: MlKitAnalyzer.Result?) {
        val faces: List<Face> = runCatching { result?.getValue(detector) }.getOrNull().orEmpty()

        val observations = faces.map { face ->
            val rect = face.boundingBox
            val eyes = minOfNotNull(face.leftEyeOpenProbability, face.rightEyeOpenProbability)
            FaceObservation(
                trackingId = face.trackingId,
                left = rect.left.toFloat(),
                top = rect.top.toFloat(),
                right = rect.right.toFloat(),
                bottom = rect.bottom.toFloat(),
                pitchDegrees = face.headEulerAngleX,
                eyesOpenProbability = eyes,
                analysisWidthPx = rect.width()
            )
        }

        onResult(DetectionResult(observations = observations, boxesMapped = transformReady))
    }

    private fun minOfNotNull(a: Float?, b: Float?): Float? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    fun close() {
        detector.close()
    }

    companion object {
        /** 默认限帧：15fps 足够做课堂统计，比不限帧省电得多 */
        const val DEFAULT_MAX_FPS = 15
    }
}
