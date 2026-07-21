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

import java.util.Collections;
import java.util.List;

import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.newpipe.NewPipeException;
import free.rm.skytube.businessobjects.YouTube.newpipe.VideoPager;

/**
 * Base class to adapt the UI to the NewPipe based paging.
 *
 */
public abstract class NewPipeVideos extends GetYouTubeVideos {

    private VideoPager pager;

    protected abstract VideoPager createNewPager() throws NewPipeException;

    @Override
    public void init() {
    }

    @Override
    public List<CardData> getNextVideos() {
        if (pager == null) {
            try {
                appendPagerDiagnostic("createNewPager start");
                pager = createNewPager();
                appendPagerDiagnostic("createNewPager finished: pager="
                        + (pager != null ? pager.getClass().getSimpleName() : "null"));
            } catch (Exception e) {
                appendPagerDiagnostic("createNewPager failed: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
                Logger.e(this, "An error has occurred while getting videos:" + e.getMessage(), e);
                setLastException(e);
                return Collections.emptyList();
            }
        }
        try {
            appendPagerDiagnostic("pager.getNextPage start");
            final List<CardData> result = pager.getNextPage();
            appendPagerDiagnostic("pager.getNextPage finished: items="
                    + (result != null ? result.size() : -1)
                    + ", hasNextPage=" + pager.isHasNextPage());
            return result;
        } catch (Exception e) {
            appendPagerDiagnostic("pager.getNextPage failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            Logger.e(this, "An error has occurred while getting videos:" + e.getMessage(), e);
            setLastException(e);
            return Collections.emptyList();
        } finally {
            noMoreVideoPages = !pager.isHasNextPage();
        }
    }

    @Override
    public void reset() {
         noMoreVideoPages = false;
         pager = null;
    }

    private void appendPagerDiagnostic(String message) {
        final StringBuilder builder = new StringBuilder();
        builder.append(new java.util.Date()).append('\n');
        builder.append("scope=NewPipeVideos").append('\n');
        builder.append("subject=").append(getClass().getSimpleName()).append('\n');
        builder.append("category=").append(pager != null ? pager.getClass().getSimpleName() : "pager-null").append('\n');
        builder.append("stage=").append(message).append('\n');
        builder.append("runtime=").append(free.rm.skytube.businessobjects.TLSSocketFactory.getRuntimeSummary()).append('\n');
        builder.append("---\n");
        free.rm.skytube.businessobjects.DiagnosticFileLogger.append(
                free.rm.skytube.businessobjects.DiagnosticFileLogger.DEBUG_LOG_FILE_NAME, builder.toString());
    }

}
