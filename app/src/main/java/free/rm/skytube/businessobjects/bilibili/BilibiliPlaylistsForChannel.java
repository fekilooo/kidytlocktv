package free.rm.skytube.businessobjects.bilibili;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubePlaylist;
import free.rm.skytube.businessobjects.YouTube.YouTubeTasks;
import free.rm.skytube.businessobjects.YouTube.newpipe.NewPipeException;

public final class BilibiliPlaylistsForChannel
        implements YouTubeTasks.ChannelPlaylistFetcher {
    private final YouTubeChannel channel;
    private int pageNumber = 1;
    private boolean hasNext = true;

    public BilibiliPlaylistsForChannel(YouTubeChannel channel) {
        this.channel = channel;
    }

    @Override
    public void reset() {
        pageNumber = 1;
        hasNext = true;
    }

    @Override
    public YouTubeChannel getChannel() {
        return channel;
    }

    @Override
    public List<YouTubePlaylist> getNextPlaylists()
            throws IOException, ExtractionException, NewPipeException {
        if (!hasNext) {
            return Collections.emptyList();
        }
        BilibiliService.PlaylistPage page = BilibiliService.get()
                .getChannelPlaylists(channel, pageNumber++);
        hasNext = page.hasNext;
        return page.playlists;
    }
}
