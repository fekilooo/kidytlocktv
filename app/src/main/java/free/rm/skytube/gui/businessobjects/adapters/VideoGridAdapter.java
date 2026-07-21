/*
 * SkyTube
 * Copyright (C) 2018  Ramon Mifsud
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

package free.rm.skytube.gui.businessobjects.adapters;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import free.rm.skytube.R;
import free.rm.skytube.businessobjects.DiagnosticFileLogger;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.TLSSocketFactory;
import free.rm.skytube.businessobjects.VideoCategory;
import free.rm.skytube.businessobjects.YouTube.GetYouTubeVideos;
import free.rm.skytube.businessobjects.YouTube.TrendingExposureTracker;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;
import free.rm.skytube.businessobjects.YouTube.YouTubeTasks;
import free.rm.skytube.businessobjects.YouTube.newpipe.ContentId;
import free.rm.skytube.businessobjects.db.PlaybackStatusDb;
import free.rm.skytube.businessobjects.interfaces.CardListener;
import free.rm.skytube.businessobjects.interfaces.VideoPlayStatusUpdateListener;
import free.rm.skytube.databinding.VideoCellBinding;
import free.rm.skytube.gui.businessobjects.MainActivityListener;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * An adapter that will display videos in a {@link android.widget.GridView}.
 */
public class VideoGridAdapter extends RecyclerViewAdapterEx<CardData, GridViewHolder> implements VideoPlayStatusUpdateListener, CardListener {
	private static final String TAG = VideoGridAdapter.class.getSimpleName();
	private static final int LOAD_MORE_THRESHOLD = 4;

	public interface Callback {
		void onVideoGridUpdated(boolean hasItems);
	}

	/**
	 * Class used to get YouTube videos from the web.
	 */
	private GetYouTubeVideos getYouTubeVideos;
	/**
	 * Set to true to display channel information (e.g. channel name) and allows user to open and
	 * browse the channel;  false to hide such information.
	 */
	private boolean showChannelInfo = true;
	/**
	 * Current video category
	 */
	private VideoCategory currentVideoCategory = null;

	// This allows the grid items to pass messages back to MainActivity
	protected MainActivityListener listener;

	/**
	 * If this is set, new videos being displayed will be saved to the database, if subscribed.
	 * RM:  This is only set and used by ChannelBrowserFragment
	 */
	private YouTubeChannel youTubeChannel;

	/**
	 * Holds a progress bar
	 */
	private SwipeRefreshLayout swipeRefreshLayout = null;

	/** Set to true if the video adapter is initialized. */
	private boolean initialized = false;
    private boolean refreshHappens = false;

	private VideoGridAdapter.Callback videoGridUpdated;

	private final CompositeDisposable compositeDisposable = new CompositeDisposable();
	private final Set<String> recordedTrendingExposureIds = new HashSet<>();

	/**
	 * Constructor.
	 */
	public VideoGridAdapter() {
		super();
		this.getYouTubeVideos = null;
		PlaybackStatusDb.getPlaybackStatusDb().addListener(this);
	}

	public void onDestroy() {
		compositeDisposable.clear();
		PlaybackStatusDb.getPlaybackStatusDb().removeListener(this);
		this.listener = null;
		this.swipeRefreshLayout = null;
		this.videoGridUpdated = null;
	}


	public void setListener(MainActivityListener listener) {
		this.listener = listener;
	}

    /**
     * Will be called once the DB is updated - by a video insertion.
     */
    @Override
    public void onCardAdded(final CardData card) {
        prepend(card);
    }

    /**
     * Will be called once the DB is updated - by a video deletion.
     */
    @Override
    public void onCardDeleted(final ContentId contentId) {
        remove(card -> contentId.getId().equals(card.getId()));
    }

	/**
	 * Set the video category.  Upon set, the adapter will download the videos of the specified
	 * category asynchronously.
	 *
	 * @see #setVideoCategory(VideoCategory, String)
	 */
	public void setVideoCategory(VideoCategory videoCategory) {
		setVideoCategory(videoCategory, null);
	}


