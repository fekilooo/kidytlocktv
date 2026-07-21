package free.rm.skytube.businessobjects.YouTube;

import android.content.SharedPreferences;

import free.rm.skytube.app.SkyTubeApp;

/**
 * Stores lightweight local feedback for trending cards without requiring a Google account.
 */
public final class TrendingExposureTracker {
    private static final String PREFERENCES_NAME = "trending_exposure_feedback";
    private static final String SHOWN_PREFIX = "shown_";
    private static final String LAST_SHOWN_PREFIX = "last_shown_";
    private static final int SUPPRESS_AFTER_IGNORED_VISITS = 6;
    private static final long FEEDBACK_DECAY_MILLIS = 21L * 24L * 60L * 60L * 1000L;

    private static final TrendingExposureTracker INSTANCE = new TrendingExposureTracker();

    private final SharedPreferences preferences = SkyTubeApp.getContext()
            .getSharedPreferences(PREFERENCES_NAME, 0);

    private TrendingExposureTracker() {
    }

    public static TrendingExposureTracker getInstance() {
        return INSTANCE;
    }

    public synchronized void markShown(String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final int ignoredVisits = getActiveIgnoredVisits(videoId, now);
        preferences.edit()
                .putInt(SHOWN_PREFIX + videoId, ignoredVisits + 1)
                .putLong(LAST_SHOWN_PREFIX + videoId, now)
                .apply();
    }

    public synchronized void markClicked(String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return;
        }
        preferences.edit()
                .remove(SHOWN_PREFIX + videoId)
                .remove(LAST_SHOWN_PREFIX + videoId)
                .apply();
    }

    public synchronized boolean shouldTemporarilySuppress(String videoId) {
        return getActiveIgnoredVisits(videoId, System.currentTimeMillis())
                >= SUPPRESS_AFTER_IGNORED_VISITS;
    }

    public synchronized double getRankingPenalty(String videoId) {
        final int ignoredVisits = getActiveIgnoredVisits(videoId, System.currentTimeMillis());
        if (ignoredVisits <= 1) {
            return 0d;
        }
        return Math.min(500d, (ignoredVisits - 1) * 85d);
    }

    private int getActiveIgnoredVisits(String videoId, long now) {
        if (videoId == null || videoId.isEmpty()) {
            return 0;
        }
        final long lastShown = preferences.getLong(LAST_SHOWN_PREFIX + videoId, 0L);
        if (lastShown <= 0L || now - lastShown > FEEDBACK_DECAY_MILLIS) {
            return 0;
        }
        return preferences.getInt(SHOWN_PREFIX + videoId, 0);
    }
}
