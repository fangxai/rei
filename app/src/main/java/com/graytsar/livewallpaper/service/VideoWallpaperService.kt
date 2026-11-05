package com.graytsar.livewallpaper.service

import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.graytsar.livewallpaper.core.common.model.VideoEngineSettings
import com.graytsar.livewallpaper.core.common.model.VideoScaling
import com.graytsar.livewallpaper.core.common.model.WallpaperFlag
import com.graytsar.livewallpaper.core.common.model.WallpaperServiceType
import com.graytsar.livewallpaper.core.repository.UserPreferencesRepository
import com.graytsar.livewallpaper.core.repository.WallpaperSelection
import com.graytsar.livewallpaper.engine.WallpaperRenderer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

//because onApplyWallpaper is not called
var currentFlag = WallpaperFlag.SYSTEM

/**
 * Automatic player rebuilds per sequence of consecutive failures. After that, a video that has
 * played before gets a fresh budget the next time it is shown; one that never prepared stays
 * stopped until the engine creates a new renderer.
 */
private const val MAX_PLAYER_RECREATE_ATTEMPTS = 5

/** Delay before the first rebuild; doubles on each consecutive failure (1, 2, 4, 8, 16 s). */
private const val PLAYER_RECREATE_BASE_DELAY_MS = 1_000L

/** A failure this long after the previous one is not consecutive and starts a new backoff sequence. */
private const val PLAYER_FAILURE_RESET_WINDOW_MS = 60_000L

/** A looping player that completes again sooner than this after a manual restart is treated as broken. */
private const val MIN_LOOP_DURATION_MS = 500L

@AndroidEntryPoint
class VideoWallpaperService : WallpaperService() {
    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    override fun onCreateEngine(): Engine = VideoWallpaperEngine()

