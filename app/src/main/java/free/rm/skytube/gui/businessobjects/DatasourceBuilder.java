/*
 * SkyTube
 * Copyright (C) 2020  Zsombor Gegesy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation (version 3 of the License).
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package free.rm.skytube.gui.businessobjects;

import android.content.Context;
import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.bilibili.BilibiliService;

public class DatasourceBuilder {
    public interface PlaybackFailureListener {
        void onHighResolutionDirectStream403(Uri mediaUri, long position);
    }

    private static final String VISION_OS_USER_AGENT =
            "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 "
                    + "like Mac OS X; TW)";
    private static final String MWEB_USER_AGENT =
            "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)";
    private static final String WEB_SAFARI_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/15.5 Safari/605.1.15,gzip(gfe)";
    private final Context context;
    private final ExoPlayer player;
    private final PlaybackFailureListener playbackFailureListener;

    private final DefaultDataSourceFactory dataSourceFactory;
    private final DefaultDataSourceFactory iosDataSourceFactory;
    private final DefaultDataSourceFactory hlsDataSourceFactory;
    private final DefaultDataSourceFactory mwebDataSourceFactory;
    private final DefaultDataSourceFactory visionOsDataSourceFactory;
    private final DefaultDataSourceFactory bilibiliDataSourceFactory;
    private final SingleSampleMediaSource.Factory singleSampleSourceFactory;
    private final ExtractorMediaSource.Factory extMediaSourceFactory;
    private final ExtractorMediaSource.Factory iosExtMediaSourceFactory;
    private final ExtractorMediaSource.Factory mwebExtMediaSourceFactory;
    private final ExtractorMediaSource.Factory visionOsExtMediaSourceFactory;
    private final ExtractorMediaSource.Factory bilibiliExtMediaSourceFactory;
    private final HlsMediaSource.Factory hlsMediaSourceFactory;
    private final DashMediaSource.Factory dashMediaSourceFactory;

    private final static int MINIMUM_LOADABLE_RETRY_COUNT = 10;
    private static final int MWEB_LOADABLE_RETRY_COUNT = 1;
    private static final int YOUTUBE_PROGRESSIVE_LOAD_INTERVAL_BYTES = 64 * 1024;

    public DatasourceBuilder(Context context, ExoPlayer player) {
        this(context, player, null);
    }

    public DatasourceBuilder(Context context, ExoPlayer player,
                             PlaybackFailureListener playbackFailureListener) {
        this.context = context;
        this.player = player;
        this.playbackFailureListener = playbackFailureListener;
        dataSourceFactory =  new DefaultDataSourceFactory(context, "ST. Agent", new DefaultBandwidthMeter());
        DefaultBandwidthMeter iosBandwidthMeter = new DefaultBandwidthMeter();
        iosDataSourceFactory = new DefaultDataSourceFactory(
                context, iosBandwidthMeter,
                new MwebPlaybackDataSource.Factory(
                        YoutubeParsingHelper.getIosUserAgent(Localization.DEFAULT),
                        iosBandwidthMeter,
                        null,
                        this::onMwebHttp403));
        DefaultBandwidthMeter hlsBandwidthMeter = new DefaultBandwidthMeter();
        hlsDataSourceFactory = new DefaultDataSourceFactory(
                context,
                hlsBandwidthMeter,
                new VisionOsPlaybackDataSource.Factory(
                        WEB_SAFARI_USER_AGENT,
                        hlsBandwidthMeter));
        DefaultBandwidthMeter mwebBandwidthMeter = new DefaultBandwidthMeter();
        mwebDataSourceFactory = new DefaultDataSourceFactory(
                context, mwebBandwidthMeter,
                new MwebPlaybackDataSource.Factory(
                        MWEB_USER_AGENT, mwebBandwidthMeter, null, this::onMwebHttp403));
        DefaultBandwidthMeter visionOsBandwidthMeter = new DefaultBandwidthMeter();
        visionOsDataSourceFactory = new DefaultDataSourceFactory(context, visionOsBandwidthMeter,
                new VisionOsPlaybackDataSource.Factory(
                        VISION_OS_USER_AGENT,
                        visionOsBandwidthMeter));
        DefaultBandwidthMeter bilibiliBandwidthMeter = new DefaultBandwidthMeter();
        DefaultHttpDataSourceFactory bilibiliHttpFactory =
                new DefaultHttpDataSourceFactory(
                        BilibiliService.DESKTOP_USER_AGENT,
                        bilibiliBandwidthMeter);
        bilibiliHttpFactory.getDefaultRequestProperties()
                .set("Referer", BilibiliService.MEDIA_REFERER);
        bilibiliHttpFactory.getDefaultRequestProperties()
                .set("Origin", "https://www.bilibili.com");
        bilibiliDataSourceFactory = new DefaultDataSourceFactory(
                context,
                bilibiliBandwidthMeter,
                bilibiliHttpFactory);
        singleSampleSourceFactory = new SingleSampleMediaSource.Factory(dataSourceFactory);

        extMediaSourceFactory = new ExtractorMediaSource.Factory(dataSourceFactory).setLoadErrorHandlingPolicy(
                new DefaultLoadErrorHandlingPolicy(MINIMUM_LOADABLE_RETRY_COUNT));
        iosExtMediaSourceFactory = new ExtractorMediaSource.Factory(iosDataSourceFactory).setLoadErrorHandlingPolicy(
                new DefaultLoadErrorHandlingPolicy(MINIMUM_LOADABLE_RETRY_COUNT));
        mwebExtMediaSourceFactory = new ExtractorMediaSource.Factory(mwebDataSourceFactory)
                .setContinueLoadingCheckIntervalBytes(YOUTUBE_PROGRESSIVE_LOAD_INTERVAL_BYTES)
                .setLoadErrorHandlingPolicy(
                        new DefaultLoadErrorHandlingPolicy(MWEB_LOADABLE_RETRY_COUNT));
        visionOsExtMediaSourceFactory = new ExtractorMediaSource.Factory(visionOsDataSourceFactory)
                .setContinueLoadingCheckIntervalBytes(YOUTUBE_PROGRESSIVE_LOAD_INTERVAL_BYTES)
                .setLoadErrorHandlingPolicy(
                        new DefaultLoadErrorHandlingPolicy(MINIMUM_LOADABLE_RETRY_COUNT));
        bilibiliExtMediaSourceFactory = new ExtractorMediaSource.Factory(bilibiliDataSourceFactory)
                .setLoadErrorHandlingPolicy(
                        new DefaultLoadErrorHandlingPolicy(MINIMUM_LOADABLE_RETRY_COUNT));
        hlsMediaSourceFactory = new HlsMediaSource.Factory(hlsDataSourceFactory)
                .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(2));
        dashMediaSourceFactory = new DashMediaSource.Factory(
                new DefaultDashChunkSource.Factory(dataSourceFactory),
                dataSourceFactory);
    }

    public void play(Uri videoUri, Uri audioUri) {
        preparePlayer(createSources(videoUri, audioUri, null, null));
    }

    public void play(Uri videoUri, Uri audioUri, StreamInfo streamInfo) {
        final List<MediaSource> titles;
        if (streamInfo != null && streamInfo.getSubtitles() != null) {
            titles = streamInfo.getSubtitles().stream().map( this::convert).collect(Collectors.toList());
        } else {
            titles = null;
        }
        List<MediaSource> sources = createSources(videoUri, audioUri, titles, streamInfo);
        preparePlayer(sources);
    }

    private MediaSource convert(SubtitlesStream subtitlesStream) {
        MediaFormat format = subtitlesStream.getFormat();
        String language = subtitlesStream.getLocale().getLanguage();
        Logger.i(this, "convert %s -> %s %s", subtitlesStream.getUrl(), format, language);
        Format exoFormat = Format.createTextSampleFormat(null, format.getMimeType(), C.SELECTION_FLAG_AUTOSELECT, language);
        return singleSampleSourceFactory.createMediaSource(
                Uri.parse(subtitlesStream.getUrl()), exoFormat,
                C.TIME_UNSET);
    }

    private List<MediaSource> createSources(Uri videoUri, Uri audioUri,
                                            List<MediaSource> subtitles,
                                            StreamInfo streamInfo) {
        Objects.requireNonNull(videoUri, "videoUri is required");
        Logger.i(this, "Create datasources for video=%s \n\taudio= %s and %s subtitles", videoUri, audioUri, subtitles);
        List<MediaSource> sources = new ArrayList<MediaSource>();

        sources.add(createSource(videoUri, streamInfo));
        if (audioUri != null) {
            sources.add(createSource(audioUri, streamInfo));
        }
        if (subtitles != null) {
            sources.addAll(subtitles);
        }
        return sources;
    }

    private MediaSource createSource(Uri uri, StreamInfo streamInfo) {
        final String url = uri.toString();
        final String lower = url.toLowerCase();
        if (lower.contains(".m3u8") || lower.contains("/manifest/hls")) {
            return hlsMediaSourceFactory.createMediaSource(uri);
        }
        if (lower.contains(".mpd")) {
            return dashMediaSourceFactory.createMediaSource(uri);
        }
        if (BilibiliService.isBilibiliMediaUrl(url)) {
            Logger.i(this, "Using Bilibili request headers for media stream");
            return bilibiliExtMediaSourceFactory.createMediaSource(uri);
        }
        if (url.contains("&c=VISIONOS") || url.contains("?c=VISIONOS")) {
            Logger.i(this, "Using visionOS request identity for YouTube media stream");
            return visionOsExtMediaSourceFactory.createMediaSource(uri);
        }
        if (url.contains("&c=MWEB") || url.contains("?c=MWEB")) {
            Logger.i(this, "Using MWEB request identity for YouTube media stream");
            return mwebExtMediaSourceFactory.createMediaSource(uri);
        }
        if (YoutubeParsingHelper.isIosStreamingUrl(url)) {
            Logger.i(this, "Using iOS request identity for YouTube media stream");
            return createIosSource(uri, streamInfo);
        }
        return extMediaSourceFactory.createMediaSource(uri);
    }

    private MediaSource createIosSource(Uri uri, StreamInfo streamInfo) {
        if (streamInfo == null) {
            return iosExtMediaSourceFactory.createMediaSource(uri);
        }
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
        DefaultDataSourceFactory factory = new DefaultDataSourceFactory(
                context,
                bandwidthMeter,
                new MwebPlaybackDataSource.Factory(
                        YoutubeParsingHelper.getIosUserAgent(Localization.DEFAULT),
                        bandwidthMeter,
                        currentUri -> refreshIosMediaUri(streamInfo, currentUri),
                        this::onMwebHttp403));
        return new ExtractorMediaSource.Factory(factory)
                .setContinueLoadingCheckIntervalBytes(YOUTUBE_PROGRESSIVE_LOAD_INTERVAL_BYTES)
                .setLoadErrorHandlingPolicy(
                        new DefaultLoadErrorHandlingPolicy(MWEB_LOADABLE_RETRY_COUNT))
                .createMediaSource(uri);
    }

    private Uri refreshIosMediaUri(StreamInfo sourceInfo, Uri currentUri) throws IOException {
        String requestedItag = currentUri.getQueryParameter("itag");
        if (requestedItag == null) {
            throw new IOException("iOS media URL has no itag");
        }
        try {
            StreamInfo refreshedInfo = StreamInfo.getInfo(sourceInfo.getUrl());
            List<VideoStream> streams = new ArrayList<>();
            streams.addAll(refreshedInfo.getVideoOnlyStreams());
            streams.addAll(refreshedInfo.getVideoStreams());
            for (VideoStream stream : streams) {
                if (!stream.isUrl() || !requestedItag.equals(stream.getId())) {
                    continue;
                }
                Uri refreshedUri = Uri.parse(stream.getContent());
                if (YoutubeParsingHelper.isIosStreamingUrl(refreshedUri.toString())) {
                    Logger.i(this, "Refreshed iOS media URL itag=%s videoId=%s",
                            requestedItag, sourceInfo.getId());
                    return refreshedUri;
                }
            }
            throw new IOException("Refreshed stream has no matching iOS itag " + requestedItag);
        } catch (ExtractionException error) {
            throw new IOException("Unable to refresh iOS media URL", error);
        }
    }

    private void onMwebHttp403(Uri mediaUri, long position) {
        if (playbackFailureListener != null) {
            playbackFailureListener.onHighResolutionDirectStream403(mediaUri, position);
        }
    }

    private void preparePlayer(List<MediaSource> sources) {
        Objects.requireNonNull(sources, "sources");
        Logger.i(this, "Prepare player with sources: %s - raw: %s", sources.size(), sources);
        if (sources.isEmpty()) {
            return;
        }
        if (sources.size() == 1) {
            player.prepare(sources.get(0));
        } else {
            MergingMediaSource merged = new MergingMediaSource(sources.toArray(new MediaSource[sources.size()]));
            player.prepare(merged);
        }
    }
}
