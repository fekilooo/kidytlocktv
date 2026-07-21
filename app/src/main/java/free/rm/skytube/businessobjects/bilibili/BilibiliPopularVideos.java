package free.rm.skytube.businessobjects.bilibili;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.YouTube.GetYouTubeVideos;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;
import free.rm.skytube.businessobjects.YouTube.newpipe.ChannelId;
import free.rm.skytube.businessobjects.db.SubscriptionsDb;

/**
 * Builds a hot feed exclusively from Bilibili channels in the subscriptions list.
 */
public final class BilibiliPopularVideos extends GetYouTubeVideos {
    private final Map<String, String> cursors = new HashMap<>();
    private final Set<String> exhaustedChannels = new HashSet<>();
    private final Set<String> emittedVideoIds = new HashSet<>();
    private List<String> channelIds;

    @Override
    public void init() throws IOException {
        // Database access is deferred to getNextVideos(), which runs off the UI thread.
    }

    @Override
    public List<CardData> getNextVideos() {
        ensureChannelIds();
        if (channelIds.isEmpty()) {
            noMoreVideoPages = true;
            return Collections.emptyList();
        }

        List<CardData> pageVideos = new ArrayList<>();
        Exception lastFailure = null;
        for (String channelId : channelIds) {
            if (exhaustedChannels.contains(channelId)) {
                continue;
            }
            try {
                BilibiliService.VideoPage page = BilibiliService.get()
                        .getChannelVideos(channelId, cursors.get(channelId));
                if (page.hasNext()) {
                    cursors.put(channelId, page.nextCursor);
                } else {
                    exhaustedChannels.add(channelId);
                }
                for (CardData card : page.videos) {
                    if (card instanceof YouTubeVideo
                            && emittedVideoIds.add(card.getId())) {
                        pageVideos.add(card);
                    }
                }
            } catch (Exception e) {
                lastFailure = e;
                exhaustedChannels.add(channelId);
                Logger.e(this, "Unable to load Bilibili hot feed for "
                        + channelId + ": " + e.getMessage(), e);
            }
        }

        if (lastFailure != null) {
            setLastException(lastFailure);
        }
        noMoreVideoPages = exhaustedChannels.size() >= channelIds.size();
        pageVideos.sort(Comparator.comparingDouble(
                BilibiliPopularVideos::hotScore).reversed());
        return pageVideos;
    }

    @Override
    public void reset() {
        super.reset();
        cursors.clear();
        exhaustedChannels.clear();
        emittedVideoIds.clear();
        channelIds = null;
    }

    private void ensureChannelIds() {
        if (channelIds != null) {
            return;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(BilibiliService.channelId(BilibiliService.INITIAL_CHANNEL_UID));
        for (ChannelId channelId
                : SubscriptionsDb.getSubscriptionsDb().getSubscribedChannelIdsSet()) {
            if (BilibiliService.isChannelId(channelId.getRawId())) {
                ids.add(channelId.getRawId());
            }
        }
        channelIds = new ArrayList<>(ids);
    }

    private static double hotScore(CardData card) {
        YouTubeVideo video = (YouTubeVideo) card;
        long views = video.getViewsCountInt() != null
                ? Math.max(0L, video.getViewsCountInt()) : 0L;
        long published = video.getPublishTimestamp() != null
                ? video.getPublishTimestamp() : System.currentTimeMillis();
        double ageHours = Math.max(
                0.0,
                (System.currentTimeMillis() - published) / 3_600_000.0);
        return Math.log1p(views) * 100.0 - Math.log1p(ageHours) * 32.0;
    }
}