    private inner class VideoWallpaperEngine : Engine() {
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var observeJob: Job? = null
        private var renderer: WallpaperRenderer? = null
        private var engineSettings: VideoEngineSettings? = null
        private var isSurfaceCreated = false
        private var lastSelection: WallpaperSelection? = null

        //what the current renderer was built from
        private var appliedSettings: VideoEngineSettings? = null
        private var appliedSelection: WallpaperSelection? = null

        private var isPaused: Boolean = false
        private var isVisibleToUser: Boolean = true

        private val gestureDetector = GestureDetector(
            this@VideoWallpaperService,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (engineSettings?.general?.doubleTapToPause == true) {
                        isPaused = !isPaused
                        renderer?.onPauseChanged(isPaused)
                        return true
                    }
                    return false
                }
            })

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            startObserveJob()
        }

        @OptIn(FlowPreview::class)
        private fun startObserveJob() {
            observeJob?.cancel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                currentFlag = WallpaperFlag.from(wallpaperFlags)
            }

            observeJob = engineScope.launch {
                combine(
                    userPreferencesRepository.getVideoEngineSettingsFlow(),
                    userPreferencesRepository.getWallpaperSelectionFlow(
                        isPreview = isPreview,
                        serviceType = WallpaperServiceType.VIDEO,
                        wallpaperFlag = currentFlag
                    )
                ) { settings, selection ->
                    settings to selection
                }.debounce(100.milliseconds).collect { (settings, selection) ->
                    engineSettings = settings
                    lastSelection = selection

                    val shouldUpdate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        selection?.flag == WallpaperFlag.from(wallpaperFlags)
                    } else true

                    if (shouldUpdate && isSurfaceCreated) {
                        handleUpdate(settings, selection)
                    }
                }
            }
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean
        ): Bundle? {
            if (action == "android.wallpaper.reapply") {
                startObserveJob()
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            isVisibleToUser = visible
            renderer?.onVisibilityChanged(visible)
        }

        override fun onTouchEvent(event: MotionEvent?) {
            if (event != null) gestureDetector.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            isSurfaceCreated = true
            //a renderer must never outlive the surface it was built for
            releaseRenderer()
            engineSettings?.let { settings ->
                handleUpdate(settings, lastSelection)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            isSurfaceCreated = false
            releaseRenderer()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            observeJob?.cancel()
            engineScope.cancel()
            releaseRenderer()
            super.onDestroy()
        }

        private fun handleUpdate(settings: VideoEngineSettings, selection: WallpaperSelection?) {
            val surfaceHolder = surfaceHolder ?: return
            if (selection == null) {
                //data store was cleared
                releaseRenderer()
                return
            }

            val file = File(selection.path)
            if (!file.exists()) {
                //app data has been cleared
                releaseRenderer()
                return
            }

            if (renderer != null && settings == appliedSettings && selection == appliedSelection) {
                //nothing changed (reapply, duplicate emission): keep the running player instead of
                //tearing it down and attaching a new one to the same surface
                return
            }

            releaseRenderer()
            appliedSettings = settings
            appliedSelection = selection
            //a pause the user can no longer toggle off must not carry over into the new renderer
            if (!settings.general.doubleTapToPause) isPaused = false
            renderer = VideoRenderer(
                holder = surfaceHolder,
                file = file,
                settings = settings
            ).also { renderer ->
                renderer.onVisibilityChanged(isVisibleToUser)
                renderer.onPauseChanged(isPaused)
                renderer.onSurfaceReady()
            }
        }

        /**
         * Releases renderer resources
         */
        private fun releaseRenderer() {
            renderer?.release()
            renderer = null
        }
    }

    /**
     * Plays [file] on the engine surface and keeps it playing.
     *
     * Any player failure (MediaPlayer error such as a mediaserver restart or a lost surface,
     * an exception while creating the player, an illegal state in start/pause) releases the
     * player and schedules a new one with exponential backoff. If the retries run out, a video
     * that has played before is rebuilt the next time the wallpaper becomes visible; one that
     * never prepared stays stopped until the engine creates a new renderer. A looping player that
     * reports completion is restarted in place.
     */
    private inner class VideoRenderer(
        private val holder: SurfaceHolder,
        private val file: File,
        private val settings: VideoEngineSettings
    ) : WallpaperRenderer {
        private val handler = Handler(Looper.getMainLooper())
        private val recreatePlayerRunnable = Runnable { createPlayer() }

        private var player: MediaPlayer? = null
        private var isPlayerPrepared = false
        private var failureCount = 0
        private var lastFailureAtMs = 0L
        private var hasEverPrepared = false
        private var lastLoopRestartAtMs = 0L
        private var isSurfaceReady = false
        private var isReleased = false

        private var isVisible = false
        private var isPaused = false

        override fun onSurfaceReady() {
            isSurfaceReady = true
            failureCount = 0
            createPlayer()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            this.isVisible = isVisible
            if (isVisible && hasEverPrepared) {
                //fresh retry budget every time the wallpaper is shown, but only for a file that played
                //before; one that never prepared would otherwise be retried on every unlock
                failureCount = 0
            }
            ensurePlaying()
        }

        override fun onPauseChanged(isPaused: Boolean) {
            this.isPaused = isPaused
            ensurePlaying()
        }

        override fun release() {
            isReleased = true
            handler.removeCallbacks(recreatePlayerRunnable)
            releasePlayer()
        }

        private fun shouldPlay(): Boolean = when {
            isPaused -> false
            isVisible -> true
            else -> settings.general.playOffscreen
        }

        /**
         * Rebuilds the player if a previous one failed, otherwise applies the play/pause state.
         */
        private fun ensurePlaying() {
            if (player == null && isSurfaceReady && shouldPlay() && failureCount < MAX_PLAYER_RECREATE_ATTEMPTS) {
                createPlayer()
            } else {
                updatePlayState()
            }
        }

        private fun createPlayer() {
            handler.removeCallbacks(recreatePlayerRunnable)
            releasePlayer()
            if (isReleased || !isSurfaceReady) return

            val surface = holder.surface
            if (surface == null || !surface.isValid) {
                //retried on the next visibility change, or the engine creates a new renderer
                //once a new surface exists
                return
            }

            val videoScalingMode = when (settings.video.videoScaling) {
                VideoScaling.FIT_CROP -> MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                VideoScaling.FIT_TO_SCREEN -> MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT
                VideoScaling.ORIGINAL -> null
            }
            val volume = if (settings.video.audio) 1.0f else 0.0f

            //Assign before configuring so the player is released even if a call below throws.
            //No apply {} here: inside it, release() would resolve to MediaPlayer.release().
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            try {
                mediaPlayer.setOnPreparedListener { mp ->
                    if (mp !== player) return@setOnPreparedListener
                    isPlayerPrepared = true
                    hasEverPrepared = true
                    try {
                        videoScalingMode?.let { mp.setVideoScalingMode(it) }
                    } catch (e: Exception) {
                        onPlayerFailed(e)
                        return@setOnPreparedListener
                    }
                    updatePlayState()
                }
                mediaPlayer.setOnErrorListener { mp, what, extra ->
                    if (mp === player) {
                        onPlayerFailed(VideoPlaybackException("MediaPlayer error what=$what extra=$extra"))
                    }
                    true
                }
                mediaPlayer.setOnCompletionListener { mp ->
                    //the player loops, so completing means native looping failed for this file/device:
                    //restart from the beginning in place, give up only if it completes again right away
                    if (mp !== player) return@setOnCompletionListener
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLoopRestartAtMs < MIN_LOOP_DURATION_MS) {
                        onPlayerFailed(VideoPlaybackException("Looping MediaPlayer completed immediately"))
                    } else {
                        lastLoopRestartAtMs = now
                        //start() from PlaybackCompleted restarts playback from the beginning
                        updatePlayState()
                    }
                }
                mediaPlayer.setSurface(surface)
                mediaPlayer.isLooping = true
                file.inputStream().use { inputStream ->
                    mediaPlayer.setDataSource(inputStream.fd)
                }
                mediaPlayer.setVolume(volume, volume)
                mediaPlayer.prepareAsync()
            } catch (e: Exception) {
                onPlayerFailed(e)
            }
        }

        private fun onPlayerFailed(error: Throwable) {
            //a failure long after the previous one starts a new sequence; a video that never prepared
            //had no recovery in between, so its failures always belong to the same sequence
            val now = SystemClock.elapsedRealtime()
            if (hasEverPrepared && now - lastFailureAtMs > PLAYER_FAILURE_RESET_WINDOW_MS) {
                failureCount = 0
            }
            lastFailureAtMs = now

            //only the first failure of a sequence becomes a non-fatal, the retries are breadcrumbs
            if (failureCount == 0) {
                FirebaseCrashlytics.getInstance().recordException(error)
            } else {
                FirebaseCrashlytics.getInstance().log("Video playback retry #$failureCount: $error")
            }
            releasePlayer()

            //while hidden (and not playing offscreen) ensurePlaying() rebuilds it when shown again
            if (isReleased || !shouldPlay() || failureCount >= MAX_PLAYER_RECREATE_ATTEMPTS) return
            val delayMs = PLAYER_RECREATE_BASE_DELAY_MS shl failureCount
            failureCount++
            handler.postDelayed(recreatePlayerRunnable, delayMs)
        }

        private fun releasePlayer() {
            isPlayerPrepared = false
            val mediaPlayer = player ?: return
            player = null

            runCatching {
                //release() internally stops the player, so we don't need to call stop() before it
                mediaPlayer.release()
            }.onFailure { error ->
                FirebaseCrashlytics.getInstance().recordException(error)
            }
        }

        private fun updatePlayState() {
            if (!isPlayerPrepared) return
            val mediaPlayer = player ?: return

            try {
                if (shouldPlay()) {
                    if (!mediaPlayer.isPlaying) {
                        mediaPlayer.start()
                    }
                } else if (mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                }
            } catch (e: Exception) {
                onPlayerFailed(e)
            }
        }
    }
}

/**
 * Reported to Crashlytics as a non-fatal when video playback fails, so these failures show up
 * as issues instead of only as log lines attached to unrelated reports.
 */
private class VideoPlaybackException(message: String) : Exception(message)