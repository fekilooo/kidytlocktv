package free.rm.skytube.gui.businessobjects;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.schabi.newpipe.player.datasource.SabrDashMediaSource;
import org.schabi.newpipe.player.datasource.SabrSessionStore;
import org.schabi.newpipe.player.datasource.SabrSourceSpec;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Isolated Media3 player used only after the legacy direct-stream path identifies a SABR case.
 */
public final class SabrPlaybackController {
    public interface Listener {
        void onFormatSelected(int videoItag, int videoHeight, int audioItag);

        void onReady();

        void onPlayingChanged(boolean playing);

        void onEnded();

        void onError(@NonNull Throwable error);
    }

    private final Context appContext;
    private final AspectRatioFrameLayout playerFrame;
    private final SurfaceView surfaceView;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService sourceExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SkyTubeSabrSource");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private final ExoPlayer player;
    private String videoId;
    private boolean ready;
    private boolean released;

    private final Runnable positionReporter = new Runnable() {
        @Override
        public void run() {
            if (released || videoId == null) {
                return;
            }
            SabrSessionStore.updatePlayerTime(videoId, Math.max(0L, player.getCurrentPosition()));
            SabrSessionStore.updatePlaybackRate(videoId, player.getPlaybackParameters().speed);
            mainHandler.postDelayed(this, 1_000L);
        }
    };

    public SabrPlaybackController(@NonNull Context context,
                                  @NonNull AspectRatioFrameLayout playerFrame,
                                  @NonNull SurfaceView surfaceView,
                                  @NonNull Listener listener) {
        this.appContext = context.getApplicationContext();
        this.playerFrame = playerFrame;
        this.surfaceView = surfaceView;
        this.listener = listener;
        // Keep the SABR surface behind the normal Android view hierarchy so SkyTube's existing
        // controller, toolbar and recommendation overlay can be drawn above it.
        this.surfaceView.setZOrderMediaOverlay(false);
        this.player = new ExoPlayer.Builder(appContext).build();
        this.player.setVideoSurfaceView(surfaceView);
        this.player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                ready = playbackState == Player.STATE_READY;
                if (playbackState == Player.STATE_READY) {
                    SabrPlaybackController.this.listener.onReady();
                } else if (playbackState == Player.STATE_ENDED) {
                    SabrPlaybackController.this.listener.onEnded();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                SabrPlaybackController.this.listener.onPlayingChanged(isPlaying);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                SabrPlaybackController.this.listener.onError(error);
            }

            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    playerFrame.setAspectRatio(
                            (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height);
                }
            }
        });
    }

    public void start(@NonNull String requestedVideoId,
                      int preferredVideoItag,
                      long positionMs) {
        final int requestGeneration = generation.incrementAndGet();
        final long startPositionMs = Math.max(0L, positionMs);
        stopPlayerOnly();
        player.setVideoSurfaceView(surfaceView);
        videoId = requestedVideoId;
        sourceExecutor.execute(() -> {
            try {
                final SabrSourceSpec spec = SabrSessionStore.createSourceSpec(
                        requestedVideoId, preferredVideoItag, null);
                mainHandler.post(() -> {
                    if (released || generation.get() != requestGeneration) {
                        return;
                    }
                    try {
                        listener.onFormatSelected(
                                spec.getVideoFormat().getItag(),
                                spec.getVideoFormat().getHeight(),
                                spec.getAudioFormat().getItag());
                        final MediaItem mediaItem = new MediaItem.Builder()
                                .setUri(Uri.parse("sabr://" + requestedVideoId))
                                .setMediaId(requestedVideoId)
                                .build();
                        player.setMediaSource(new SabrDashMediaSource(
                                appContext, mediaItem, spec));
                        player.prepare();
                        if (startPositionMs > 0L) {
                            player.seekTo(startPositionMs);
                        }
                        player.setPlayWhenReady(true);
                        mainHandler.removeCallbacks(positionReporter);
                        mainHandler.post(positionReporter);
                    } catch (Throwable error) {
                        listener.onError(error);
                    }
                });
            } catch (Throwable error) {
                mainHandler.post(() -> {
                    if (!released && generation.get() == requestGeneration) {
                        listener.onError(error);
                    }
                });
            }
        });
    }

    public long getCurrentPosition() {
        return Math.max(0L, player.getCurrentPosition());
    }

    public long getDuration() {
        return player.getDuration();
    }

    public long getBufferedPosition() {
        return player.getBufferedPosition();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReady() {
        return ready;
    }

    public boolean getPlayWhenReady() {
        return player.getPlayWhenReady();
    }

    public void setPlayWhenReady(boolean playWhenReady) {
        player.setPlayWhenReady(playWhenReady);
    }

    public void seekTo(long positionMs) {
        player.seekTo(Math.max(0L, positionMs));
    }

    public float getPlaybackSpeed() {
        return player.getPlaybackParameters().speed;
    }

    public void setPlaybackSpeed(float speed) {
        player.setPlaybackSpeed(speed);
    }

    public void stop() {
        generation.incrementAndGet();
        stopPlayerOnly();
        player.clearVideoSurfaceView(surfaceView);
        if (videoId != null) {
            SabrSessionStore.evict(videoId);
            videoId = null;
        }
    }

    private void stopPlayerOnly() {
        mainHandler.removeCallbacks(positionReporter);
        ready = false;
        player.stop();
        player.clearMediaItems();
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        stop();
        sourceExecutor.shutdownNow();
        player.clearVideoSurfaceView(surfaceView);
        player.release();
    }

    @Nullable
    public String getVideoId() {
        return videoId;
    }
}
