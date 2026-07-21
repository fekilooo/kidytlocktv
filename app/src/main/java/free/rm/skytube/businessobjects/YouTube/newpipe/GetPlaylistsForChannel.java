/*
 * SkyTube
 * Copyright (C) 2023  Zsombor Gegesy
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

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.StreamingService;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubePlaylist;
import free.rm.skytube.businessobjects.YouTube.YouTubeTasks;

public class GetPlaylistsForChannel implements YouTubeTasks.ChannelPlaylistFetcher {
    static class Paging {
        private final YouTubeChannel channel;
        @Nullable private final ChannelTabExtractor extractor;
        private Page nextPage;
        private boolean firstPage = false;
        Paging(YouTubeChannel channel, ChannelTabExtractor extractor) {
            this.channel = channel;
            this.extractor = extractor;
        }

        private synchronized List<InfoItem> getNextPage() throws ExtractionException, IOException {
            if (extractor == null) {
                return Collections.emptyList();
            }
            extractor.fetchPage();
            if (firstPage) {
                if (Page.isValid(nextPage)) {
                    ListExtractor.InfoItemsPage<InfoItem> res = extractor.getPage(nextPage);
                    nextPage = res.getNextPage();
                    return res.getItems();
                } else {
                    return Collections.emptyList();
                }
            } else {
                ListExtractor.InfoItemsPage<InfoItem> res = extractor.getInitialPage();
                firstPage = true;
                nextPage = res.getNextPage();
                return res.getItems();
            }
        }

        private List<YouTubePlaylist> getNextPlaylists() throws ExtractionException, IOException {
            List<InfoItem> infoItems = getNextPage();
            List<YouTubePlaylist> playlists = new ArrayList<>(infoItems.size());
            for (InfoItem item : infoItems) {
                YouTubePlaylist playlist = toPlaylist(item);
                if (playlist != null) {
                    playlists.add(playlist);
                }
            }
            return playlists;
        }

        @Nullable
        private YouTubePlaylist toPlaylist(InfoItem item) {
            if (item == null) {
                return null;
            }
            final String url = item.getUrl();
            final String playlistId = getPlaylistId(url);
            if (playlistId == null) {
                Logger.i(this, "[ChannelPlaylists] Skipping non-playlist item type=%s url=%s",
                        item.getClass().getSimpleName(), url);
                return null;
            }
            final long streamCount = item instanceof PlaylistInfoItem
                    ? ((PlaylistInfoItem) item).getStreamCount()
                    : 0L;
            Logger.i(this, "[ChannelPlaylists] Mapped item type=%s playlistId=%s title=%s url=%s",
                    item.getClass().getSimpleName(), playlistId, item.getName(), url);
            return new YouTubePlaylist(playlistId, url, item.getName(), "", null, streamCount,
                    NewPipeUtils.getThumbnailUrl(item), channel);
        }

        @Nullable
        private String getPlaylistId(@Nullable String url) {
            String fastId = extractPlaylistIdFast(url);
            if (fastId != null) {
                return fastId;
            }
            if (url == null || url.isEmpty()) {
                return null;
            }
            try {
                StreamingService streamingService = NewPipeService.get().getStreamingService();
                ListLinkHandlerFactory factory = streamingService.getPlaylistLHFactory();
                return factory.getId(url);
            } catch (ParsingException e) {
                return null;
            }
        }

        @Nullable
        private String extractPlaylistIdFast(@Nullable String url) {
            if (url == null || url.isEmpty()) {
                return null;
            }
            try {
                URI uri = URI.create(url);
                String query = uri.getQuery();
                if (query != null) {
                    for (String part : query.split("&")) {
                        if (part.startsWith("list=") && part.length() > 5) {
                            return part.substring(5);
                        }
                    }
                }
                if (url.startsWith("VL") && url.length() > 2) {
                    return url.substring(2);
                }
            } catch (RuntimeException e) {
                Logger.e(this, "Unable to fast parse playlist url " + url + ": " + e.getMessage(), e);
            }
            return null;
        }
    }

    private final YouTubeChannel channel;

    private Paging paging;
    public GetPlaylistsForChannel(YouTubeChannel channel) {
        this.channel = channel;
    }
    @Override
    public void reset() {
        paging = null;
    }

    @Override
    public YouTubeChannel getChannel() {
        return channel;
    }

    @Override
    public List<YouTubePlaylist> getNextPlaylists() throws IOException, ExtractionException, NewPipeException {
        return getPaging().getNextPlaylists();
    }

    private synchronized Paging getPaging() throws NewPipeException, ParsingException {
        SkyTubeApp.nonUiThread();
        if (paging == null) {
            NewPipeService.ChannelWithExtractor cwe = NewPipeService.get().getChannelWithExtractor(channel.getChannelId());
            paging = new Paging(cwe.channel, cwe.findPlaylistTab());
        }
        return paging;
    }

}
