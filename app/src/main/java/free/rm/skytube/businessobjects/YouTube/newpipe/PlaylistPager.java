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

package free.rm.skytube.businessobjects.YouTube.newpipe;

import android.os.Build;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubePlaylist;

public class PlaylistPager extends VideoPager {
    private YouTubePlaylist playlist;
    private final PlaylistExtractor playlistExtractor;
    private boolean initialPageConsumed;
    private boolean usePlaylistInfoPaging;

    public PlaylistPager(StreamingService streamingService, PlaylistExtractor playlistExtractor) {
        super(streamingService, playlistExtractor);
        this.playlistExtractor = playlistExtractor;
    }

    @Override
    public List<CardData> getNextPage() throws NewPipeException {
        Logger.i(this, "getNextPage start: initialPageConsumed=%s hasNext=%s playlistId=%s",
                initialPageConsumed, isHasNextPage(), getSafePlaylistId());
        // PlaylistInfo.getInfo() can stall after a successful response on some Android 10 boxes.
        // The extractor pager is the original SkyTube path and already supports lockup items.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            appendPagerDiagnostic("Android 10 direct playlist extractor path");
            return super.getNextPage();
        }
        if (!initialPageConsumed) {
            initialPageConsumed = true;
            try {
                List<CardData> result = loadItemsFromPlaylistInfo();
                Logger.i(this, "Initial playlist page loaded: playlistId=%s items=%s nextPage=%s",
                        getSafePlaylistId(), result.size(), getNextPageInfo());
                return result;
            } catch (IOException | ExtractionException | RuntimeException e) {
                throw new NewPipeException("Error:" + e.getMessage(), e);
            }
        }
        if (usePlaylistInfoPaging) {
            try {
                List<CardData> result = loadMoreItemsFromPlaylistInfo();
                Logger.i(this, "Next playlist page loaded: playlistId=%s items=%s nextPage=%s",
                        getSafePlaylistId(), result.size(), getNextPageInfo());
                return result;
            } catch (IOException | ExtractionException | RuntimeException e) {
                throw new NewPipeException("Error:" + e.getMessage(), e);
            }
        }
        List<CardData> result = super.getNextPage();
        Logger.i(this, "Next playlist page loaded: playlistId=%s items=%s nextPage=%s",
                getSafePlaylistId(), result.size(), getNextPageInfo());
        return result;
    }

    @Override
    protected List<CardData> extract(ListExtractor.InfoItemsPage<? extends InfoItem> page) throws NewPipeException {
        ensurePlaylistMetadata();
        List<CardData> result = super.extract(page);
        Logger.i(this, "Extracted playlist page: playlistId=%s pageItems=%s resultItems=%s hasNext=%s",
                getSafePlaylistId(),
                page != null && page.getItems() != null ? page.getItems().size() : -1,
                result.size(),
                page != null && page.hasNextPage());
        return result;
    }

    public YouTubePlaylist getPlaylist() {
        ensurePlaylistMetadata();
        return playlist;
    }

    private void ensurePlaylistMetadata() {
        if (playlist != null) {
            return;
        }
        try {
            String uploaderUrl = playlistExtractor.getUploaderUrl();
            String channelId = uploaderUrl != null && !uploaderUrl.isEmpty()
                    ? getStreamingService().getChannelLHFactory().fromUrl(uploaderUrl).getId()
                    : null;
            playlist = new YouTubePlaylist(
                    playlistExtractor.getId(),
                    playlistExtractor.getName(),
                    "" /* description */,
                    null /* publishDate */,
                    playlistExtractor.getStreamCount(),
                    NewPipeUtils.getThumbnailUrl(playlistExtractor.getThumbnails()),
                    new YouTubeChannel(channelId, playlistExtractor.getUploaderName())
            );
            Logger.i(this, "Playlist metadata ready: id=%s title=%s channelId=%s streamCount=%s",
                    playlist.getId(), playlist.getTitle(), channelId, playlist.getVideoCount());
        } catch (ParsingException e) {
            Logger.e(this, "Unable to parse: " + e.getMessage(), e);
        }
    }

    private String getSafePlaylistId() {
        try {
            return playlistExtractor.getId();
        } catch (ParsingException e) {
            return "parse-error";
        }
    }

    private List<CardData> loadItemsFromPlaylistInfo() throws IOException, ExtractionException, NewPipeException {
        PlaylistInfo playlistInfo = PlaylistInfo.getInfo(getStreamingService(), playlistExtractor.getLinkHandler().getUrl());
        usePlaylistInfoPaging = true;
        Logger.i(this, "PlaylistInfo initial load: playlistId=%s relatedItems=%s hasNext=%s",
                getSafePlaylistId(), playlistInfo.getRelatedItems().size(), playlistInfo.hasNextPage());
        return process(createInfoItemsPage(playlistInfo.getRelatedItems(), playlistInfo.getNextPage()));
    }

    private List<CardData> loadMoreItemsFromPlaylistInfo() throws IOException, ExtractionException, NewPipeException {
        if (getNextPageInfo() == null) {
            return Collections.emptyList();
        }
        InfoItemsPage<StreamInfoItem> nextPage = PlaylistInfo.getMoreItems(
                getStreamingService(),
                playlistExtractor.getLinkHandler().getUrl(),
                getNextPageInfo());
        Logger.i(this, "PlaylistInfo next load: playlistId=%s relatedItems=%s hasNext=%s",
                getSafePlaylistId(), nextPage.getItems().size(), nextPage.hasNextPage());
        return process(nextPage);
    }

    private InfoItemsPage<StreamInfoItem> createInfoItemsPage(List<StreamInfoItem> items, org.schabi.newpipe.extractor.Page nextPage) {
        return new InfoItemsPage<>(items, nextPage, Collections.emptyList());
    }

    private String getStreamId(String url) throws ParsingException {
        return streamLinkHandler.getId(url);
    }
}