	/**
	 * Set the video category.  Upon set, the adapter will download the videos of the specified
	 * category asynchronously.
	 *
	 * @param videoCategory The video category you want to change to.
	 * @param searchQuery   The search query.  Should only be set if videoCategory is equal to
	 *                      SEARCH_QUERY.
	 */
	public void setVideoCategory(VideoCategory videoCategory, String searchQuery) {
		// do not change the video category if its the same!
		if (videoCategory == currentVideoCategory)
			return;

		try {
			Logger.d(this, "setVideoCategory:" + videoCategory.toString());

			// do not show channel name if the video category == CHANNEL_VIDEOS or PLAYLIST_VIDEOS
			this.showChannelInfo = !(videoCategory == VideoCategory.CHANNEL_VIDEOS  ||  videoCategory == VideoCategory.PLAYLIST_VIDEOS);

			// create a new instance of GetYouTubeVideos
			this.getYouTubeVideos = videoCategory.createGetYouTubeVideos();
			this.getYouTubeVideos.init();

			// set the query
			if (searchQuery != null) {
				getYouTubeVideos.setQuery(searchQuery);
			}
			Logger.i(this, "Video category initialized: category=%s query=%s fetcher=%s",
					videoCategory, searchQuery, getYouTubeVideos.getClass().getSimpleName());

			// set current video category
			this.currentVideoCategory = videoCategory;

		} catch (IOException e) {
			Logger.e(this, "Could not init " + videoCategory, e);
			Toast.makeText(getContext(),
							String.format(getContext().getString(R.string.could_not_get_videos), videoCategory.toString()),
							Toast.LENGTH_LONG).show();
			this.currentVideoCategory = null;
		}
	}


