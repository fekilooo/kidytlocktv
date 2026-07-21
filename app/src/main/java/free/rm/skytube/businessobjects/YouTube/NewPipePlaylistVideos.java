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

package free.rm.skytube.businessobjects.YouTube;

import java.util.Objects;
import java.util.Collections;
import java.util.List;

import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.newpipe.NewPipeException;
import free.rm.skytube.businessobjects.YouTube.newpipe.NewPipeService;
import free.rm.skytube.businessobjects.YouTube.newpipe.VideoPager;
import free.rm.skytube.businessobjects.bilibili.BilibiliService;

public class NewPipePlaylistVideos extends NewPipeVideos {

    private String playlistId;
    private int bilibiliPage = 1;

    // Important, this is called from the channel tab
    @Override
    public void setQuery(String query) {
        this.playlistId = Objects.requireNonNull(query, "query missing");
        Logger.i(this, "Playlist query set to id=%s", this.playlistId);
    }

    @Override
    protected VideoPager createNewPager() throws NewPipeException {
        Logger.i(this, "Creating playlist pager for id=%s", playlistId);
        return NewPipeService.get().getPlaylistPager(Objects.requireNonNull(playlistId, "playlistId missing"));
    }

    @Override
    public List<CardData> getNextVideos() {
        if (!BilibiliService.isPlaylistQuery(playlistId)) {
            return super.getNextVideos();
        }
        try {
            BilibiliService.VideoPage page = BilibiliService.get()
                    .getPlaylistVideos(playlistId, bilibiliPage);
            bilibiliPage++;
            noMoreVideoPages = !page.hasNext();
            return page.videos;
        } catch (Exception e) {
            Logger.e(this, "Unable to load Bilibili playlist: " + e.getMessage(), e);
            setLastException(e);
            noMoreVideoPages = true;
            return Collections.emptyList();
        }
    }

    @Override
    public void reset() {
        super.reset();
        bilibiliPage = 1;
    }
}
