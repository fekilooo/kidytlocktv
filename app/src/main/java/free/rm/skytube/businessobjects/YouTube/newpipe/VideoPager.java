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

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubePlaylist;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;

public class VideoPager extends Pager<InfoItem, CardData> {
    private final Set<String> seenVideos = new HashSet<>();

    public VideoPager(StreamingService streamingService, ListExtractor<? extends InfoItem> channelExtractor) {
        super(streamingService, channelExtractor);
    }

    @Override
    protected List<CardData> extract(ListExtractor.InfoItemsPage<? extends InfoItem> page) throws NewPipeException {
        appendPagerDiagnostic("extract start: pageItems="
                + (page != null && page.getItems() != null ? page.getItems().size() : -1));
        List<CardData> result = new ArrayList<>(page.getItems().size());
        if (NewPipeService.DEBUG_LOG) {
            Logger.d(this, "extract from %s, items: %s", page, page.getItems().size());
        }
        int repeatCounter = 0;
        int unexpected = 0;
        int processed = 0;
        int skippedErrors = 0;

        for (InfoItem infoItem : page.getItems()) {
            processed++;
            try {
                if (infoItem instanceof StreamInfoItem) {
                    appendPagerDiagnostic("extract item #" + processed + " stream start");
                    final String url = infoItem.getUrl();
                    appendPagerDiagnostic("extract item #" + processed + " stream url ok");
                    String id = tryExtractVideoIdFast(url);
                    if (id != null) {
                        appendPagerDiagnostic("extract item #" + processed + " stream id fast=" + id);
                    } else {
                        id = getId(streamLinkHandler, url);
                    }
                    appendPagerDiagnostic("extract item #" + processed + " stream id=" + id);
                    StreamInfoItem streamInfo = (StreamInfoItem) infoItem;
                    if (seenVideos.contains(id)) {
                        repeatCounter++;
                        appendPagerDiagnostic("extract item #" + processed + " duplicate id=" + id);
                    } else {
                        seenVideos.add(id);
                        result.add(convertSafely(streamInfo, id, processed));
                    }
                } else if (infoItem instanceof PlaylistInfoItem) {
                    appendPagerDiagnostic("extract item #" + processed + " playlist start");
                    PlaylistInfoItem playlistInfoItem = (PlaylistInfoItem) infoItem;
                    String playlistId = tryExtractPlaylistIdFast(infoItem.getUrl());
                    if (playlistId == null) {
                        playlistId = getId(playlistLinkHandler, infoItem.getUrl());
                    } else {
                        appendPagerDiagnostic("extract item #" + processed
                                + " playlist id fast=" + playlistId);
                    }
                    result.add(convert(playlistInfoItem, playlistId));
                    appendPagerDiagnostic("extract item #" + processed + " playlist finished");
                } else if (infoItem instanceof ChannelInfoItem) {
                    appendPagerDiagnostic("extract item #" + processed + " channel start");
                    ChannelInfoItem channelInfoItem = (ChannelInfoItem) infoItem;
                    result.add(convert(channelInfoItem));
                    appendPagerDiagnostic("extract item #" + processed + " channel finished");
                } else {
                    Logger.i(this, "Unexpected item %s, type:%s", infoItem, infoItem.getClass());
                    unexpected ++;
                    appendPagerDiagnostic("extract item #" + processed + " unexpected type="
                            + infoItem.getClass().getSimpleName());
                }
            } catch (RuntimeException | NewPipeException e) {
                skippedErrors++;
                appendPagerDiagnostic("extract item #" + processed + " skipped due to "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                Logger.e(this, "Skipping problematic infoItem at index " + processed + ": " + e.getMessage(), e);
            }
        }
        if (NewPipeService.DEBUG_LOG) {
            Logger.d(this, "From the requested %s, number of duplicates: %s, wrong types: %s", page.getItems().size(), repeatCounter, unexpected);
        }
        appendPagerDiagnostic("extract finished: processed=" + processed
                + ", result=" + result.size()
                + ", duplicates=" + repeatCounter
                + ", unexpected=" + unexpected
                + ", skippedErrors=" + skippedErrors);
        return result;
    }

    private String getId(LinkHandlerFactory handler, String url) throws NewPipeException {
        try {
            return handler.getId(url);
        } catch (ParsingException e) {
            throw new NewPipeException("Unable to convert " + url + " with " + handler, e);
        }
    }

    private String tryExtractVideoIdFast(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        try {
            final URI uri = URI.create(url);
            final String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            final String path = uri.getPath() != null ? uri.getPath() : "";
            final String query = uri.getQuery();

            final String videoIdFromQuery = extractQueryParam(query, "v");
            if (videoIdFromQuery != null && !videoIdFromQuery.isEmpty()) {
                return videoIdFromQuery;
            }

            if (host.contains("youtu.be")) {
                final String shortId = trimSlashes(path);
                return shortId.isEmpty() ? null : shortId;
            }

            if (path.startsWith("/shorts/")) {
                final String shortId = firstPathSegment(path.substring("/shorts/".length()));
                return shortId.isEmpty() ? null : shortId;
            }

            if (path.startsWith("/live/")) {
                final String liveId = firstPathSegment(path.substring("/live/".length()));
                return liveId.isEmpty() ? null : liveId;
            }
        } catch (RuntimeException e) {
            Logger.d(this, "Fast video id extraction failed for %s: %s", url, e.getMessage());
        }

        return null;
    }

    private String tryExtractPlaylistIdFast(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            final URI uri = URI.create(url);
            final String playlistId = extractQueryParam(uri.getQuery(), "list");
            if (playlistId != null && !playlistId.isEmpty()) {
                return playlistId;
            }
            final String path = uri.getPath() != null ? uri.getPath() : "";
            if (path.startsWith("/playlist/")) {
                final String pathId = firstPathSegment(path.substring("/playlist/".length()));
                return pathId.isEmpty() ? null : pathId;
            }
        } catch (RuntimeException e) {
            Logger.d(this, "Fast playlist id extraction failed for %s: %s", url, e.getMessage());
        }
        return null;
    }

