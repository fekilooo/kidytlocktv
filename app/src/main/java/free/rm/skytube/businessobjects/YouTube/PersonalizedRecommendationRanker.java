package free.rm.skytube.businessobjects.YouTube;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;
import free.rm.skytube.businessobjects.db.PlaybackStatusDb;
import free.rm.skytube.businessobjects.db.SubscriptionsDb;

/** Ranks local candidates using the recommendation signals available without a Google account. */
public final class PersonalizedRecommendationRanker {
    private static final long PROFILE_WINDOW_MILLIS = Duration.ofDays(180).toMillis();
    private static final long FRESHNESS_WINDOW_MILLIS = Duration.ofDays(14).toMillis();
    private static final int MAX_PROFILE_VIDEOS = 500;
    private static final int DIVERSE_PREFIX_SIZE = 16;
    private static final int MAX_PER_CHANNEL_IN_PREFIX = 2;

    private final PlaybackStatusDb playbackStatusDb = PlaybackStatusDb.getPlaybackStatusDb();
    private final TrendingExposureTracker exposureTracker = TrendingExposureTracker.getInstance();
    private final Random random = new Random(System.nanoTime());
    private final Set<String> subscribedChannelIds = new HashSet<>();
    private final Map<String, Integer> watchedChannelCounts = new HashMap<>();
    private final Map<String, Integer> watchedTopicCounts = new HashMap<>();
    private final Map<Integer, Integer> watchedCategoryCounts = new HashMap<>();

    public PersonalizedRecommendationRanker() {
        SubscriptionsDb subscriptionsDb = SubscriptionsDb.getSubscriptionsDb();
        subscriptionsDb.getSubscribedChannelIdsSet().forEach(
                channelId -> subscribedChannelIds.add(channelId.getRawId()));
        List<YouTubeVideo> profileVideos = subscriptionsDb.getRecentSubscriptionVideos(
                System.currentTimeMillis() - PROFILE_WINDOW_MILLIS);
        int inspected = 0;
        for (YouTubeVideo video : profileVideos) {
            if (inspected++ >= MAX_PROFILE_VIDEOS) {
                break;
            }
            PlaybackStatusDb.VideoWatchedStatus status = playbackStatusDb
                    .getVideoWatchedStatus(video.getId());
            if (!status.isWatched()) {
                continue;
            }
            int weight = status.isFullyWatched() ? 3 : 1;
            String channelId = safeChannelId(video);
            if (channelId != null) {
                watchedChannelCounts.merge(channelId, weight, Integer::sum);
            }
            if (video.getCategoryId() != null) {
                watchedCategoryCounts.merge(video.getCategoryId(), weight, Integer::sum);
            }
            for (String token : extractTopicTokens(video.getTitle())) {
                watchedTopicCounts.merge(token, weight, Integer::sum);
            }
        }
    }

    public List<YouTubeVideo> rankHome(List<YouTubeVideo> candidates) {
        return rank(candidates, null, false);
    }

    public List<YouTubeVideo> rankPlayback(YouTubeVideo currentVideo,
                                           List<YouTubeVideo> candidates) {
        return rank(candidates, currentVideo, true);
    }

    private List<YouTubeVideo> rank(List<YouTubeVideo> candidates, YouTubeVideo currentVideo,
                                    boolean playbackContext) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        final long now = System.currentTimeMillis();
        final Set<String> currentTopics = currentVideo != null
                ? extractTopicTokens(currentVideo.getTitle()) : Collections.emptySet();
        final String currentChannelId = currentVideo != null ? safeChannelId(currentVideo) : null;
        final List<ScoredVideo> scored = new ArrayList<>();
        final Set<String> seenIds = new HashSet<>();
        for (int index = 0; index < candidates.size(); index++) {
            YouTubeVideo video = candidates.get(index);
            if (video == null || video.getId() == null || !seenIds.add(video.getId())) {
                continue;
            }
            if (!playbackContext && exposureTracker.shouldTemporarilySuppress(video.getId())) {
                continue;
            }
            double score = score(video, index, now, currentTopics, currentChannelId,
                    playbackContext);
            scored.add(new ScoredVideo(video, score, index));
        }
        scored.sort(Comparator.comparingDouble((ScoredVideo item) -> item.score).reversed()
                .thenComparingInt(item -> item.originalIndex));

