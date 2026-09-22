package com.example.taitoulv

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Observer
import com.example.taitoulv.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 手机上一颗可用摄像头的描述 */
private data class CameraOption(
    val id: String,
    val lensFacing: Int,
    val fovDegrees: Float,
    val label: String
)

private data class RawCamera(val id: String, val lensFacing: Int, val fovDegrees: Float)

/** 屏幕方向：自动跟随传感器 / 锁横屏 / 锁竖屏 */
private enum class OrientationMode { AUTO, LANDSCAPE, PORTRAIT }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var analyzer: FaceAnalyzer? = null

    // 行为统计：状态机（低头/趴桌事件、窗口抬头率）+ CSV 记录
    private lateinit var tracker: AttentionTracker
    private lateinit var recorder: CsvRecorder

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var cameras: List<CameraOption> = emptyList()
    private var selectedCameraId: String? = null
    private var bindRetry = 0
    private val cameraButtons = mutableMapOf<String, MaterialButton>()

    // 变焦档位按钮（比例 -> 按钮），用来高亮当前档位
    private val zoomButtons = mutableListOf<Pair<Float, MaterialButton>>()
    private var zoomRangeBuilt: Pair<Float, Float>? = null
    private var draggingZoom = false

    // 重建（横竖屏切换）后要恢复的变焦倍数
    private var pendingZoomRatio: Float? = null
    private var transformLogged = false

    private val zoomObserver = Observer<ZoomState> { state -> updateZoomUi(state) }

    // 俯仰角低于这个度数就算「低头」。headEulerAngleX 正值=抬头，所以阈值取负值。
    @Volatile
    private var headDownThreshold = DEFAULT_THRESHOLD

    private var orientationMode = OrientationMode.AUTO

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.permissionPanel.visibility = View.GONE
                startCamera()
            } else {
                binding.permissionPanel.visibility = View.VISIBLE
                Toast.makeText(this, R.string.need_camera_permission, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 监测期间不熄屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 横屏走沉浸式真全屏（隐藏状态栏/导航栏）
        setupWindowInsets()

        // 点屏幕空白处可以隐藏/显示控件面板
        setupPanelToggle()

        // 恢复上次的摄像头选择与变焦倍数（横竖屏切换会重建 Activity，不恢复就等于重置）
        selectedCameraId = prefs.getString(KEY_CAMERA_ID, null)
        prefs.getFloat(KEY_ZOOM, -1f).takeIf { it > 0f }?.let { pendingZoomRatio = it }

        // 行为统计 + CSV 记录（记录器是进程级单例，转屏不会另起文件）
        tracker = AttentionTracker(
            headDownThresholdProvider = { headDownThreshold },
            lyingPitch = LYING_PITCH,
            eyesClosedProb = EYES_CLOSED_PROB,
            headDownEventMs = HEAD_DOWN_EVENT_MS,
            lyingEventMs = LYING_EVENT_MS
        )
        recorder = CsvRecorder.get(this)
        Log.i(TAG, "数据目录：${recorder.directoryPath}")
        binding.recordButton.setOnClickListener { toggleRecording() }
        updateRecordButton()

        analyzer = FaceAnalyzer(
            onResult = { result -> handleDetection(result) },
            executor = analysisExecutor
        )

        // 预览开始出画面、以及布局变化（横竖屏/窗口尺寸）后，都要重新喂一次坐标变换
        binding.previewView.previewStreamState.observe(this) { state ->
            if (state == PreviewView.StreamState.STREAMING) refreshAnalyzerTransform()
        }
        binding.previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            refreshAnalyzerTransform()
        }

        binding.thresholdSeekBar.max = THRESHOLD_STEPS
        binding.thresholdSeekBar.progress = ((DEFAULT_THRESHOLD - MIN_THRESHOLD) / THRESHOLD_STEP).toInt()
        binding.thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                headDownThreshold = MIN_THRESHOLD + progress * THRESHOLD_STEP
                showThreshold()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        showThreshold()

        binding.zoomSeekBar.max = 100
        binding.zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                camera?.cameraControl?.setLinearZoom(progress / 100f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                draggingZoom = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                draggingZoom = false
                persistZoom()
            }
        })

        binding.grantPermissionButton.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // 方向：默认「自动」= 跟方向传感器走（Manifest 里的 screenOrientation="sensor"，
        // 会无视系统「自动旋转」开关）；手机平放在讲台上时传感器可能判不准，可手动锁横屏。
        orientationMode = prefs.getString(KEY_ORIENTATION, null)
            ?.let { name -> OrientationMode.entries.firstOrNull { it.name == name } }
            ?: OrientationMode.AUTO
        applyOrientationMode(orientationMode, persist = false)

        binding.orientationButton.setOnClickListener {
            val next = when (orientationMode) {
                OrientationMode.AUTO -> OrientationMode.LANDSCAPE
                OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
                OrientationMode.PORTRAIT -> OrientationMode.AUTO
            }
            applyOrientationMode(next)
        }

        // 数据记录管理（列表 / 曲线 / 查看 / 分享 / 删除）
        binding.recordsButton.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            binding.permissionPanel.visibility = View.GONE
            startCamera()
        } else {
            binding.permissionPanel.visibility = View.VISIBLE
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------------------------------------------------------------- 开始 / 停止记录

    private fun toggleRecording() {
        if (recorder.isRecording) {
            // 停止前先把进行中的事件收尾，避免正在低头的人这次事件丢掉
            recorder.writeEvents(tracker.finishAll(SystemClock.elapsedRealtime()))
            val rows = recorder.detailRows
            val events = recorder.eventRows
            recorder.stop()
            Toast.makeText(this, getString(R.string.record_stopped_fmt, rows, events), Toast.LENGTH_LONG).show()
        } else {
            val ok = recorder.start()
            Toast.makeText(
                this,
                if (ok) getString(R.string.record_started_fmt, recorder.directoryPath)
                else getString(R.string.record_start_failed),
                Toast.LENGTH_LONG
            ).show()
        }
        updateRecordButton()
    }

    private fun updateRecordButton() {
        val recording = ::recorder.isInitialized && recorder.isRecording
        binding.recordButton.text = getString(if (recording) R.string.record_stop else R.string.record_start)
        binding.recordButton.backgroundTintList =
            ColorStateList.valueOf(if (recording) COLOR_RECORDING else COLOR_UNSELECTED)
        binding.recordButton.setTextColor(Color.WHITE)
    }

    private fun showThreshold() {
        // 横屏右侧面板窄，用短文案避免换行
        val resId = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            R.string.threshold_fmt_short
        } else {
            R.string.threshold_fmt
        }
        binding.thresholdText.text = getString(resId, headDownThreshold)
    }

    /**
     * 横屏：沉浸式真全屏——隐藏状态栏和导航栏，内容铺到屏幕边缘（只给挖孔/刘海留白）。
     * 竖屏：不动系统栏，保持正常布局。
     * Activity 在旋转时会重建，所以这里按当前方向设一次即可。
     */
    /** 需要避开状态栏/导航栏/挖孔的面板，记下它原本的外边距 */
    private class SafePanel(val view: View, val base: IntArray)

    private val safePanels = mutableListOf<SafePanel>()

    /**
     * 处理系统栏安全区。
     *
     * 注意：Android 15（targetSdk 35）起系统**强制 edge-to-edge**，系统栏会浮在内容上层，
     * 竖屏再也不能靠 `setDecorFitsSystemWindows(true)` 自动内缩——之前竖屏的抬头率卡片和
     * 底部按钮就是这样顶到状态栏/导航栏里的。
     * 现在统一：预览仍旧铺满整屏（好看），只给三块面板叠加安全区边距。
     */
    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (isLandscape()) {
            // 横屏：隐藏状态栏/导航栏做真全屏，并允许内容画进挖孔区域
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
            hideSystemBars()
        } else {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }

        safePanels.clear()
        listOfNotNull(
            binding.statsPanel,
            binding.controlPanel,
            binding.root.findViewById<View>(R.id.bottomBar)
        ).forEach { panel ->
            val lp = panel.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            safePanels += SafePanel(panel, intArrayOf(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin))
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safePanels.forEach { panel ->
                val lp = panel.view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
                lp.setMargins(
                    panel.base[0] + bars.left,
                    panel.base[1] + bars.top,
                    panel.base[2] + bars.right,
                    panel.base[3] + bars.bottom
                )
                panel.view.layoutParams = lp
            }
            insets
        }
        // 主动请求一次，保证首帧就应用（否则要等一次系统栏变化）
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun hideSystemBars() {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        WindowCompat.getInsetsController(window, window.decorView).apply {
            // 从边缘上滑可以临时唤出，随后自动再隐藏
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------------------------------------------------------------- 点屏幕隐藏 / 显示控件

    private var panelsVisible = true

    /** 会被整体隐藏的控件面板；bottomBar 只在横屏布局里存在，所以用 findViewById 兜一下 */
    private val panelViews: List<View> by lazy {
        listOfNotNull(
            binding.controlPanel,
            binding.root.findViewById<View>(R.id.bottomBar)
        )
    }

    /**
     * 点屏幕空白处（预览画面）切换控件面板的显示/隐藏，方便一键清空画面；
     * 点面板本身、按钮、滑块都不会误触切换。状态会记住。
     */
    private fun setupPanelToggle() {
        // 面板「吃掉」点击，避免点在面板空白处也触发切换
        listOfNotNull(
            binding.statsPanel,
            binding.controlPanel,
            binding.root.findViewById<View>(R.id.bottomBar)
        ).forEach { panel -> panel.setOnClickListener { /* 故意留空 */ } }

        binding.root.setOnClickListener { setPanelsVisible(!panelsVisible, fromUser = true) }
        setPanelsVisible(prefs.getBoolean(KEY_PANELS_VISIBLE, true), fromUser = false)
    }

    private fun setPanelsVisible(visible: Boolean, fromUser: Boolean) {
        panelsVisible = visible
        if (fromUser) prefs.edit().putBoolean(KEY_PANELS_VISIBLE, visible).apply()

        panelViews.forEach { panel ->
            panel.animate().cancel()
            if (visible) {
                panel.visibility = View.VISIBLE
                panel.alpha = 0f
                panel.animate().alpha(1f).setDuration(PANEL_FADE_MS).start()
            } else {
                panel.animate().alpha(0f).setDuration(PANEL_FADE_MS)
                    .withEndAction { panel.visibility = View.GONE }
                    .start()
            }
        }

        if (visible) hideHint() else showHint()
    }

    /** 隐藏控件后短暂提示「点屏幕显示控件」，免得以为界面坏了 */
    private fun showHint() {
        val hint = binding.hintText
        hint.animate().cancel()
        hint.visibility = View.VISIBLE
        hint.alpha = 1f
        hint.postDelayed(hintFadeOut, HINT_SHOW_MS)
    }

    private val hintFadeOut = Runnable {
        binding.hintText.animate().alpha(0f).setDuration(PANEL_FADE_MS)
            .withEndAction { binding.hintText.visibility = View.GONE }
            .start()
    }

    private fun hideHint() {
        binding.hintText.animate().cancel()
        binding.hintText.visibility = View.GONE
    }

    private fun persistZoom() {
        camera?.cameraInfo?.zoomState?.value?.let {
            prefs.edit().putFloat(KEY_ZOOM, it.zoomRatio).apply()
        }
    }

    // 预览控件宽度：用来把「框宽」换算成统一尺度的人脸像素（见 REFERENCE_ANALYSIS_WIDTH）
    @Volatile
    private var previewWidth = 0

    /**
     * 把「传感器 → PreviewView」的变换交给分析器。
     * MlKitAnalyzer 用它与 sensorToBufferTransform 组合，把 ML Kit 的框换算到 PreviewView 坐标，
     * 因此旋转、镜像、ViewPort 裁剪都由 CameraX 处理，自己不用算。
     */
    private fun refreshAnalyzerTransform() {
        val matrix = binding.previewView.sensorToViewTransform
        previewWidth = binding.previewView.width
        analyzer?.updateTransform(matrix)
        if (matrix != null && !transformLogged) {
            transformLogged = true
            Log.i(TAG, "坐标变换就绪：view=${binding.previewView.width}x${binding.previewView.height}")
        }
    }

    /**
     * 定期刷新一次坐标变换。
     * 换摄像头、预览尺寸变化、系统栏临时显隐等都会让这个矩阵变，而 PreviewView 不一定会回调，
     * 所以用低频轮询兜底（每 500ms 一次，只是取个矩阵，开销可忽略）。
     */
    private val transformRefresher = object : Runnable {
        override fun run() {
            refreshAnalyzerTransform()
            binding.root.postDelayed(this, TRANSFORM_REFRESH_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.root.postDelayed(transformRefresher, TRANSFORM_REFRESH_MS)
    }

    override fun onPause() {
        binding.root.removeCallbacks(transformRefresher)
        super.onPause()
    }

    private fun selectedLensFacing(): Int =
        cameras.firstOrNull { it.id == selectedCameraId }?.lensFacing ?: CameraSelector.LENS_FACING_BACK

    /**
     * 切换屏幕方向。
     * AUTO 用 SENSOR（跟随方向传感器，不理会系统「自动旋转」开关）；
     * 另外两种用 SENSOR_LANDSCAPE / SENSOR_PORTRAIT：锁定方向，但该方向内仍允许左右/上下翻转。
     */
    private fun applyOrientationMode(mode: OrientationMode, persist: Boolean = true) {
        orientationMode = mode
        requestedOrientation = when (mode) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        if (persist) prefs.edit().putString(KEY_ORIENTATION, mode.name).apply()
        updateOrientationButton()
    }

    private fun updateOrientationButton() {
        binding.orientationButton.text = getString(
            when (orientationMode) {
                OrientationMode.AUTO -> R.string.orientation_auto
                OrientationMode.LANDSCAPE -> R.string.orientation_landscape
                OrientationMode.PORTRAIT -> R.string.orientation_portrait
            }
        )
        styleChip(binding.orientationButton, orientationMode != OrientationMode.AUTO)
    }

    // ---------------------------------------------------------------- 摄像头枚举与选择

    private fun enumerateCameras(provider: ProcessCameraProvider): List<CameraOption> {
        val raw = provider.availableCameraInfos.mapNotNull { info ->
            val facing = info.lensFacing ?: return@mapNotNull null
            val camera2 = Camera2CameraInfo.from(info)

            // 只保留能正常出彩色图的摄像头，滤掉深度/红外等辅助 sensor
            val capabilities =
                camera2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (capabilities == null ||
                !capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
            ) {
                return@mapNotNull null
            }

            val focal = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()
            val sensor = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val fov = if (focal != null && focal > 0f && sensor != null &&
                sensor.width > 0f && sensor.height > 0f
            ) {
                // 对角线视场角，用来判断哪颗是广角/超广角
                val diagonal = hypot(sensor.width.toDouble(), sensor.height.toDouble())
                (2.0 * atan(diagonal / (2.0 * focal)) * 180.0 / Math.PI).toFloat()
            } else {
                0f
            }

            Log.i(TAG, "摄像头 id=${camera2.cameraId} facing=$facing 焦距=$focal 视场角=${"%.1f".format(fov)}°")
            RawCamera(camera2.cameraId, facing, fov)
        }

        val options = mutableListOf<CameraOption>()
        raw.distinctBy { it.id }.groupBy { it.lensFacing }.forEach { (facing, group) ->
            val sorted = group.sortedByDescending { it.fovDegrees }
            val widest = sorted.firstOrNull()?.fovDegrees ?: 0f
            val second = sorted.getOrNull(1)?.fovDegrees ?: 0f
            val lensName = when (facing) {
                CameraSelector.LENS_FACING_FRONT -> "前置"
                CameraSelector.LENS_FACING_EXTERNAL -> "外接"
                else -> "后置"
            }

            sorted.forEachIndexed { index, cameraInfo ->
                val tag = when {
                    index == 0 && (widest >= ULTRA_WIDE_FOV || (second > 0f && widest >= second * 1.15f)) -> "超广角"
                    index == 0 -> "主摄"
                    else -> "副摄"
                }
                val fovText = if (cameraInfo.fovDegrees > 0f) "${cameraInfo.fovDegrees.roundToInt()}°" else "?"
                options += CameraOption(cameraInfo.id, facing, cameraInfo.fovDegrees, "$lensName $fovText $tag")
            }
        }

        Log.i(TAG, "对 App 公开的摄像头共 ${options.size} 颗：" + options.joinToString { "${it.label}(id=${it.id})" })

        // 后置优先，其次外接（USB 广角摄像头），最后前置
        return options.sortedBy {
            when (it.lensFacing) {
                CameraSelector.LENS_FACING_BACK -> 0
                CameraSelector.LENS_FACING_EXTERNAL -> 1
                else -> 2
            }
        }
    }

    private fun buildCameraButtons() {
        binding.cameraRow.removeAllViews()
        cameraButtons.clear()
        if (cameras.isEmpty()) return

        if (selectedCameraId == null || cameras.none { it.id == selectedCameraId }) {
            // 默认选后置；真正的「广角」在下面那排变焦档位里（很多机型把超广角藏在 1× 以下）
            selectedCameraId = cameras.first().id
            prefs.edit().putString(KEY_CAMERA_ID, selectedCameraId).apply()
        }

        cameras.forEachIndexed { index, option ->
            val button = createChipButton(option.label) { selectCamera(option.id) }
            binding.cameraRow.addView(
                button,
                // 摄像头按钮两边都等分整行，标签不会被截断
                chipLayoutParams(labelWeight(option.label), useWeight = true, isLast = index == cameras.lastIndex)
            )
            cameraButtons[option.id] = button
        }
        highlightSelectedCamera()
    }

    private fun highlightSelectedCamera() {
        cameraButtons.forEach { (id, button) -> styleChip(button, id == selectedCameraId) }
    }

    private fun selectCamera(id: String) {
        if (id == selectedCameraId) return
        selectedCameraId = id
        prefs.edit().putString(KEY_CAMERA_ID, id).apply()
        highlightSelectedCamera()
        cameraProvider?.let { bindUseCases(it) }
    }

    private fun selectorFor(id: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter(CameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == id }
            })
            .build()

    // ---------------------------------------------------------------- 变焦（很多机型的「广角」在这里）

    private fun updateZoomUi(state: ZoomState) {
        val min = state.minZoomRatio
        val max = state.maxZoomRatio

        // 重建后恢复上次的变焦倍数
        pendingZoomRatio?.let { want ->
            pendingZoomRatio = null
            if (want in min..max) camera?.cameraControl?.setZoomRatio(want)
        }

        binding.zoomText.text = getString(R.string.zoom_fmt, state.zoomRatio, min, max)

        if (zoomRangeBuilt != Pair(min, max)) {
            zoomRangeBuilt = Pair(min, max)
            buildZoomPresets(min, max)
            Log.i(TAG, "变焦范围 $min–$max（min < 1 表示支持超广角）")
        }

        highlightZoom(state.zoomRatio)

        binding.zoomSeekBar.isEnabled = max > min + 0.01f
        if (!draggingZoom) {
            binding.zoomSeekBar.progress = (state.linearZoom * binding.zoomSeekBar.max).roundToInt()
        }
    }

    /**
     * 生成变焦档位：0.6× 这类小于 1× 的档位就是超广角。
     * 很多厂商不把超广角作为独立 cameraId 开放给第三方 App，只能靠变焦切过去。
     */
    private fun buildZoomPresets(min: Float, max: Float) {
        binding.zoomPresetRow.removeAllViews()
        zoomButtons.clear()

        val candidates = mutableListOf<Float>()
        if (min < 0.995f) candidates += min
        candidates += 1f
        candidates += 2f
        candidates += 5f
        candidates += max
        val ratios = candidates
            .filter { it >= min - 0.001f && it <= max + 0.001f }
            .distinct()
            .sorted()

        ratios.forEachIndexed { index, ratio ->
            val label = if (ratio < 0.995f) {
                getString(R.string.zoom_preset_ultra_fmt, ratio)
            } else {
                getString(R.string.zoom_preset_fmt, ratio)
            }
            val button = createChipButton(label) {
                camera?.cameraControl?.setZoomRatio(ratio)
                prefs.edit().putFloat(KEY_ZOOM, ratio).apply()
            }
            binding.zoomPresetRow.addView(
                button,
                // 竖屏的变焦行在横向滚动里，按内容宽度；横屏等分整行
                chipLayoutParams(labelWeight(label), useWeight = isLandscape(), isLast = index == ratios.lastIndex)
            )
            zoomButtons += ratio to button
        }
        highlightZoom(min)
    }

    private fun highlightZoom(current: Float) {
        zoomButtons.forEach { (ratio, button) ->
            val isCurrent = abs(ratio - current) <= (ratio * 0.03f).coerceAtLeast(0.02f)
            styleChip(button, isCurrent)
        }
    }

    // ---------------------------------------------------------------- 按钮外观

    private fun createChipButton(text: String, onClick: () -> Unit): MaterialButton {
        val density = resources.displayMetrics.density
        val padH = (10 * density).toInt()
        val padV = (6 * density).toInt()
        return MaterialButton(this).apply {
            this.text = text
            textSize = 15f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            insetTop = 0
            insetBottom = 0
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            setOnClickListener { onClick() }
        }
    }

    /**
     * 横屏：按文字长度分配权重、等分整行宽度（一屏显示完，不用左右滑，长标签也不会被截断）；
     * 竖屏下摄像头行同样是等分整行；竖屏的变焦行放在横向滚动里，用内容宽度。
     * 按钮统一 48dp 高，手指好按。
     */
    private fun chipLayoutParams(
        weight: Float = 1f,
        useWeight: Boolean = isLandscape(),
        isLast: Boolean = false
    ): LinearLayout.LayoutParams {
        val density = resources.displayMetrics.density
        val margin = if (isLast) 0 else (8 * density).toInt()
        val height = (40 * density).toInt()
        return if (useWeight) {
            LinearLayout.LayoutParams(0, height, weight).apply { marginEnd = margin }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, height)
                .apply { marginEnd = margin }
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 文字越长给越大的权重，"0.6× 超广角" 这种长标签才不会变成 "0.6× 超…" */
    private fun labelWeight(text: String): Float = text.length.coerceIn(4, 12) / 4f

    private fun styleChip(button: MaterialButton, selected: Boolean) {
        button.backgroundTintList =
            ColorStateList.valueOf(if (selected) COLOR_SELECTED else COLOR_UNSELECTED)
        button.setTextColor(Color.WHITE)
    }

    // ---------------------------------------------------------------- 相机绑定

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            if (cameras.isEmpty()) {
                cameras = enumerateCameras(provider)
                buildCameraButtons()
            }
            bindRetry = 0
            bindUseCases(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(provider: ProcessCameraProvider) {
        // ViewPort 必须等 PreviewView 量好尺寸才拿得到；
        // 用 PreviewView 的 ViewPort 能保证预览和分析裁剪出完全一致的画面
        val viewPort = binding.previewView.viewPort
        if (viewPort == null) {
            if (bindRetry++ < MAX_BIND_RETRY) {
                binding.previewView.post { bindUseCases(provider) }
            }
            return
        }
        bindRetry = 0
        transformLogged = false
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)

        val rotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0
        val targetId = selectedCameraId

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

        val imageAnalysis = ImageAnalysis.Builder()
            // 只保留最新一帧，推理慢的时候自动丢帧，不堆积延迟
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setTargetRotation(rotation)
            .build()
            .also { it.setAnalyzer(analysisExecutor, analyzer!!.analyzer) }

        val group = UseCaseGroup.Builder()
            .setViewPort(viewPort)
            .addUseCase(preview)
            .addUseCase(imageAnalysis)
            .build()

        try {
            provider.unbindAll()
            val selector = if (targetId != null) selectorFor(targetId) else CameraSelector.DEFAULT_BACK_CAMERA
            onCameraBound(provider.bindToLifecycle(this, selector, group))
        } catch (e: Exception) {
            // 选中的摄像头不支持这套组合时，退回默认后置
            try {
                provider.unbindAll()
                val bound = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(
                            Preview.Builder()
                                .setTargetRotation(rotation)
                                .build()
                                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
                        )
                        .addUseCase(imageAnalysis)
                        .build()
                )
                onCameraBound(bound)
                Toast.makeText(this, getString(R.string.camera_fallback), Toast.LENGTH_SHORT).show()
            } catch (inner: Exception) {
                Toast.makeText(
                    this,
                    getString(R.string.no_camera, inner.message ?: inner.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun onCameraBound(bound: Camera) {
        camera = bound
        zoomRangeBuilt = null
        bound.cameraInfo.zoomState.observe(this, zoomObserver)
        binding.previewView.post { refreshAnalyzerTransform() }
    }

    /**
     * 检测结果的落地处理，跑在分析线程上（不阻塞主线程）：
     * 1) 状态机更新：持续低头 / 趴桌事件 + 5s/30s 窗口抬头率
     * 2) 写 CSV（每 5s 一行明细、事件即时落盘）
     * 3) 主线程更新 UI：画框 + 数字
     */
    private fun handleDetection(result: DetectionResult) {
        if (isDestroyed || isFinishing) return

        val now = SystemClock.elapsedRealtime()
        // ML Kit 现在返回的是 PreviewView 坐标，人脸宽度不是「分析图像素」。
        // 统一换算成「≈1280 宽的分析图」下的像素，这样不同机型/方向下的预警阈值才可比。
        val viewWidth = if (previewWidth > 0) previewWidth.toFloat() else 1f
        val observations = result.observations.map { obs ->
            val fraction = (obs.right - obs.left) / viewWidth
            obs.copy(analysisWidthPx = (fraction * REFERENCE_ANALYSIS_WIDTH).roundToInt())
        }

        val snapshot = tracker.onFrame(observations, now)
        recorder.maybeWriteDetail(now, snapshot, headDownThreshold)
        recorder.writeEvents(tracker.drainFinishedEvents())

        val marks = observations.map { obs ->
            val state = obs.trackingId?.let { snapshot.stateByTrackingId[it] }
                ?: tracker.classify(obs.pitchDegrees, obs.eyesOpenProbability)
            FaceMark(
                left = obs.left,
                top = obs.top,
                right = obs.right,
                bottom = obs.bottom,
                state = state,
                pitchDegrees = obs.pitchDegrees,
                eyesClosed = obs.eyesOpenProbability?.let { it < EYES_CLOSED_PROB } == true
            )
        }
        val recording = recorder.isRecording
        val detailRows = recorder.detailRows
        val eventRows = recorder.eventRows

        binding.root.post {
            if (isDestroyed || isFinishing) return@post
            binding.overlayView.setFaces(marks, result.boxesMapped)

            val eventCount = snapshot.headDownEvents + snapshot.lyingEvents
            // 画面里没人时不显示百分数（否则会拿 30s 窗口里的历史值误导人）
            binding.rateText.text = if (snapshot.total == 0) {
                getString(R.string.rate_none)
            } else {
                getString(R.string.rate_fmt, snapshot.window30Rate ?: snapshot.instantRate)
            }
            binding.statsText.text = getString(
                R.string.stats_fmt,
                snapshot.total,
                snapshot.headUp,
                snapshot.headDown,
                snapshot.lying,
                snapshot.eyesClosed
            )
            binding.detailText.text = if (snapshot.minFacePx in 1 until MIN_FACE_PX_WARN) {
                getString(R.string.detail_warn_fmt, snapshot.instantRate, snapshot.minFacePx, eventCount)
            } else {
                getString(R.string.detail_fmt, snapshot.instantRate, snapshot.minFacePx, eventCount)
            }
            binding.recordText.text = if (recording) {
                getString(R.string.record_fmt, detailRows, eventRows, snapshot.fps)
            } else {
                getString(R.string.record_idle_fmt, snapshot.fps)
            }
        }
    }

    override fun onDestroy() {
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        persistZoom()
        binding.hintText.removeCallbacks(hintFadeOut)
        binding.root.removeCallbacks(transformRefresher)
        analyzer?.close()
        // 记录器是进程级单例，只 flush 不关闭，转屏后继续往同一个文件写；
        // 只有真正退出（而不是转屏）才把进行中的事件收尾，否则一次持续低头会被切成两段
        if (isFinishing) {
            runCatching {
                if (::tracker.isInitialized && ::recorder.isInitialized) {
                    recorder.writeEvents(tracker.finishAll(SystemClock.elapsedRealtime()))
                    recorder.stop()
                }
            }
        }
        if (::recorder.isInitialized) runCatching { recorder.flush() }
        // 注意：analysisExecutor 是进程级共享的，绝对不能在这里 shutdown()。
        // MlKitAnalyzer 用它跑 ML Kit(com.google.android.gms.tasks) 的回调；
        // 转屏重建 Activity 时若把池 terminate 掉，ML Kit 还没回调完的任务会往已终止的池投递，
        // 主线程抛 RejectedExecutionException 直接闪退（Crash-Tag: window_resize）。
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TaiTouLv"

        /**
         * 进程级共享的分析线程。
         * 不能用 Activity 生命周期管理：ML Kit 的 Task 回调要在 Activity 重建后继续能投递进来。
         */
        private val analysisExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }
        private const val PREFS_NAME = "taitoulv"
        private const val KEY_ORIENTATION = "orientation_mode"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_ZOOM = "zoom_ratio"
        private const val KEY_PANELS_VISIBLE = "panels_visible"

        // 分析分辨率：请求接近 720p 的分辨率，实际用哪档由相机决定
        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 720

        // 认为算「超广角」的对角线视场角下限
        private const val ULTRA_WIDE_FOV = 100f

        // 行为判定口径（和 AttentionTracker 的默认值保持一致）
        private const val LYING_PITCH = -35f          // 俯仰角 ≤ 这个值 + 闭眼 → 趴桌
        private const val EYES_CLOSED_PROB = 0.4f     // 睁眼概率低于它算闭眼
        private const val HEAD_DOWN_EVENT_MS = 5_000L // 连续低头 ≥5s 记一次事件
        private const val LYING_EVENT_MS = 10_000L    // 连续趴桌 ≥10s 记一次事件

        // 人脸太小就提示机位偏远（ML Kit 建议 ≥100px，这里 80px 就预警）
        private const val MIN_FACE_PX_WARN = 80

        /** 人脸像素的参考尺度：按「1280 宽的分析图」折算 */
        private const val REFERENCE_ANALYSIS_WIDTH = 1280

        // 阈值范围 -40° ~ +20°，滑块每格 1°
        private const val MIN_THRESHOLD = -40f
        private const val THRESHOLD_STEP = 1f
        private const val THRESHOLD_STEPS = 60
        private const val DEFAULT_THRESHOLD = -10f

        private const val MAX_BIND_RETRY = 10
        private const val TRANSFORM_REFRESH_MS = 500L
        private const val PANEL_FADE_MS = 160L
        private const val HINT_SHOW_MS = 1600L
        private const val COLOR_SELECTED = 0xFF007AFF.toInt()
        private const val COLOR_RECORDING = 0xFFE53935.toInt()
        private const val COLOR_UNSELECTED = 0x33FFFFFF
    }
}