	@Override
	public GridViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
		setContext(parent.getContext());
		final VideoCellBinding binding = VideoCellBinding.inflate(LayoutInflater.from(getContext()),
				parent, false);
		return new GridViewHolder(binding, listener, showChannelInfo);
	}

	/**
	 * Initialize the video list, if it's not yet initialized.
	 */
	public void initializeList() {
		if (!initialized && getYouTubeVideos != null) {
			initialized = true;
			refresh(true);
		}
	}

	/**
	 * Refresh the video grid, by running the task to get the videos again.
	 */
	public void refresh() {
		refresh(false);
	}


	/**
	 * Refresh the video grid, by running the task to get the videos again.
	 *
	 * @param clearVideosList If set to true, it will clear out any previously loaded videos (found
	 *                        in this adapter).
	 */
	public final synchronized void refresh(boolean clearVideosList) {
		if (getYouTubeVideos != null && !refreshHappens) {
			refreshHappens = true;
			if (clearVideosList) {
				getYouTubeVideos.reset();
			}
			// now, we consider this as initialized - sometimes 'refresh' can be called before the initializeList is called.
			initialized = true;
			Logger.i(this, "Refreshing video grid: category=%s clear=%s currentItems=%s fetcher=%s",
					currentVideoCategory, clearVideosList, getItemCount(), getYouTubeVideos.getClass().getSimpleName());
			appendUiDiagnostic("Adapter refresh requested: clear=" + clearVideosList
					+ ", currentItems=" + getItemCount()
					+ ", fetcher=" + getYouTubeVideos.getClass().getSimpleName());

			compositeDisposable.add(YouTubeTasks.getYouTubeVideos(getYouTubeVideos, this,
					swipeRefreshLayout, clearVideosList).subscribe());
		}
	}

	@Override
	public void onBindViewHolder(@NonNull GridViewHolder viewHolder, int position) {
		CardData card = get(position);
		viewHolder.updateInfo(card, getContext(), listener);
		if (currentVideoCategory == VideoCategory.MOST_POPULAR
				&& card instanceof YouTubeVideo
				&& recordedTrendingExposureIds.add(card.getId())) {
			TrendingExposureTracker.getInstance().markShown(card.getId());
		}

		// Preload before the last row so TV D-pad navigation does not run into an empty focus edge.
		if (position >= Math.max(0, getItemCount() - LOAD_MORE_THRESHOLD)) {
			Logger.d(this, "BOTTOM REACHED!!!");
			if (getYouTubeVideos != null && !refreshHappens
					&& !getYouTubeVideos.noMoreVideoPages()) {
				refreshHappens = true;
				compositeDisposable.add(YouTubeTasks.getYouTubeVideos(getYouTubeVideos, this,
						swipeRefreshLayout, false).subscribe());
			}
		}
	}

	@Override
	public void onBindViewHolder(@NonNull GridViewHolder viewHolder, int position,
								 @NonNull List<Object> payloads) {
		if (payloads.contains(PlaybackStatusPayload.INSTANCE)) {
			viewHolder.updatePlaybackStatus();
			return;
		}
		onBindViewHolder(viewHolder, position);
	}

	@Override
	public void clearList() {
		recordedTrendingExposureIds.clear();
		super.clearList();
	}

    public synchronized void notifyVideoGridUpdated() {
        refreshHappens = false;
        Logger.i(this, "Video grid updated: category=%s finalItems=%s", currentVideoCategory, getItemCount());
        appendUiDiagnostic("notifyVideoGridUpdated: finalItems=" + getItemCount()
                + ", hasCallback=" + (videoGridUpdated != null));
        if (videoGridUpdated != null) {
            int itemCount = getItemCount();
            videoGridUpdated.onVideoGridUpdated(itemCount > 0);
        }
    }

	@Override
	public void onViewRecycled(@NonNull GridViewHolder holder) {
		holder.clearBackgroundTasks();
	}

	public void setSwipeRefreshLayout(SwipeRefreshLayout swipeRefreshLayout) {
		this.swipeRefreshLayout = swipeRefreshLayout;
	}

	public void setYouTubeChannel(YouTubeChannel youTubeChannel) {
		this.youTubeChannel = youTubeChannel;
	}

	public void setVideoGridUpdated(Callback videoGridUpdated) {
		this.videoGridUpdated = videoGridUpdated;
	}

	public YouTubeChannel getYouTubeChannel() {
		return youTubeChannel;
	}

	public VideoCategory getCurrentVideoCategory() {
		return currentVideoCategory;
	}

    @Override
    public void onVideoStatusUpdated(CardData video) {
        if (video != null) {
			for (int i = 0; i < list.size(); i++) {
				if (java.util.Objects.equals(list.get(i).getId(), video.getId())) {
					notifyItemChanged(i, PlaybackStatusPayload.INSTANCE);
					break;
				}
			}
        } else {
            notifyDataSetChanged();
        }
    }

	private enum PlaybackStatusPayload {
		INSTANCE
	}

    private void appendUiDiagnostic(@NonNull String message) {
        if (currentVideoCategory == null || !currentVideoCategory.isVideoFilteringEnabled()
                && currentVideoCategory != VideoCategory.MOST_POPULAR
                && currentVideoCategory != VideoCategory.CHANNEL_VIDEOS
                && currentVideoCategory != VideoCategory.SEARCH_QUERY
                && currentVideoCategory != VideoCategory.PLAYLIST_VIDEOS
                && currentVideoCategory != VideoCategory.MIXED_PLAYLIST_VIDEOS) {
            return;
        }

        final StringBuilder builder = new StringBuilder();
        builder.append(new java.util.Date()).append('\n');
        builder.append("scope=VideoGridAdapter").append('\n');
        builder.append("subject=").append(currentVideoCategory).append('\n');
        builder.append("category=").append(currentVideoCategory).append('\n');
        builder.append("stage=").append(message).append('\n');
        builder.append("runtime=").append(TLSSocketFactory.getRuntimeSummary()).append('\n');
        builder.append("---\n");
        DiagnosticFileLogger.append(DiagnosticFileLogger.DEBUG_LOG_FILE_NAME, builder.toString());
    }
}

