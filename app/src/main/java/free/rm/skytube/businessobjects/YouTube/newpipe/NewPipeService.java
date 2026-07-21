/*
 * SkyTube
 * Copyright (C) 2019  Zsombor Gegesy
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
package free.rm.skytube.businessobjects.YouTube.newpipe;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;

import com.github.skytube.components.httpclient.OkHttpDownloader;

import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.feed.FeedExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.kiosk.KioskList;
import org.schabi.newpipe.extractor.linkhandler.LinkHandler;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.DateWrapper;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubePlaylistExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeSearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor;
import org.schabi.newpipe.extractor.utils.Utils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import free.rm.skytube.BuildConfig;
import free.rm.skytube.app.Settings;
import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.businessobjects.DiagnosticFileLogger;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.NetworkDiagnosticsInterceptor;
import free.rm.skytube.businessobjects.TLSSocketFactory;
import free.rm.skytube.businessobjects.YouTube.POJOs.PersistentChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;
import okhttp3.OkHttpClient;

/**
 * Service to interact with remote video services, using the NewPipeExtractor backend.
 */
public class NewPipeService {
    // TODO: remove this singleton
    private static NewPipeService instance;
    private final static boolean VERBOSE_DISLIKE_COUNT_LOG = false;

    private final StreamingService streamingService;
    private final Settings settings;
    final static boolean DEBUG_LOG = false;

    static class ChannelWithExtractor {
        final YouTubeChannel channel;
        final ChannelExtractor extractor;

        ChannelWithExtractor(YouTubeChannel channel, ChannelExtractor extractor) {
            this.channel = channel;
            this.extractor = extractor;
        }

        ListLinkHandler findListLinkHandler(String name) throws ParsingException {
            // it's a bit overcomplicated
            return extractor.getTabs().stream()
                .filter(linkHandler -> {
                    List<String> filters = linkHandler.getContentFilters();
                    return filters != null && filters.contains(name);
                }).findAny().orElse(null);
        }

        ChannelTabExtractor findChannelTab(String name) throws ParsingException {
            ListLinkHandler listLinkHandler = findListLinkHandler(name);
            if(listLinkHandler != null) {
                try {
                    return instance.streamingService.getChannelTabExtractor(listLinkHandler);
                } catch (ExtractionException e) {
                    Logger.e(instance, "findChannelTab (" + name + ") : " + listLinkHandler + ", err:" + e.getMessage(), e);
                    return null;
                }
            }
            return null;
        }

        ChannelTabExtractor findVideosTab() throws ParsingException {
            return findChannelTab(ChannelTabs.VIDEOS);
        }

        ChannelTabExtractor findPlaylistTab() throws ParsingException {
            return findChannelTab(ChannelTabs.PLAYLISTS);
        }
    }

    @FunctionalInterface
    interface ParserCall<X> {
        X get() throws ParsingException;
    }

    public NewPipeService(StreamingService streamingService, Settings settings) {
        this.streamingService = streamingService;
        this.settings = settings;
    }

    public StreamingService getStreamingService() {
        return streamingService;
    }

    /**
     * Returns a list of video/stream meta-data that is supported by this app.
     *
     * @return The {@link StreamInfo}.
     */
    private StreamInfo getStreamInfoByUrl(String videoUrl) throws IOException, ExtractionException {
        // actual extraction
        return StreamInfo.getInfo(streamingService, videoUrl);
    }

    public ContentId getVideoId(String url) throws ParsingException {
        if (url == null) {
            return null;
        }
        return parse(streamingService.getStreamLHFactory(), url, StreamingService.LinkType.STREAM);
    }

    public ContentId getContentId(String url) {
        if (url == null) {
            return null;
        }
        ContentId id;
        id = parse(streamingService.getStreamLHFactory(), url, StreamingService.LinkType.STREAM);
        if (id != null) {
            return id;
        }
        id = parse(streamingService.getChannelLHFactory(), url, StreamingService.LinkType.CHANNEL);
        if (id != null) {
            return id;
        }
        id = parse(streamingService.getPlaylistLHFactory(), url, StreamingService.LinkType.PLAYLIST);
        return id;
    }

    private ContentId parse(LinkHandlerFactory handlerFactory, String url, StreamingService.LinkType type) {
        if (handlerFactory != null) {
            try {
                String id = handlerFactory.getId(url);
                String canonicalUrl = handlerFactory.getUrl(id);
                if (type == StreamingService.LinkType.STREAM) {
                    return new VideoId(id, canonicalUrl, parseTimeStamp(url));
                } else {
                    return new ContentId(id, handlerFactory.getUrl(id), type);
                }
            } catch (ParsingException pe) {
                return null;
            }
        }
        return null;
    }

