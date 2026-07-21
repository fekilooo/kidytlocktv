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
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import free.rm.skytube.businessobjects.DiagnosticFileLogger;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.TLSSocketFactory;

/**
 * Class to parse a channel screen for searching for videos and returning as pages with {@link #getNextPage()} }.
 *
 * @author zsombor
 */
public abstract class Pager<I extends InfoItem, O> implements PagerBackend<O> {
    private final StreamingService streamingService;
    private final ListExtractor<? extends I> channelExtractor;
    private Page nextPage;
    private boolean hasNextPage = true;
    private Exception lastException;
    protected final LinkHandlerFactory streamLinkHandler;
    protected final ListLinkHandlerFactory playlistLinkHandler;
    protected final LinkHandlerFactory channelLinkHandler;

    Pager(StreamingService streamingService, ListExtractor<? extends I> channelExtractor) {
        this.streamingService = streamingService;
        this.channelExtractor = channelExtractor;
        this.streamLinkHandler = streamingService.getStreamLHFactory();
        this.playlistLinkHandler = streamingService.getPlaylistLHFactory();
        this.channelLinkHandler = streamingService.getChannelLHFactory();
    }

    StreamingService getStreamingService() {
        return streamingService;
    }

    /**
     * @return true, if there could be more videos available in the next page.
     */
    @Override
    public boolean isHasNextPage() {
        return hasNextPage;
    }

    @Override
    public Exception getLastException() {
        return lastException;
    }

    /**
     * @return the next page of videos.
     * @throws ParsingException
     * @throws IOException
     * @throws ExtractionException
     */
    public List<O> getNextPage() throws NewPipeException {
        if (!hasNextPage || channelExtractor == null) {
            appendPagerDiagnostic("getNextPage skipped: hasNextPage=" + hasNextPage
                    + ", extractorNull=" + (channelExtractor == null));
            return Collections.emptyList();
        }
        try {
            if (nextPage == null) {
                appendPagerDiagnostic("getNextPage start: fetchPage initial");
                channelExtractor.fetchPage();
                appendPagerDiagnostic("getNextPage fetchPage finished: initial");
                final InfoItemsPage<? extends I> initialPage = channelExtractor.getInitialPage();
                appendPagerDiagnostic("getInitialPage finished: items="
                        + (initialPage != null && initialPage.getItems() != null ? initialPage.getItems().size() : -1)
                        + ", hasNextPage=" + (initialPage != null && initialPage.hasNextPage()));
                return process(initialPage);
            } else {
                appendPagerDiagnostic("getNextPage start: continuation url=" + nextPage.getUrl());
                final InfoItemsPage<? extends I> continuationPage = channelExtractor.getPage(nextPage);
                appendPagerDiagnostic("getPage finished: items="
                        + (continuationPage != null && continuationPage.getItems() != null ? continuationPage.getItems().size() : -1)
                        + ", hasNextPage=" + (continuationPage != null && continuationPage.hasNextPage()));
                return process(continuationPage);
            }
        } catch (IOException| ExtractionException| RuntimeException e) {
            appendPagerDiagnostic("getNextPage failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new NewPipeException("Error:" + e.getMessage() +
                    (nextPage != null ? " (nextPage=" + nextPage.getUrl() + ",ids=" + nextPage.getIds() + ")" : ""), e);
        }
    }

    @Override
    public List<O> getPageAndExtract(Page page) throws NewPipeException {
        try {
            return extract(channelExtractor.getPage(page));
        } catch (IOException| ExtractionException| RuntimeException e) {
            throw new NewPipeException("Error:" + e.getMessage() +
                    (page != null ? " (page=" + page.getUrl() + ",ids=" + page.getIds() + ")" : ""), e);
        }
    }

    @Override
    public List<O> getSafeNextPage() {
        try {
            return getNextPage();
        } catch (NewPipeException e) {
            lastException = e;
            Logger.e(this.getClass().getSimpleName(), "Error: " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Process the page, and update the pager with the content, if the Pager needs to keep state
     */
    protected List<O> process(ListExtractor.InfoItemsPage<? extends I> page) throws NewPipeException, ExtractionException {
        appendPagerDiagnostic("process start: items="
                + (page != null && page.getItems() != null ? page.getItems().size() : -1));
        nextPage = page.getNextPage();
        hasNextPage = page.hasNextPage();
        appendPagerDiagnostic("process page info: hasNextPage=" + hasNextPage
                + ", nextPageNull=" + (nextPage == null));
        final List<O> result = extract(page);
        appendPagerDiagnostic("process finished: extracted="
                + (result != null ? result.size() : -1));
        return result;
    }

    /**
     * Information about the next page
     */
    @Override
    public Page getNextPageInfo() {
        return nextPage;
    }

    protected abstract List<O> extract(InfoItemsPage<? extends I> page) throws NewPipeException, ExtractionException ;

    protected void appendPagerDiagnostic(String message) {
        final StringBuilder builder = new StringBuilder();
        builder.append(new java.util.Date()).append('\n');
        builder.append("scope=Pager").append('\n');
        builder.append("subject=").append(getClass().getSimpleName()).append('\n');
        builder.append("category=").append(channelExtractor != null ? channelExtractor.getClass().getSimpleName() : "null").append('\n');
        builder.append("stage=").append(message).append('\n');
        builder.append("runtime=").append(TLSSocketFactory.getRuntimeSummary()).append('\n');
        builder.append("---\n");
        DiagnosticFileLogger.append(DiagnosticFileLogger.DEBUG_LOG_FILE_NAME, builder.toString());
    }

}
