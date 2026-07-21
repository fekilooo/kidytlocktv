package free.rm.skytube.businessobjects.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.JsonSerializer;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;
import free.rm.skytube.businessobjects.interfaces.VideoPlayStatusUpdateListener;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * A database (DB) that stores video playback history
 */
public class PlaybackStatusDb extends SQLiteOpenHelperEx {
	private static volatile PlaybackStatusDb playbackStatusDb = null;
	private static HashMap<String, VideoWatchedStatus> playbackHistoryMap = null;

	private static final int DATABASE_VERSION = 2;
	private static final long PLAYBACK_HISTORY_RETENTION_MS = 72L * 60L * 60L * 1000L;
	private int updateCounter = 0;
	private static final String DATABASE_NAME = "playbackhistory.db";
	private final JsonSerializer jsonSerializer = new JsonSerializer();

	private final Set<VideoPlayStatusUpdateListener> listeners = new HashSet<>();

	public static synchronized PlaybackStatusDb getPlaybackStatusDb() {
		if (playbackStatusDb == null) {
			playbackStatusDb = new PlaybackStatusDb(SkyTubeApp.getContext());
		}

		return playbackStatusDb;
	}

	private PlaybackStatusDb(Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	public void deleteAllPlaybackHistory() {
		getWritableDatabase().delete(PlaybackStatusTable.TABLE_NAME, null, null);
		playbackHistoryMap = null;
		updateCounter++;
		onUpdated(null);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL(PlaybackStatusTable.getCreateStatement());
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion < 2) {
			db.execSQL(PlaybackStatusTable.getAddVideoColumn());
			db.execSQL(PlaybackStatusTable.getAddLastPlayedColumn());
		}
	}

	/** Returns playback entries from the last 72 hours, newest first. */
	public List<PlaybackHistoryEntry> getRecentPlaybackHistory() {
		SkyTubeApp.nonUiThread();
		final long cutoff = System.currentTimeMillis() - PLAYBACK_HISTORY_RETENTION_MS;
		getWritableDatabase().delete(
				PlaybackStatusTable.TABLE_NAME,
				PlaybackStatusTable.COL_LAST_PLAYED_TS + " IS NOT NULL AND "
						+ PlaybackStatusTable.COL_LAST_PLAYED_TS + " < ?",
				new String[]{Long.toString(cutoff)});

		final List<PlaybackHistoryEntry> result = new ArrayList<>();
		try (Cursor cursor = getReadableDatabase().query(
				PlaybackStatusTable.TABLE_NAME,
				new String[]{PlaybackStatusTable.COL_YOUTUBE_VIDEO,
						PlaybackStatusTable.COL_LAST_PLAYED_TS},
				PlaybackStatusTable.COL_YOUTUBE_VIDEO + " IS NOT NULL AND "
						+ PlaybackStatusTable.COL_LAST_PLAYED_TS + " >= ?",
				new String[]{Long.toString(cutoff)}, null, null,
				PlaybackStatusTable.COL_LAST_PLAYED_TS + " DESC")) {
			final int videoIndex = cursor.getColumnIndexOrThrow(
					PlaybackStatusTable.COL_YOUTUBE_VIDEO);
			final int timestampIndex = cursor.getColumnIndexOrThrow(
					PlaybackStatusTable.COL_LAST_PLAYED_TS);
			while (cursor.moveToNext()) {
				final YouTubeVideo video = jsonSerializer.fromPersistedVideoJson(
						cursor.getBlob(videoIndex));
				if (video != null) {
					result.add(new PlaybackHistoryEntry(video, cursor.getLong(timestampIndex)));
				}
			}
		}
		return result;
	}