    private Integer parseTimeStamp(String url) {
        try {
            String time = Utils.getQueryValue(new URL(url), "t");
            if (time != null) {
                return Integer.parseInt(time);
            }
        } catch (MalformedURLException|NumberFormatException e) {
        }
        return null;
    }

    /**
     * Returns a list of video/stream meta-data that is supported by this app for this video ID.
     *
     * @param videoId the id of the video.
     * @return List of {@link StreamInfo}.
     */
    public StreamInfo getStreamInfoByVideoId(String videoId) throws ExtractionException, IOException {
        SkyTubeApp.nonUiThread();
        Logger.i(this, "[Playback] getStreamInfoByVideoId start id=%s", videoId);
        appendPlaybackLog("stream info start id=" + videoId + " sdk=" + Build.VERSION.SDK_INT);

        // On the affected Android 10 boxes StreamInfo.getInfo() never returns even though
        // YouTube's player responses are complete. Keep newer devices on the upstream path.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            appendPlaybackLog("Android 10 compatibility path selected id=" + videoId);
            StreamInfo streamInfo = getStreamInfoByVideoIdFallback(videoId);
            appendPlaybackLog("Android 10 compatibility path success id=" + videoId);
            return streamInfo;
        }

        if (isPlainVideoId(videoId)) {
            try {
                StreamInfo streamInfo = getStreamInfoByVideoIdOfficial(videoId);
                Logger.i(this, "[Playback] getStreamInfoByVideoId official success id=%s type=%s",
                        videoId, streamInfo.getStreamType());
                return streamInfo;
            } catch (Throwable officialError) {
                Logger.e(this, "[Playback] official StreamInfo path failed for %s, falling back: %s",
                        videoId, officialError.getMessage());
            }
        }

