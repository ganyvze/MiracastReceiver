package top.jast.miracastreceiver.ui

import top.jast.miracastreceiver.util.VideoContent
import top.jast.miracastreceiver.util.applyVideoTextureTransform
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TimeBar
import androidx.media3.ui.AspectRatioFrameLayout
import top.jast.miracastreceiver.R
import top.jast.miracastreceiver.cast.CastPlayerBridge
import top.jast.miracastreceiver.airplay.StreamStats
import top.jast.miracastreceiver.airplay.UxPlaySubtitles
import top.jast.miracastreceiver.miracast.RtpReceiver
import top.jast.miracastreceiver.util.AccentTheme
import top.jast.miracastreceiver.util.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URL

/**
 * 投屏播放页面
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val ACTION_REPLACED = "top.jast.miracastreceiver.PLAYBACK_REPLACED"
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MEDIA_TITLE = "media_title"
        const val EXTRA_MEDIA_URIS = "media_uris"
        const val EXTRA_MEDIA_TITLES = "media_titles"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_IS_AIRPLAY_MIRROR = "is_airplay_mirror"

        // Miracast 额外参数
        const val EXTRA_SOURCE_TYPE = "SOURCE_TYPE"
        const val EXTRA_RTP_PORT = "RTP_PORT"
        const val EXTRA_SESSION_ID = "SESSION_ID"

        // 广播 Action
        const val ACTION_PLAY = "top.jast.miracastreceiver.ACTION_PLAY"
        const val ACTION_PAUSE = "top.jast.miracastreceiver.ACTION_PAUSE"
        const val ACTION_STOP = "top.jast.miracastreceiver.ACTION_STOP"
        const val ACTION_SEEK = "top.jast.miracastreceiver.ACTION_SEEK"
        const val ACTION_SET_VOLUME = "top.jast.miracastreceiver.ACTION_SET_VOLUME"
        const val ACTION_SET_PLAYLIST = "top.jast.miracastreceiver.ACTION_SET_PLAYLIST"
        const val ACTION_SET_SPEED = "top.jast.miracastreceiver.ACTION_SET_SPEED"
        const val ACTION_SET_QUALITY_URL = "top.jast.miracastreceiver.ACTION_SET_QUALITY_URL"

        const val EXTRA_SEEK_POSITION = "seek_position"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_QUALITY_URI = "quality_uri"

        private val mirrorSurfaces = MirrorSurfaceRegistry<Surface>()
        val mirrorSurface: Surface? get() = mirrorSurfaces.surface
        val airPlaySurface: Surface? get() = mirrorSurfaces.surfaceFor(MirrorSurfaceSource.AIRPLAY)
        val miracastSurface: Surface? get() = mirrorSurfaces.surfaceFor(MirrorSurfaceSource.MIRACAST)

        private fun updatePublishedMirrorSurface(owner: Any, surface: Surface?, source: MirrorSurfaceSource?) {
            if (mirrorSurfaces.publish(owner, surface, source)) mirrorSurfaceListener?.invoke()
        }

        /** Surface 重建时通知 AirPlay 的 URL 视频播放器与镜像解码器恢复输出。 */
        @Volatile
        var mirrorSurfaceListener: (() -> Unit)? = null

        private const val IMAGE_SLIDE_INTERVAL_MS = 5_000L
        private const val QUALITY_AUTO = -1

        /** 预览进度模式：最后一次按键后多久自动完成跳转 */
        private const val PREVIEW_IDLE_COMMIT_MS = 1_000L

        /** 进度预览框宽度 */
        private const val PREVIEW_BOX_WIDTH_DP = 220

        /** 进度预览框高度范围：竖屏片源也不至于占掉半屏 */
        private const val PREVIEW_BOX_MIN_HEIGHT_DP = 96
        private const val PREVIEW_BOX_MAX_HEIGHT_DP = 150

        /** 预览帧缓存数量（当前帧 + 预取帧），超出后回收最旧的位图 */
        private const val PREVIEW_FRAME_CACHE_SIZE = 4

        /** 连续按键时两次取帧的最小间隔，避免高频重复事件反复取消/新建解码任务 */
        private const val PREVIEW_FRAME_THROTTLE_MS = 250L

        /** 加载圆圈下方的速度刷新间隔 */
        private const val LOADING_SPEED_INTERVAL_MS = 500L

        /** 加载速度文字与圆圈底部的间距 */
        private const val LOADING_SPEED_GAP_DP = 8f

        private const val ACTION_PLAYBACK_STOPPED = "top.jast.miracastreceiver.ACTION_PLAYBACK_STOPPED"

        private const val ACTION_DISCONNECT_SENDER = "top.jast.miracastreceiver.DISCONNECT_SENDER"

        private const val ROTATE_BUTTON_HIDE_DELAY_MS = 4_000L
        private val ROTATE_REVEAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN
        )

        /** 连按两次返回退出投屏的间隔上限 */
        private const val EXIT_BACK_INTERVAL_MS = 2_000L

        /** 长按左/右键时，每隔多久把快进/快退步进值翻一倍 */
        private const val SEEK_ACCEL_STEP_MS = 2_000L

        /** 长按加速的步进上限（基础步进的 16 倍） */
        private const val SEEK_ACCEL_MAX_MULTIPLIER = 16
    }

    private lateinit var playerView: PlayerView
    private lateinit var mirrorSurfaceView: SurfaceView
    private lateinit var imageView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var speedIndicator: TextView
    private lateinit var tvStreamInfo: TextView
    private lateinit var bufferingIndicator: ProgressBar
    private lateinit var tvLoadingSpeed: TextView

    // 加载速度：复用 StreamInfoTracker 把 ExoPlayer 的累计下载字节换算成速率
    private val loadingSpeedTracker = StreamInfoTracker()
    private var loadingSpeedJob: Job? = null
    private var loadedBytesTotal = 0L

    private var player: ExoPlayer? = null
    private var castToken: String? = null
    private var castIdleReason: String? = null
    private var castStoppedBySender = false
    private var trackSelector: DefaultTrackSelector? = null
    private var mediaUri: String? = null
    private var mediaTitle: String? = null
    private var playlist: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var currentIndex: Int = 0
    private var slideJob: Job? = null
    private var progressUpdateJob: Job? = null
    private var qualityHeight: Int = QUALITY_AUTO
    private var isMiracastSession = false
    private var isAirPlayMirrorSession = false
    private var isPlayerVisible = false
    private var screenOnSessionActive = false
    private var mirrorAspectJob: Job? = null
    private lateinit var btnRotateOverlay: ImageButton
    private var rotateButtonHideJob: Job? = null

    // 手动旋转画面：只转画面内容，不改屏幕方向，所以电视上也有效
    /** 当前手动旋转角度（0/90/180/270，顺时针）。 */
    private var rotationDegrees = 0
    private var manualRotationDegrees = 0
    private var autoRotationDegrees = 0
    private var miracastContent = RectF(0f, 0f, 1f, 1f)
    private var cropCandidate: RectF? = null
    private var cropConfirmations = 0
    private var cropCopyPending = false
    private var lastCropSampleAt = 0L
    private var cropSession = 0
    private var autoRotateToFit = false
    /** 镜像旋转用的 TextureView 层：0° 时隐藏，仍走 SurfaceView 保住电视上的性能。 */
    private lateinit var mirrorRotateLayer: FrameLayout
    private lateinit var mirrorRotateTexture: TextureView
    private var mirrorHolderSurface: Surface? = null
    private val mirrorSurfaceToken = Any()
    private var surfacePublisherStopped = false
    private var mirrorTextureSurface: Surface? = null
    /** 点播视频旋转用的 TextureView，挂在 PlayerView 的 overlay 层（画面之上、控制条之下）。 */
    private var exoRotateLayer: FrameLayout? = null
    private var exoRotateTexture: TextureView? = null
    private var currentSpeed = 1f

    // 视频流信息面板
    private val streamInfoTracker = StreamInfoTracker()
    private var streamInfoJob: Job? = null
    private var isStreamInfoVisible = false
    private var bandwidthEstimateBps = 0L

    // 预览进度模式：按方向键只移动进度条和预览框，播放器继续从原位置播放；
    // 按 OK 或 1 秒无按键后才真正跳到所选位置。
    private var previewPositionMs: Long? = null
    private var previewStepMs = 0L
    private var previewIdleJob: Job? = null
    private var previewFrameJob: Job? = null
    private var previewPrefetchJob: Job? = null
    private var previewFrameThrottleJob: Job? = null
    private var previewFrameThrottleAt = 0L
    private var previewFramePendingMs = -1L
    private var previewFrameSeq = 0
    private var isSeekKeyDown = false
    private lateinit var previewFrameBox: FrameLayout
    private lateinit var previewFrameImage: ImageView
    private var previewShownBitmap: Bitmap? = null
    private val previewFrameCache = LinkedHashMap<Long, Bitmap>(PREVIEW_FRAME_CACHE_SIZE, 0.75f, true)

    private var isDialogShowing = false
    private var okLongPressJob: Job? = null
    private var isLongPressSpeedActive = false
    private var okPressCommittedSeek = false

    // 长按左/右键的步进加速状态（只影响跳转步进，不碰播放速率，与 OK 长按倍速无关）
    private var seekAccelJob: Job? = null
    /** 长按时的当前步进倍数：每次长按从 1 开始，每 2 秒翻倍，上限 16。 */
    private var seekAccelMultiplier = 1

    // 连按两次返回退出投屏：记录第一次按下的时间，0 表示未处于待确认状态
    private var exitBackArmedAt = 0L

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REPLACED) {
                stopPlayback(endCastSession = true)
                finish() // Do not finishAffinity: a new protocol player is taking over.
                return
            }
            if (intent?.action == CastPlayerBridge.ACTION_COMMAND) {
                if (castToken == null || intent.getStringExtra(CastPlayerBridge.TOKEN) != castToken) return
                val value = intent.getDoubleExtra(CastPlayerBridge.VALUE, 0.0)
                when (intent.getStringExtra(CastPlayerBridge.COMMAND)) {
                    "PLAY" -> player?.play()
                    "PAUSE" -> player?.pause()
                    "SEEK" -> player?.seekTo(value.toLong().coerceAtLeast(0))
                    "VOLUME" -> player?.volume = value.toFloat().coerceIn(0f, 1f)
                    "STOP" -> { castStoppedBySender = true; stopPlayback(); exitPlayer() }
                }
                reportCastState()
                return
            }
            // Legacy DLNA/AirPlay commands have no Cast token and must not control
            // a newer Cast load. Global service shutdown uses the scoped bridge too.
            if (castToken != null) return
            when (intent?.action) {
                ACTION_PLAY -> if (isCurrentImage()) startImageSlideShow() else player?.play()
                ACTION_PAUSE -> if (isCurrentImage()) stopImageSlideShow() else player?.pause()
                ACTION_STOP -> {
                    // A late stop from the previous protocol must not close its replacement.
                    when (intent.getStringExtra(EXTRA_SOURCE_TYPE)) {
                        "AIRPLAY" -> if (!isAirPlayMirrorSession) return
                        "MIRACAST" -> if (!isMiracastSession) return
                    }
                    stopPlayback()
                    exitPlayer()
                }
                ACTION_SEEK -> {
                    val position = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
                    player?.seekTo(position)
                    reportPlaybackPosition()
                }
                ACTION_SET_VOLUME -> {
                    val volume = intent.getIntExtra(EXTRA_VOLUME, 50)
                    player?.volume = volume / 100f
                }
                ACTION_SET_PLAYLIST -> handleIntent(intent)
                ACTION_SET_SPEED -> {
                    val speed = intent.getFloatExtra(EXTRA_SPEED, 1f).coerceIn(0.25f, 4f)
                    player?.setPlaybackSpeed(speed)
                    currentSpeed = speed
                    tvStatus.text = getString(R.string.status_speed, "${speed}x")
                    reportPlaybackPosition()
                }
                ACTION_SET_QUALITY_URL -> {
                    val uri = intent.getStringExtra(EXTRA_QUALITY_URI)
                    if (!uri.isNullOrBlank()) {
                        playMedia(uri)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AccentTheme.apply(this)
        super.onCreate(savedInstanceState)
        autoRotateToFit = top.jast.miracastreceiver.util.AppSettings.isAutoRotateToFit(this)
        setContentView(R.layout.activity_player)

        // 播放期间保持屏幕常亮：索尼等电视对无操作计时较激进，
        // 不加这个标志播放一段时间后会进系统屏保
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        initViews()
        initPlayer()
        handleIntent(intent)
        val airPlaySubtitles = findViewById<SubtitleView>(R.id.airplay_subtitles)
        airPlaySubtitles.setUserDefaultStyle()
        airPlaySubtitles.setUserDefaultTextSize()
        lifecycleScope.launch {
            UxPlaySubtitles.cues.collect { cues ->
                val visible = isAirPlayMirrorSession && cues.isNotEmpty()
                airPlaySubtitles.setCues(if (visible) cues else emptyList())
                airPlaySubtitles.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
        registerControlReceiver()

        Timber.i("PlayerActivity created")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun initViews() {
        playerView = findViewById(R.id.player_view)
        playerView.setControllerShowTimeoutMs(AppSettings.getControllerTimeoutMs(this))
        mirrorSurfaceView = findViewById(R.id.airplay_mirror_surface)
        imageView = findViewById(R.id.image_view)
        tvStatus = findViewById(R.id.tv_status)
        tvTitle = findViewById(R.id.tv_title)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        tvStreamInfo = findViewById(R.id.tv_stream_info)
        bufferingIndicator = findViewById(R.id.buffering_indicator)
        tvLoadingSpeed = findViewById(R.id.tv_loading_speed)
        // 速度文字与圆圈同为居中约束，用 translationY 下移到圆圈正下方；
        // 它不参与圆圈布局，圆圈尺寸与居中位置保持原状。
        tvLoadingSpeed.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val gapPx = LOADING_SPEED_GAP_DP * resources.displayMetrics.density
            tvLoadingSpeed.translationY =
                progressBar.height / 2f + gapPx + tvLoadingSpeed.height / 2f
        }
        speedIndicator = findViewById(R.id.tv_speed_indicator)
        previewFrameBox = findViewById(R.id.preview_frame_box)
        previewFrameImage = findViewById(R.id.preview_frame_image)

        // 控制条播放中会周期性（约每秒）用真实播放位置刷新进度条。预览进度模式下
        // 必须在该回调里同步把预览位置写回去（同一帧内完成），否则进度条会跟着播放走动。
        playerView.findViewById<PlayerControlView?>(androidx.media3.ui.R.id.exo_controller)
            ?.setProgressUpdateListener { _, _ ->
                if (previewPositionMs != null) renderPreviewPosition()
            }
        btnRotateOverlay = findViewById(R.id.btn_rotate_overlay)
        btnRotateOverlay.setOnClickListener {
            rotateClockwise()
            showRotateButton(focus = false)
        }
        mirrorRotateLayer = findViewById(R.id.mirror_rotate_layer)
        mirrorRotateTexture = findViewById(R.id.mirror_rotate_texture)
        mirrorRotateTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                mirrorTextureSurface = Surface(st)
                updateMirrorSurfaceTarget()
                applyRotation()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyRotation()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                val old = mirrorTextureSurface
                mirrorTextureSurface = null
                updateMirrorSurfaceTarget()
                old?.release()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        // 屏幕尺寸/方向变化后重新计算旋转后的缩放
        findViewById<View>(android.R.id.content).addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) applyRotation()
        }
    }

    /**
     * @param lowLatency 实时投屏（Miracast）用。默认的缓冲策略是为点播设计的
     *   （起播要缓冲 2.5 秒、重缓冲后要 5 秒），而实时流的数据严格按实时速率到达，
     *   永远攒不出那么多缓冲，会陷入「解几帧 → 缓冲耗尽 → 转圈」的循环。
     */
    private fun initPlayer(lowLatency: Boolean = false) {
        player?.release()

        // 默认不限制分辨率（对应画质菜单的「自动」）。原先默认 setMaxVideoSizeSd() 会把所有
        // 播放压到标清，Miracast 的 1080p 视频轨会被直接排除，导致选不出轨道、
        // loader 停止加载，最终报 "stuck buffering and not loading"。
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().clearVideoSizeConstraints())
        }

        val loadControl = DefaultLoadControl.Builder()
            .apply {
                if (lowLatency) {
                    // maxBufferMs 不能压太小：到达上限后 ExoPlayer 会停止读取数据源，
                    // 而 RTP 仍按实时速率灌进管道，管道溢出丢数据就会把 TS 流打出空洞，
                    // 解码器拿不到完整 PES 直接黑屏。留出足够余量让它持续排空管道。
                    setBufferDurationsMs(
                        /* minBufferMs = */ 1_000,
                        /* maxBufferMs = */ 8_000,
                        /* bufferForPlaybackMs = */ 500,
                        /* bufferForPlaybackAfterRebufferMs = */ 1_000
                    )
                    setPrioritizeTimeOverSizeThresholds(true)
                }
            }
            .build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        updateScreenOn()
                        reportCastState()
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateBufferingState(playbackState == Player.STATE_BUFFERING)
                        when (playbackState) {
                            Player.STATE_IDLE -> Timber.d("Player state: IDLE")
                            Player.STATE_BUFFERING -> {
                                Timber.d("Player state: BUFFERING")
                                tvStatus.text = getString(R.string.status_buffering)
                            }
                            Player.STATE_READY -> {
                                Timber.d("Player state: READY")
                                tvError.visibility = View.GONE
                                tvStatus.text = getString(R.string.playing)
                                adaptOrientationToVideo()
                                startProgressUpdates()
                            }
                            Player.STATE_ENDED -> {
                                Timber.d("Player state: ENDED")
                                tvStatus.text = getString(R.string.status_finished)
                                reportPlaybackPosition()
                                playNextOrFinish()
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // 缓冲中 isPlaying 也是 false，此时别把 onPlaybackStateChanged
                        // 刚写好的「正在缓冲...」覆盖成「已暂停」，那会让人以为是暂停了
                        tvStatus.text = when {
                            isPlaying -> getString(R.string.playing)
                            playbackState == Player.STATE_READY -> getString(R.string.status_paused)
                            else -> return
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        applyRotation()
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        if (castToken == null) return
                        val video = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                        val audio = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                        Timber.i("Cast media tracks: video=%d, audio=%d", video.sumOf { it.length }, audio.sumOf { it.length })
                        tracks.groups.forEach { group ->
                            for (index in 0 until group.length) {
                                val format = group.getTrackFormat(index)
                                Timber.i("Cast track: type=%d, mime=%s, codec=%s, size=%dx%d, selected=%s, supported=%s",
                                    group.type, format.sampleMimeType, format.codecs, format.width, format.height,
                                    group.isTrackSelected(index), group.isTrackSupported(index))
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        castIdleReason = "ERROR"
                        reportCastState("IDLE")
                        Timber.e(error, "Player error")
                        updateBufferingState(false)
                        showPlaybackError(error.message)
                        tvError.visibility = View.VISIBLE
                    }
                })
                // 视频信息面板的「网速」取自带宽估计（ExoPlayer 默认的 DefaultBandwidthMeter 采样）
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onBandwidthEstimate(
                        eventTime: AnalyticsListener.EventTime,
                        totalLoadTimeMs: Int,
                        totalBytesLoaded: Long,
                        bitrateEstimate: Long
                    ) {
                        bandwidthEstimateBps = bitrateEstimate
                        loadedBytesTotal = totalBytesLoaded
                    }
                })
            }

        playerView.player = player
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
    }

    /** 「更多」弹窗：控制条精简后，低频功能都收到这里。 */
    private fun showMoreMenu() {
        val labels = arrayOf(
            getString(if (isStreamInfoVisible) R.string.menu_stream_info_hide else R.string.menu_stream_info),
            getString(R.string.menu_quality),
            getString(R.string.menu_speed, formatSpeedLabel(currentSpeed)),
            getString(R.string.menu_subtitles),
            getString(R.string.cd_rotate_screen)
        )
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.more)
            .setItems(labels) { _, which ->
                // 本弹窗关闭后再打开下一级弹窗，否则 onDismiss 会把 isDialogShowing 误置回 false
                tvStatus.post {
                    when (which) {
                        0 -> toggleStreamInfo()
                        1 -> showQualityDialog()
                        2 -> showSpeedDialog()
                        3 -> Toast.makeText(this, if (isAirPlayMirrorSession) R.string.subtitles_follow_sender else R.string.subtitles_auto, Toast.LENGTH_SHORT).show()
                        4 -> rotateClockwise()
                    }
                }
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun formatSpeedLabel(speed: Float): String =
        if (speed == 1f) "1.0x" else "${speed}x"

    /**
     * 按左/右键进入「预览进度模式」：只把进度条和预览框移到目标位置，播放器不 seek，
     * 画面继续从原位置播放。按 OK 或 1 秒无按键后才真正跳到所选位置。
     *
     * @param multiplier 长按加速时的步进倍数（1 表示正常单击）
     */
    private fun handleSeekPress(forward: Boolean, multiplier: Int = 1) {
        val currentPlayer = player ?: return
        if (!canPreviewSeek()) return
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: return
        val base = previewPositionMs ?: currentPlayer.currentPosition
        val configuredValue = if (forward) AppSettings.getForwardValue(this) else AppSettings.getRewindValue(this)
        val baseStep = if (AppSettings.isSeekByPercent(this)) {
            (duration * configuredValue / 100f).toLong()
        } else {
            (configuredValue * 1000).toLong()
        }
        if (baseStep <= 0) return
        previewStepMs = baseStep * multiplier
        val target = (base + if (forward) previewStepMs else -previewStepMs).coerceIn(0L, duration)
        previewPositionMs = target
        renderPreviewPosition()
        schedulePreviewCommit()
        schedulePreviewFrame(target)
    }

    /** 只有可 seek 的点播内容才启用预览进度；镜像/直播流既不跳转也不弹任何提示。 */
    private fun canPreviewSeek(): Boolean {
        if (isMirrorSession() || isCurrentImage()) return false
        val currentPlayer = player ?: return false
        if (currentPlayer.currentMediaItem == null) return false
        if (currentPlayer.duration <= 0) return false
        return currentPlayer.isCurrentMediaItemSeekable
    }

    /** 把进度条、时间文字和预览框统一画到 [previewPositionMs] 所指位置。 */
    private fun renderPreviewPosition() {
        val target = previewPositionMs ?: return
        (playerView.findViewById<View?>(R.id.exo_progress) as? TimeBar)?.setPosition(target)
        playerView.findViewById<TextView?>(R.id.exo_position)?.text = formatTimeMs(target)
        playerView.showController()
        positionPreviewFrameBox(target)
    }

    /** 每次按键都重新计时；按键仍按着时不提交，等抬起后再从 1 秒重新计时。 */
    private fun schedulePreviewCommit() {
        previewIdleJob?.cancel()
        previewIdleJob = lifecycleScope.launch {
            delay(PREVIEW_IDLE_COMMIT_MS)
            if (isSeekKeyDown) return@launch
            commitPreviewSeek()
        }
    }

    /** 退出预览进度并从进度条所选位置开始播放。 */
    private fun commitPreviewSeek() {
        val target = previewPositionMs ?: return
        previewPositionMs = null
        previewIdleJob?.cancel()
        previewIdleJob = null
        previewFrameThrottleJob?.cancel()
        previewFrameThrottleJob = null
        previewFramePendingMs = -1L
        player?.seekTo(target)
        hidePreviewFrameBox()
        clearPreviewFrameCache()
        reportPlaybackPosition()
    }

    /** 放弃预览（切源/退出投屏/退到后台）：不跳转，清掉所有取帧资源。 */
    private fun cancelPreviewSeek() {
        isSeekKeyDown = false
        previewPositionMs = null
        previewStepMs = 0L
        previewFramePendingMs = -1L
        previewIdleJob?.cancel()
        previewIdleJob = null
        previewFrameJob?.cancel()
        previewFrameJob = null
        previewPrefetchJob?.cancel()
        previewPrefetchJob = null
        previewFrameThrottleJob?.cancel()
        previewFrameThrottleJob = null
        previewFrameSeq++
        hidePreviewFrameBox()
        clearPreviewFrameCache()
    }

    /** 松开方向键：如果预览模式还在，从此刻开始 1 秒后自动完成跳转。 */
    private fun onSeekKeyReleased() {
        isSeekKeyDown = false
        if (previewPositionMs != null) schedulePreviewCommit()
    }

    // ─── 预览帧与预览框 ────────────────────────────────────────────────────

    /** 预览框宽度固定、高度按视频比例换算，悬浮在进度条所选位置的正上方。 */
    private fun positionPreviewFrameBox(target: Long) {
        val bar = playerView.findViewById<View?>(R.id.exo_progress) ?: return
        val duration = player?.duration?.takeIf { it > 0 } ?: return
        val root = findViewById<View>(android.R.id.content) ?: return
        if (bar.width <= 0 || root.width <= 0) return
        updatePreviewBoxSize()
        val boxWidth = previewFrameBox.layoutParams.width
        val boxHeight = previewFrameBox.layoutParams.height
        if (boxWidth <= 0 || boxHeight <= 0) return
        val fraction = (target.toFloat() / duration).coerceIn(0f, 1f)
        val barLocation = IntArray(2).also(bar::getLocationOnScreen)
        val rootLocation = IntArray(2).also(root::getLocationOnScreen)
        val margin = dp(8)
        val centerX = barLocation[0] - rootLocation[0] + (bar.width * fraction).toInt()
        val maxLeft = (root.width - boxWidth - margin).coerceAtLeast(margin)
        previewFrameBox.x = (centerX - boxWidth / 2).coerceIn(margin, maxLeft).toFloat()
        previewFrameBox.y = (barLocation[1] - rootLocation[1] - boxHeight - margin).toFloat()
    }

    private fun updatePreviewBoxSize() {
        val size = player?.videoSize
        val aspect = if (size != null && size.width > 0 && size.height > 0) {
            size.width.toFloat() / size.height.toFloat()
        } else {
            16f / 9f
        }
        val width = dp(PREVIEW_BOX_WIDTH_DP)
        val height = (width / aspect).toInt()
            .coerceIn(dp(PREVIEW_BOX_MIN_HEIGHT_DP), dp(PREVIEW_BOX_MAX_HEIGHT_DP))
        val params = previewFrameBox.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            previewFrameBox.layoutParams = params
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * 连续按键（尤其是长按的重复事件）时对取帧做节流：最多每
     * [PREVIEW_FRAME_THROTTLE_MS] 取一次，期间只记住最后落点，窗口结束后再取那一帧。
     * 否则每次重复事件都取消/新建解码任务，会拖慢进度条并堆积内存。
     */
    private fun schedulePreviewFrame(positionMs: Long) {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - previewFrameThrottleAt
        if (elapsed >= PREVIEW_FRAME_THROTTLE_MS) {
            previewFrameThrottleAt = now
            requestPreviewFrame(positionMs)
            return
        }
        previewFramePendingMs = positionMs
        if (previewFrameThrottleJob?.isActive == true) return
        previewFrameThrottleJob = lifecycleScope.launch {
            delay(PREVIEW_FRAME_THROTTLE_MS - elapsed)
            val pending = previewFramePendingMs
            previewFramePendingMs = -1L
            previewFrameThrottleAt = SystemClock.uptimeMillis()
            if (pending >= 0 && previewPositionMs != null) requestPreviewFrame(pending)
        }
    }

    /**
     * 取目标位置那一帧。命中预取缓存就直接显示，否则后台解码后显示并继续预取。
     * 取不到帧时只把预览框藏起来，不弹任何提示。
     */
    private fun requestPreviewFrame(positionMs: Long) {
        if (previewPositionMs == null) return
        val uri = mediaUri ?: run { hidePreviewFrameBox(); return }
        if (!canExtractPreviewFrame(uri)) {
            hidePreviewFrameBox()
            return
        }
        previewFrameJob?.cancel()
        previewPrefetchJob?.cancel()
        previewFrameCache[positionMs]?.let { cached ->
            showPreviewFrame(cached)
            prefetchPreviewFrames(positionMs)
            return
        }
        previewFrameSeq++
        val seq = previewFrameSeq
        previewFrameJob = lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { loadPreviewFrame(uri, positionMs) }.getOrNull()
            }
            if (!isActive || seq != previewFrameSeq || previewPositionMs == null) {
                bitmap?.recycle()
                return@launch
            }
            if (bitmap == null) {
                hidePreviewFrameBox()
                return@launch
            }
            cachePreviewFrame(positionMs, bitmap)
            showPreviewFrame(bitmap)
            prefetchPreviewFrames(positionMs)
        }
    }

    /** HLS 等取不到单帧的容器不做预览，避免出现一个无意义的空框。 */
    private fun canExtractPreviewFrame(uri: String): Boolean =
        !uri.contains(".m3u8", ignoreCase = true) &&
            (uri.startsWith("http://") || uri.startsWith("https://") ||
                uri.startsWith("content://") || uri.startsWith("file://"))

    /**
     * 后台解码关键帧。每次取帧都在 IO 线程里独立创建并释放 retriever，不和其它
     * 协程共享句柄，避免连续按键时互相踩到已释放的资源。API 27+ 直接解成预览框
     * 大小，避免为一个小窗分配整帧大图（4K 片源单帧就有数十 MB）。
     */
    private fun loadPreviewFrame(uri: String, positionMs: Long): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                retriever.setDataSource(uri, emptyMap<String, String>())
            } else {
                retriever.setDataSource(this, Uri.parse(uri))
            }
            val timeUs = positionMs * 1000
            if (Build.VERSION.SDK_INT >= 27) {
                val params = previewFrameBox.layoutParams
                retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    params.width.coerceAtLeast(1),
                    params.height.coerceAtLeast(1)
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (t: Throwable) {
            Timber.w(t, "Preview frame extraction failed at $positionMs ms")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun showPreviewFrame(bitmap: Bitmap) {
        previewShownBitmap = bitmap
        previewFrameImage.setImageBitmap(bitmap)
        previewFrameBox.visibility = View.VISIBLE
        previewPositionMs?.let { positionPreviewFrameBox(it) }
    }

    private fun hidePreviewFrameBox() {
        previewFrameBox.visibility = View.GONE
        previewFrameImage.setImageDrawable(null)
        previewShownBitmap = null
    }

    /**
     * 预取下一次按键最可能落到的两个位置（继续同向一步、反向一步），让连续按键时
     * 预览框可以立即切换。缓存只保留最近几帧，被淘汰的位图及时回收。
     */
    private fun prefetchPreviewFrames(fromMs: Long) {
        val uri = mediaUri ?: return
        val duration = player?.duration?.takeIf { it > 0 } ?: return
        val step = previewStepMs
        if (step <= 0 || !canExtractPreviewFrame(uri)) return
        val targets = listOf(fromMs + step, fromMs - step)
            .map { it.coerceIn(0L, duration) }
            .filter { it != fromMs && !previewFrameCache.containsKey(it) }
            .distinct()
        if (targets.isEmpty()) return
        previewPrefetchJob?.cancel()
        previewPrefetchJob = lifecycleScope.launch {
            targets.forEach { target ->
                if (!isActive || previewPositionMs == null) return@launch
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { loadPreviewFrame(uri, target) }.getOrNull()
                } ?: return@forEach
                if (!isActive || previewPositionMs == null) {
                    bitmap.recycle()
                    return@launch
                }
                cachePreviewFrame(target, bitmap)
            }
        }
    }

    private fun cachePreviewFrame(positionMs: Long, bitmap: Bitmap) {
        synchronized(previewFrameCache) {
            previewFrameCache[positionMs] = bitmap
            while (previewFrameCache.size > PREVIEW_FRAME_CACHE_SIZE) {
                val oldest = previewFrameCache.keys.firstOrNull() ?: break
                val removed = previewFrameCache.remove(oldest)
                if (removed != null && removed !== previewShownBitmap) removed.recycle()
            }
        }
    }

    private fun clearPreviewFrameCache() {
        synchronized(previewFrameCache) {
            previewFrameCache.values.forEach { if (it !== previewShownBitmap) it.recycle() }
            previewFrameCache.clear()
        }
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 镜像/图片模式：遥控器按 OK 或上下键先呼出旋转按钮并给它焦点，再按 OK 就旋转
        if (event.action == KeyEvent.ACTION_DOWN && !isDialogShowing && usesRotateOverlay() &&
            event.keyCode in ROTATE_REVEAL_KEYS) {
            val alreadyFocused = btnRotateOverlay.visibility == View.VISIBLE && btnRotateOverlay.isFocused
            showRotateButton(focus = true)
            if (!alreadyFocused) return true
        }
        val isOkKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (isOkKey && !isCurrentImage() && !isDialogShowing && player != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                // 预览进度模式下按 OK：从进度条所选位置开始播放
                if (previewPositionMs != null) {
                    commitPreviewSeek()
                    okPressCommittedSeek = true
                    return true
                }
                isLongPressSpeedActive = false
                okLongPressJob?.cancel()
                okLongPressJob = lifecycleScope.launch {
                    delay(500)
                    val speed = AppSettings.getLongPressSpeed(this@PlayerActivity)
                    player?.setPlaybackSpeed(speed)
                    isLongPressSpeedActive = true
                    speedIndicator.text = String.format("%.1f倍速中", speed)
                    speedIndicator.visibility = View.VISIBLE
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) return true
            if (event.action == KeyEvent.ACTION_UP) {
                if (okPressCommittedSeek) {
                    okPressCommittedSeek = false
                    return true
                }
                okLongPressJob?.cancel()
                if (isLongPressSpeedActive) {
                    player?.setPlaybackSpeed(1f)
                    currentSpeed = 1f
                    speedIndicator.visibility = View.GONE
                } else {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                }
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && !isDialogShowing) {
            // 镜像/Miracast 没有控制条，INFO 键是信息面板入口；MENU 键弹出「更多」菜单
            if (event.keyCode == KeyEvent.KEYCODE_INFO) {
                toggleStreamInfo()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                showMoreMenu()
                return true
            }
            // 面板打开时返回键先关面板；否则连按两次才退出投屏
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.repeatCount == 0) return handleBackPressed()
                return true
            }
        }
        val isForwardKey = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        val isRewindKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
        if ((isForwardKey || isRewindKey) && !isCurrentImage() && !isDialogShowing && player != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isSeekKeyDown = true
                    startSeekRepeatAcceleration()
                    handleSeekPress(forward = isForwardKey)
                } else if (!AppSettings.isSeekByPercent(this)) {
                    // 长按产生的重复事件每个都推进一次，倍数由 2 秒定时器单独翻倍。
                    // 按比例模式下不加速，重复事件一律忽略，等同普通单击。
                    handleSeekPress(forward = isForwardKey, multiplier = seekAccelMultiplier)
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                finishSeekRepeatAcceleration()
                onSeekKeyReleased()
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && !isCurrentImage() && !isDialogShowing) {
            val isPlayPauseKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            if (isPlayPauseKey && player != null) {
                player?.let { activePlayer ->
                    if (activePlayer.isPlaying) activePlayer.pause() else activePlayer.play()
                }
                playerView.showController()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 返回键：第一次只提示，2 秒内再按一次才真正退出投屏。
     *
     * @return 是否已消费该按键
     */
    private fun handleBackPressed(): Boolean {
        if (isStreamInfoVisible) {
            hideStreamInfo()
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (exitBackArmedAt != 0L && now - exitBackArmedAt <= EXIT_BACK_INTERVAL_MS) {
            exitBackArmedAt = 0L
            exitCast()
            return true
        }
        exitBackArmedAt = now
        Toast.makeText(this, "再按一次返回退出投屏", Toast.LENGTH_SHORT).show()
        return true
    }

    /** 用户主动退出：先断开当前投屏会话，再关界面，避免会话留在后台。 */
    private fun exitCast() {
        val disconnect = Intent(ACTION_DISCONNECT_SENDER).apply { setPackage(packageName) }
        when {
            isMiracastSession -> {
                disconnect.putExtra("miracast", true)
                sendBroadcast(disconnect)
            }
            isAirPlayMirrorSession -> {
                disconnect.putExtra("miracast", false)
                sendBroadcast(disconnect)
            }
        }
        stopPlayback(endCastSession = true)
        exitPlayer()
    }

    /**
     * 开始一次长按：倍数从 1 起步，按住期间每 2 秒翻倍（上限 16 倍）。
     * 这里只负责维护倍数，实际推进由系统按键重复事件驱动，所以进度条会连续移动，
     * 不会出现「每 2 秒才走一步」。
     *
     * 按比例跳转模式下不启用加速，倍数固定为 1，重复事件也不会推进。
     */
    private fun startSeekRepeatAcceleration() {
        seekAccelMultiplier = 1
        seekAccelJob?.cancel()
        if (AppSettings.isSeekByPercent(this) || !canPreviewSeek()) return
        seekAccelJob = lifecycleScope.launch {
            while (isActive) {
                delay(SEEK_ACCEL_STEP_MS)
                seekAccelMultiplier = (seekAccelMultiplier * 2).coerceAtMost(SEEK_ACCEL_MAX_MULTIPLIER)
            }
        }
    }

    /** 松开按键：立即停止加速并清除累计倍数，下次长按重新从 1 倍开始。 */
    private fun finishSeekRepeatAcceleration() {
        seekAccelJob?.cancel()
        seekAccelJob = null
        seekAccelMultiplier = 1
    }

    private fun handleIntent(intent: Intent?) {
        // 换源/换会话时先丢掉上一个内容的预览状态与位图
        cancelPreviewSeek()
        findViewById<SubtitleView>(R.id.airplay_subtitles).apply {
            setCues(emptyList())
            visibility = View.GONE
        }
        top.jast.miracastreceiver.castplus.CastPlusReceiver.current?.end()
        val nextCastToken = if (intent?.getStringExtra(EXTRA_SOURCE_TYPE) == "CAST")
            intent.getStringExtra(CastPlayerBridge.TOKEN) else null
        if (castToken != null && castToken != nextCastToken) reportCastState("IDLE", "INTERRUPTED", endSession = true)
        if (castToken == null && nextCastToken != null) reportPlaybackStopped()
        // Stop the old media before assigning the new load token: its callbacks must not
        // report IDLE/ERROR against the next Cast LOAD.
        if (castToken != null || nextCastToken != null) {
            castToken = null
            player?.stop()
            player?.clearMediaItems()
        }
        castToken = nextCastToken
        castIdleReason = null
        castStoppedBySender = false
        // 每次新的投屏请求都从 0° 开始
        rotationDegrees = 0
        manualRotationDegrees = 0
        autoRotationDegrees = 0
        cropSession++
        miracastContent = RectF(0f, 0f, 1f, 1f)
        cropCandidate = null
        cropConfirmations = 0
        lastCropSampleAt = 0L
        // Activity 可被新投屏请求复用；清角度之外还要清掉旧 TextureView 变换和输出目标。
        mirrorRotateTexture.setTransform(Matrix())
        mirrorRotateLayer.visibility = View.GONE
        exoRotateTexture?.setTransform(Matrix())
        exoRotateTexture?.let { player?.clearVideoTextureView(it) }
        exoRotateLayer?.visibility = View.GONE
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        (playerView.videoSurfaceView as? SurfaceView)?.let { player?.setVideoSurfaceView(it) }
        resetImageRotation()
        updateMirrorSurfaceTarget()
        if (intent?.getStringExtra(EXTRA_SOURCE_TYPE) == "MIRACAST") {
            manualRotationDegrees = top.jast.miracastreceiver.util.AppSettings.getMiracastManualRotation(this)
            startMiracastPlayback(intent.getIntExtra(EXTRA_RTP_PORT, 0), intent.getStringExtra(EXTRA_SESSION_ID))
            return
        }

        if (intent?.getBooleanExtra(EXTRA_IS_AIRPLAY_MIRROR, false) == true) {
            manualRotationDegrees = top.jast.miracastreceiver.util.AppSettings.getAirPlayManualRotation(this)
            startAirPlayMirrorPlayback()
            return
        }

        val list = intent?.getStringArrayListExtra(EXTRA_MEDIA_URIS)
            ?: intent?.getStringExtra(EXTRA_MEDIA_URI)?.let { arrayListOf(it) }
            ?: arrayListOf()
        val titles = intent?.getStringArrayListExtra(EXTRA_MEDIA_TITLES)
            ?: intent?.getStringExtra(EXTRA_MEDIA_TITLE)?.let { arrayListOf(it) }
            ?: arrayListOf()

        playlist = list.filter { it.isNotBlank() }
        playlistTitles = titles
        currentIndex = intent?.getIntExtra(EXTRA_START_INDEX, 0)?.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)) ?: 0

        if (playlist.isNotEmpty()) {
            playCurrent()
        }
    }

    private fun playCurrent() {
        mediaUri = playlist.getOrNull(currentIndex)
        mediaTitle = playlistTitles.getOrNull(currentIndex) ?: getString(R.string.title_dlna_indexed, currentIndex + 1, playlist.size)
        tvTitle.text = mediaTitle
        val uri = mediaUri ?: return
        if (castToken == null && isImageUri(uri)) {
            showImage(uri)
        } else {
            playMedia(uri)
        }
    }

    private fun startAirPlayMirrorPlayback() {
        Timber.i("Starting AirPlay mirror playback")
        isAirPlayMirrorSession = true
        isMiracastSession = false
        // AirPlay 镜像由发送端决定画面方向，不再提供右下角旋转按钮
        rotateButtonHideJob?.cancel()
        btnRotateOverlay.visibility = View.GONE
        streamInfoTracker.reset()
        screenOnSessionActive = true
        updateScreenOn()
        stopImageSlideShow()
        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.VISIBLE
        imageView.visibility = View.GONE
        tvTitle.text = getString(R.string.title_iphone_mirror)
        tvStatus.text = getString(R.string.status_receiving_iphone)
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        publishMirrorSurface()
        applyRotation()
        showRotateButton(focus = false)
    }

    /**
     * 把镜像 SurfaceView 的 Surface 发布给解码器。AirPlay 镜像和 Miracast 共用这一块
     * Surface —— 两者都是「解码器直接送显」的实时镜像，不经过 ExoPlayer。
     *
     * 应用切后台时 SurfaceView 会销毁 Surface、回到前台再造一个新的，所以必须持续跟踪
     * 变化并置空，否则解码器会往失效的 Surface 上写，画面一直黑。
     */
    private fun publishMirrorSurface() {
        val waitingText = getString(
            if (isMiracastSession) R.string.status_waiting_windows else R.string.status_waiting_airplay
        )
        val activeText = getString(
            if (isMiracastSession) R.string.status_receiving_windows else R.string.status_receiving_iphone
        )

        fun publish(holder: SurfaceHolder) {
            mirrorHolderSurface = holder.surface.takeIf { it.isValid }
            updateMirrorSurfaceTarget()
            Timber.i("Mirror surface ${if (mirrorSurface == null) "not ready" else "ready"}")
            tvStatus.text = if (mirrorSurface == null) waitingText else activeText
        }

        mirrorSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = publish(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = publish(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Timber.i("Mirror surface destroyed")
                mirrorHolderSurface = null
                updateMirrorSurfaceTarget()
            }
        })
        mirrorSurfaceView.post { publish(mirrorSurfaceView.holder) }
        startMirrorAspectFitUpdates()
    }

    private fun startMirrorAspectFitUpdates() {
        mirrorAspectJob?.cancel()
        mirrorAspectJob = lifecycleScope.launch {
            var lastW = 0
            var lastH = 0
            var lastParentW = 0
            var lastParentH = 0
            while (isActive) {
                sampleMiracastContent()
                val videoW = StreamStats.videoWidth
                val videoH = StreamStats.videoHeight
                // 手动旋转后父视图宽高会对调，也要重新适配，否则画面仍按旋转前的框显示
                val parent = mirrorSurfaceView.parent as? View
                val parentW = parent?.width ?: 0
                val parentH = parent?.height ?: 0
                // 只有真正适配成功才记下尺寸：上一会话残留的尺寸会在布局完成前就读到，
                // 此时父视图宽高为 0 无法适配，若提前记下就不再重试，画面会一直被拉伸铺满。
                if (videoW > 0 && videoH > 0 &&
                    (videoW != lastW || videoH != lastH || parentW != lastParentW || parentH != lastParentH) &&
                    fitMirrorSurface(videoW, videoH)) {
                    lastW = videoW
                    lastH = videoH
                    lastParentW = parentW
                    lastParentH = parentH
                    applyRotation()
                }
                delay(300)
            }
        }
    }

    /** 按视频比例缩放镜像 Surface；父视图尚未完成布局时返回 false，由调用方稍后重试。 */
    private fun fitMirrorSurface(videoW: Int, videoH: Int): Boolean {
        val parent = mirrorSurfaceView.parent as? View ?: return false
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return false

        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()
        val (targetW, targetH) = if (videoAspect > parentAspect) {
            parentW to (parentW / videoAspect).toInt()
        } else {
            (parentH * videoAspect).toInt() to parentH
        }

        mirrorSurfaceView.layoutParams = mirrorSurfaceView.layoutParams.apply {
            width = targetW.coerceAtLeast(1)
            height = targetH.coerceAtLeast(1)
        }
        Timber.i("AirPlay mirror aspect-fit: video=${videoW}x$videoH view=${targetW}x$targetH parent=${parentW}x$parentH")
        return true
    }

    /**
     * Miracast 显示。RTP 接收、TS 解复用和解码都由
     * [top.jast.miracastreceiver.miracast.WfdServer] 那条链路完成，这里只负责把镜像
     * Surface 交出去 —— 和 AirPlay 镜像完全同一套机制。
     *
     * 刻意不经过 ExoPlayer：播放器的缓冲和时钟同步会引入秒级延迟（实测超过 10 秒），
     * 而第二屏幕这种用途要的是「收到即解码、解完即送显」。
     */
    private fun startMiracastPlayback(rtpPort: Int, sessionId: String?) {
        Timber.i("Starting Miracast display: rtpPort=$rtpPort session=$sessionId")
        isMiracastSession = true
        isAirPlayMirrorSession = false
        streamInfoTracker.reset()
        screenOnSessionActive = true
        updateScreenOn()
        stopImageSlideShow()

        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        imageView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.VISIBLE
        tvTitle.text = getString(R.string.title_windows_display)
        tvStatus.text = getString(R.string.status_receiving_windows)
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        publishMirrorSurface()
        applyRotation()
        showRotateButton(focus = false)
    }

    private fun playMedia(uri: String) {
        Timber.i("Playing media: $uri")
        isMiracastSession = false
        isAirPlayMirrorSession = false
        screenOnSessionActive = true
        streamInfoTracker.reset()
        stopImageSlideShow()
        imageView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideRotateButton()
        applyRotation()

        try {
            tvError.visibility = View.GONE
            updateBufferingState(true)

            // 根据 URI 判断媒体类型
            val mediaItem = createMediaItem(uri)

            val items = playlist.mapIndexedNotNull { index, itemUri ->
                if (!isImageUri(itemUri)) {
                    createMediaItem(itemUri).also { if (index == currentIndex) mediaUri = itemUri }
                } else null
            }
            if (playlist.size > 1 && items.isNotEmpty()) {
                val videoIndex = playlist.take(currentIndex + 1).count { !isImageUri(it) } - 1
                player?.setMediaItems(items, videoIndex.coerceAtLeast(0), 0L)
            } else {
                player?.setMediaItem(mediaItem)
            }
            player?.prepare()
            if (castToken != null) {
                player?.seekTo(intent.getLongExtra(CastPlayerBridge.POSITION, 0))
                player?.volume = intent.getFloatExtra(CastPlayerBridge.VOLUME, 1f).coerceIn(0f, 1f)
                player?.playWhenReady = intent.getBooleanExtra(CastPlayerBridge.AUTOPLAY, true)
                reportCastState()
            } else player?.play()
            updateScreenOn()
        } catch (e: Exception) {
            screenOnSessionActive = false
            updateScreenOn()
            castIdleReason = "ERROR"
            reportCastState("IDLE")
            Timber.e(e, "Error playing media")
            showPlaybackError(e.message)
            tvError.visibility = View.VISIBLE
            updateBufferingState(false)
        }
    }

    private fun showPlaybackError(message: String?) {
        tvStatus.text = getString(R.string.status_playback_error)
        tvError.text = getString(R.string.error_playback_detail, message ?: getString(R.string.error_unknown))
    }

    private fun createMediaItem(uri: String): MediaItem {
        castToken?.let { token ->
            return MediaItem.Builder().setMediaId(token).setUri(uri)
                .setMimeType(intent.getStringExtra(CastPlayerBridge.MIME)).build()
        }
        // 检测 HLS 流
        val isHls = uri.contains(".m3u8") || uri.contains("/playlist/m3u8")

        return if (isHls) {
            // HLS 流：明确指定 MIME 类型
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        } else {
            // 其他格式：让 ExoPlayer 自动检测
            MediaItem.fromUri(uri)
        }
    }

    private fun showImage(uri: String) {
        Timber.i("Showing image: $uri")
        isMiracastSession = false
        isAirPlayMirrorSession = false
        screenOnSessionActive = true
        player?.pause()
        playerView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        updateScreenOn()
        tvError.visibility = View.GONE
        tvStatus.text = getString(R.string.status_showing_image)
        updateBufferingState(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        applyRotation()
        showRotateButton(focus = false)

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    if (uri.startsWith("http://") || uri.startsWith("https://")) {
                        URL(uri).openStream().use { BitmapFactory.decodeStream(it) }
                    } else {
                        contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                    }
                }
                updateBufferingState(false)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    adaptOrientationToImage(bitmap.width, bitmap.height)
                    imageView.post { applyRotation() }
                    startImageSlideShow()
                } else {
                    throw IllegalArgumentException(getString(R.string.error_image_decode))
                }
            } catch (e: Exception) {
                screenOnSessionActive = false
                updateScreenOn()
                Timber.e(e, "Error showing image")
                updateBufferingState(false)
                tvStatus.text = getString(R.string.status_image_error)
                tvError.text = getString(R.string.error_image_detail, e.message ?: getString(R.string.error_unknown))
                tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun startImageSlideShow() {
        if (playlist.count { isImageUri(it) } <= 1) return
        slideJob?.cancel()
        slideJob = lifecycleScope.launch {
            while (isActive && isCurrentImage()) {
                delay(IMAGE_SLIDE_INTERVAL_MS)
                playNextOrFinish(loopImages = true)
            }
        }
    }

    private fun stopImageSlideShow() {
        slideJob?.cancel()
        slideJob = null
        if (isCurrentImage()) tvStatus.text = getString(R.string.status_slideshow_paused)
    }

    private fun playNextOrFinish(loopImages: Boolean = false) {
        if (playlist.isEmpty()) return
        if (currentIndex < playlist.lastIndex) {
            currentIndex++
            playCurrent()
        } else if (loopImages) {
            currentIndex = 0
            playCurrent()
        } else {
            updateBufferingState(false)
            reportPlaybackStopped()
        }
    }

    private fun isCurrentImage(): Boolean = mediaUri?.let { isImageUri(it) } == true

    private fun isImageUri(uri: String): Boolean {
        val clean = uri.substringBefore('?').lowercase()
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".gif") || clean.endsWith(".webp") || clean.endsWith(".bmp") ||
            clean.startsWith("content://") && clean.contains("image")
    }

    private fun updateBufferingState(isBuffering: Boolean) {
        progressBar.visibility = if (isBuffering) View.VISIBLE else View.GONE
        bufferingIndicator.visibility = if (isBuffering) View.VISIBLE else View.GONE
        if (isBuffering) startLoadingSpeedUpdates() else stopLoadingSpeedUpdates()
    }

    /**
     * 加载圆圈显示期间，每 [LOADING_SPEED_INTERVAL_MS] 采样一次 ExoPlayer 的累计下载
     * 字节数并差分出真实加载速率，显示在圆圈正下方；没有可统计的下载时显示 0 KB/s。
     */
    private fun startLoadingSpeedUpdates() {
        loadingSpeedTracker.reset()
        // 加载一开始就显示 0 KB/s，等首次采样（500ms 后）再刷新为真实速率
        tvLoadingSpeed.text = "0 KB/s"
        tvLoadingSpeed.visibility = View.VISIBLE
        loadingSpeedJob?.cancel()
        loadingSpeedJob = lifecycleScope.launch {
            while (isActive) {
                delay(LOADING_SPEED_INTERVAL_MS)
                val sample = loadingSpeedTracker.sample(loadedBytesTotal, 0L)
                // 复用信息面板的格式化：≥1 MiB/s 用 MB/s 一位小数，否则 KB/s 整数；
                // 速度为 0 时 formatSpeed 会返回占位符，这里固定显示 0 KB/s。
                tvLoadingSpeed.text = if (sample.bytesPerSec > 0) {
                    StreamInfoTracker.formatSpeed(sample.bytesPerSec)
                } else {
                    "0 KB/s"
                }
            }
        }
    }

    /** 圆圈消失时同步隐藏加载速度，不残留文字。 */
    private fun stopLoadingSpeedUpdates() {
        loadingSpeedJob?.cancel()
        loadingSpeedJob = null
        tvLoadingSpeed.visibility = View.GONE
    }

    // ─── 视频流信息面板 ────────────────────────────────────────────────────
    /** 切换信息面板。镜像/Miracast 没有控制条，只能靠遥控 INFO / MENU 键触发。 */
    private fun toggleStreamInfo() {
        if (isStreamInfoVisible) hideStreamInfo() else showStreamInfo()
    }

    private fun showStreamInfo() {
        isStreamInfoVisible = true
        streamInfoTracker.reset()
        tvStreamInfo.text = buildStreamInfoText()
        tvStreamInfo.visibility = View.VISIBLE
        streamInfoJob?.cancel()
        streamInfoJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                tvStreamInfo.text = buildStreamInfoText()
            }
        }
    }

    private fun hideStreamInfo() {
        isStreamInfoVisible = false
        streamInfoJob?.cancel()
        streamInfoJob = null
        tvStreamInfo.visibility = View.GONE
    }

    private fun buildStreamInfoText(): String = when {
        isAirPlayMirrorSession -> buildAirPlayStreamInfo()
        isMiracastSession -> buildMiracastStreamInfo()
        else -> buildExoPlayerStreamInfo()
    }

    /** DLNA / 普通网络播放：分辨率、帧率、码率取自当前视频轨，网速取带宽估计。 */
    private fun buildExoPlayerStreamInfo(): String {
        val currentPlayer = player
        val format = currentPlayer?.videoFormat
        val videoSize = currentPlayer?.videoSize
        val bitrateBps = listOfNotNull(format?.bitrate, format?.averageBitrate, format?.peakBitrate)
            .firstOrNull { it != Format.NO_VALUE }?.toLong() ?: 0L
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(videoSize?.width ?: 0, videoSize?.height ?: 0),
            codec = StreamInfoTracker.formatCodec(format?.sampleMimeType),
            fps = StreamInfoTracker.formatFps(format?.frameRate ?: 0f),
            bitrate = StreamInfoTracker.formatBitrate(bitrateBps),
            speed = StreamInfoTracker.formatSpeed(bandwidthEstimateBps / 8)
        )
    }

    /** AirPlay 镜像：码率只算视频流，网速把音频流字节也算进去。 */
    private fun buildAirPlayStreamInfo(): String {
        val videoBytes = StreamStats.videoBytesTotal
        val totalBytes = videoBytes + StreamStats.audioBytesTotal
        val sample = streamInfoTracker.sample(totalBytes, StreamStats.videoFramesTotal)
        // 视频码率 = 总码率中扣掉音频部分，按本次采样的视频/总字节比例折算
        val videoShare = if (totalBytes > 0) videoBytes.toDouble() / totalBytes else 1.0
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(StreamStats.videoWidth, StreamStats.videoHeight),
            codec = StreamInfoTracker.formatCodec(StreamStats.videoCodec),
            fps = StreamInfoTracker.formatFps(sample.fps.toFloat()),
            bitrate = StreamInfoTracker.formatBitrate((sample.bitrateBps * videoShare).toLong()),
            speed = StreamInfoTracker.formatSpeed(sample.bytesPerSec)
        )
    }

    /**
     * Miracast：不经过 ExoPlayer，所以分辨率取解码器上报的值（[StreamStats]，由
     * VideoDecoder 写入），码率和网速取 [RtpReceiver] 的累计字节数。
     * 帧率暂不统计 —— 解码路径上没有帧计数器。
     */
    private fun buildMiracastStreamInfo(): String {
        val sample = streamInfoTracker.sample(RtpReceiver.active?.bytesReceived ?: 0L, 0L)
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(StreamStats.videoWidth, StreamStats.videoHeight),
            codec = StreamInfoTracker.formatCodec("video/avc"),
            fps = StreamInfoTracker.formatFps(0f),
            bitrate = StreamInfoTracker.formatBitrate(sample.bitrateBps),
            speed = StreamInfoTracker.formatSpeed(sample.bytesPerSec)
        )
    }

    private fun streamInfoLines(
        resolution: String,
        codec: String,
        fps: String,
        bitrate: String,
        speed: String
    ): String {
        val (displayW, displayH) = currentDisplaySize()
        val rows = listOf(
            getString(R.string.info_source_resolution) to resolution,
            getString(R.string.info_display_resolution) to StreamInfoTracker.formatResolution(displayW, displayH),
            getString(R.string.info_codec) to codec,
            getString(R.string.info_frame_rate) to fps,
            getString(R.string.info_bitrate) to bitrate,
            getString(R.string.info_network_speed) to speed
        )
        // 各语言标签长短差别很大（英文、俄文远比中文长），按最长标签对齐值列
        val columns = rows.maxOf { StreamInfoTracker.labelWidth(it.first) } + 2
        return rows.joinToString("\n", prefix = getString(R.string.info_title) + "\n") { (label, value) ->
            StreamInfoTracker.padLabel(label, columns) + value
        }
    }

    /**
     * 画面实际渲染到屏幕上的像素尺寸。
     * PlayerView 会按比例把内容框缩到片源宽高比，所以这里拿到的是去掉黑边后的真实显示尺寸；
     * 镜像模式则读 [fitMirrorSurface] 调整过的镜像 Surface。视图还没测量时退回屏幕分辨率。
     */
    private fun currentDisplaySize(): Pair<Int, Int> {
        val renderView = if (isAirPlayMirrorSession) mirrorSurfaceView else playerView.videoSurfaceView
        val width = renderView?.width ?: 0
        val height = renderView?.height ?: 0
        if (width > 0 && height > 0) return width to height
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun showQualityDialog() {
        val labels = arrayOf(
            getString(R.string.quality_auto),
            getString(R.string.quality_480),
            getString(R.string.quality_720),
            getString(R.string.quality_1080),
            getString(R.string.quality_original)
        )
        val heights = intArrayOf(QUALITY_AUTO, 480, 720, 1080, Int.MAX_VALUE)
        val checked = heights.indexOf(qualityHeight).takeIf { it >= 0 } ?: 0
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                qualityHeight = heights[which]
                applyQuality(qualityHeight)
                dialog.dismiss()
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun applyQuality(height: Int) {
        val builder = trackSelector?.buildUponParameters() ?: return
        when (height) {
            QUALITY_AUTO -> builder.clearVideoSizeConstraints()
            Int.MAX_VALUE -> builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            else -> builder.setMaxVideoSize(Int.MAX_VALUE, height)
        }
        trackSelector?.setParameters(builder)
        tvStatus.text = getString(
            R.string.status_quality,
            if (height == QUALITY_AUTO) getString(R.string.quality_auto) else "${height}p"
        )
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.speed_title)
            .setItems(labels) { _, which ->
                player?.setPlaybackSpeed(speeds[which])
                currentSpeed = speeds[which]
                tvStatus.text = getString(R.string.status_speed, labels[which])
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun adaptOrientationToVideo() {
        // TV 端固定横屏显示，竖屏视频由播放器按比例居中渲染。
        // 不再根据视频宽高切换 Activity 方向，避免 Surface 重建导致竖屏投屏反复缓冲/黑屏。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun adaptOrientationToImage(width: Int, height: Int) {
        requestedOrientation = if (width >= height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    /**
     * 手动把画面顺时针转 90°。每次都朝同一方向转，转满一圈回到 0°。
     * 只旋转画面内容、不改屏幕方向，所以电视（忽略方向请求）上也有效。
     */
    private fun rotateClockwise() {
        manualRotationDegrees = (manualRotationDegrees + 90) % 360
        Timber.i("Manual rotation offset -> $manualRotationDegrees°")
        applyRotation()
    }

    /** 按当前模式把 [rotationDegrees] 应用到画面上；尺寸变化时也会被反复调用，要保证幂等。 */
    private fun applyRotation() {
        autoRotateToFit = top.jast.miracastreceiver.util.AppSettings.isAutoRotateToFit(this)
        if (autoRotateToFit && (isMirrorSession() || !isCurrentImage())) {
            val content = findViewById<View>(android.R.id.content)
            val size = player?.videoSize
            val w = if (isMirrorSession()) StreamStats.videoWidth.toFloat() * activeMirrorCrop().width()
                else (size?.width ?: 0) * (size?.pixelWidthHeightRatio ?: 1f)
            val h = if (isMirrorSession()) StreamStats.videoHeight * activeMirrorCrop().height() else (size?.height ?: 0).toFloat()
            if (w > 0 && h > 0 && content.width > 0 && content.height > 0) {
                val targetRotation = if ((w > h) != (content.width > content.height)) 90 else 0
                if (autoRotationDegrees != targetRotation) {
                    Timber.i("Auto rotation: video=${w}x$h screen=${content.width}x${content.height} -> $targetRotation")
                    autoRotationDegrees = targetRotation
                }
            }
        } else {
            autoRotationDegrees = 0
        }
        rotationDegrees = (autoRotationDegrees + manualRotationDegrees) % 360
        if (isAirPlayMirrorSession && top.jast.miracastreceiver.util.AppSettings.setAirPlayRotation(
                this, manualRotationDegrees, rotationDegrees)) {
            Toast.makeText(this, R.string.airplay_resolution_reconnect, Toast.LENGTH_LONG).show()
        }
        if (isMiracastSession && top.jast.miracastreceiver.util.AppSettings.setMiracastRotation(
                this, manualRotationDegrees, rotationDegrees)) {
            Toast.makeText(this, R.string.miracast_resolution_reconnect, Toast.LENGTH_LONG).show()
        }
        when {
            isMirrorSession() -> applyMirrorRotation()
            isCurrentImage() -> applyImageRotation()
            else -> applyExoRotation()
        }
        // 离开的模式恢复原状，避免残留的变换带到下一个模式
        if (!isMirrorSession()) setMirrorRotateLayerVisible(false)
        if (!isCurrentImage() || isMirrorSession()) resetImageRotation()
    }

    private fun applyMirrorRotation() {
        val needsTexture = rotationDegrees != 0 || activeMirrorCrop().width() < 0.99f
        setMirrorRotateLayerVisible(needsTexture)
        if (needsTexture) {
            setTextureRotation(mirrorRotateTexture, StreamStats.videoWidth, StreamStats.videoHeight)
        }
    }

    private fun setMirrorRotateLayerVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (mirrorRotateLayer.visibility != target) mirrorRotateLayer.visibility = target
        updateMirrorSurfaceTarget()
    }

    /**
     * 选出解码器该往哪块 Surface 送帧：旋转时用 TextureView（可以做变换），
     * 0° 时用 SurfaceView（走硬件视频层，电视上 4K 也不卡）。解码器检测到 Surface
     * 变化会原地切换输出（见 VideoDecoder.switchOutputSurface）。
     */
    private fun updateMirrorSurfaceTarget() {
        if (surfacePublisherStopped || isFinishing) return
        // TextureView 第一次显示时 SurfaceTexture 要等一帧绘制才就绪。这段时间继续用 SurfaceView，
        // 千万不能发布 null：解码器会当成 Surface 丢失而重建，重建后要等 iPhone 的下一个关键帧，
        // 静止画面下可能一直黑屏。就绪后再原地切过去。
        val useTexture = isMirrorSession() && (rotationDegrees != 0 || activeMirrorCrop().width() < 0.99f) && mirrorTextureSurface != null
        val source = when {
            isAirPlayMirrorSession -> MirrorSurfaceSource.AIRPLAY
            isMiracastSession -> MirrorSurfaceSource.MIRACAST
            else -> null
        }
        val target = if (source == null) null else if (useTexture) mirrorTextureSurface else mirrorHolderSurface
        if (target !== mirrorSurface) {
            Timber.i("Mirror render target -> ${if (useTexture) "TextureView" else "SurfaceView"} (${if (target == null) "not ready" else "ready"})")
        }
        updatePublishedMirrorSurface(mirrorSurfaceToken, target, source)
    }

    private fun applyExoRotation() {
        val currentPlayer = player ?: return
        // overlay 位于 PlayerView 的内容框内。旋转时先让内容框占满屏幕，
        // 否则竖屏片源会先被缩成窄条，旋转后再次缩放，画面始终很小。
        val resizeMode = if (rotationDegrees == 0) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
        } else {
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        if (playerView.resizeMode != resizeMode) playerView.resizeMode = resizeMode
        if (rotationDegrees == 0) {
            if (exoRotateLayer?.visibility == View.VISIBLE) {
                exoRotateLayer?.visibility = View.GONE
                exoRotateTexture?.let { currentPlayer.clearVideoTextureView(it) }
                (playerView.videoSurfaceView as? SurfaceView)?.let { currentPlayer.setVideoSurfaceView(it) }
            }
            return
        }
        val texture = exoRotateTexture ?: createExoRotateLayer() ?: return
        if (exoRotateLayer?.visibility != View.VISIBLE) {
            exoRotateLayer?.visibility = View.VISIBLE
            currentPlayer.setVideoTextureView(texture)
        }
        val size = currentPlayer.videoSize
        setTextureRotation(texture, (size.width * size.pixelWidthHeightRatio).toInt(), size.height)
    }

    /** 在 PlayerView 的 overlay 层（视频之上、控制条之下）放一块黑底 TextureView 承接旋转后的画面。 */
    private fun createExoRotateLayer(): TextureView? {
        val overlay = playerView.overlayFrameLayout ?: return null
        val texture = TextureView(this)
        val layer = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.GONE
            addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // 尺寸变化（转屏、首次布局）后重新算变换
        texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (rotationDegrees != 0) applyRotation() }
        overlay.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        exoRotateLayer = layer
        exoRotateTexture = texture
        return texture
    }

    /**
     * TextureView 默认把画面拉伸铺满整个视图。这里先还原成视频本身的宽高、绕中心旋转，
     * 再按旋转后的宽高等比缩放到能完整放进视图，效果等同 fitCenter + 旋转。
     */
    private fun setTextureRotation(texture: TextureView, videoW: Int, videoH: Int) {
        val viewW = texture.width.toFloat()
        val viewH = texture.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val w = if (videoW > 0) videoW.toFloat() else viewW
        val h = if (videoH > 0) videoH.toFloat() else viewH
        val crop = if (texture === mirrorRotateTexture) activeMirrorCrop() else RectF(0f, 0f, 1f, 1f)
        applyVideoTextureTransform(texture, videoW, videoH, rotationDegrees,
            VideoContent(crop.left, crop.top, crop.right, crop.bottom))
    }

    /** 图片直接转 ImageView；转 90°/270° 时按旋转后的宽高重新等比缩放，保证完整显示。 */
    private fun applyImageRotation() {
        val drawable = imageView.drawable
        val viewW = imageView.width.toFloat()
        val viewH = imageView.height.toFloat()
        var scale = 1f
        if (drawable != null && viewW > 0f && viewH > 0f && rotationDegrees % 180 != 0) {
            val iw = drawable.intrinsicWidth.toFloat()
            val ih = drawable.intrinsicHeight.toFloat()
            if (iw > 0f && ih > 0f) {
                val fitNormal = minOf(viewW / iw, viewH / ih)
                val fitRotated = minOf(viewW / ih, viewH / iw)
                scale = fitRotated / fitNormal
            }
        }
        imageView.rotation = rotationDegrees.toFloat()
        imageView.scaleX = scale
        imageView.scaleY = scale
    }

    private fun resetImageRotation() {
        if (imageView.rotation != 0f || imageView.scaleX != 1f) {
            imageView.rotation = 0f
            imageView.scaleX = 1f
            imageView.scaleY = 1f
        }
    }

    private fun isMirrorSession() = isAirPlayMirrorSession || isMiracastSession

    private fun activeMirrorCrop(): RectF =
        if (isMiracastSession && (autoRotateToFit || manualRotationDegrees != 0)) miracastContent else RectF(0f, 0f, 1f, 1f)

    /** 只分析 Miracast 原始解码帧，避免把旋转后的显示结果再次当作输入。 */
    private fun sampleMiracastContent() {
        if (Build.VERSION.SDK_INT < 24 || !isMiracastSession ||
            (!top.jast.miracastreceiver.util.AppSettings.isAutoRotateToFit(this) && manualRotationDegrees == 0) || cropCopyPending) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCropSampleAt < 1000) return
        val surface = mirrorSurface?.takeIf { it.isValid } ?: return
        lastCropSampleAt = now
        val session = cropSession
        val bitmap = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888)
        cropCopyPending = true
        try {
            PixelCopy.request(surface, bitmap, { result ->
                cropCopyPending = false
                try {
                    if (result == PixelCopy.SUCCESS && session == cropSession && !isDestroyed && isMiracastSession) {
                        detectMiracastContent(bitmap)
                    }
                } finally { bitmap.recycle() }
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) {
            cropCopyPending = false
            bitmap.recycle()
        }
    }

    private fun detectMiracastContent(bitmap: Bitmap) {
        fun darkColumn(x: Int): Boolean {
            var lit = 0
            for (y in 0 until bitmap.height) {
                val pixel = bitmap.getPixel(x, y)
                if (maxOf((pixel shr 16) and 255, (pixel shr 8) and 255, pixel and 255) > 24) lit++
            }
            return lit <= 1
        }
        var left = 0
        var right = bitmap.width - 1
        while (left < right && darkColumn(left)) left++
        while (right > left && darkColumn(right)) right--
        val width = right - left + 1
        // 全黑、极窄内容和不对称暗区不参与判断，保留上一次可靠结果。
        if (width < bitmap.width / 5 || kotlin.math.abs(left - (bitmap.width - 1 - right)) > 6) {
            cropCandidate = null
            cropConfirmations = 0
            return
        }
        // 留出两列保护边界，只处理两侧都存在的明显黑边。
        val candidate = if (left >= 6 && bitmap.width - 1 - right >= 6)
            RectF((left - 2f) / bitmap.width, 0f, (right + 3f) / bitmap.width, 1f)
            else RectF(0f, 0f, 1f, 1f)
        val old = cropCandidate
        cropConfirmations = if (old != null && kotlin.math.abs(old.left - candidate.left) < 0.02f &&
            kotlin.math.abs(old.right - candidate.right) < 0.02f) cropConfirmations + 1 else 1
        cropCandidate = candidate
        if (cropConfirmations >= 3 && (kotlin.math.abs(miracastContent.left - candidate.left) > 0.02f ||
                kotlin.math.abs(miracastContent.right - candidate.right) > 0.02f)) {
            miracastContent = candidate
            Timber.i("Miracast content detected: $candidate")
            applyRotation()
        }
    }


    /** Miracast 镜像和图片没有控制条，用浮动按钮旋转；AirPlay 镜像由发送端控制方向，不显示。 */
    private fun usesRotateOverlay() = isMiracastSession || isCurrentImage()

    /** 呼出浮动旋转按钮，几秒无操作后自动隐藏。[focus] 为 true 时让遥控器焦点落在按钮上，按 OK 即可旋转。 */
    private fun showRotateButton(focus: Boolean) {
        if (!usesRotateOverlay()) return
        btnRotateOverlay.visibility = View.VISIBLE
        if (focus) btnRotateOverlay.requestFocus()
        rotateButtonHideJob?.cancel()
        rotateButtonHideJob = lifecycleScope.launch {
            delay(ROTATE_BUTTON_HIDE_DELAY_MS)
            btnRotateOverlay.visibility = View.GONE
        }
    }

    private fun hideRotateButton() {
        rotateButtonHideJob?.cancel()
        btnRotateOverlay.visibility = View.GONE
    }

    // 镜像/图片模式没有控制条，点一下屏幕任意位置呼出旋转按钮
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showRotateButton(focus = false)
        return super.dispatchTouchEvent(ev)
    }

    private fun registerControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_REPLACED)
            addAction(ACTION_PLAY)
            addAction(CastPlayerBridge.ACTION_COMMAND)
            addAction(ACTION_PAUSE)
            addAction(ACTION_STOP)
            addAction(ACTION_SEEK)
            addAction(ACTION_SET_VOLUME)
            addAction(ACTION_SET_PLAYLIST)
            addAction(ACTION_SET_SPEED)
            addAction(ACTION_SET_QUALITY_URL)
        }
        // 同上：带 flags 的 registerReceiver 是 API 26 起才有的重载
        ContextCompat.registerReceiver(
            this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStart() {
        super.onStart()
        isPlayerVisible = true
        if (isCurrentImage()) startImageSlideShow() else {
            if (castToken == null) player?.play()
            startProgressUpdates()
        }
        if (isStreamInfoVisible) showStreamInfo()
        updateScreenOn()
    }

    override fun onStop() {
        isPlayerVisible = false
        // 退到后台时收不到按键抬起事件，这里兜底清掉长按跳转加速和预览进度状态
        finishSeekRepeatAcceleration()
        cancelPreviewSeek()
        okLongPressJob?.cancel()
        updateScreenOn()
        super.onStop()
        if (isCurrentImage()) stopImageSlideShow() else player?.pause()
        stopProgressUpdates()
        // 保留 isStreamInfoVisible，回到前台时自动恢复刷新
        streamInfoJob?.cancel()
        streamInfoJob = null
        reportPlaybackPosition()
    }

    /** TV 屏保依据前台窗口的常亮标志，不能依赖解码器或 PlayerView 的 Surface。 */
    private fun updateScreenOn() {
        val current = player
        val playing = current != null && current.playerError == null && current.playWhenReady &&
            (current.playbackState == Player.STATE_BUFFERING || current.playbackState == Player.STATE_READY)
        val keepOn = isPlayerVisible && screenOnSessionActive &&
            (isMirrorSession() || isCurrentImage() || playing)
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopPlayback(endCastSession: Boolean = false) {
        screenOnSessionActive = false
        finishSeekRepeatAcceleration()
        cancelPreviewSeek()
        stopImageSlideShow()
        stopProgressUpdates()
        mirrorAspectJob?.cancel()
        mirrorAspectJob = null
        updateScreenOn()
        player?.stop()
        reportPlaybackStopped(endCastSession)
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressUpdateJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                reportPlaybackPosition()
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    private fun reportPlaybackPosition() {
        if (castToken != null) { reportCastState(); return }
        val currentPlayer = player ?: return
        val position = currentPlayer.currentPosition
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: 0L

        // 发送广播以更新 DLNA renderer 状态
        val intent = Intent("top.jast.miracastreceiver.ACTION_UPDATE_POSITION").apply {
            putExtra("position", position)
            putExtra("duration", duration)
            putExtra("is_playing", currentPlayer.isPlaying)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * TV 端本地退出/播放结束时通知 DLNA 渲染器状态归零，
     * 否则 Emby 会一直认为该设备处于播放中，无法取消投屏或切换到新文件。
     */
    private fun reportPlaybackStopped(endCastSession: Boolean = false) {
        if (castToken != null) {
            reportCastState("IDLE", castIdleReason ?: "CANCELLED", endSession = endCastSession)
            return
        }
        val intent = Intent(ACTION_PLAYBACK_STOPPED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun reportCastState(forcedState: String? = null, reason: String? = null, endSession: Boolean = false) {
        val token = castToken ?: return
        val current = player
        if (forcedState == null && current?.currentMediaItem?.mediaId != token) return
        val state = forcedState ?: when {
            current?.playerError != null -> "IDLE"
            current?.playbackState == Player.STATE_ENDED -> "IDLE"
            current?.playbackState == Player.STATE_BUFFERING -> "BUFFERING"
            current?.playbackState == Player.STATE_READY -> if (current.playWhenReady) "PLAYING" else "PAUSED"
            else -> "IDLE"
        }
        if (reason != null) castIdleReason = reason
        else if (current?.playerError != null) castIdleReason = "ERROR"
        else if (current?.playbackState == Player.STATE_ENDED) castIdleReason = "FINISHED"
        sendBroadcast(Intent(CastPlayerBridge.ACTION_STATE).setPackage(packageName).apply {
            putExtra(CastPlayerBridge.TOKEN, token)
            putExtra(CastPlayerBridge.SESSION_ENDED, endSession)
            putExtra("state", state)
            putExtra("position", current?.currentPosition?.coerceAtLeast(0) ?: 0L)
            putExtra("duration", current?.duration?.takeIf { it > 0 } ?: 0L)
            putExtra("idle_reason", if (state == "IDLE") castIdleReason else null)
            putExtra(CastPlayerBridge.VOLUME, current?.volume ?: 1f)
        })
    }

    override fun onDestroy() {
        surfacePublisherStopped = true
        screenOnSessionActive = false
        isPlayerVisible = false
        cropSession++ // 丢弃退出前尚未返回的取帧结果。
        seekAccelJob?.cancel()
        seekAccelJob = null
        cancelPreviewSeek()
        super.onDestroy()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering receiver")
        }
        stopImageSlideShow()
        mirrorAspectJob?.cancel()
        mirrorAspectJob = null
        streamInfoJob?.cancel()
        streamInfoJob = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // RTP 接收器归 WfdServer 所有（会话结束时由它停止），这里不要碰
        updatePublishedMirrorSurface(mirrorSurfaceToken, null, null)
        mirrorTextureSurface?.release()
        mirrorTextureSurface = null
        player?.release()
        player = null
        reportPlaybackStopped(endCastSession = isFinishing && !castStoppedBySender)
        Timber.i("PlayerActivity destroyed")
    }

    /**
     * 退出播放界面的统一出口。遥控器返回键和手机端「停止投屏」都走这里。
     *
     * 开关关闭时回电视桌面，但这里不能只用 finish()：设置页经常已经压在同一个任务栈底下
     * （用户先在电视上打开过 App 看 IP 或改设置），单 finish() 只是把播放页弹掉，
     * 露出来的是下面那一页，看起来就是「退出投屏又回到了这个应用」。
     * 用 finishAffinity() 把同一任务里同 affinity 的页面一起结束，任务空下来才回到桌面。
     * 实测 finishAndRemoveTask() 在部分电视固件上只关掉播放页、不动下面的页面，不可用。
     *
     * 开关打开时走另一条路：把设置页带到栈顶再结束播放页。用 CLEAR_TOP + SINGLE_TOP
     * 复用已经在栈里的实例（否则会再叠一个设置页，返回一次才退干净）。
     */
    private fun exitPlayer() {
        if (AppSettings.isReturnToAppAfterCast(this)) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
            finish()
        } else {
            finishAffinity()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackPressed()
    }
}