	/**
	 * Get the watched status of the passed {@link YouTubeVideo}. Instead of always querying the database, a HashMap
	 * is constructed that stores the watch status of all videos (that have a status). Subsequent calls to this method
	 * will return the watch status for the passed video from this HashMap (which also gets updated by calls to setWatchedStatus().
	 *
	 * @param videoId {@link YouTubeVideo}
	 * @return {@link VideoWatchedStatus} of the passed video, which contains the position (in ms) and whether or not the video
	 * 					has been (completely) watched.
	 */
	public synchronized VideoWatchedStatus getVideoWatchedStatus(@NonNull String videoId) {
		if(playbackHistoryMap == null) {
            try (Cursor cursor = getReadableDatabase().query(
                    PlaybackStatusTable.TABLE_NAME,
                    new String[]{PlaybackStatusTable.COL_YOUTUBE_VIDEO_ID, PlaybackStatusTable.COL_YOUTUBE_VIDEO_POSITION, PlaybackStatusTable.COL_YOUTUBE_VIDEO_WATCHED},
                    null,
                    null, null, null, null)) {
                playbackHistoryMap = new HashMap<>();
                final int videoIdIdx = cursor.getColumnIndexOrThrow(PlaybackStatusTable.COL_YOUTUBE_VIDEO_ID);
                final int positionIdx = cursor.getColumnIndexOrThrow(PlaybackStatusTable.COL_YOUTUBE_VIDEO_POSITION);
                final int finishedIdx = cursor.getColumnIndexOrThrow(PlaybackStatusTable.COL_YOUTUBE_VIDEO_WATCHED);
                while (cursor.moveToNext()) {
                    String video_id = cursor.getString(videoIdIdx);
                    int position = cursor.getInt(positionIdx);
                    int finished = cursor.getInt(finishedIdx);
                    VideoWatchedStatus status = new VideoWatchedStatus(position, finished == 1);
                    playbackHistoryMap.put(video_id, status);
                }
            }
		}
		if(playbackHistoryMap.get(videoId) == null) {
			// Requested video has no entry in the database, so create one in the Map. No need to create it in the Database yet - if needed,
			// that will happen when video position is set
			VideoWatchedStatus status = new VideoWatchedStatus();
			playbackHistoryMap.put(videoId, status);
		}
		return playbackHistoryMap.get(videoId);
	}