        StreamInfo streamInfo = getStreamInfoByVideoIdFallback(videoId);
        Logger.i(this, "[Playback] getStreamInfoByVideoId fallback success id=%s type=%s",
                videoId, streamInfo.getStreamType());
        return streamInfo;
    }

    private StreamInfo getStreamInfoByVideoIdOfficial(String videoId)
            throws ExtractionException, IOException {
        final LinkHandler linkHandler = streamingService.getStreamLHFactory().fromId(videoId);
        Logger.i(this, "[Playback] official handler url=%s", linkHandler.getUrl());
        final StreamExtractor streamExtractor = streamingService.getStreamExtractor(linkHandler);
        return StreamInfo.getInfo(streamExtractor);
    }

    private StreamInfo getStreamInfoByVideoIdFallback(String videoId)
            throws ExtractionException, IOException {
        appendPlaybackLog("fallback handler start id=" + videoId);
        final LinkHandler linkHandler = isPlainVideoId(videoId)
                ? streamingService.getStreamLHFactory().fromId(videoId)
                : getStreamHandler(videoId);
        Logger.i(this, "[Playback] fallback handler url=%s", linkHandler.getUrl());
        appendPlaybackLog("fallback handler ready id=" + videoId + " url=" + linkHandler.getUrl());
        StreamExtractor streamExtractor = streamingService.getStreamExtractor(linkHandler);
        appendPlaybackLog("fallback fetchPage start id=" + videoId);
        streamExtractor.fetchPage();
        Logger.i(this, "[Playback] fallback fetchPage finished id=%s", videoId);
        appendPlaybackLog("fallback fetchPage finished id=" + videoId);
        return buildMinimalStreamInfo(streamExtractor);
    }

    public LiveStreamUrls getLiveStreamUrlsByVideoId(String videoId) throws ExtractionException, IOException {
        SkyTubeApp.nonUiThread();
        LinkHandler linkHandler = getStreamHandler(videoId);
        StreamExtractor streamExtractor = streamingService.getStreamExtractor(linkHandler);
        streamExtractor.fetchPage();
        String hlsUrl = streamExtractor.getHlsUrl();
        String dashUrl = streamExtractor.getDashMpdUrl();
        if ((hlsUrl == null || hlsUrl.isEmpty()) && (dashUrl == null || dashUrl.isEmpty())) {
            throw new ExtractionException("No internal live playback URL is available.");
        }
        return new LiveStreamUrls(hlsUrl, dashUrl);
    }

    public static final class LiveStreamUrls {
        private final String hlsUrl;
        private final String dashUrl;

        LiveStreamUrls(String hlsUrl, String dashUrl) {
            this.hlsUrl = hlsUrl;
            this.dashUrl = dashUrl;
        }

        public String getPreferredUrl() {
            return hlsUrl != null && !hlsUrl.isEmpty() ? hlsUrl : dashUrl;
        }
    }

    /**
     * Return the most recent videos for the given channel
     * @param channelId the id of the channel
     * @return list of recent {@link YouTubeVideo}.
     * @throws ExtractionException
     * @throws IOException
     */
    private List<YouTubeVideo> getChannelVideos(ChannelId channelId) throws NewPipeException {
        SkyTubeApp.nonUiThread();
        VideoPagerWithChannel pager = getChannelPager(channelId);
        List<YouTubeVideo> result = pager.getNextPageAsVideosAndUpdateChannel(null).channel().getYouTubeVideos();
        Logger.i(this, "getChannelVideos for %s(%s)  -> %s videos", pager.getChannel().getTitle(), channelId, result.size());
        return result;
    }

    /**
     * Return the most recent videos for the given channel from a dedicated feed (with a {@link FeedExtractor}).
     * @param channelId the id of the channel
     * @return list of recent {@link YouTubeVideo}, or null, if there is no feed.
     * @throws ExtractionException
     * @throws IOException
     */
    private List<YouTubeVideo> getFeedVideos(String channelId) throws ExtractionException, IOException, NewPipeException {
        SkyTubeApp.nonUiThread();
        final String url = getListLinkHandler(channelId).getUrl();
        final FeedExtractor feedExtractor = streamingService.getFeedExtractor(url);
        if (feedExtractor == null) {
            Logger.i(this, "getFeedExtractor doesn't return anything for %s -> %s", channelId, url);
            return null;
        }
        feedExtractor.fetchPage();
        return new VideoPagerWithChannel(streamingService, feedExtractor, createInternalChannelFromFeed(feedExtractor)).getNextPageAsVideos();
    }

    /**
     * Return the most recent videos for the given channel, either from a dedicated feed (with a {@link FeedExtractor} or from
     * the generic {@link ChannelExtractor}.
     * @param channelId the id of the channel
     * @return list of recent {@link YouTubeVideo}.
     * @throws ExtractionException
     * @throws IOException
     */
    public List<YouTubeVideo> getVideosFromFeedOrFromChannel(ChannelId channelId) throws NewPipeException {
        try {
            SkyTubeApp.nonUiThread();

            List<YouTubeVideo> videos = getFeedVideos(channelId.getRawId());
            if (videos != null) {
                return videos;
            }
        } catch (IOException | ExtractionException | RuntimeException | NewPipeException e) {
            Logger.e(this, "Unable to get videos from a feed " + channelId + " : "+ e.getMessage(), e);
        }
        return getChannelVideos(channelId);
    }

    public VideoPager getTrending() throws NewPipeException {
        try {
            KioskList kiosks = streamingService.getKioskList();
            KioskExtractor kex = kiosks.getDefaultKioskExtractor();
            appendTrendingLog("default kiosk id=" + kex.getId() + " service=" + streamingService.getServiceInfo().getName());
            kex.fetchPage();
            appendTrendingLog("kiosk fetch success id=" + kex.getId());
            return new VideoPager(streamingService, kex);
        } catch (ExtractionException | IOException e) {
            appendTrendingLog("kiosk fetch failure msg=" + e.getMessage() + " type=" + e.getClass().getName());
            throw new NewPipeException("Unable to get 'trending' list:" + e.getMessage(), e);
        }
    }

    private static void appendTrendingLog(String message) {
        DiagnosticFileLogger.append(DiagnosticFileLogger.TRENDING_LOG_FILE_NAME,
                new StringBuilder()
                        .append(new java.util.Date()).append('\n')
                        .append("message=").append(message).append('\n')
                        .append("runtime=").append(TLSSocketFactory.getRuntimeSummary()).append('\n')
                        .append("---\n")
                        .toString());
    }

    public VideoPagerWithChannel getChannelPager(ChannelId channelId) throws NewPipeException {
        try {
            Logger.i(this, "[ChannelVideos] Fetching channel info for %s", channelId);
            ChannelWithExtractor channelExtractor = getChannelWithExtractor(channelId);
            return new VideoPagerWithChannel(streamingService, channelExtractor.findVideosTab(), channelExtractor.channel);
        } catch (ParsingException | RuntimeException e) {
            throw new NewPipeException("Getting videos for " + channelId + " fails: " + e.getMessage(), e);
        }
    }

    public ChannelWithExtractor getChannelWithExtractor(ChannelId channelId) throws NewPipeException {
        try {
            ChannelExtractor channelExtractor = getChannelExtractor(channelId);

            YouTubeChannel channel = createInternalChannel(channelExtractor);
            return new ChannelWithExtractor(channel, channelExtractor);
        } catch (ExtractionException | IOException | RuntimeException e) {
            throw new NewPipeException("Getting channel details for " + channelId + " fails: " + e.getMessage(), e);
        }
    }

    public PlaylistPager getPlaylistPager(String playlistId) throws NewPipeException {
        try {
            Logger.i(this, "[PlaylistVideos] Creating playlist pager for id=%s", playlistId);
            appendExtractorLog("PlaylistVideos", "handler start id=" + playlistId);
            final ListLinkHandler playlistLinkHandler;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                final String id = extractPlaylistIdCompat(playlistId);
                final String canonicalUrl = "https://www.youtube.com/playlist?list=" + id;
                playlistLinkHandler = new ListLinkHandler(
                        playlistId, canonicalUrl, id, Collections.emptyList(), null);
            } else {
                playlistLinkHandler = getPlaylistHandler(playlistId);
            }
            appendExtractorLog("PlaylistVideos", "handler finished id=" + playlistId);
            final PlaylistExtractor playlistExtractor = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                    ? new YoutubePlaylistExtractor(streamingService, playlistLinkHandler)
                    : streamingService.getPlaylistExtractor(playlistLinkHandler);
            appendExtractorLog("PlaylistVideos", "fetchPage start id=" + playlistId);
            playlistExtractor.fetchPage();
            appendExtractorLog("PlaylistVideos", "fetchPage finished id=" + playlistId);
            return new PlaylistPager(streamingService, playlistExtractor);
        } catch (ExtractionException | IOException | RuntimeException e) {
            throw new NewPipeException("Getting playlists for " + playlistId + " fails:" + e.getMessage(), e);
        }
    }

    public CommentPager getCommentPager(String videoId) throws NewPipeException {
        try {
            final ListLinkHandler linkHandler = streamingService.getCommentsLHFactory().fromId(videoId);
            final CommentsExtractor commentsExtractor = streamingService.getCommentsExtractor(linkHandler);
            return new CommentPager(streamingService, commentsExtractor);
        } catch (ExtractionException | RuntimeException | IOException e) {
            throw new NewPipeException("Getting comments for " + videoId + " fails:" + e.getMessage(), e);
        }
    }

    /**
     * Return detailed, fresh information for a channel from it's id.
     * @param persistentChannel
     * @return the {@link YouTubeChannel}, with a list of recent videos.
     * @throws ExtractionException
     * @throws IOException
     */
    public PersistentChannel getChannelDetails(ChannelId channelId, PersistentChannel persistentChannel) throws NewPipeException {
        Logger.i(this, "Fetching channel details for " + channelId);
        VideoPagerWithChannel pager = getChannelPager(channelId);
        // get the channel, and add all the videos from the first page
        try {
            return pager.getNextPageAsVideosAndUpdateChannel(persistentChannel);
        } catch (NewPipeException e) {
            Logger.e(this, "Unable to retrieve videos for "+channelId+", error: "+e.getMessage(), e);
            throw e;
        }
    }

    private YouTubeChannel createInternalChannelFromFeed(FeedExtractor extractor) throws ParsingException {
        return new YouTubeChannel(extractor.getId(), extractor.getName(), null,
                null, null, -1, false, 0, System.currentTimeMillis(), null, Collections.emptyList());
    }

    private YouTubeChannel createInternalChannel(ChannelExtractor extractor) throws ParsingException {
        return new YouTubeChannel(
                extractor.getId(),
                extractor.getName(),
                NewPipeUtils.filterHtml(extractor.getDescription()),
                callParser(() -> NewPipeUtils.getThumbnailUrl(extractor.getAvatars()), null),
                callParser(() -> NewPipeUtils.getThumbnailUrl(extractor.getBanners()), null),
                callParser(() -> extractor.getSubscriberCount(), -1L),
                false,
                0,
                System.currentTimeMillis(),
                null,
                extractor.getTags());
    }

    private <X> X callParser(ParserCall<X> parser, X defaultValue) {
        try {
            return parser.get();
        } catch (NullPointerException | ParsingException e) {
            Logger.e(this, "Unable to parse: " + parser + ", error: " + e.getMessage(), e);
            return defaultValue;
        }
    }

    private ChannelExtractor getChannelExtractor(ChannelId channelId)
            throws ExtractionException, IOException {
        // Extract from it
        ChannelExtractor channelExtractor = streamingService
                .getChannelExtractor(getListLinkHandler(Objects.requireNonNull(channelId, "channelId").getRawId()));
        channelExtractor.fetchPage();
        return channelExtractor;
    }

    private ListLinkHandler getListLinkHandler(String channelId) throws ParsingException {
        // Get channel LinkHandler, handle three cases:
        // 1, channelId=UCbx1TZgxfIauUZyPuBzEwZg
        // 2, channelId=https://www.youtube.com/channel/UCbx1TZgxfIauUZyPuBzEwZg
        // 3, channelId=channel/UCbx1TZgxfIauUZyPuBzEwZg
        ListLinkHandlerFactory channelLHFactory = streamingService.getChannelLHFactory();
        try {
            return channelLHFactory.fromUrl(channelId);
        } catch (ParsingException p) {
            if (DEBUG_LOG) {
                Logger.d(this, "Unable to parse channel url=%s", channelId);
            }
        }
        if (channelId.startsWith("channel/") || channelId.startsWith("c/") || channelId.startsWith("user/")) {
            return channelLHFactory.fromId(channelId);
        }
        return channelLHFactory.fromId("channel/" + channelId);
    }

    private ListLinkHandler getPlaylistHandler(String playlistId) throws ParsingException {
        ListLinkHandlerFactory factory = streamingService.getPlaylistLHFactory();
        try {
            return factory.fromUrl(playlistId);
        } catch (Exception parsingException) {
            Logger.i(instance, "PlaylistId '"+playlistId+"' is not an url:"+ parsingException.getMessage());
            return factory.fromId(playlistId);
        }
    }

    /**
     * Return detailed information about a video from it's id.
     * @param videoId the id of the video.
     * @return a {@link YouTubeVideo}
     * @throws ExtractionException
     * @throws IOException
     */
    public YouTubeVideo getDetails(String videoId) throws ExtractionException, IOException {
        SkyTubeApp.nonUiThread();
        LinkHandler url = getStreamHandler(videoId);
        StreamExtractor extractor = streamingService.getStreamExtractor(url);
        extractor.fetchPage();

        DateInfo uploadDate = new DateInfo(extractor.getUploadDate());
        Logger.i(this, "getDetails for %s -> %s %s", videoId, url.getUrl(), uploadDate);

        long viewCount;
        try {
            viewCount = extractor.getViewCount();
        } catch (NumberFormatException|ParsingException e) {
            Logger.e(this, "Unable to get view count for " + url.getUrl()+", error: "+e.getMessage(), e);
            viewCount = 0;
        }

        YouTubeVideo video = new YouTubeVideo(extractor.getId(), extractor.getName(), NewPipeUtils.filterHtml(extractor.getDescription()),
                extractor.getLength(), new YouTubeChannel(extractor.getUploaderUrl(), extractor.getUploaderName()),
                viewCount, uploadDate.instant, uploadDate.exact, NewPipeUtils.getThumbnailUrl(extractor.getThumbnails()));
        StreamType streamType = extractor.getStreamType();
        if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
            video.setLiveStream(true);
        }
        try {
            video.setLikeDislikeCount(extractor.getLikeCount(), getDislikeCount(extractor, videoId));
        } catch (ParsingException pe) {
            Logger.e(this, "Unable get like count for " + url.getUrl() + ", created at " + uploadDate + ", error:" + pe.getMessage(), pe);
            video.setLikeDislikeCount(null, null);
        }
        return video;
    }

    /**
     * Returns the recommendations supplied by YouTube for the current watch page.
     * Filtering is intentionally left to the caller so the app's VideoBlocker rules
     * can be applied consistently with every other video list.
     */
    public List<YouTubeVideo> getRelatedVideos(String videoId) throws ExtractionException, IOException {
        SkyTubeApp.nonUiThread();
        LinkHandler url = getStreamHandler(videoId);
        StreamExtractor extractor = streamingService.getStreamExtractor(url);
        extractor.fetchPage();

        List<YouTubeVideo> videos = new ArrayList<>();
        if (extractor.getRelatedItems() == null) {
            return videos;
        }
        for (InfoItem item : extractor.getRelatedItems().getItems()) {
            if (!(item instanceof StreamInfoItem)) {
                continue;
            }
            StreamInfoItem stream = (StreamInfoItem) item;
            try {
                ContentId contentId = getVideoId(stream.getUrl());
                DateInfo uploadDate = new DateInfo(stream.getUploadDate());
                YouTubeChannel channel = new YouTubeChannel(stream.getUploaderUrl(), stream.getUploaderName());
                YouTubeVideo video = new YouTubeVideo(contentId.getId(), stream.getName(),
                        stream.getShortDescription(), stream.getDuration(), channel,
                        stream.getViewCount(), uploadDate.instant, uploadDate.exact,
                        NewPipeUtils.getThumbnailUrl(stream.getThumbnails()));
                StreamType streamType = stream.getStreamType();
                if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
                    video.setLiveStream(true);
                }
                videos.add(video);
            } catch (Exception itemError) {
                Logger.e(this, "Unable to convert related item " + stream.getUrl(), itemError);
            }
        }
        Logger.i(this, "Related videos for %s: %d", videoId, videos.size());
        return videos;
    }

    private LinkHandler getStreamHandler(String videoIdOrUrl) throws ParsingException {
        final LinkHandlerFactory factory = streamingService.getStreamLHFactory();
        ParsingException lastError = null;

        if (videoIdOrUrl != null && !videoIdOrUrl.isEmpty()) {
            final String normalizedUrl = normalizeVideoUrl(videoIdOrUrl);
            final String watchUrl = buildWatchUrl(videoIdOrUrl);
            try {
                if (watchUrl != null) {
                    return factory.fromUrl(watchUrl);
                }
            } catch (ParsingException e) {
                lastError = e;
            }
            try {
                if (normalizedUrl != null && !normalizedUrl.equals(videoIdOrUrl)) {
                    return factory.fromUrl(normalizedUrl);
                }
            } catch (ParsingException e) {
                lastError = e;
            }
            try {
                return factory.fromId(videoIdOrUrl);
            } catch (ParsingException e) {
                lastError = e;
            }
        }

        throw lastError != null ? lastError : new ParsingException("Unable to resolve stream id/url: " + videoIdOrUrl);
    }

    private String normalizeVideoUrl(String videoIdOrUrl) {
        if (videoIdOrUrl == null) {
            return null;
        }
        if (videoIdOrUrl.startsWith("//")) {
            return "https:" + videoIdOrUrl;
        }
        if (videoIdOrUrl.startsWith("/")) {
            return "https://www.youtube.com" + videoIdOrUrl;
        }
        return videoIdOrUrl;
    }

    private String buildWatchUrl(String videoIdOrUrl) {
        if (videoIdOrUrl == null || videoIdOrUrl.isEmpty()) {
            return null;
        }
        if (videoIdOrUrl.startsWith("http://") || videoIdOrUrl.startsWith("https://")) {
            return videoIdOrUrl;
        }
        if (videoIdOrUrl.startsWith("/")) {
            return "https://www.youtube.com" + videoIdOrUrl;
        }
        return "https://www.youtube.com/watch?v=" + videoIdOrUrl;
    }

    private boolean isPlainVideoId(String videoIdOrUrl) {
        if (videoIdOrUrl == null || videoIdOrUrl.isEmpty()) {
            return false;
        }
        return !videoIdOrUrl.contains("://")
                && !videoIdOrUrl.contains("/")
                && !videoIdOrUrl.contains("?")
                && !videoIdOrUrl.contains("&");
    }

    private StreamInfo buildMinimalStreamInfo(StreamExtractor extractor)
            throws ExtractionException, IOException {
        appendPlaybackLog("minimal StreamInfo basic fields start");
        final String id = extractor.getId();
        final String url = extractor.getUrl();
        final String originalUrl = extractor.getOriginalUrl();
        final StreamType streamType = extractor.getStreamType();
        final String name = extractor.getName();
        final int ageLimit = extractor.getAgeLimit();

        final StreamInfo info = new StreamInfo(
                extractor.getServiceId(),
                url,
                originalUrl,
                streamType,
                id,
                name,
                ageLimit
        );

        info.setDescription(Description.EMPTY_DESCRIPTION);
        appendPlaybackLog("minimal manifest URLs start id=" + id);
        info.setDashMpdUrl(safeParse(() -> extractor.getDashMpdUrl(), "", "dashMpdUrl", id));
        info.setHlsUrl(safeParse(() -> extractor.getHlsUrl(), "", "hlsUrl", id));
        appendPlaybackLog("minimal audio streams start id=" + id);
        info.setAudioStreams(extractor.getAudioStreams());
        Logger.i(this, "[Playback] audio streams loaded id=%s count=%s", id, info.getAudioStreams().size());
        appendPlaybackLog("minimal audio streams finished id=" + id + " count=" + info.getAudioStreams().size());
        appendPlaybackLog("minimal video streams start id=" + id);
        info.setVideoStreams(extractor.getVideoStreams());
        Logger.i(this, "[Playback] video streams loaded id=%s count=%s", id, info.getVideoStreams().size());
        appendPlaybackLog("minimal video streams finished id=" + id + " count=" + info.getVideoStreams().size());
        appendPlaybackLog("minimal video-only streams start id=" + id);
        info.setVideoOnlyStreams(extractor.getVideoOnlyStreams());
        Logger.i(this, "[Playback] video-only streams loaded id=%s count=%s", id, info.getVideoOnlyStreams().size());
        appendPlaybackLog("minimal video-only streams finished id=" + id
                + " count=" + info.getVideoOnlyStreams().size());
        return info;
    }

    private void appendPlaybackLog(String message) {
        DiagnosticFileLogger.append(DiagnosticFileLogger.DEBUG_LOG_FILE_NAME,
                new java.util.Date() + "\nscope=NewPipePlayback\nstage=" + message + "\n---\n");
    }

    private <T> T safeParse(ParserCall<T> parser, T defaultValue, String field, String videoId) {
        try {
            return parser.get();
        } catch (Throwable e) {
            Logger.e(this, "[Playback] Unable to parse %s for %s: %s", field, videoId, e.getMessage());
            return defaultValue;
        }
    }

    @FunctionalInterface
    private interface ExtractorIoCall<T> {
        T get() throws IOException, ExtractionException;
    }

    private <T> T safeStreamExtract(ExtractorIoCall<T> parser, T defaultValue, String field, String videoId) {
        try {
            return parser.get();
        } catch (Throwable e) {
            Logger.e(this, "[Playback] Unable to extract %s for %s: %s", field, videoId, e.getMessage());
            return defaultValue;
        }
    }

    private Long getDislikeCount(StreamExtractor extractor, String id) {
        try {
            long dislikeCount = extractor.getDislikeCount();
            if (dislikeCount >= 0) {
                return dislikeCount;
            }
        } catch (ParsingException e) {
            Logger.e(this, "Unable get dislike count for " + extractor.getLinkHandler().getUrl() + ", error:" + e.getMessage(), e);
        }
        return getDislikeCountFromApi(id);
    }

    public Long getDislikeCountFromApi(String videoId)  {
        if (settings.isUseDislikeApi()) {
            // send the request
            String url = "https://returnyoutubedislikeapi.com/votes?videoId=" + videoId;
            try {
                Logger.i(this, "fetching dislike count for "+ url);
                OkHttpDownloader downloader = OkHttpDownloader.getInstance();
                Response response = downloader.get(url);
                // get the response
                int responseCode = response.responseCode();
                if (responseCode != 200) {
                    Logger.e(this, "ResponseCode " + responseCode + " for " + url);
                    return null;
                }

                JSONObject jsonObject = new JSONObject(response.responseBody());
                Logger.i(this, "for "+ url +" -> "+jsonObject);
                return jsonObject.getLong("dislikes");
            } catch (IOException | JSONException | ReCaptchaException e) {
                if (VERBOSE_DISLIKE_COUNT_LOG) {
                    Logger.e(this, "getDislikeCount: error: " + e.getMessage() + " for url:" + url, e);
                } else {
                    Logger.i(this, "getDislikeCount: error: " + e.getMessage() + " for url:" + url);
                }
            }
        } else {
            Logger.i(this, "Like fetching disabled for " + videoId);
        }
        return null;
    }

    static class DateInfo {
        boolean exact;
        Instant instant;

        public DateInfo(DateWrapper uploadDate) {
            if (uploadDate != null) {
                instant = uploadDate.offsetDateTime().toInstant();
                exact = !uploadDate.isApproximation();
            } else {
                instant = null;
                exact = false;
            }
        }

        private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        @NonNull
        @Override
        public String toString() {
            try {
                return "[time= " + dtf.format(instant) + ",exact=" + exact + ']';
            } catch (Exception e){
                return "[incorrect time= " + instant + " ,exact=" + exact + ']';
            }
        }
    }

    static String getThumbnailUrl(String id) {
        // Logger.d(NewPipeService.class, "getThumbnailUrl  %s", id);
        return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
    }


    public VideoPager getSearchResult(String query) throws NewPipeException {
        SkyTubeApp.nonUiThread();
        try {
            Logger.i(this, "[SearchResults] Fetching search results for query=%s", query);
            appendExtractorLog("SearchResults", "extractor start query=" + query);
            final SearchExtractor extractor;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                final String searchUrl = "https://www.youtube.com/results?search_query="
                        + URLEncoder.encode(query, "UTF-8");
                final SearchQueryHandler handler = new SearchQueryHandler(
                        searchUrl, searchUrl, query,
                        Collections.singletonList("all"), null);
                extractor = new YoutubeSearchExtractor(streamingService, handler);
            } else {
                extractor = streamingService.getSearchExtractor(query);
            }
            appendExtractorLog("SearchResults", "fetchPage start query=" + query);
            extractor.fetchPage();
            appendExtractorLog("SearchResults", "fetchPage finished query=" + query);
            return new VideoPager(streamingService, extractor);
        } catch (ExtractionException | IOException | RuntimeException e) {
            throw new NewPipeException("Getting search result for " + query + " fails: " + e.getMessage(), e);
        }
    }

    public synchronized static NewPipeService get() {
        if (instance == null) {
            getHttpDownloader();
            instance = new NewPipeService(ServiceList.YouTube, SkyTubeApp.getSettings());
        }
        return instance;
    }

    private void appendExtractorLog(String scope, String message) {
        DiagnosticFileLogger.append(DiagnosticFileLogger.DEBUG_LOG_FILE_NAME,
                new java.util.Date() + "\nscope=" + scope + "\nstage=" + message + "\n---\n");
    }

    private String extractPlaylistIdCompat(String playlistIdOrUrl) throws ParsingException {
        if (playlistIdOrUrl == null || playlistIdOrUrl.isEmpty()) {
            throw new ParsingException("Playlist id is empty");
        }
        if (!playlistIdOrUrl.contains("://")) {
            return playlistIdOrUrl;
        }
        try {
            final String query = URI.create(playlistIdOrUrl).getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("list=") && part.length() > 5) {
                        return part.substring(5);
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new ParsingException("Unable to parse playlist URL: " + playlistIdOrUrl, e);
        }
        throw new ParsingException("Playlist URL has no list id: " + playlistIdOrUrl);
    }

    public SubscriptionExtractor createSubscriptionExtractor() {
        return streamingService.getSubscriptionExtractor();
    }
    /**
     * Initialize NewPipe with a custom HttpDownloader.
     */
    public static OkHttpDownloader getHttpDownloader() {
        final OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder();
        TLSSocketFactory.configureOkHttpBuilder(httpClientBuilder);
        httpClientBuilder.addInterceptor(new NetworkDiagnosticsInterceptor());
        OkHttpDownloader downloader = OkHttpDownloader.init(httpClientBuilder);
        downloader.setApiUserAgent("SkyTube-Android-" + BuildConfig.VERSION_CODE);
        Logger.i(NewPipeService.class, "Initialized NewPipe downloader with runtime: %s", TLSSocketFactory.getRuntimeSummary());
        if (NewPipe.getDownloader() == null) {
            YoutubeStreamExtractor.setFetchIosClient(
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q);
            YoutubeStreamExtractor.setFetchMwebClient(false);
            NewPipe.init(downloader, Localization.DEFAULT, toContentCountry(SkyTubeApp.getSettings().getPreferredContentCountry()));
        }
        return downloader;
    }

    private static ContentCountry toContentCountry(String countryCode){
        if (countryCode == null || countryCode.isEmpty()) {
            return ContentCountry.DEFAULT;
        } else {
            return new ContentCountry(countryCode);
        }
    }

    public static void setCountry(String countryCodeStr) {
        getHttpDownloader();
        final ContentCountry contentCountry = toContentCountry(countryCodeStr);
        Log.i("NewPipeService", "set preferred content country to " + contentCountry);
        NewPipe.setPreferredContentCountry(contentCountry);
    }
}
