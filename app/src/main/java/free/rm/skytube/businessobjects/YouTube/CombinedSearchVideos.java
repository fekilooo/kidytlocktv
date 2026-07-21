package free.rm.skytube.businessobjects.YouTube;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.bilibili.BilibiliSearchVideos;

/**
 * Keeps the existing YouTube search and appends subscribed Bilibili matches.
 */
public final class CombinedSearchVideos extends GetYouTubeVideos {
    private final NewPipeVideoBySearch youtubeSearch = new NewPipeVideoBySearch();
    private final BilibiliSearchVideos bilibiliSearch = new BilibiliSearchVideos();

    @Override
    public void init() throws IOException {
        youtubeSearch.init();
        bilibiliSearch.init();
    }

    @Override
    public void setQuery(String query) {
        youtubeSearch.setQuery(query);
        bilibiliSearch.setQuery(query);
    }

    @Override
    public List<CardData> getNextVideos() {
        List<CardData> results = new ArrayList<>();
        if (!youtubeSearch.noMoreVideoPages()) {
            results.addAll(youtubeSearch.getNextVideos());
        }
        if (!bilibiliSearch.noMoreVideoPages()) {
            results.addAll(bilibiliSearch.getNextVideos());
        }

        noMoreVideoPages = youtubeSearch.noMoreVideoPages()
                && bilibiliSearch.noMoreVideoPages();
        if (results.isEmpty()) {
            Exception failure = youtubeSearch.getLastException();
            if (failure == null) {
                failure = bilibiliSearch.getLastException();
            }
            if (failure != null) {
                setLastException(failure);
            }
        }
        return results;
    }

    @Override
    public void reset() {
        super.reset();
        youtubeSearch.reset();
        bilibiliSearch.reset();
    }
}