    private String extractQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        final String prefix = key + "=";
        for (String part : query.split("&")) {
            if (part.startsWith(prefix) && part.length() > prefix.length()) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }

    private String trimSlashes(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    private String firstPathSegment(String value) {
        final String trimmed = trimSlashes(value);
        final int slashIndex = trimmed.indexOf('/');
        return slashIndex >= 0 ? trimmed.substring(0, slashIndex) : trimmed;
    }

    public List<YouTubeVideo> getNextPageAsVideos() throws NewPipeException {
        List<CardData> cards = getNextPage();
        List<YouTubeVideo> result = new ArrayList<>(cards.size());
        for (CardData cardData: cards) {
            if (cardData instanceof YouTubeVideo) {
                result.add((YouTubeVideo) cardData);
            }
        }
        return result;
    }

    protected YouTubeVideo convert(StreamInfoItem item, String id) {
        NewPipeService.DateInfo date = new NewPipeService.DateInfo(item.getUploadDate());
        if (NewPipeService.DEBUG_LOG) {
            Logger.i(this, "item %s, title=%s at %s", id, item.getName(), date);
        }
        YouTubeChannel ch = new YouTubeChannel(item.getUploaderUrl(), item.getUploaderName());
        return new YouTubeVideo(id, item.getName(), null, item.getDuration(), ch,
                item.getViewCount(), date.instant, date.exact, NewPipeService.getThumbnailUrl(id));
    }

    private YouTubeVideo convertSafely(StreamInfoItem item, String id, int index) {
        appendPagerDiagnostic("extract item #" + index + " convert start id=" + id);
        final NewPipeService.DateInfo date;
        try {
            date = new NewPipeService.DateInfo(item.getUploadDate());
            appendPagerDiagnostic("extract item #" + index + " uploadDate ok id=" + id);
        } catch (RuntimeException e) {
            appendPagerDiagnostic("extract item #" + index + " uploadDate failed id=" + id
                    + " err=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }

        final String uploaderUrl = item.getUploaderUrl();
        final String uploaderName = item.getUploaderName();
        final String name = item.getName();
        final long duration = item.getDuration();
        final long viewCount = item.getViewCount();
        appendPagerDiagnostic("extract item #" + index + " metadata ok id=" + id);

        final YouTubeChannel ch = new YouTubeChannel(uploaderUrl, uploaderName);
        final YouTubeVideo video = new YouTubeVideo(id, name, null, duration, ch,
                viewCount, date.instant, date.exact, NewPipeService.getThumbnailUrl(id));
        appendPagerDiagnostic("extract item #" + index + " convert finished id=" + id);
        return video;
    }

    private CardData convert(PlaylistInfoItem playlistInfoItem, String id) {
        return new YouTubePlaylist(id, playlistInfoItem.getUrl(), playlistInfoItem.getName(), "", null, playlistInfoItem.getStreamCount(), NewPipeUtils.getThumbnailUrl(playlistInfoItem),
                null);
    }

    private CardData convert(ChannelInfoItem channelInfoItem) {
        String url = channelInfoItem.getUrl();
        String id = getId(url);
        return new YouTubeChannel(id, channelInfoItem.getName(), channelInfoItem.getDescription(), NewPipeUtils.getThumbnailUrl(channelInfoItem), null,
                channelInfoItem.getSubscriberCount(), false, -1, System.currentTimeMillis(), null, Collections.emptyList());
    }

    private String getId(String url) {
        try {
            return channelLinkHandler.getId(url);
        } catch (ParsingException p) {
            Logger.e(this, "Unable to parse channel url "+ url, p);
            return url;
        }
    }

}