    public Maybe<VideoWatchedStatus> getVideoWatchedStatusAsync(@NonNull String videoId) {
        return Maybe.fromCallable(() -> getVideoWatchedStatus(videoId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

	/**
	 * Set the position (in ms) of the passed {@link YouTubeVideo}. If the position is less than 5 seconds,
	 * don't do anything. If the position is greater than or equal to 90% of the duration of the video, set
	 * the position to 0 and mark the video as watched.
	 *
	 * @param video {@link YouTubeVideo}
	 * @param position Number of milliseconds
	 * @return Disposable which contains the background task.
	 */
	public Disposable setVideoPositionInBackground(YouTubeVideo video, long position) {
		// Don't record the position if it's < 5 seconds
		if (SkyTubeApp.getSettings().isPlaybackStatusEnabled() && position >= 5000) {
			VideoWatchedStatus previousStatus = getVideoWatchedStatus(video.getId());
			boolean watched = previousStatus.isFullyWatched() || position >= 120_000;
			// Short videos still count as watched when at least 90% has played.
			if (video.getDurationInSeconds() > 0
					&& (float) position / (video.getDurationInSeconds() * 1000) >= 0.9) {
				watched = true;
			}
			if (watched) {
				position = 0;
			}
			final long positionValue = position;
			final boolean watchedValue = watched;
			return Single.fromCallable(() -> saveVideoWatchStatus(
					video, positionValue, watchedValue, true))
					.subscribeOn(Schedulers.io())
					.observeOn(AndroidSchedulers.mainThread())
					.subscribe((success) -> {
						Logger.i(this, "onUpdated " + success);

						if (success) {
							onUpdated(video);
						}
					});
		} else {
			return Disposable.empty();
		}

	}

	/**
	 * Set the watched status of the passed {@link YouTubeVideo}. Regardless of watched status, set the
	 * position to 0.
	 *
	 * @param video {@link YouTubeVideo}
	 * @param watched boolean on whether or not the passed video has been watched
	 * @return boolean on whether the database was updated successfully.
	 */
	public Maybe<Boolean> setVideoWatchedStatusInBackground(YouTubeVideo video, boolean watched) {
		if (SkyTubeApp.getSettings().isPlaybackStatusEnabled()) {
			return Maybe.fromCallable(() -> saveVideoWatchStatus(video, 0, watched, true))
					.subscribeOn(Schedulers.io())
					.observeOn(AndroidSchedulers.mainThread())
					.doOnSuccess((success) -> {
						Logger.i(this, "onUpdated " + success);

                        if (success) {
                            onUpdated(video);
                        }
					});
		} else {
			return Maybe.empty();
		}
	}

	private boolean saveVideoWatchStatus(YouTubeVideo video, long position, boolean watched,
			boolean recordPlayback) {
		final String videoId = video.getId();
		ContentValues values = new ContentValues();
		values.put(PlaybackStatusTable.COL_YOUTUBE_VIDEO_ID, videoId);
		if (recordPlayback) {
			values.put(PlaybackStatusTable.COL_YOUTUBE_VIDEO,
					jsonSerializer.toPersistedVideoJson(video).getBytes());
			values.put(PlaybackStatusTable.COL_LAST_PLAYED_TS, System.currentTimeMillis());
		}
		values.put(PlaybackStatusTable.COL_YOUTUBE_VIDEO_POSITION, (int)position);
		values.put(PlaybackStatusTable.COL_YOUTUBE_VIDEO_WATCHED, watched ? 1 : 0);

		boolean addSuccessful = getWritableDatabase().insertWithOnConflict(PlaybackStatusTable.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1;
		if(addSuccessful) {
			updateCounter++;
		}

		if (playbackHistoryMap == null) {
			playbackHistoryMap = new HashMap<>();
		}
		VideoWatchedStatus status = playbackHistoryMap.get(videoId);
		if (status == null) {
			status = new VideoWatchedStatus();
			playbackHistoryMap.put(videoId, status);
		}
		status.position = position;
		status.watched = watched;


		return addSuccessful;
	}

	/** Records that playback actually started, independently from the 5-second resume threshold. */
	public Disposable recordPlaybackInBackground(@NonNull YouTubeVideo video) {
		if (!SkyTubeApp.getSettings().isPlaybackStatusEnabled()) {
			return Disposable.empty();
		}
		return Single.fromCallable(() -> recordPlayback(video))
				.subscribeOn(Schedulers.io())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(success -> {
					if (success) {
						onUpdated(video);
					}
				});
	}

	private boolean recordPlayback(@NonNull YouTubeVideo video) {
		final ContentValues initialValues = new ContentValues();
		initialValues.put(PlaybackStatusTable.COL_YOUTUBE_VIDEO_ID, video.getId());
		initialValues.put(PlaybackStatusTable.COL_YOUTUBE_VIDEO_POSITION, 0);
		initialValues.put(PlaybackStatusTable.COL_YOUTUBE_VIDEO_WATCHED, 0);
		getWritableDatabase().insertWithOnConflict(
				PlaybackStatusTable.TABLE_NAME, null, initialValues,
				SQLiteDatabase.CONFLICT_IGNORE);

		final ContentValues playbackValues = new ContentValues();
		playbackValues.put(PlaybackStatusTable.COL_YOUTUBE_VIDEO,
				jsonSerializer.toPersistedVideoJson(video).getBytes());
		playbackValues.put(PlaybackStatusTable.COL_LAST_PLAYED_TS,
				System.currentTimeMillis());
		final boolean updated = getWritableDatabase().update(
				PlaybackStatusTable.TABLE_NAME,
				playbackValues,
				PlaybackStatusTable.COL_YOUTUBE_VIDEO_ID + " = ?",
				new String[]{video.getId()}) > 0;
		if (updated) {
			updateCounter++;
		}
		return updated;
	}

	public static final class PlaybackHistoryEntry {
		private final YouTubeVideo video;
		private final long playedAt;

		PlaybackHistoryEntry(YouTubeVideo video, long playedAt) {
			this.video = video;
			this.playedAt = playedAt;
		}

		public YouTubeVideo getVideo() {
			return video;
		}

		public long getPlayedAt() {
			return playedAt;
		}
	}

    private void onUpdated(CardData cardData) {
        for(VideoPlayStatusUpdateListener listener : listeners) {
            listener.onVideoStatusUpdated(cardData);
        }
    }

	/**
	 * Class that contains the position and watched status of a video.
	 */
	public static class VideoWatchedStatus {
		public VideoWatchedStatus() {}
		public VideoWatchedStatus(long position, boolean watched) {
			this.position = position;
			this.watched = watched;
		}

		@Override
		public String toString() {
			return String.format("Position: %d\nWatched: %s\n", position, watched);
		}

		private long position = 0;
		private boolean watched = false;

		public boolean isFullyWatched() {
			return watched;
		}

		public boolean isWatched() {
			return position > 0 || watched;
		}

		public long getPosition() {
			return position;
		}
	}

	/**
	 * Return the number of updates happened to the playback status
	 * If it different than the VideoGrid has, it needs to be refreshed.
	 *
	 * @return int updateCounter
	 */
	public int getUpdateCounter() {
		return updateCounter;
	}

	public void addListener(VideoPlayStatusUpdateListener listener) {
		listeners.add(listener);
	}

	public void removeListener(VideoPlayStatusUpdateListener listener) {
		listeners.remove(listener);
	}
}
