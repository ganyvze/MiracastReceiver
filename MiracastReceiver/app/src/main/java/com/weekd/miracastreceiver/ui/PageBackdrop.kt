package com.weekd.miracastreceiver.ui

import android.net.Uri
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.weekd.miracastreceiver.util.AppSettings
import timber.log.Timber

/**
 * 主页 / 设置页的自定义背景：把用户选的图片、GIF 或视频铺到内容底下的容器里。
 *
 * 容器底下仍然是默认的渐变背景（bg_main_glass_backdrop），所以媒体加载失败时
 * 页面还是原来的样子；背景上面再盖一层暗色遮罩保证文字可读。
 */
class PageBackdrop(
    private val activity: ComponentActivity,
    private val container: FrameLayout,
    private val scrim: View
) {
    private var mediaView: View? = null
    private var videoPlayer: ExoPlayer? = null

    init {
        // 页面不可见时暂停背景视频，回到前台再继续，避免后台白耗解码资源
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                videoPlayer?.pause()
            }

            override fun onStart(owner: LifecycleOwner) {
                videoPlayer?.play()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                releaseVideo()
            }
        })
    }

    /** 按当前设置应用背景；重复调用会先清理上一次的媒体视图和播放器。 */
    fun apply() {
        clear()
        val type = AppSettings.getBackgroundType(activity)
        val uri = AppSettings.getBackgroundUri(activity)?.let(Uri::parse)
        if (type == AppSettings.BACKGROUND_NONE || uri == null) return

        when (type) {
            AppSettings.BACKGROUND_IMAGE, AppSettings.BACKGROUND_GIF -> showImage(uri)
            AppSettings.BACKGROUND_VIDEO -> showVideo(uri)
            else -> return
        }
        scrim.visibility = View.VISIBLE
    }

    private fun showImage(uri: Uri) {
        val imageView = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        container.addView(imageView)
        mediaView = imageView
        // Glide 识别 GIF 会自动播放动画；加载失败则保持透明，露出默认渐变
        Glide.with(activity).load(uri).into(imageView)
    }

    private fun showVideo(uri: Uri) {
        // 用 TextureView 而不是 SurfaceView：背景上面盖着半透明面板和遮罩，
        // SurfaceView 的打孔合成在这些场景下容易出现层级问题
        val textureView = TextureView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(textureView)
        mediaView = textureView
        videoPlayer = ExoPlayer.Builder(activity).build().apply {
            setVideoTextureView(textureView)
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            play()
        }
        Timber.i("Video background applied: $uri")
    }

    private fun clear() {
        releaseVideo()
        mediaView?.let { container.removeView(it) }
        mediaView = null
        scrim.visibility = View.GONE
    }

    private fun releaseVideo() {
        videoPlayer?.release()
        videoPlayer = null
    }
}
