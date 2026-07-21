/*
 * SkyTube Bilibili bridge
 *
 * The endpoint selection and signing flow are adapted from PipePipeExtractor:
 * https://github.com/InfinityLoop1308/PipePipeExtractor
 *
 * Both projects are licensed under GPL-3.0-or-later.
 */
package free.rm.skytube.businessobjects.bilibili;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import free.rm.skytube.businessobjects.TLSSocketFactory;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.POJOs.ChannelView;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubePlaylist;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;
import free.rm.skytube.businessobjects.YouTube.newpipe.ChannelId;
import free.rm.skytube.businessobjects.model.Status;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class BilibiliService {
    public static final String CHANNEL_PREFIX = "bili:";
    public static final String PLAYLIST_PREFIX = "bili:playlist:";
    public static final String VIDEO_PREFIX = "bili:video:";

    public static final String INITIAL_CHANNEL_UID = "629044766";
    public static final String INITIAL_CHANNEL_NAME =
            "\u4e2d\u914d\u52d5\u6f2b\u760b";
    public static final String INITIAL_CHANNEL_AVATAR =
            "https://i2.hdslb.com/bfs/face/a02f85272d925d0c45210b56ade425f1514df5e0.jpg";

    public static final String MEDIA_REFERER = "https://www.bilibili.com/";
    public static final String SPACE_REFERER = "https://space.bilibili.com/";
    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    private static final String APP_KEY = "1d8b6e7d45233436";
    private static final String APP_SECRET = "560c52ccd288fed045859ed18bffd973";
    private static final int PAGE_SIZE = 30;

    private final OkHttpClient httpClient;
    private final Map<String, List<Episode>> episodeCache = new ConcurrentHashMap<>();
    private final Map<String, List<String>> mediaUrlVariants = new ConcurrentHashMap<>();
    private final Map<String, YouTubeChannel> channelCache = new ConcurrentHashMap<>();

    private BilibiliService() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS);
        TLSSocketFactory.configureOkHttpBuilder(builder);
        httpClient = builder.build();
    }

    private static final class InstanceHolder {
        private static final BilibiliService INSTANCE = new BilibiliService();
    }

    public static BilibiliService get() {
        return InstanceHolder.INSTANCE;
    }

    public static boolean isChannelId(@Nullable String id) {
        return id != null && id.startsWith(CHANNEL_PREFIX)
                && !id.startsWith(PLAYLIST_PREFIX)
                && !id.startsWith(VIDEO_PREFIX);
    }

    public static boolean isPlaylistQuery(@Nullable String query) {
        return query != null && query.startsWith(PLAYLIST_PREFIX);
    }

    public static boolean isBilibiliMediaUrl(@Nullable String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".bilivideo.com")
                || lower.contains(".bilibili.com")
                || lower.contains(".akamaized.net")
                || lower.contains(".mcdn.bilivideo");
    }

    @NonNull
    public static String channelId(String uid) {
        return CHANNEL_PREFIX + uid;
    }

    @NonNull
    public static String rawUid(String channelId) {
        return isChannelId(channelId)
                ? channelId.substring(CHANNEL_PREFIX.length()) : channelId;
    }

    /** Returns the primary CDN URL followed by every backup URL supplied by Bilibili. */
    @NonNull
    public List<String> getMediaUrlVariants(@Nullable String primaryUrl) {
        if (primaryUrl == null || primaryUrl.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> variants = mediaUrlVariants.get(primaryUrl);
        return variants != null ? variants : Collections.singletonList(primaryUrl);
    }

    public ChannelView createInitialChannelView() {
        return new ChannelView(
                new ChannelId(channelId(INITIAL_CHANNEL_UID)),
                "Bili | " + INITIAL_CHANNEL_NAME,
                INITIAL_CHANNEL_AVATAR,
                false,
                Status.OK);
    }

    public YouTubeChannel createChannel(String channelId) {
        String uid = rawUid(channelId);
        YouTubeChannel cachedChannel = channelCache.get(uid);
        if (cachedChannel != null) {
            return cachedChannel;
        }
        String name = INITIAL_CHANNEL_UID.equals(uid)
                ? INITIAL_CHANNEL_NAME : "Bilibili " + uid;
        String avatar = INITIAL_CHANNEL_UID.equals(uid) ? INITIAL_CHANNEL_AVATAR : null;
        return new YouTubeChannel(
                channelId(uid),
                name,
                "https://space.bilibili.com/" + uid,
                avatar,
                null,
                -1,
                false,
                -1,
                System.currentTimeMillis(),
                null,
                Collections.emptyList());
    }

    /**
     * Retrieves the public profile for any Bilibili UID. This is used after OPML import so
     * non-default Bilibili subscriptions receive the same name and avatar treatment as the
     * bundled channel.
     */
    public YouTubeChannel getChannelDetails(String channelId) throws IOException {
        String uid = rawUid(channelId);
        if (uid.isEmpty() || !uid.matches("\\d+")) {
            throw new IOException("Invalid Bilibili channel UID: " + uid);
        }

        JSONObject data = requireData(requestJson(
                "https://api.bilibili.com/x/web-interface/card?mid=" + encode(uid),
                SPACE_REFERER + uid + "/"));
        JSONObject card = data.optJSONObject("card");
        if (card == null) {
            throw new IOException("Bilibili did not return channel details for UID " + uid);
        }

        String name = card.optString("name").trim();
        if (name.isEmpty()) {
            throw new IOException("Bilibili channel UID " + uid + " has no display name");
        }
        String avatar = normalizeImageUrl(card.optString("face"));
        long subscriberCount = data.optLong("follower", card.optLong("fans", -1L));
        YouTubeChannel channel = new YouTubeChannel(
                channelId(uid),
                name,
                SPACE_REFERER + uid + "/",
                avatar,
                null,
                subscriberCount,
                false,
                -1,
                System.currentTimeMillis(),
                null,
                Collections.emptyList());
        channelCache.put(uid, channel);
        return channel;
    }

    /** Makes complete channel details loaded from the database available to video/channel pages. */
    public void rememberChannel(@NonNull YouTubeChannel channel) {
        String rawId = channel.getChannelId().getRawId();
        if (isChannelId(rawId)
                && channel.getTitle() != null && !channel.getTitle().trim().isEmpty()
                && channel.getThumbnailUrl() != null && !channel.getThumbnailUrl().isEmpty()) {
            channelCache.put(rawUid(rawId), channel);
        }
    }

    public VideoPage getChannelVideos(String channelId, @Nullable String cursor)
            throws IOException {
        String uid = rawUid(channelId);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vmid", uid);
        if (cursor != null && !cursor.isEmpty()) {
            params.put("aid", cursor);
        }
        params.put("order", "pubdate");
        params.put("mobi_app", "android");
        params.put("ts", String.valueOf(System.currentTimeMillis() / 1000L));
        params.put("sign", createAppSign(params));

        JSONObject data = requireData(requestJson(
                "https://app.bilibili.com/x/v2/space/archive/cursor?"
                        + createQueryString(params),
                SPACE_REFERER + uid));
        JSONArray items = data.optJSONArray("item");
        List<CardData> videos = new ArrayList<>();
        String nextCursor = null;
        YouTubeChannel channel = createChannel(channelId(uid));

        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || item.optString("bvid").isEmpty()) {
                    continue;
                }
                videos.add(toVideo(item, channel, true));
                String aid = item.optString("param");
                if (aid.isEmpty() && item.optLong("aid", 0L) > 0L) {
                    aid = String.valueOf(item.optLong("aid"));
                }
                if (!aid.isEmpty()) {
                    nextCursor = aid;
                }
            }
        }
        boolean hasNext = data.optBoolean("has_next", !videos.isEmpty());
        return new VideoPage(videos, hasNext ? nextCursor : null);
    }

    public PlaylistPage getChannelPlaylists(YouTubeChannel channel, int pageNumber)
            throws IOException {
        String uid = rawUid(channel.getChannelId().getRawId());
        String url = "https://api.bilibili.com/x/polymer/web-space/seasons_series_list"
                + "?mid=" + encode(uid)
                + "&page_num=" + pageNumber
                + "&page_size=20";
        JSONObject data = requireData(requestJson(url, SPACE_REFERER + uid));
        JSONObject lists = data.optJSONObject("items_lists");
        List<YouTubePlaylist> playlists = new ArrayList<>();
        if (lists != null) {
            appendPlaylists(playlists, lists.optJSONArray("seasons_list"),
                    "season", channel, uid);
            appendPlaylists(playlists, lists.optJSONArray("series_list"),
                    "series", channel, uid);
        }
        boolean hasNext = !playlists.isEmpty() && playlists.size() >= 20;
        return new PlaylistPage(playlists, hasNext);
    }

    public VideoPage getPlaylistVideos(String playlistQuery, int pageNumber)
            throws IOException {
        PlaylistRef ref = PlaylistRef.parse(playlistQuery);
        String url;
        if ("series".equals(ref.type)) {
            url = "https://api.bilibili.com/x/series/archives"
                    + "?mid=" + encode(ref.uid)
                    + "&series_id=" + encode(ref.playlistId)
                    + "&only_normal=true&sort=desc"
                    + "&pn=" + pageNumber + "&ps=" + PAGE_SIZE;
        } else {
            url = "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list"
                    + "?mid=" + encode(ref.uid)
                    + "&season_id=" + encode(ref.playlistId)
                    + "&sort_reverse=false"
                    + "&page_num=" + pageNumber + "&page_size=" + PAGE_SIZE;
        }
        JSONObject data = requireData(requestJson(url, SPACE_REFERER + ref.uid));
        JSONArray archives = data.optJSONArray("archives");
        List<CardData> videos = new ArrayList<>();
        YouTubeChannel channel = createChannel(channelId(ref.uid));
        if (archives != null) {
            for (int i = 0; i < archives.length(); i++) {
                JSONObject item = archives.optJSONObject(i);
                if (item != null && !item.optString("bvid").isEmpty()) {
                    videos.add(toVideo(item, channel, false));
                }
            }
        }
        JSONObject page = data.optJSONObject("page");
        int total = page != null ? page.optInt("total", videos.size()) : videos.size();
        boolean hasNext = pageNumber * PAGE_SIZE < total;
        return new VideoPage(videos, hasNext ? String.valueOf(pageNumber + 1) : null);
    }

    public StreamInfo getStreamInfo(YouTubeVideo video) throws IOException {
        String bvid = video.getBilibiliBvid();
        String cid = video.getBilibiliCid();
        if (bvid == null || bvid.isEmpty()) {
            throw new IOException("Missing Bilibili video id");
        }
        if (cid == null || cid.isEmpty() || "0".equals(cid)) {
            cid = resolveFirstCid(bvid);
            video.setBilibiliSource(bvid, cid);
        }

        String url = "https://api.bilibili.com/x/player/playurl"
                + "?bvid=" + encode(bvid)
                + "&cid=" + encode(cid)
                + "&fnval=4048&qn=120&fourk=1&try_look=1";
        JSONObject data = requireData(requestJson(url,
                "https://www.bilibili.com/video/" + bvid));
        JSONObject dash = data.optJSONObject("dash");
        if (dash == null) {
            throw new IOException("Bilibili did not return DASH streams");
        }

        List<VideoStream> videoStreams = buildVideoStreams(
                dash.optJSONArray("video"), bvid);
        List<AudioStream> audioStreams = buildAudioStreams(
                dash.optJSONArray("audio"), bvid);
        if (videoStreams.isEmpty() || audioStreams.isEmpty()) {
            throw new IOException("No compatible Bilibili AVC/HEVC/AAC streams were returned");
        }

        StreamInfo info = new StreamInfo(
                4,
                video.getVideoUrl(),
                video.getVideoUrl(),
                StreamType.VIDEO_STREAM,
                video.getId(),
                video.getTitle(),
                0);
        info.setDuration(Math.max(video.getDurationInSeconds(), 0));
        info.setDescription(new Description(
                video.getDescription() != null ? video.getDescription() : "",
                Description.PLAIN_TEXT));
        info.setUploaderName(video.getSafeChannelName());
        info.setUploaderUrl(video.getChannel() != null
                ? video.getChannel().getDescription() : null);
        info.setViewCount(video.getViewsCountInt() != null
                ? video.getViewsCountInt() : -1L);
        info.setVideoStreams(Collections.emptyList());
        info.setVideoOnlyStreams(videoStreams);
        info.setAudioStreams(audioStreams);
        return info;
    }

    public List<Episode> getVideoEpisodes(String bvid) throws IOException {
        if (bvid == null || bvid.isEmpty()) {
            return Collections.emptyList();
        }
        List<Episode> cached = episodeCache.get(bvid);
        if (cached != null) {
            return cached;
        }

        JSONObject root = requestJson(
                "https://api.bilibili.com/x/player/pagelist?bvid=" + encode(bvid),
                "https://www.bilibili.com/video/" + bvid);
        if (root.optInt("code", -1) != 0) {
            throw new IOException("Unable to load Bilibili episodes: "
                    + root.optString("message"));
        }
        JSONArray data = root.optJSONArray("data");
        if (data == null || data.length() == 0) {
            return Collections.emptyList();
        }

        List<Episode> episodes = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String cid = String.valueOf(item.optLong("cid", 0L));
            if ("0".equals(cid)) {
                continue;
            }
            int page = item.optInt("page", i + 1);
            String title = item.optString("part", String.valueOf(page));
            episodes.add(new Episode(page, cid, title, item.optInt("duration", 0)));
        }
        List<Episode> immutable = Collections.unmodifiableList(episodes);
        episodeCache.put(bvid, immutable);
        return immutable;
    }

    public YouTubeVideo createEpisodeVideo(@NonNull YouTubeVideo source,
                                           @NonNull Episode episode) {
        String bvid = source.getBilibiliBvid();
        Long views = source.getViewsCountInt();
        Long published = source.getPublishTimestamp();
        YouTubeVideo video = new YouTubeVideo(
                VIDEO_PREFIX + bvid + ":" + episode.cid,
                source.getTitle() + " · " + episode.title,
                source.getDescription(),
                episode.durationSeconds,
                source.getChannel(),
                views != null ? views : -1L,
                published != null ? Instant.ofEpochMilli(published) : null,
                source.getPublishTimestampExact(),
                source.getThumbnailUrl());
        video.setBilibiliSource(bvid, episode.cid);
        return video;
    }

    private String resolveFirstCid(String bvid) throws IOException {
        List<Episode> episodes = getVideoEpisodes(bvid);
        if (episodes.isEmpty()) {
            throw new IOException("Bilibili video has no playable parts");
        }
        return episodes.get(0).cid;
    }

    private List<VideoStream> buildVideoStreams(@Nullable JSONArray array, String bvid) {
        List<JSONObject> candidates = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String codec = item.optString("codecs");
                int height = item.optInt("height", 0);
                String normalizedCodec = codec.toLowerCase(Locale.ROOT);
                if (height > 0 && height <= 1080
                        && (normalizedCodec.startsWith("avc1")
                        || normalizedCodec.startsWith("h264")
                        || normalizedCodec.startsWith("hev1")
                        || normalizedCodec.startsWith("hvc1")
                        || normalizedCodec.startsWith("hevc"))) {
                    candidates.add(item);
                }
            }
        }
        candidates.sort(Comparator
                .comparingInt((JSONObject item) -> item.optInt("height", 0)).reversed()
                .thenComparing(Comparator.comparingInt(
                        (JSONObject item) -> item.optInt("bandwidth", 0)).reversed()));

        List<VideoStream> result = new ArrayList<>();
        for (JSONObject item : candidates) {
            String mediaUrl = firstNonEmpty(item, "baseUrl", "base_url");
            if (mediaUrl == null) {
                continue;
            }
            mediaUrl = normalizeImageUrl(mediaUrl);
            registerMediaUrlVariants(item, mediaUrl);
            int height = item.optInt("height", 0);
            int width = item.optInt("width", 0);
            int fps = parseFps(firstNonEmpty(item, "frameRate", "frame_rate"));
            int qualityId = item.optInt("id", height);
            String resolution = height + "p";
            ItagItem itag = new ItagItem(
                    qualityId,
                    ItagItem.ItagType.VIDEO_ONLY,
                    MediaFormat.MPEG_4,
                    resolution,
                    fps);
            itag.setCodec(item.optString("codecs", "avc1"));
            itag.setBitrate(item.optInt("bandwidth", 0));
            itag.setWidth(width);
            itag.setHeight(height);
            itag.setQuality(resolution);
            applySegmentBase(item, itag);
            result.add(new VideoStream.Builder()
                    .setId("bilibili-" + bvid + "-video-" + qualityId + "-"
                            + codecFamily(item.optString("codecs", "avc1")))
                    .setContent(mediaUrl, true)
                    .setMediaFormat(MediaFormat.MPEG_4)
                    .setIsVideoOnly(true)
                    .setResolution(resolution)
                    .setItagItem(itag)
                    .build());
        }
        return result;
    }

    private List<AudioStream> buildAudioStreams(@Nullable JSONArray array, String bvid) {
        List<JSONObject> candidates = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    candidates.add(item);
                }
            }
        }
        candidates.sort(Comparator.comparingInt(
                (JSONObject item) -> item.optInt("bandwidth", 0)).reversed());

        List<AudioStream> result = new ArrayList<>();
        for (JSONObject item : candidates) {
            String mediaUrl = firstNonEmpty(item, "baseUrl", "base_url");
            if (mediaUrl == null) {
                continue;
            }
            mediaUrl = normalizeImageUrl(mediaUrl);
            registerMediaUrlVariants(item, mediaUrl);
            int audioId = item.optInt("id", 30280);
            int bitrate = item.optInt("bandwidth", 192000);
            ItagItem itag = new ItagItem(
                    audioId,
                    ItagItem.ItagType.AUDIO,
                    MediaFormat.M4A,
                    bitrate);
            itag.setCodec(item.optString("codecs", "mp4a"));
            itag.setBitrate(bitrate);
            applySegmentBase(item, itag);
            result.add(new AudioStream.Builder()
                    .setId("bilibili-" + bvid + "-audio-" + audioId)
                    .setContent(mediaUrl, true)
                    .setMediaFormat(MediaFormat.M4A)
                    .setAverageBitrate(bitrate)
                    .setAudioTrackId("bilibili-default")
                    .setAudioTrackName("Bilibili")
                    .setAudioLocale(Locale.CHINESE)
                    .setItagItem(itag)
                    .build());
        }
        return result;
    }

    private void registerMediaUrlVariants(@NonNull JSONObject item, @NonNull String primaryUrl) {
        LinkedHashMap<String, Boolean> unique = new LinkedHashMap<>();
        unique.put(primaryUrl, Boolean.TRUE);
        appendMediaUrls(unique, item.optJSONArray("backupUrl"));
        appendMediaUrls(unique, item.optJSONArray("backup_url"));
        mediaUrlVariants.put(primaryUrl,
                Collections.unmodifiableList(new ArrayList<>(unique.keySet())));
    }

    private static void appendMediaUrls(@NonNull Map<String, Boolean> output,
                                        @Nullable JSONArray urls) {
        if (urls == null) {
            return;
        }
        for (int i = 0; i < urls.length(); i++) {
            String url = urls.optString(i, null);
            if (url != null && !url.isEmpty()) {
                output.put(normalizeImageUrl(url), Boolean.TRUE);
            }
        }
    }

    @NonNull
    private static String codecFamily(@Nullable String codec) {
        String normalized = codec != null ? codec.toLowerCase(Locale.ROOT) : "";
        return normalized.startsWith("hev1") || normalized.startsWith("hvc1")
                || normalized.startsWith("hevc") ? "hvc1" : "avc1";
    }

    private static void applySegmentBase(JSONObject stream, ItagItem itag) {
        JSONObject segmentBase = stream.optJSONObject("SegmentBase");
        if (segmentBase == null) {
            segmentBase = stream.optJSONObject("segment_base");
        }
        if (segmentBase == null) {
            return;
        }
        int[] initialization = parseByteRange(firstNonEmpty(
                segmentBase, "Initialization", "initialization"));
        int[] indexRange = parseByteRange(firstNonEmpty(
                segmentBase, "indexRange", "index_range"));
        if (initialization != null) {
            itag.setInitStart(initialization[0]);
            itag.setInitEnd(initialization[1]);
        }
        if (indexRange != null) {
            itag.setIndexStart(indexRange[0]);
            itag.setIndexEnd(indexRange[1]);
        }
    }

    @Nullable
    private static int[] parseByteRange(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.trim().split("-");
        if (parts.length != 2) {
            return null;
        }
        try {
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            return start >= 0 && end >= start ? new int[]{start, end} : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private YouTubeVideo toVideo(JSONObject item, YouTubeChannel channel, boolean clientItem) {
        String bvid = item.optString("bvid");
        String cid = clientItem
                ? String.valueOf(item.optLong("first_cid", 0L)) : "0";
        String thumbnail = normalizeImageUrl(clientItem
                ? item.optString("cover") : item.optString("pic"));
        long published = item.optLong(
                clientItem ? "ctime" : "pubdate",
                item.optLong("ctime", 0L));
        long views = item.optLong("play", -1L);
        JSONObject stats = item.optJSONObject("stat");
        if (views < 0 && stats != null) {
            views = stats.optLong("view", -1L);
        }
        YouTubeVideo video = new YouTubeVideo(
                VIDEO_PREFIX + bvid + ":" + cid,
                item.optString("title", bvid),
                "",
                item.optLong("duration", 0L),
                channel,
                views,
                published > 0 ? Instant.ofEpochSecond(published) : null,
                true,
                thumbnail);
        video.setBilibiliSource(bvid, cid);
        return video;
    }

    private void appendPlaylists(List<YouTubePlaylist> output,
                                 @Nullable JSONArray items,
                                 String type,
                                 YouTubeChannel channel,
                                 String uid) {
        if (items == null) {
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject wrapper = items.optJSONObject(i);
            JSONObject meta = wrapper != null ? wrapper.optJSONObject("meta") : null;
            if (meta == null || meta.optLong("total", 0L) <= 0L) {
                continue;
            }
            String key = "series".equals(type) ? "series_id" : "season_id";
            String playlistId = String.valueOf(meta.optLong(key, 0L));
            if ("0".equals(playlistId)) {
                continue;
            }
            String source = PLAYLIST_PREFIX + type + ":" + uid + ":" + playlistId;
            String title = meta.optString("name", meta.optString("title", playlistId));
            output.add(new YouTubePlaylist(
                    source,
                    source,
                    title,
                    meta.optString("description", ""),
                    null,
                    meta.optLong("total", 0L),
                    normalizeImageUrl(meta.optString("cover")),
                    channel));
        }
    }

    private JSONObject requestJson(String url, String referer) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "application/json,text/plain,*/*")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("Bilibili HTTP " + response.code());
            }
            try {
                return new JSONObject(body.string());
            } catch (JSONException e) {
                throw new IOException("Invalid Bilibili JSON response", e);
            }
        }
    }

    private static JSONObject requireData(JSONObject root) throws IOException {
        int code = root.optInt("code", -1);
        if (code != 0) {
            throw new IOException("Bilibili API " + code + ": "
                    + root.optString("message", root.optString("msg")));
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new IOException("Bilibili API returned no data");
        }
        return data;
    }

    private static String createAppSign(Map<String, String> params) {
        params.put("appkey", APP_KEY);
        String query = createQueryString(new TreeMap<>(params));
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(
                    (query + APP_SECRET).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return output.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign Bilibili request", e);
        }
    }

    private static String createQueryString(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(entry.getKey()))
                    .append('=')
                    .append(encode(entry.getValue()));
        }
        return query.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to encode URL value", e);
        }
    }

    @Nullable
    private static String firstNonEmpty(JSONObject object, String first, String second) {
        String value = object.optString(first);
        if (value.isEmpty()) {
            value = object.optString(second);
        }
        return value.isEmpty() ? null : value;
    }

    private static String normalizeImageUrl(String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith("//")) {
            return "https:" + url;
        }
        return url.replace("http:", "https:");
    }

    private static int parseFps(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return 30;
        }
        try {
            if (value.contains("/")) {
                String[] parts = value.split("/");
                return Math.max(1, Math.round(
                        Float.parseFloat(parts[0]) / Float.parseFloat(parts[1])));
            }
            return Math.max(1, Math.round(Float.parseFloat(value)));
        } catch (RuntimeException e) {
            return 30;
        }
    }

    public static final class VideoPage {
        public final List<CardData> videos;
        @Nullable public final String nextCursor;

        VideoPage(List<CardData> videos, @Nullable String nextCursor) {
            this.videos = videos;
            this.nextCursor = nextCursor;
        }

        public boolean hasNext() {
            return nextCursor != null && !nextCursor.isEmpty();
        }
    }

    public static final class PlaylistPage {
        public final List<YouTubePlaylist> playlists;
        public final boolean hasNext;

        PlaylistPage(List<YouTubePlaylist> playlists, boolean hasNext) {
            this.playlists = playlists;
            this.hasNext = hasNext;
        }
    }

    public static final class Episode {
        public final int page;
        public final String cid;
        public final String title;
        public final int durationSeconds;

        Episode(int page, String cid, String title, int durationSeconds) {
            this.page = page;
            this.cid = cid;
            this.title = title;
            this.durationSeconds = durationSeconds;
        }
    }

    private static final class PlaylistRef {
        final String type;
        final String uid;
        final String playlistId;

        private PlaylistRef(String type, String uid, String playlistId) {
            this.type = type;
            this.uid = uid;
            this.playlistId = playlistId;
        }

        static PlaylistRef parse(String query) throws IOException {
            String[] parts = query.split(":", 5);
            if (parts.length != 5 || !"bili".equals(parts[0])
                    || !"playlist".equals(parts[1])) {
                throw new IOException("Invalid Bilibili playlist reference");
            }
            return new PlaylistRef(parts[2], parts[3], parts[4]);
        }
    }
}
