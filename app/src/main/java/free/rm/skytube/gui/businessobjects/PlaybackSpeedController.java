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
package free.rm.skytube.gui.businessobjects;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;

import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;

import java.util.Objects;

import free.rm.skytube.app.Utils;
import free.rm.skytube.R;

public class PlaybackSpeedController implements View.OnClickListener {

    public interface AlternatePlaybackTarget {
        float getPlaybackSpeed();

        void setPlaybackSpeed(float speed);
    }

    private final static float[] PLAYBACK_SPEEDS = {0.6f, 1f, 2f, 3f};

    private final Context context;
    private final TextView playbackSpeedTextView;
    private final SimpleExoPlayer player;
    private AlternatePlaybackTarget alternatePlaybackTarget;
    private CharSequence[] playbackSpeedItems = new CharSequence[0];

    public PlaybackSpeedController(Context context, TextView playbackSpeedTextView, SimpleExoPlayer player) {
        this.context = Objects.requireNonNull(context, "context");
        this.player = Objects.requireNonNull(player, "SimpleExoPlayer");
        this.playbackSpeedTextView = Objects.requireNonNull(playbackSpeedTextView, "playbackSpeedTextView");
        this.playbackSpeedTextView.setOnClickListener(this);
    }

    public void updateMenu() {
        playbackSpeedItems = new CharSequence[PLAYBACK_SPEEDS.length];
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            playbackSpeedItems[i] = Utils.formatSpeed(PLAYBACK_SPEEDS[i]);
        }
        playbackSpeedTextView.setText(Utils.formatSpeed(getPlaybackSpeed()));
    }

    public float getPlaybackSpeed() {
        if (alternatePlaybackTarget != null) {
            return alternatePlaybackTarget.getPlaybackSpeed();
        }
        return getPlaybackParameters().speed;
    }

    public float getPlaybackPitch() {
        return getPlaybackParameters().pitch;
    }

    public boolean getPlaybackSkipSilence() {
        return getPlaybackParameters().skipSilence;
    }

    public void setPlaybackSpeed(float speed) {
        if (alternatePlaybackTarget != null) {
            alternatePlaybackTarget.setPlaybackSpeed(speed);
            return;
        }
        setPlaybackParameters(speed, getPlaybackPitch(), getPlaybackSkipSilence());
    }

    public void setAlternatePlaybackTarget(AlternatePlaybackTarget target) {
        alternatePlaybackTarget = target;
        updateMenu();
    }

    public PlaybackParameters getPlaybackParameters() {
        if (player == null) return PlaybackParameters.DEFAULT;
        final PlaybackParameters parameters = player.getPlaybackParameters();
        return parameters == null ? PlaybackParameters.DEFAULT : parameters;
    }

    public void setPlaybackParameters(float speed, float pitch, boolean skipSilence) {
        player.setPlaybackParameters(new PlaybackParameters(speed, pitch, skipSilence));
    }

    @Override
    public void onClick(View v) {
        int checkedItem = findSelectedSpeedIndex();
        SkyTubeMaterialDialog dialog = new SkyTubeMaterialDialog(context);
        dialog.title(R.string.pref_title_playback_speed);
        dialog.items(playbackSpeedItems);
        dialog.itemsCallbackSingleChoice(checkedItem, (materialDialog, itemView, which, text) -> {
            float speed = PLAYBACK_SPEEDS[which];
            setPlaybackSpeed(speed);
            playbackSpeedTextView.setText(Utils.formatSpeed(speed));
            return true;
        });
        dialog.onPositive((materialDialog, which) -> materialDialog.dismiss());
        dialog.onNegativeOrCancel(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dismissedDialog) {
                playbackSpeedTextView.post(playbackSpeedTextView::requestFocus);
            }
        });
        dialog.show();
    }

    private int findSelectedSpeedIndex() {
        float speed = getPlaybackSpeed();
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            if (Math.abs(PLAYBACK_SPEEDS[i] - speed) < 0.001f) {
                return i;
            }
        }
        return 2;
    }
}
