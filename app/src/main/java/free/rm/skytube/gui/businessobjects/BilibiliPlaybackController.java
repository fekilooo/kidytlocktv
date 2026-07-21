package free.rm.skytube.gui.businessobjects;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import free.rm.skytube.businessobjects.bilibili.BilibiliService;

/**
 * Isolated Media3 backend for Bilibili DASH/fMP4 streams.
 *
 * <p>SkyTube's legacy ExoPlayer remains responsible for YouTube playback. Keeping Bilibili on a
 * separate modern parser avoids feeding current Bilibili M4S fragments into ExoPlayer 2.9.6.</p>
 */
public final class BilibiliPlaybackController {
    public interface Listener {
        void onReady();

        void onPlayingChanged(boolean playing);

        void onEnded();

        void onStalled(long positionMs);

        void onError(@NonNull Throwable error);
    }

    private final AspectRatioFrameLayout playerFrame;
    private final SurfaceView surfaceView;
    private final Listener listener;
    private final ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stallTimeout;
    private boolean ready;
    private boolean released;

    public BilibiliPlaybackController(@NonNull Context context,
                                      @NonNull AspectRatioFrameLayout playerFrame,
                                      @NonNull SurfaceView surfaceView,
                                      @NonNull Listener listener) {
        this.playerFrame = playerFrame;
        this.surfaceView = surfaceView;
        this.listener = listener;

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20_000, 90_000, 1_500, 3_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(context.getApplicationContext())
                .setLoadControl(loadControl)
                .build();
        stallTimeout = () -> {
            if (!released && player.getPlaybackState() == Player.STATE_BUFFERING
                    && player.getPlayWhenReady()) {
                listener.onStalled(Math.max(0L, player.getCurrentPosition()));
            }
        };
        surfaceView.setZOrderMediaOverlay(false);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                ready = playbackState == Player.STATE_READY;
                handler.removeCallbacks(stallTimeout);
                if (playbackState == Player.STATE_READY) {
                    BilibiliPlaybackController.this.listener.onReady();
                } else if (playbackState == Player.STATE_BUFFERING
                        && player.getPlayWhenReady()) {
                    handler.postDelayed(stallTimeout, 18_000L);
                } else if (playbackState == Player.STATE_ENDED) {
                    BilibiliPlaybackController.this.listener.onEnded();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                BilibiliPlaybackController.this.listener.onPlayingChanged(isPlaying);
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                BilibiliPlaybackController.this.listener.onError(error);
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

    public void start(@NonNull VideoStream videoStream,
                      @Nullable AudioStream audioStream,
                      @NonNull String videoUrl,
                      @Nullable String audioUrl,
                      long durationSeconds,
                      long positionMs,
                      boolean playWhenReady) throws IOException {
        stopPlayerOnly();
        player.setVideoSurfaceView(surfaceView);

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", BilibiliService.MEDIA_REFERER);
        headers.put("Origin", "https://www.bilibili.com");
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(BilibiliService.DESKTOP_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(18_000)
                .setDefaultRequestProperties(headers);
        DefaultDataSource.Factory dataSourceFactory =
                new DefaultDataSource.Factory(playerFrame.getContext(), httpFactory);
        MediaSource source = createSegmentBaseDashSource(
                dataSourceFactory, videoStream, audioStream, videoUrl, audioUrl,
                durationSeconds);

        player.setMediaSource(source, Math.max(0L, positionMs));
        player.prepare();
        player.setPlayWhenReady(playWhenReady);
    }

    private MediaSource createSegmentBaseDashSource(
            DefaultDataSource.Factory dataSourceFactory,
            VideoStream videoStream,
            @Nullable AudioStream audioStream,
            String videoUrl,
            @Nullable String audioUrl,
            long durationSeconds) throws IOException {
        if (!hasSegmentBase(videoStream)
                || (audioStream != null && !hasSegmentBase(audioStream))) {
            return createProgressiveSources(dataSourceFactory, videoUrl, audioUrl);
        }

        String manifestXml = buildDashManifest(
                videoStream, audioStream, videoUrl, audioUrl, durationSeconds);
        DashManifest manifest = new DashManifestParser().parse(
                Uri.EMPTY,
                new ByteArrayInputStream(manifestXml.getBytes(StandardCharsets.UTF_8)));
        DashMediaSource.Factory dashFactory = new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(dataSourceFactory),
                dataSourceFactory);
        return dashFactory.createMediaSource(
                manifest,
                new MediaItem.Builder().setUri(Uri.parse(videoUrl)).build());
    }

    private MediaSource createProgressiveSources(
            DefaultDataSource.Factory dataSourceFactory,
            String videoUrl,
            @Nullable String audioUrl) {
        ProgressiveMediaSource.Factory sourceFactory =
                new ProgressiveMediaSource.Factory(dataSourceFactory);
        MediaSource videoSource = sourceFactory.createMediaSource(new MediaItem.Builder()
                .setUri(Uri.parse(videoUrl))
                .setMimeType(MimeTypes.VIDEO_MP4)
                .build());
        if (audioUrl == null) {
            return videoSource;
        }
        MediaSource audioSource = sourceFactory.createMediaSource(new MediaItem.Builder()
                .setUri(Uri.parse(audioUrl))
                .setMimeType(MimeTypes.AUDIO_MP4)
                .build());
        return new MergingMediaSource(videoSource, audioSource);
    }

    private static boolean hasSegmentBase(VideoStream stream) {
        return stream.getInitEnd() >= stream.getInitStart()
                && stream.getIndexEnd() >= stream.getIndexStart()
                && stream.getIndexEnd() > 0;
    }

    private static boolean hasSegmentBase(AudioStream stream) {
        return stream.getInitEnd() >= stream.getInitStart()
                && stream.getIndexEnd() >= stream.getIndexStart()
                && stream.getIndexEnd() > 0;
    }

    private static String buildDashManifest(VideoStream videoStream,
                                            @Nullable AudioStream audioStream,
                                            String videoUrl,
                                            @Nullable String audioUrl,
                                            long durationSeconds) {
        long duration = Math.max(1L, durationSeconds);
        StringBuilder xml = new StringBuilder(1_024)
                .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
                .append("minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"PT")
                .append(duration).append("S\"><Period duration=\"PT")
                .append(duration).append("S\">");
        appendVideoRepresentation(xml, videoStream, videoUrl);
        if (audioStream != null && audioUrl != null) {
            appendAudioRepresentation(xml, audioStream, audioUrl);
        }
        return xml.append("</Period></MPD>").toString();
    }

    private static void appendVideoRepresentation(StringBuilder xml, VideoStream stream,
                                                  String mediaUrl) {
        xml.append("<AdaptationSet contentType=\"video\" mimeType=\"video/mp4\" ")
                .append("subsegmentAlignment=\"true\"><Representation id=\"")
                .append(escapeXml(stream.getId())).append("\" bandwidth=\"")
                .append(Math.max(1, stream.getBitrate())).append("\" codecs=\"")
                .append(escapeXml(stream.getCodec())).append("\" width=\"")
                .append(Math.max(1, stream.getWidth())).append("\" height=\"")
                .append(Math.max(1, stream.getHeight())).append("\" frameRate=\"")
                .append(Math.max(1, stream.getFps())).append("\"><BaseURL>")
                .append(escapeXml(mediaUrl)).append("</BaseURL><SegmentBase indexRange=\"")
                .append(stream.getIndexStart()).append('-').append(stream.getIndexEnd())
                .append("\"><Initialization range=\"")
                .append(stream.getInitStart()).append('-').append(stream.getInitEnd())
                .append("\"/></SegmentBase></Representation></AdaptationSet>");
    }

    private static void appendAudioRepresentation(StringBuilder xml, AudioStream stream,
                                                  String mediaUrl) {
        xml.append("<AdaptationSet contentType=\"audio\" mimeType=\"audio/mp4\" ")
                .append("subsegmentAlignment=\"true\"><Representation id=\"")
                .append(escapeXml(stream.getId())).append("\" bandwidth=\"")
                .append(Math.max(1, stream.getBitrate())).append("\" codecs=\"")
                .append(escapeXml(stream.getCodec())).append("\"><BaseURL>")
                .append(escapeXml(mediaUrl)).append("</BaseURL><SegmentBase indexRange=\"")
                .append(stream.getIndexStart()).append('-').append(stream.getIndexEnd())
                .append("\"><Initialization range=\"")
                .append(stream.getInitStart()).append('-').append(stream.getInitEnd())
                .append("\"/></SegmentBase></Representation></AdaptationSet>");
    }

    private static String escapeXml(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
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
        stopPlayerOnly();
        player.clearVideoSurfaceView(surfaceView);
    }

    private void stopPlayerOnly() {
        handler.removeCallbacks(stallTimeout);
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
        player.release();
    }
}
