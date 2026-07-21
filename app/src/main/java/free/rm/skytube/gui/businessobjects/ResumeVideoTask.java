/*
 * SkyTube
 * Copyright (C) 2018  Zsombor Gegesy
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
package free.rm.skytube.gui.businessobjects;

import android.content.Context;

import androidx.annotation.NonNull;

import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.businessobjects.db.PlaybackStatusDb;

/**
 * Loads a video from its saved playback position when one exists.
 */
public class ResumeVideoTask {

    public interface Callback {
        void loadVideo(int position);
    }

    private final @NonNull Callback callback;
    private final @NonNull String videoId;

    public ResumeVideoTask(@NonNull Context context, @NonNull String videoId, @NonNull Callback callback) {
        this.videoId = videoId;
        this.callback = callback;
    }

    /**
     * Resume the video automatically if it has been played in the past.
     *
     */
    public void ask() {
        if (SkyTubeApp.getSettings().isPlaybackStatusEnabled()) {
            final PlaybackStatusDb.VideoWatchedStatus watchStatus = PlaybackStatusDb.getPlaybackStatusDb().getVideoWatchedStatus(videoId);
            if (watchStatus.getPosition() > 0) {
                callback.loadVideo((int) watchStatus.getPosition());
            } else {
                callback.loadVideo(0);
            }
        } else {
            callback.loadVideo(0);
        }

    }
}
