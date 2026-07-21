package free.rm.skytube.businessobjects.YouTube;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import free.rm.skytube.businessobjects.DiagnosticFileLogger;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.TLSSocketFactory;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;
import free.rm.skytube.businessobjects.YouTube.newpipe.NewPipeTrendingItems;
import free.rm.skytube.businessobjects.db.SubscriptionsDb;

/** Combines subscribed-channel candidates with regional trending and ranks them personally. */
public class TrendingVideosWithSubscriptions extends GetYouTubeVideos {
    private static final int PAGE_SIZE = 20;
    private static final long RECOMMENDATION_WINDOW_MILLIS = Duration.ofDays(180).toMillis();

    private final NewPipeTrendingItems fallbackTrending = new NewPipeTrendingItems();
    private final Set<String> seenVideoIds = new HashSet<>();
    private List<YouTubeVideo> subscriptionCandidates = Collections.emptyList();
    private List<YouTubeVideo> pendingRankedVideos = Collections.emptyList();
    private int pendingOffset;
    private boolean initialPoolBuilt;
    private boolean subscriptionCandidatesLoaded;
    private PersonalizedRecommendationRanker ranker;

    @Override
    public synchronized void init() {
        appendTrendingLog("init");
    }

    @Override
    public synchronized List<CardData> getNextVideos() {
        if (!subscriptionCandidatesLoaded) {
            loadSubscriptionCandidates();
            subscriptionCandidatesLoaded = true;
        }
        if (ranker == null) {
            ranker = new PersonalizedRecommendationRanker();
        }
        if (pendingOffset < pendingRankedVideos.size()) {
            return getNextPendingPage();
        }

        List<CardData> trendingPage = fallbackTrending.getNextVideos();
        List<YouTubeVideo> candidates = new ArrayList<>();
        if (!initialPoolBuilt) {
            candidates.addAll(subscriptionCandidates);
            initialPoolBuilt = true;
        }
        addVideoCandidates(candidates, trendingPage);
        pendingRankedVideos = ranker.rankHome(candidates);
        pendingOffset = 0;
        noMoreVideoPages = pendingRankedVideos.isEmpty() && fallbackTrending.noMoreVideoPages();
        appendTrendingLog("ranked home pool candidates=" + candidates.size()
                + " ranked=" + pendingRankedVideos.size()
                + " regionalRaw=" + (trendingPage != null ? trendingPage.size() : 0)
                + " noMore=" + noMoreVideoPages);
        return getNextPendingPage();
    }

    @Override
    public synchronized void reset() {
        super.reset();
        fallbackTrending.reset();
        seenVideoIds.clear();
        pendingRankedVideos = Collections.emptyList();
        pendingOffset = 0;
        initialPoolBuilt = false;
        subscriptionCandidates = Collections.emptyList();
        subscriptionCandidatesLoaded = false;
        ranker = null;
    }

    private void loadSubscriptionCandidates() {
        List<YouTubeVideo> recentVideos = SubscriptionsDb.getSubscriptionsDb()
                .getRecentSubscriptionVideos(System.currentTimeMillis() - RECOMMENDATION_WINDOW_MILLIS);
        subscriptionCandidates = recentVideos;
        Logger.i(this, "Loaded %s subscription candidates for personalized home ranking",
                recentVideos.size());
    }

    private void addVideoCandidates(List<YouTubeVideo> target, List<CardData> cards) {
        if (cards == null) {
            return;
        }
        for (CardData card : cards) {
            if (card instanceof YouTubeVideo) {
                target.add((YouTubeVideo) card);
            }
        }
    }

    private List<CardData> getNextPendingPage() {
        List<CardData> page = new ArrayList<>(PAGE_SIZE);
        while (pendingOffset < pendingRankedVideos.size() && page.size() < PAGE_SIZE) {
            YouTubeVideo video = pendingRankedVideos.get(pendingOffset++);
            if (seenVideoIds.add(video.getId())) {
                page.add(video);
            }
        }
        if (pendingOffset >= pendingRankedVideos.size()) {
            pendingRankedVideos = Collections.emptyList();
            pendingOffset = 0;
            noMoreVideoPages = fallbackTrending.noMoreVideoPages();
        }
        appendTrendingLog("return personalized page size=" + page.size()
                + " pending=" + (pendingRankedVideos.size() - pendingOffset)
                + " noMore=" + noMoreVideoPages);
        return page;
    }

    private void appendTrendingLog(String message) {
        DiagnosticFileLogger.append(DiagnosticFileLogger.TRENDING_LOG_FILE_NAME,
                new StringBuilder()
                        .append(new java.util.Date()).append('\n')
                        .append("message=").append(message).append('\n')
                        .append("runtime=").append(TLSSocketFactory.getRuntimeSummary()).append('\n')
                        .append("---\n")
                        .toString());
    }
}