        List<YouTubeVideo> ranked = diversify(scored);
        Logger.i(this, "Personalized recommendation ranking context=%s candidates=%s profileChannels=%s profileTopics=%s",
                playbackContext ? "playback" : "home", ranked.size(),
                watchedChannelCounts.size(), watchedTopicCounts.size());
        for (int i = 0; i < Math.min(5, scored.size()); i++) {
            ScoredVideo item = scored.get(i);
            Logger.i(this, "Recommendation rank context=%s position=%s score=%.1f channel=%s title=%s",
                    playbackContext ? "playback" : "home", i + 1, item.score,
                    item.video.getSafeChannelName(), item.video.getTitle());
        }
        return ranked;
    }

    private double score(YouTubeVideo video, int originalIndex, long now,
                         Set<String> currentTopics, String currentChannelId,
                         boolean playbackContext) {
        String channelId = safeChannelId(video);
        PlaybackStatusDb.VideoWatchedStatus watchedStatus = playbackStatusDb
                .getVideoWatchedStatus(video.getId());
        double score = playbackContext
                ? 520d / (1d + originalIndex * 0.12d)
                : 120d / (1d + originalIndex * 0.08d);

        if (channelId != null && subscribedChannelIds.contains(channelId)) {
            score += playbackContext ? 100d : 240d;
        }
        int channelAffinity = channelId != null
                ? watchedChannelCounts.getOrDefault(channelId, 0) : 0;
        score += Math.min(playbackContext ? 180d : 320d,
                Math.log1p(channelAffinity) * (playbackContext ? 75d : 130d));

        Set<String> candidateTopics = extractTopicTokens(video.getTitle());
        double profileTopicScore = 0d;
        for (String token : candidateTopics) {
            profileTopicScore += Math.log1p(watchedTopicCounts.getOrDefault(token, 0));
        }
        score += Math.min(playbackContext ? 150d : 300d,
                profileTopicScore * (playbackContext ? 18d : 30d));
        if (video.getCategoryId() != null) {
            int categoryAffinity = watchedCategoryCounts.getOrDefault(video.getCategoryId(), 0);
            score += Math.min(playbackContext ? 90d : 170d,
                    Math.log1p(categoryAffinity) * (playbackContext ? 35d : 65d));
        }

        if (playbackContext) {
            if (currentChannelId != null && currentChannelId.equals(channelId)) {
                score += 260d;
            }
            int overlap = 0;
            for (String token : candidateTopics) {
                if (currentTopics.contains(token)) {
                    overlap++;
                }
            }
            score += Math.min(300d, overlap * 75d);
        }

        Long publishTimestamp = video.getPublishTimestamp();
        if (publishTimestamp != null && publishTimestamp > 0L) {
            long age = Math.max(0L, now - publishTimestamp);
            double freshness = 1d - Math.min(1d, (double) age / FRESHNESS_WINDOW_MILLIS);
            score += freshness * (playbackContext ? 90d : 220d);
        }
        long views = video.getViewsCountInt() != null ? video.getViewsCountInt() : 0L;
        score += Math.min(playbackContext ? 70d : 140d,
                Math.log10(Math.max(1L, views) + 1d) * (playbackContext ? 10d : 20d));

        if (watchedStatus.isFullyWatched()) {
            score -= playbackContext ? 650d : 420d;
        } else if (watchedStatus.isWatched()) {
            score -= playbackContext ? 180d : 100d;
        } else {
            score += playbackContext ? 140d : 70d;
        }
        if (!playbackContext) {
            score -= exposureTracker.getRankingPenalty(video.getId());
            score += (random.nextDouble() - 0.5d) * 180d;
        }
        return score;
    }

    private List<YouTubeVideo> diversify(List<ScoredVideo> scored) {
        List<YouTubeVideo> result = new ArrayList<>(scored.size());
        Set<String> selectedIds = new HashSet<>();
        Map<String, Integer> channelCounts = new HashMap<>();
        for (ScoredVideo item : scored) {
            if (result.size() >= DIVERSE_PREFIX_SIZE) {
                break;
            }
            String channelId = safeChannelId(item.video);
            int count = channelId != null ? channelCounts.getOrDefault(channelId, 0) : 0;
            if (channelId != null && count >= MAX_PER_CHANNEL_IN_PREFIX) {
                continue;
            }
            result.add(item.video);
            selectedIds.add(item.video.getId());
            if (channelId != null) {
                channelCounts.put(channelId, count + 1);
            }
        }
        for (ScoredVideo item : scored) {
            if (selectedIds.add(item.video.getId())) {
                result.add(item.video);
            }
        }
        return result;
    }

    private static Set<String> extractTopicTokens(String title) {
        if (title == null || title.trim().isEmpty()) {
            return Collections.emptySet();
        }
        String normalized = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (normalized.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String word : normalized.split("\\s+")) {
            if (word.length() >= 2 && !isCommonToken(word)) {
                tokens.add(word);
            }
            if (containsCjk(word)) {
                for (int i = 0; i + 1 < word.length(); i++) {
                    String bigram = word.substring(i, i + 2);
                    if (!isCommonToken(bigram)) {
                        tokens.add(bigram);
                    }
                }
            }
        }
        return tokens;
    }

    private static String safeChannelId(YouTubeVideo video) {
        return video != null && video.getChannel() != null
                && video.getChannel().getChannelId() != null
                ? video.getChannel().getChannelId().getRawId() : null;
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value.charAt(i));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCommonToken(String token) {
        return token.equals("the") || token.equals("and") || token.equals("with")
                || token.equals("official") || token.equals("video") || token.equals("最新")
                || token.equals("影片") || token.equals("完整版") || token.equals("中文");
    }

    private static final class ScoredVideo {
        private final YouTubeVideo video;
        private final double score;
        private final int originalIndex;

        private ScoredVideo(YouTubeVideo video, double score, int originalIndex) {
            this.video = video;
            this.score = score;
            this.originalIndex = originalIndex;
        }
    }
}
