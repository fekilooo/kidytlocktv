package free.rm.skytube.businessobjects.bilibili;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
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
 * Searches only videos belonging to Bilibili channels shown in subscriptions.
 */
public final class BilibiliSearchVideos extends GetYouTubeVideos {
    private static final int MAX_PAGES_PER_CHANNEL_AND_REQUEST = 3;
    private static final int TARGET_RESULTS_PER_REQUEST = 16;

    private final Map<String, String> cursors = new HashMap<>();
    private final Set<String> exhaustedChannels = new HashSet<>();
    private final Set<String> emittedVideoIds = new HashSet<>();
    private List<String> channelIds;
    private String query = "";

    @Override
    public void init() throws IOException {
        // Database and network access run from getNextVideos() on the worker thread.
    }

    @Override
    public void setQuery(String query) {
        this.query = query != null ? query : "";
    }

    @Override
    public List<CardData> getNextVideos() {
        ensureChannelIds();
        if (channelIds.isEmpty() || ChineseSearchNormalizer.normalize(query).isEmpty()) {
            noMoreVideoPages = true;
            return Collections.emptyList();
        }

        List<CardData> matches = new ArrayList<>();
        Exception lastFailure = null;
        for (String channelId : channelIds) {
            int pagesLoaded = 0;
            while (!exhaustedChannels.contains(channelId)
                    && pagesLoaded < MAX_PAGES_PER_CHANNEL_AND_REQUEST
                    && matches.size() < TARGET_RESULTS_PER_REQUEST) {
                try {
                    BilibiliService.VideoPage page = BilibiliService.get()
                            .getChannelVideos(channelId, cursors.get(channelId));
                    pagesLoaded++;
                    if (page.hasNext()) {
                        cursors.put(channelId, page.nextCursor);
                    } else {
                        exhaustedChannels.add(channelId);
                    }
                    collectMatches(page.videos, matches);
                } catch (Exception e) {
                    lastFailure = e;
                    exhaustedChannels.add(channelId);
                    Logger.e(this, "Unable to search Bilibili subscription "
                            + channelId + ": " + e.getMessage(), e);
                }
            }
        }

        if (lastFailure != null) {
            setLastException(lastFailure);
        }
        noMoreVideoPages = exhaustedChannels.size() >= channelIds.size();
        return matches;
    }

    @Override
    public void reset() {
        super.reset();
        cursors.clear();
        exhaustedChannels.clear();
        emittedVideoIds.clear();
        channelIds = null;
    }

    private void collectMatches(List<CardData> candidates, List<CardData> matches) {
        for (CardData card : candidates) {
            if (!(card instanceof YouTubeVideo)) {
                continue;
            }
            YouTubeVideo video = (YouTubeVideo) card;
            String searchableText = video.getTitle() + " " + video.getSafeChannelName();
            if (ChineseSearchNormalizer.matches(query, searchableText)
                    && emittedVideoIds.add(video.getId())) {
                matches.add(video);
            }
        }
    }

    private void ensureChannelIds() {
        if (channelIds != null) {
            return;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        // This channel is always present in the visible subscriptions rail.
        ids.add(BilibiliService.channelId(BilibiliService.INITIAL_CHANNEL_UID));
        for (ChannelId channelId
                : SubscriptionsDb.getSubscriptionsDb().getSubscribedChannelIdsSet()) {
            if (BilibiliService.isChannelId(channelId.getRawId())) {
                ids.add(channelId.getRawId());
            }
        }
        channelIds = new ArrayList<>(ids);
    }
}
