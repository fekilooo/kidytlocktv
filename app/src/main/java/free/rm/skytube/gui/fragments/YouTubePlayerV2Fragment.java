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

package free.rm.skytube.gui.fragments;

import static free.rm.skytube.gui.activities.YouTubePlayerActivity.YOUTUBE_VIDEO_OBJ;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseExpandableListAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.media.AudioManagerCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.UnrecognizedInputFormatException;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.HttpDataSource;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.stream.StreamInfo.StreamExtractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import free.rm.skytube.R;
import free.rm.skytube.app.Settings;
import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.app.StreamSelectionPolicy;
import free.rm.skytube.app.Utils;
import free.rm.skytube.app.enums.Policy;
import free.rm.skytube.businessobjects.DiagnosticFileLogger;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.Sponsorblock.SBSegment;
import free.rm.skytube.businessobjects.Sponsorblock.SBTasks;
import free.rm.skytube.businessobjects.Sponsorblock.SBTimeBarView;
import free.rm.skytube.businessobjects.Sponsorblock.SBVideoInfo;
import free.rm.skytube.businessobjects.YouTube.POJOs.PersistentChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;
import free.rm.skytube.businessobjects.YouTube.PersonalizedRecommendationRanker;
import free.rm.skytube.businessobjects.YouTube.VideoBlocker;
import free.rm.skytube.businessobjects.YouTube.YouTubeTasks;
import free.rm.skytube.businessobjects.YouTube.newpipe.ContentId;
import free.rm.skytube.businessobjects.YouTube.newpipe.NewPipeService;
import free.rm.skytube.businessobjects.bilibili.BilibiliService;
import free.rm.skytube.businessobjects.YouTube.newpipe.VideoId;
import free.rm.skytube.businessobjects.db.DatabaseTasks;
import free.rm.skytube.businessobjects.db.DownloadedVideosDb;
import free.rm.skytube.businessobjects.db.PlaybackStatusDb;
import free.rm.skytube.businessobjects.interfaces.GetDesiredStreamListener;
import free.rm.skytube.businessobjects.interfaces.PlaybackStateListener;
import free.rm.skytube.businessobjects.interfaces.YouTubePlayerActivityListener;
import free.rm.skytube.businessobjects.interfaces.YouTubePlayerFragmentInterface;
import free.rm.skytube.databinding.FragmentYoutubePlayerV2Binding;
import free.rm.skytube.databinding.VideoDescriptionBinding;
import free.rm.skytube.gui.activities.ThumbnailViewerActivity;
import free.rm.skytube.gui.businessobjects.BilibiliPlaybackController;
import free.rm.skytube.gui.businessobjects.DatasourceBuilder;
import free.rm.skytube.gui.businessobjects.MobileNetworkWarningDialog;
import free.rm.skytube.gui.businessobjects.PlaybackSpeedController;
import free.rm.skytube.gui.businessobjects.PlayerViewGestureDetector;
import free.rm.skytube.gui.businessobjects.ResumeVideoTask;
import free.rm.skytube.gui.businessobjects.SabrPlaybackController;
import free.rm.skytube.gui.businessobjects.SkyTubeMaterialDialog;
import free.rm.skytube.gui.businessobjects.TvFocusHelper;
import free.rm.skytube.gui.businessobjects.YouTubeIFramePlayerController;
import free.rm.skytube.gui.businessobjects.adapters.CommentsAdapter;
import free.rm.skytube.gui.businessobjects.fragments.ImmersiveModeFragment;
import free.rm.skytube.gui.businessobjects.views.ChannelActionHandler;
import free.rm.skytube.gui.businessobjects.views.Linker;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.internal.functions.Functions;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * A fragment that holds a standalone YouTube player (version 2).
 */
@RequiresApi(api = 14)
public class YouTubePlayerV2Fragment extends ImmersiveModeFragment implements YouTubePlayerFragmentInterface, Linker.CurrentActivity {
    private static final String TAG = YouTubePlayerV2Fragment.class.getSimpleName();
    private YouTubeVideo youTubeVideo = null;
    private VideoId videoId;
    private YouTubeChannel youTubeChannel = null;

    private FragmentYoutubePlayerV2Binding fragmentBinding;
    private VideoDescriptionBinding videoDescriptionBinding;

    private SimpleExoPlayer player;
    private long playerInitialPosition = 0;
    private DatasourceBuilder datasourceBuilder;

    private Menu menu = null;

    private BaseExpandableListAdapter commentsAdapter = null;
    private YouTubePlayerActivityListener listener = null;
    private PlayerViewGestureHandler playerViewGestureHandler;

    private PlaybackSpeedController playbackSpeedController;

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private final ChannelActionHandler actionHandler = new ChannelActionHandler(compositeDisposable);
    private boolean videoIsPlaying;
    private PlaybackStateListener playbackStateListener = null;

    private SBVideoInfo sponsorBlockVideoInfo;
    private boolean autoPlayingNextVideo;
    private boolean playNextVideoWhenReady;
    private static final int AUTO_PLAY_NEXT_SECONDS = 8;
    private static final int MIN_RECOMMENDATION_COUNT = 16;
    private final Handler recommendationHandler = new Handler(Looper.getMainLooper());
    private final List<YouTubeVideo> recommendations = new ArrayList<>();
    private final List<BilibiliService.Episode> bilibiliEpisodes = new ArrayList<>();
    private int currentBilibiliEpisodeIndex;
    private int selectedBilibiliEpisodeIndex;
    private int renderedBilibiliEpisodeGeneration = -1;
    private int renderedBilibiliEpisodeCount = -1;
    private int renderedBilibiliCurrentEpisode = -1;
    private boolean renderedBilibiliEpisodesLoading;
    private int renderedRecommendationGeneration = -1;
    private int renderedRecommendationCount = -1;
    private boolean renderedRecommendationsLoading;
    private boolean pausePlaybackChoicesVisible;
    private int bilibiliEpisodeGeneration;
    private boolean bilibiliEpisodesLoading;
    private boolean focusBilibiliEpisodesWhenShown;
    private String bilibiliEpisodesBvid;
    private YouTubeVideo bilibiliSeriesSource;
    private int recommendationGeneration;
    private int selectedRecommendationIndex;
    private int autoPlaySecondsRemaining;
    private boolean recommendationsLoading;
    private boolean waitingToAutoPlay;
    private boolean focusRecommendationsWhenShown = true;
    private Runnable recommendationCountdown;
    private boolean watchedThresholdRecorded;
    private boolean playbackHistoryRecordedForCurrentVideo;
    private final Runnable watchedProgressCheck = new Runnable() {
        @Override
        public void run() {
            if (player == null || youTubeVideo == null || watchedThresholdRecorded) {
                return;
            }
            long position = getActivePlaybackPosition();
            long duration = getActivePlaybackDuration();
            boolean mostlyFinished = duration > 0 && duration != C.TIME_UNSET
                    && (float) position / duration >= 0.9f;
            if (position >= 120_000L || mostlyFinished) {
                watchedThresholdRecorded = true;
                compositeDisposable.add(PlaybackStatusDb.getPlaybackStatusDb()
                        .setVideoPositionInBackground(youTubeVideo, position));
                Logger.i(YouTubePlayerV2Fragment.this,
                        "Playback history marked watched at %d ms for %s", position, youTubeVideo.getId());
            } else if (getActivePlayWhenReady()) {
                recommendationHandler.postDelayed(this, 10_000L);
            }
        }
    };
    private Uri hlsFallbackVideoUri;
    private Uri hlsFallbackAudioUri;
    private StreamInfo hlsFallbackStreamInfo;
    private boolean hlsFallbackAttempted;
    private Uri stableFallbackVideoUri;
    private StreamInfo stableFallbackStreamInfo;
    private boolean stableFallbackAttempted;
    private static final long HIGH_RESOLUTION_STARTUP_TIMEOUT_MS = 12_000L;
    private static final long IFRAME_STARTUP_TIMEOUT_MS = 20_000L;
    private static final long SABR_STARTUP_TIMEOUT_MS = 30_000L;
    private Runnable highResolutionStartupTimeout;
    private Runnable iframeStartupTimeout;
    private Runnable sabrStartupTimeout;
    private YouTubeIFramePlayerController iframePlayer;
    private boolean iframePlaybackActive;
    private boolean iframePlaybackAttempted;
    private SabrPlaybackController sabrPlayer;
    private boolean sabrPlaybackActive;
    private boolean sabrPlaybackAttempted;
    private int sabrPreferredVideoItag;
    private BilibiliPlaybackController bilibiliPlayer;
    private boolean bilibiliPlaybackActive;
    private boolean bilibiliPlaybackStarting;
    private StreamInfo bilibiliStreamInfo;
    private VideoStream bilibiliVideoStream;
    private AudioStream bilibiliAudioStream;
    private int bilibiliSourceIndex;
    private int bilibiliStallRetryCount;
    private static final int BILIBILI_DEFAULT_HEIGHT = 720;
    private static final int BILIBILI_MAX_STALL_RETRIES = 3;
    private static final String BILIBILI_PREF_HEIGHT = "bilibili.preferred_height";
    private static final String BILIBILI_PREF_CODEC = "bilibili.preferred_codec";
    private final Runnable sabrControlsProgressUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isAlternatePlaybackActive() || fragmentBinding == null) {
                return;
            }
            updateSabrControls();
            recommendationHandler.postDelayed(this, 500L);
        }
    };
    private Uri iframeNativeReturnVideoUri;
    private Uri iframeNativeReturnAudioUri;
    private StreamInfo iframeNativeReturnStreamInfo;
    private StreamInfo selectableStreamInfo;
    private List<AudioStream> selectableAudioTracks = Collections.emptyList();
    private AudioStream selectedAudioStream;
    private io.reactivex.rxjava3.disposables.Disposable recommendationPrefetchDisposable;
    private String recommendationPrefetchVideoId;
    private StreamInfo prefetchedRecommendationStream;
    private YouTubeVideo prefetchedRecommendationVideo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        hideNavigationBar();

        playerViewGestureHandler = new PlayerViewGestureHandler(SkyTubeApp.getSettings());

        // inflate the layout for this fragment
        fragmentBinding = FragmentYoutubePlayerV2Binding.inflate(inflater, container, false);
        videoDescriptionBinding = fragmentBinding.desContent;

        // indicate that this fragment has an action bar menu
        setHasOptionsMenu(true);

        if (youTubeVideo == null) {
            // initialise the views
            initViews();

            // get which video we need to play...
            Intent intent = requireActivity().getIntent();
            Bundle bundle = intent.getExtras();
            if (bundle != null && bundle.getSerializable(YOUTUBE_VIDEO_OBJ) != null) {
                // ... either the video details are passed through the previous activity
                setYouTubeVideo((YouTubeVideo) bundle.getSerializable(YOUTUBE_VIDEO_OBJ));
                setUpHUDAndPlayVideo();
            } else {
                // ... or the video URL is passed to SkyTube via another Android app
                final ContentId contentId = SkyTubeApp.getUrlFromIntent(requireContext(), intent);
                openVideo(contentId);
            }
        }

        return fragmentBinding.getRoot();
    }

    private TextView getPlaybackSpeedTextView() {
        return fragmentBinding.getRoot().findViewById(R.id.playbackSpeed);
    }

    private void openVideo(ContentId contentId) {
        Utils.isTrue(contentId.getType() == StreamingService.LinkType.STREAM, "Content is a video:" + contentId);
        compositeDisposable.add(YouTubeTasks.getVideoDetails(requireContext(), contentId)
            .subscribe(video -> {
                if (video == null) {
                    // invalid URL error (i.e. we are unable to decode the URL)
                    String err = String.format(getString(R.string.error_invalid_url), contentId.getCanonicalUrl());
                    Toast.makeText(getActivity(), err, Toast.LENGTH_LONG).show();

                    // log error
                    Logger.e(this, err);

                    // close the video player activity
                    closeActivity();
                } else {
                    setYouTubeVideo(video);

                    // setup the HUD and play the video
                    setUpHUDAndPlayVideo();
                }
            }));
    }

    protected void setYouTubeVideo(YouTubeVideo video) {
        this.youTubeVideo = video;
        this.videoId = video != null ? video.getVideoId() : null;
    }
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        try {
            Activity activity = (Activity) context;
            listener = (YouTubePlayerActivityListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException("YouTubePlayerFragment must be instantiated from an Activity that implements YouTubePlayerActivityListener");
		}
	}

	private void updateAudioTrackMenu() {
		if (menu == null) {
			return;
		}
		MenuItem audioItem = menu.findItem(R.id.menu_audio_track);
		if (audioItem == null) {
			return;
		}
		boolean selectable = !isAlternatePlaybackActive()
				&& selectableAudioTracks.size() > 1 && selectedAudioStream != null;
		audioItem.setVisible(selectable);
		if (selectable) {
			audioItem.setTitle(getString(R.string.audio_language_current,
					getAudioTrackLabel(selectedAudioStream, 1)));
		}
	}

	private void showAudioTrackDialog() {
		Context context = getContext();
		if (context == null || selectableAudioTracks.size() <= 1) {
			return;
		}
		CharSequence[] labels = new CharSequence[selectableAudioTracks.size()];
		int selectedIndex = 0;
		for (int i = 0; i < selectableAudioTracks.size(); i++) {
			AudioStream track = selectableAudioTracks.get(i);
			labels[i] = getAudioTrackLabel(track, i + 1);
			if (isSameAudioTrack(track, selectedAudioStream)) {
				selectedIndex = i;
			}
		}
		new SkyTubeMaterialDialog(context)
				.title(R.string.audio_language)
				.items(labels)
				.itemsCallbackSingleChoice(selectedIndex, (dialog, itemView, which, text) -> {
					switchAudioTrack(selectableAudioTracks.get(which));
					return true;
				})
				.show();
	}

	private void switchAudioTrack(@NonNull AudioStream audioTrack) {
		if (isAlternatePlaybackActive() || selectableStreamInfo == null || player == null
				|| isSameAudioTrack(audioTrack, selectedAudioStream)) {
			return;
		}
		StreamSelectionPolicy selectionPolicy = SkyTubeApp.getSettings()
				.getDesiredVideoResolution(false)
				.withAllowVideoOnly(true);
		StreamSelectionPolicy.StreamSelection selection = selectionPolicy.select(selectableStreamInfo, audioTrack);
		if (selection == null || selection.getAudioStreamUri() == null) {
			return;
		}
		long position = Math.max(0L, player.getCurrentPosition());
		boolean playWhenReady = player.getPlayWhenReady();
		String label = getAudioTrackLabel(audioTrack, 1);
		appendPlaybackDiagnostic("switch audio track label=" + label
				+ " locale=" + audioTrack.getAudioLocale(), null);
		hlsFallbackVideoUri = null;
		hlsFallbackAudioUri = null;
		hlsFallbackStreamInfo = null;
		hlsFallbackAttempted = false;
		stableFallbackVideoUri = null;
		stableFallbackStreamInfo = null;
		stableFallbackAttempted = false;
		cancelHighResolutionStartupTimeout();
		selectedAudioStream = audioTrack;
		player.stop();
		playVideo(selection.getVideoStreamUri(), selection.getAudioStreamUri(), selectableStreamInfo);
		player.seekTo(position);
		player.setPlayWhenReady(playWhenReady);
		updateAudioTrackMenu();
		Toast.makeText(getContext(), getString(R.string.audio_language_switched, label),
				Toast.LENGTH_SHORT).show();
	}

	private String getAudioTrackLabel(@NonNull AudioStream audioTrack, int fallbackIndex) {
		Locale locale = audioTrack.getAudioLocale();
		if (locale != null) {
			String displayName = locale.getDisplayName(Locale.getDefault());
			if (displayName != null && !displayName.isEmpty()) {
				return displayName;
			}
		}
		String name = audioTrack.getAudioTrackName();
		if (name != null && !name.trim().isEmpty()) {
			return name.trim();
		}
		return getString(R.string.audio_language_unknown, fallbackIndex);
	}

	private boolean isSameAudioTrack(@Nullable AudioStream left, @Nullable AudioStream right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		if (left.getAudioTrackId() != null || right.getAudioTrackId() != null) {
			return Objects.equals(left.getAudioTrackId(), right.getAudioTrackId());
		}
		return Objects.equals(left.getAudioLocale(), right.getAudioLocale())
				&& Objects.equals(left.getAudioTrackName(), right.getAudioTrackName());
	}

    private void updateBilibiliPlaybackMenu() {
        if (menu == null) {
            return;
        }
        boolean visible = youTubeVideo != null && youTubeVideo.isBilibili()
                && bilibiliStreamInfo != null && bilibiliVideoStream != null;
        MenuItem qualityItem = menu.findItem(R.id.menu_bilibili_quality);
        MenuItem codecItem = menu.findItem(R.id.menu_bilibili_codec);
        MenuItem sourceItem = menu.findItem(R.id.menu_bilibili_source);
        if (qualityItem != null) {
            qualityItem.setVisible(visible);
            if (visible) {
                qualityItem.setTitle(getString(R.string.bilibili_quality_current,
                        bilibiliVideoStream.getResolution()));
            }
        }
        if (codecItem != null) {
            codecItem.setVisible(visible);
            if (visible) {
                codecItem.setTitle(getString(R.string.bilibili_codec_current,
                        bilibiliCodecFamily(bilibiliVideoStream.getCodec()).toUpperCase(Locale.ROOT)));
            }
        }
        if (sourceItem != null) {
            sourceItem.setVisible(visible);
            if (visible) {
                sourceItem.setTitle(getString(R.string.bilibili_source_current,
                        bilibiliSourceIndex + 1, getBilibiliSourceCount()));
            }
        }
    }

    private void showBilibiliQualityDialog() {
        if (getContext() == null || bilibiliStreamInfo == null) {
            return;
        }
        Set<Integer> uniqueHeights = new HashSet<>();
        for (VideoStream stream : getAllBilibiliVideoStreams()) {
            if (stream.getHeight() > 0) {
                uniqueHeights.add(stream.getHeight());
            }
        }
        List<Integer> heights = new ArrayList<>(uniqueHeights);
        heights.sort(Collections.reverseOrder());
        if (heights.isEmpty()) {
            return;
        }
        int currentHeight = bilibiliVideoStream != null
                ? bilibiliVideoStream.getHeight() : BILIBILI_DEFAULT_HEIGHT;
        CharSequence[] labels = new CharSequence[heights.size()];
        int selectedIndex = 0;
        for (int i = 0; i < heights.size(); i++) {
            labels[i] = heights.get(i) + "p";
            if (heights.get(i) == currentHeight) {
                selectedIndex = i;
            }
        }
        new SkyTubeMaterialDialog(getContext())
                .title(R.string.bilibili_quality)
                .items(labels)
                .itemsCallbackSingleChoice(selectedIndex, (dialog, itemView, which, text) -> {
                    SkyTubeApp.getPreferenceManager().edit()
                            .putInt(BILIBILI_PREF_HEIGHT, heights.get(which)).apply();
                    restartBilibiliWithPreferences();
                    return true;
                })
                .show();
    }

    private void showBilibiliCodecDialog() {
        if (getContext() == null || bilibiliStreamInfo == null) {
            return;
        }
        Set<String> uniqueCodecs = new LinkedHashSet<>();
        for (VideoStream stream : getAllBilibiliVideoStreams()) {
            uniqueCodecs.add(bilibiliCodecFamily(stream.getCodec()));
        }
        List<String> codecs = new ArrayList<>(uniqueCodecs);
        codecs.sort((left, right) -> "avc1".equals(left) ? -1 : "avc1".equals(right) ? 1 : 0);
        if (codecs.isEmpty()) {
            return;
        }
        String currentCodec = bilibiliVideoStream != null
                ? bilibiliCodecFamily(bilibiliVideoStream.getCodec()) : "avc1";
        CharSequence[] labels = new CharSequence[codecs.size()];
        int selectedIndex = 0;
        for (int i = 0; i < codecs.size(); i++) {
            labels[i] = "hvc1".equals(codecs.get(i))
                    ? getString(R.string.bilibili_codec_hvc1)
                    : getString(R.string.bilibili_codec_avc1);
            if (codecs.get(i).equals(currentCodec)) {
                selectedIndex = i;
            }
        }
        new SkyTubeMaterialDialog(getContext())
                .title(R.string.bilibili_codec)
                .items(labels)
                .itemsCallbackSingleChoice(selectedIndex, (dialog, itemView, which, text) -> {
                    SkyTubeApp.getPreferenceManager().edit()
                            .putString(BILIBILI_PREF_CODEC, codecs.get(which)).apply();
                    restartBilibiliWithPreferences();
                    return true;
                })
                .show();
    }

    private void showBilibiliSourceDialog() {
        if (getContext() == null || bilibiliVideoStream == null) {
            return;
        }
        int sourceCount = getBilibiliSourceCount();
        CharSequence[] labels = new CharSequence[sourceCount];
        List<String> videoUrls = BilibiliService.get().getMediaUrlVariants(
                bilibiliVideoStream.getContent());
        for (int i = 0; i < sourceCount; i++) {
            String url = videoUrls.get(Math.min(i, videoUrls.size() - 1));
            String host = Uri.parse(url).getHost();
            labels[i] = getString(R.string.bilibili_source_item, i + 1,
                    host != null ? host : "CDN");
        }
        new SkyTubeMaterialDialog(getContext())
                .title(R.string.bilibili_source)
                .items(labels)
                .itemsCallbackSingleChoice(bilibiliSourceIndex,
                        (dialog, itemView, which, text) -> {
                            bilibiliSourceIndex = which;
                            bilibiliStallRetryCount = 0;
                            restartCurrentBilibiliStream();
                            return true;
                        })
                .show();
    }

    @NonNull
    private List<VideoStream> getAllBilibiliVideoStreams() {
        List<VideoStream> streams = new ArrayList<>();
        if (bilibiliStreamInfo != null) {
            streams.addAll(bilibiliStreamInfo.getVideoOnlyStreams());
            streams.addAll(bilibiliStreamInfo.getVideoStreams());
        }
        return streams;
    }

    private void restartBilibiliWithPreferences() {
        bilibiliSourceIndex = 0;
        bilibiliStallRetryCount = 0;
        long position = bilibiliPlayer != null ? bilibiliPlayer.getCurrentPosition() : 0L;
        boolean playWhenReady = bilibiliPlayer == null || bilibiliPlayer.getPlayWhenReady();
        selectAndStartBilibiliStream(position, playWhenReady);
    }

    private void restartCurrentBilibiliStream() {
        if (bilibiliVideoStream == null || bilibiliStreamInfo == null) {
            return;
        }
        long position = bilibiliPlayer != null ? bilibiliPlayer.getCurrentPosition() : 0L;
        boolean playWhenReady = bilibiliPlayer == null || bilibiliPlayer.getPlayWhenReady();
        startBilibiliPlayback(bilibiliVideoStream, bilibiliAudioStream,
                bilibiliStreamInfo.getDuration(), position, playWhenReady);
        updateBilibiliPlaybackMenu();
    }

	/**
     * Initialise the views.
     */
    private void initViews() {
        // setup the toolbar / actionbar
        Toolbar toolbar = fragmentBinding.getRoot().findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);

        // setup the player
        playerViewGestureHandler.initView();
        fragmentBinding.playerView.setOnTouchListener(playerViewGestureHandler);
        fragmentBinding.playerView.requestFocus();

        setupPlayer();

        // ensure that videos are played in their correct aspect ratio
        fragmentBinding.playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);

        videoDescriptionBinding.videoDescChannelThumbnailImageView.setOnClickListener(v -> {
            if (youTubeChannel != null) {
                SkyTubeApp.launchChannel(youTubeChannel, getActivity());
            }
        });
        fragmentBinding.commentsDrawer.setOnDrawerOpenListener(() -> {
            if (commentsAdapter == null) {
                commentsAdapter = CommentsAdapter.createAdapter(getActivity(), this, youTubeVideo.getId(),
                        fragmentBinding.commentsExpandableListView, fragmentBinding.commentsProgressBar,
                        fragmentBinding.noVideoCommentsTextView, fragmentBinding.videoCommentsAreDisabled);
            }
        });
        this.playbackSpeedController = new PlaybackSpeedController(getContext(),
                getPlaybackSpeedTextView(), player);

        //set playback speed
        float playbackSpeed = SkyTubeApp.getSettings().getDefaultPlaybackSpeed();
        playbackSpeedController.setPlaybackSpeed(playbackSpeed);
        configureTvPlayerFocus();

        Linker.configure(videoDescriptionBinding.videoDescDescription, this);
    }

    private synchronized void setupPlayer() {
        if (fragmentBinding.playerView.getPlayer() == null) {
            if (player == null) {
                player = createExoPlayer();
                datasourceBuilder = new DatasourceBuilder(
                        getContext(),
                        player,
                        (mediaUri, position) -> recommendationHandler.post(() -> {
                            if (fragmentBinding == null || isAlternatePlaybackActive()
                                    || sabrPlaybackAttempted
                                    || (youTubeVideo != null && youTubeVideo.isBilibili())) {
                                return;
                            }
                            final String failedItag = mediaUri.getQueryParameter("itag");
                            if (failedItag != null
                                    && !failedItag.equals(Integer.toString(sabrPreferredVideoItag))) {
                                return;
                            }
                            appendPlaybackDiagnostic(
                                    "direct finite range HTTP 403 itag=" + failedItag
                                            + " bytePosition=" + position,
                                    null);
                            tryStartSabrPlayback("direct finite range HTTP 403");
                        }));
            } else {
                Logger.i(this, ">> found already existing player, re-using it, to avoid duplicate usage");
            }
            player.addListener(new Player.EventListener() {
                @Override
                public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                    Logger.i(this, ">> onPlayerStateChanged " + playWhenReady + " state=" + playbackState);
                    if (isAlternatePlaybackActive()) {
                        return;
                    }
                    videoIsPlaying = playbackState == Player.STATE_READY && playWhenReady;
                    boolean videoIsPaused = playbackState == Player.STATE_READY && !playWhenReady;

                    if (playbackState == Player.STATE_READY) {
                        cancelHighResolutionStartupTimeout();
                    }
                    if (videoIsPlaying) {
                        recordCurrentPlayback();
                        preventDeviceSleeping(true);
                        playbackSpeedController.updateMenu();
                        recommendationHandler.removeCallbacks(watchedProgressCheck);
                        recommendationHandler.postDelayed(watchedProgressCheck, 10_000L);
                        if (!waitingToAutoPlay) {
                            hideTvRecommendations();
                        }
                    } else {
                        preventDeviceSleeping(false);
                        recommendationHandler.removeCallbacks(watchedProgressCheck);
                        watchedProgressCheck.run();
                    }

                    if (playbackStateListener != null) {
                        if (videoIsPlaying) {
                            playbackStateListener.started();
                        } else if (videoIsPaused) {
                            playbackStateListener.paused();
                        } else {
                            playbackStateListener.ended();
                        }
                    }

                    if (videoIsPaused && fragmentBinding != null && isVisible()) {
                        showTvRecommendationsForPause();
                    }

                    if (playbackState == Player.STATE_ENDED
                            && !tryPlayNextBilibiliEpisode()) {
                        beginRecommendedAutoPlay();
                    }
                }

                @Override
                public void onPlayerError(ExoPlaybackException error) {
                    Logger.e(this, ":: onPlayerError " + error.getMessage(), error);
                    appendPlaybackDiagnostic("exo_player_error", error);
                    if (isAlternatePlaybackActive()) {
                        return;
                    }

                    if (hlsFallbackVideoUri != null && !hlsFallbackAttempted) {
                        hlsFallbackAttempted = true;
                        Logger.i(this, "HLS playback failed; falling back to selected stream: %s",
                                hlsFallbackVideoUri);
                        player.stop();
                        datasourceBuilder.play(
                                hlsFallbackVideoUri,
                                hlsFallbackAudioUri,
                                hlsFallbackStreamInfo);
                        player.setPlayWhenReady(true);
                        return;
                    }

                    if (isHttp403(error)
                            && (youTubeVideo == null || !youTubeVideo.isBilibili())
                            && tryStartSabrPlayback("direct stream HTTP 403")) {
                        return;
                    }

                    if (stableFallbackVideoUri != null && !stableFallbackAttempted) {
                        fallbackToStableStream("player error");
                        return;
                    }

                    saveVideoPosition();

                    if (playbackStateListener != null) {
                        playbackStateListener.ended();
                    }

                    boolean askForDelete = askForDelete(error);
                    String errorMessage = error.getCause().getMessage();
                    Context ctx = YouTubePlayerV2Fragment.this.getContext();
                    new SkyTubeMaterialDialog(ctx)
                            .onNegativeOrCancel(dialog -> closeActivity())
                            .content(askForDelete ? R.string.error_downloaded_file_is_corrupted : R.string.error_video_parse_error, errorMessage)
                            .title(R.string.error_video_play)
                            .negativeText(R.string.close)
                            .positiveText(null)
                            .positiveText(askForDelete ? R.string.delete_download : 0)
                            .onPositive((dialog, which) -> {
                                if (askForDelete) {
                                    compositeDisposable.add(
                                            DownloadedVideosDb.getVideoDownloadsDb().removeDownload(ctx, youTubeVideo.getVideoId())
                                                    .subscribe(
                                                            status -> closeActivity(),
                                                            err -> Logger.e(YouTubePlayerV2Fragment.this, "Error:" + err.getMessage(), err)));
                                } else {
                                    closeActivity();
                                }
                            }).show();
                }

                private boolean askForDelete(ExoPlaybackException error) {
                    Throwable cause = error.getCause();
                    if (cause instanceof UnrecognizedInputFormatException) {
                        UnrecognizedInputFormatException uie = (UnrecognizedInputFormatException) cause;
                        return "file".equals(uie.uri.getScheme());
                    }
                    return false;
                }
            });
            player.setPlayWhenReady(true);
            player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);    // ensure that videos are played in their correct aspect ratio
            fragmentBinding.playerView.setPlayer(player);
        }
    }

    private SimpleExoPlayer createExoPlayer() {
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();

        TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveTrackSelection.Factory();
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);
        trackSelector.setParameters(new DefaultTrackSelector.ParametersBuilder()
                .setMaxVideoSize(3840, 2160)
                .setForceHighestSupportedBitrate(true)
                .build());
        Context context = getContext();
        DefaultRenderersFactory defaultRenderersFactory = new DefaultRenderersFactory(context);

        return ExoPlayerFactory.newSimpleInstance(getContext(), defaultRenderersFactory, trackSelector, new DefaultLoadControl(), null, bandwidthMeter);
    }


    /**
     * Will setup the HUD's details according to the contents of {@link #youTubeVideo}.  Then it
     * will try to load and play the video.
     */
    private void setUpHUDAndPlayVideo() {
        startPlaybackForVideo(youTubeVideo, true);
    }

    private void setupInfoDisplay(YouTubeVideo video) {
        getSupportActionBar().setTitle(video.getTitle());
        videoDescriptionBinding.videoDescTitle.setText(video.getTitle());
        videoDescriptionBinding.videoDescChannel.setText(video.getChannelName());
        videoDescriptionBinding.videoDescViews.setText(video.getViewsCount());
        videoDescriptionBinding.videoDescPublishDate.setText(video.getPublishDatePretty());

        if (video.getDescription() != null) {
            Linker.setTextAndLinkify(videoDescriptionBinding.videoDescDescription, video.getDescription());
        } else {
            Linker.setTextAndLinkify(videoDescriptionBinding.videoDescDescription, "");
        }

        setupLikeCounters(video);

        if (SkyTubeApp.getSettings().isSponsorblockEnabled()) {
            initSponsorBlock();
        }
    }

    private void setupLikeCounters(YouTubeVideo video) {
        if (video.isBilibili()) {
            setVisibility(videoDescriptionBinding.videoDescLikes, false);
            setVisibility(videoDescriptionBinding.videoDescDislikes, false);
            setVisibility(videoDescriptionBinding.videoDescLikesBar, false);
            setVisibility(videoDescriptionBinding.videoDescRatingsDisabled, true);
            return;
        }
        final boolean hasLikes = video.getLikeCountNumber() != null;
        setTextAndVisibility(videoDescriptionBinding.videoDescLikes, hasLikes, video.getLikeCount());
        final boolean hasDislikes = video.getDislikeCountNumber() != null;
        setTextAndVisibility(videoDescriptionBinding.videoDescDislikes, hasDislikes, video.getDislikeCount());
        setValueAndVisibility(videoDescriptionBinding.videoDescLikesBar, video.isThumbsUpPercentageSet(), video.getThumbsUpPercentage());
        setVisibility(videoDescriptionBinding.videoDescRatingsDisabled, !hasLikes && !hasDislikes);
        if (!hasDislikes) {
            YouTubeTasks.getDislikeCountFromApi(video.getId()).subscribe(dislikeCount -> {
                video.setLikeDislikeCount(video.getLikeCountNumber(), dislikeCount);
                final boolean hasDislikesFresh = video.getDislikeCountNumber() != null;
                setTextAndVisibility(videoDescriptionBinding.videoDescDislikes, hasDislikesFresh, video.getDislikeCount());
                setVisibility(videoDescriptionBinding.videoDescRatingsDisabled, !hasLikes && !hasDislikesFresh);
            });
        }
    }

    /**
     * Retrieve the sponsorBlock information, either from the internal downloaded videos table, or from the network.
     */
    private void retrieveSponsorBlockIfPossible() {
        if (youTubeVideo != null && youTubeVideo.isBilibili()) {
            return;
        }
        if (SkyTubeApp.getSettings().isSponsorblockEnabled()) {
            if (sponsorBlockVideoInfo == null) {
                sponsorBlockVideoInfo = DownloadedVideosDb.getVideoDownloadsDb().getDownloadedVideoSponsorblock(youTubeVideo.getId());
                if (sponsorBlockVideoInfo == null) {
                    sponsorBlockVideoInfo = SBTasks.retrieveSponsorblockSegmentsBk(youTubeVideo.getVideoId());
                }
                initSponsorBlock();
            }
        }
    }

    private void initSponsorBlock() {
        if (sponsorBlockVideoInfo != null) {
            Log.d(TAG, "SBInfo has loaded");
            Handler handler = new Handler(Looper.getMainLooper());
            for (SBSegment segment : sponsorBlockVideoInfo.getSegments()) {
                long startPosMs = Math.round(segment.getStartPos() * 1000);
                player.createMessage((messageType, payload) -> {
                            SBSegment payloadSegment = (SBSegment) payload;

                            handler.post(() -> {
                                SBTasks.LabelAndColor labelAndColor = SBTasks.getLabelAndColor(payloadSegment.getCategory());

                                if (labelAndColor != null) {
                                    String categoryLabel = getString(labelAndColor.label);
                                    Toast.makeText(getContext(),
                                            getString(R.string.sponsorblock_skipped, categoryLabel),
                                            Toast.LENGTH_SHORT).show();
                                } else {
                                    Log.w(TAG, "Unknown sponsorBlock category: " + payloadSegment.getCategory());
                                }
                            });

                            long pos = Math.round(payloadSegment.getEndPos() * 1000);
                            player.seekTo(pos);
                        })
                        .setHandler(handler)
                        .setPosition(startPosMs)
                        .setPayload(segment)
                        .setDeleteAfterDelivery(false)
                        .send();
            }

            SBTimeBarView sbView = fragmentBinding.getRoot().findViewById(R.id.exo_sponsorblock_progress);
            if (sbView != null) {
                sbView.setSegments(sponsorBlockVideoInfo);
            } else {
                Log.e(TAG, "SBView not found!");
            }
        } else {
            Log.d(TAG, "SBInfo not loaded yet");
        }
    }

    private void setTextAndVisibility(TextView view, boolean visible, String text) {
        setVisibility(view, visible);
        if (visible) {
            view.setText(text);
        }
    }

    private void setValueAndVisibility(ProgressBar view, boolean visible, int percentage) {
        setVisibility(view, visible);
        if (visible) {
            view.setProgress(percentage);
        }
    }

    private void setVisibility(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Loads the video specified in {@link #youTubeVideo}.
     */
    private void loadVideo() {
        loadVideo(true);
    }

    private void preventDeviceSleeping(boolean flag) {
        // prevent the device from sleeping while playing
        Activity activity = getActivity();
        if (activity != null) {
            Window window = activity.getWindow();
            if (window != null) {
                if (flag) {
                    Logger.i(this, ">> Setting FLAG_KEEP_SCREEN_ON");
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    Logger.i(this, ">> Clearing FLAG_KEEP_SCREEN_ON");
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }
        }
    }

    /**
     * Loads the video specified in {@link #videoId}.
     *
     * @param showMobileNetworkWarning Set to true to show the warning displayed when the user is
     *                                 using mobile network data (i.e. 4g).
     */
    private void loadVideo(boolean showMobileNetworkWarning) {
        appendPlaybackDiagnostic("loadVideo start showMobileNetworkWarning=" + showMobileNetworkWarning, null);
        Context ctx = getContext();
        compositeDisposable.add(
                DownloadedVideosDb.getVideoDownloadsDb().getDownloadedFileStatus(ctx, videoId)
                        .subscribe(downloadStatus -> {
                            Policy decision = Policy.ALLOW;

                            // if the user is using mobile network (i.e. 4g), then warn him
                            if (showMobileNetworkWarning && downloadStatus.getUri() == null) {
                                decision = new MobileNetworkWarningDialog(getActivity())
                                        .onPositive((dialog, which) -> loadVideo(false))
                                        .onNegativeOrCancel((dialog) -> closeActivity())
                                        .showAndGetStatus(MobileNetworkWarningDialog.ActionType.STREAM_VIDEO);
                            }

                            if (decision == Policy.ALLOW) {
                                // if the video is NOT live
                                if (!shouldOpenAsLiveStream()) {
                                    fragmentBinding.loadingVideoView.setVisibility(View.VISIBLE);

                                    if (downloadStatus.isDisappeared()) {
                                        // If the file for this video has gone missing, warn and then play remotely.
                                        Toast.makeText(getContext(),
                                                getString(R.string.playing_video_file_missing),
                                                Toast.LENGTH_LONG).show();
                                        loadVideo();
                                        return;
                                    }
                                    if (downloadStatus.getUri() != null) {
                                        fragmentBinding.loadingVideoView.setVisibility(View.GONE);
                                        Logger.i(this, ">> PLAYING LOCALLY: %s", downloadStatus.getUri());
                                        playVideo(downloadStatus.getUri(), downloadStatus.getAudioUri(), null);

                                        retrieveSponsorBlockIfPossible();

                                        // get the video statistics
                                        compositeDisposable.add(YouTubeTasks.getVideoDetails(ctx, youTubeVideo.getVideoId())
                                                .subscribe(video -> {
                                                    if (video != null) {
                                                        setupInfoDisplay(video);
                                                    }
                                                }));

                                    } else {
                                        appendPlaybackDiagnostic("request desired stream", null);
                                        compositeDisposable.add(
                                                YouTubeTasks.getDesiredStream(youTubeVideo,
                                                        new GetDesiredStreamListener() {
                                                            @Override
                                                            public void onGetDesiredStream(StreamInfo desiredStream, YouTubeVideo video) {
                                                                playResolvedStream(desiredStream, video);
                                                            }

                                                            @Override
                                                            public void onGetDesiredStreamError(Throwable throwable) {
                                                                Logger.e(YouTubePlayerV2Fragment.this, "Error during getting desired stream:" + throwable, throwable);
                                                                appendPlaybackDiagnostic("desired stream error", throwable);
                                                                if (shouldFallbackToLivePlayback(throwable)) {
                                                                    fragmentBinding.loadingVideoView.setVisibility(View.GONE);
                                                                    attemptInternalLivePlayback();
                                                                } else if (throwable != null) {
                                                                    videoPlaybackError(throwable.getMessage());
                                                                }
                                                            }
                                                        }).subscribe());
                                    }
                                } else {
                                    attemptInternalLivePlayback();
                                }
                            }
                        }));
    }

    private void playResolvedStream(@NonNull StreamInfo desiredStream, @NonNull YouTubeVideo video) {
        appendPlaybackDiagnostic("desired stream fetched", null);
        if (fragmentBinding == null || !isVisible()) {
            return;
        }
        fragmentBinding.loadingVideoView.setVisibility(View.GONE);
        if (video.isBilibili()) {
            playResolvedBilibiliStream(desiredStream, video);
            return;
        }
        StreamSelectionPolicy selectionPolicy = SkyTubeApp.getSettings()
                .getDesiredVideoResolution(false)
                .withAllowVideoOnly(true);
        StreamSelectionPolicy.StreamSelection selection = selectionPolicy.select(desiredStream);
        if (selection == null) {
            appendPlaybackDiagnostic("stream selection returned null", null);
            if (video.isBilibili()) {
                videoPlaybackError(selectionPolicy.getErrorMessage(getContext()));
                return;
            }
            sabrPreferredVideoItag = 137;
            if (tryStartSabrPlayback("no matching direct stream")) {
                setupInfoDisplay(video);
                return;
            }
            videoPlaybackError(selectionPolicy.getErrorMessage(getContext()));
            return;
        }

        selectableStreamInfo = desiredStream;
        selectableAudioTracks = selectionPolicy.getSelectableAudioTracks(desiredStream);
        selectedAudioStream = selection.getAudioStream();
        sabrPreferredVideoItag = selection.getVideoStream().getItag();
        Uri uri = selection.getVideoStreamUri();
        Uri audioUri = selection.getAudioStreamUri();
        if (selection.getVideoStream().isVideoOnly() && isIosMediaUrl(audioUri)) {
            Uri combinedStreamUri = findCombinedStreamUri(desiredStream);
            if (combinedStreamUri != null) {
                Logger.i(this, "Using stable combined stream as audio source for iOS high-resolution video");
                audioUri = combinedStreamUri;
                selectedAudioStream = null;
                selectableAudioTracks = Collections.emptyList();
            }
        }
        updateAudioTrackMenu();
        appendPlaybackDiagnostic("stream selected resolution=" + selection.getResolution()
                + " videoOnly=" + selection.getVideoStream().isVideoOnly()
                + " hasAudio=" + (audioUri != null) + " uri=" + uri, null);
        Logger.i(this, "Selected playback stream: resolution=%s videoOnly=%s separateAudio=%s",
                selection.getResolution(), selection.getVideoStream().isVideoOnly(),
                selection.getAudioStream() != null);
        stableFallbackVideoUri = selection.getVideoStream().isVideoOnly()
                ? findCombinedStreamUri(desiredStream) : null;
        stableFallbackStreamInfo = desiredStream;
        stableFallbackAttempted = false;
        iframeNativeReturnVideoUri = stableFallbackVideoUri != null
                ? stableFallbackVideoUri : uri;
        iframeNativeReturnAudioUri = stableFallbackVideoUri != null ? null : audioUri;
        iframeNativeReturnStreamInfo = desiredStream;
        String hlsUrl = desiredStream.getHlsUrl();
        // YouTube's HLS manifest chooses its own default audio and can ignore the AudioStream that
        // SkyTube selected. For multi-audio videos use the explicit video+audio pair so Chinese
        // preference is effective from the first frame instead of only after a manual track switch.
        boolean hasExplicitAudioChoice = selectedAudioStream != null
                && selectableAudioTracks.size() > 1;
        if (isHttpUrl(hlsUrl) && !hasExplicitAudioChoice) {
            hlsFallbackVideoUri = uri;
            hlsFallbackAudioUri = audioUri;
            hlsFallbackStreamInfo = desiredStream;
            hlsFallbackAttempted = false;
            Logger.i(this, ">> PLAYING HLS (Android fallback=%s)", uri);
            playVideo(Uri.parse(hlsUrl), null, desiredStream);
        } else {
            hlsFallbackVideoUri = null;
            hlsFallbackAudioUri = null;
            hlsFallbackStreamInfo = null;
            Logger.i(this, ">> PLAYING: %s, audio: %s track=%s locale=%s",
                    uri, audioUri,
                    selectedAudioStream != null ? selectedAudioStream.getAudioTrackName() : null,
                    selectedAudioStream != null ? selectedAudioStream.getAudioLocale() : null);
            playVideo(uri, audioUri, desiredStream);
        }
        if (stableFallbackVideoUri != null) {
            scheduleHighResolutionStartupTimeout();
        } else {
            cancelHighResolutionStartupTimeout();
        }
        setupInfoDisplay(video);
    }

    private void scheduleHighResolutionStartupTimeout() {
        cancelHighResolutionStartupTimeout();
        highResolutionStartupTimeout = () -> {
            if (player == null || stableFallbackVideoUri == null || stableFallbackAttempted
                    || player.getPlaybackState() == Player.STATE_READY) {
                return;
            }
            fallbackToStableStream("startup timeout");
        };
        recommendationHandler.postDelayed(
                highResolutionStartupTimeout,
                HIGH_RESOLUTION_STARTUP_TIMEOUT_MS);
    }

    private void cancelHighResolutionStartupTimeout() {
        if (highResolutionStartupTimeout != null) {
            recommendationHandler.removeCallbacks(highResolutionStartupTimeout);
            highResolutionStartupTimeout = null;
        }
    }

    private static boolean isHttp403(@Nullable Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException
                    && ((HttpDataSource.InvalidResponseCodeException) current).responseCode == 403) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean tryStartSabrPlayback(@NonNull String reason) {
        if (sabrPlaybackActive
                || sabrPlaybackAttempted
                || fragmentBinding == null
                || youTubeVideo == null
                || youTubeVideo.isBilibili()
                || youTubeVideo.isLiveStream()
                || youTubeVideo.getId() == null) {
            return false;
        }

        sabrPlaybackAttempted = true;
        sabrPlaybackActive = true;
        cancelHighResolutionStartupTimeout();
        cancelSabrStartupTimeout();
        hideTvRecommendations();

        final long position = getActivePlaybackPosition();
        if (iframePlaybackActive) {
            stopYouTubeIFramePlayback();
        }
        if (player != null) {
            player.setPlayWhenReady(false);
            player.stop();
        }

        ensureSabrPlayer();
        fragmentBinding.sabrPlayerFrame.setVisibility(View.VISIBLE);
        enableSabrControlOverlay();
        fragmentBinding.loadingVideoView.setVisibility(View.VISIBLE);
        appendPlaybackDiagnostic("SABR fallback reason=" + reason
                + " preferredItag=" + sabrPreferredVideoItag
                + " startPositionMs=" + position, null);
        Logger.i(this, "Starting isolated SABR backend reason=%s itag=%d positionMs=%d",
                reason, sabrPreferredVideoItag, position);
        sabrPlayer.start(youTubeVideo.getId(), sabrPreferredVideoItag, position);

        sabrStartupTimeout = () -> {
            if (sabrPlaybackActive) {
                fallbackFromSabr(new java.util.concurrent.TimeoutException(
                        "SABR startup timed out after " + SABR_STARTUP_TIMEOUT_MS + " ms"));
            }
        };
        recommendationHandler.postDelayed(sabrStartupTimeout, SABR_STARTUP_TIMEOUT_MS);
        return true;
    }

    private void ensureSabrPlayer() {
        if (sabrPlayer != null) {
            return;
        }
        sabrPlayer = new SabrPlaybackController(
                requireContext(),
                fragmentBinding.sabrPlayerFrame,
                fragmentBinding.sabrSurfaceView,
                new SabrPlaybackController.Listener() {
                    @Override
                    public void onFormatSelected(int videoItag, int videoHeight, int audioItag) {
                        appendPlaybackDiagnostic("SABR format selected videoItag=" + videoItag
                                + " height=" + videoHeight + " audioItag=" + audioItag, null);
                        Logger.i(YouTubePlayerV2Fragment.this,
                                "SABR selected videoItag=%d height=%d audioItag=%d",
                                videoItag, videoHeight, audioItag);
                    }

                    @Override
                    public void onReady() {
                        if (!sabrPlaybackActive || fragmentBinding == null) {
                            return;
                        }
                        cancelSabrStartupTimeout();
                        fragmentBinding.loadingVideoView.setVisibility(View.GONE);
                        updateSabrControls();
                        appendPlaybackDiagnostic("SABR ready", null);
                    }

                    @Override
                    public void onPlayingChanged(boolean playing) {
                        if (!sabrPlaybackActive) {
                            return;
                        }
                        videoIsPlaying = playing;
                        preventDeviceSleeping(playing);
                        recommendationHandler.removeCallbacks(watchedProgressCheck);
                        if (playing) {
                            recordCurrentPlayback();
                            recommendationHandler.postDelayed(watchedProgressCheck, 10_000L);
                            if (!waitingToAutoPlay) {
                                hideTvRecommendations();
                            }
                            if (playbackStateListener != null) {
                                playbackStateListener.started();
                            }
                        } else {
                            if (sabrPlayer != null && !sabrPlayer.isReady()) {
                                return;
                            }
                            watchedProgressCheck.run();
                            if (playbackStateListener != null) {
                                playbackStateListener.paused();
                            }
                            if (fragmentBinding != null && isVisible()) {
                                showTvRecommendationsForPause();
                            }
                        }
                    }

                    @Override
                    public void onEnded() {
                        if (!sabrPlaybackActive) {
                            return;
                        }
                        videoIsPlaying = false;
                        preventDeviceSleeping(false);
                        if (playbackStateListener != null) {
                            playbackStateListener.ended();
                        }
                        beginRecommendedAutoPlay();
                    }

                    @Override
                    public void onError(@NonNull Throwable error) {
                        if (!sabrPlaybackActive) {
                            return;
                        }
                        fallbackFromSabr(error);
                    }
                });
    }

    private void cancelSabrStartupTimeout() {
        if (sabrStartupTimeout != null) {
            recommendationHandler.removeCallbacks(sabrStartupTimeout);
            sabrStartupTimeout = null;
        }
    }

    private void fallbackFromSabr(@NonNull Throwable error) {
        final long position = sabrPlayer != null ? sabrPlayer.getCurrentPosition() : 0L;
        Logger.e(this, "SABR playback failed at %d ms: %s", position, error);
        appendPlaybackDiagnostic("SABR failed positionMs=" + position, error);
        stopSabrPlayback();
        if (stableFallbackVideoUri != null && !stableFallbackAttempted) {
            fallbackToStableStream("after SABR failure", position);
            return;
        }
        videoPlaybackError(error.getMessage() != null
                ? error.getMessage() : "SABR playback failed");
    }

    private void stopSabrPlayback() {
        cancelSabrStartupTimeout();
        recommendationHandler.removeCallbacks(sabrControlsProgressUpdater);
        sabrPlaybackActive = false;
        videoIsPlaying = false;
        float sabrSpeed = sabrPlayer != null ? sabrPlayer.getPlaybackSpeed()
                : SkyTubeApp.getSettings().getDefaultPlaybackSpeed();
        if (sabrPlayer != null) {
            sabrPlayer.stop();
        }
        if (playbackSpeedController != null) {
            playbackSpeedController.setAlternatePlaybackTarget(null);
            playbackSpeedController.setPlaybackSpeed(sabrSpeed);
        }
        if (fragmentBinding != null) {
            fragmentBinding.sabrPlayerFrame.setVisibility(View.GONE);
            restoreLegacyPlayerLayer();
            fragmentBinding.playerView.requestFocus();
        }
    }

    private void playResolvedBilibiliStream(@NonNull StreamInfo streamInfo,
                                            @NonNull YouTubeVideo video) {
        bilibiliStreamInfo = streamInfo;
        bilibiliSourceIndex = 0;
        bilibiliStallRetryCount = 0;
        selectableStreamInfo = streamInfo;
        selectableAudioTracks = Collections.emptyList();
        selectedAudioStream = null;
        updateAudioTrackMenu();
        if (!selectAndStartBilibiliStream(Math.max(0L, playerInitialPosition), true)) {
            videoPlaybackError(getString(R.string.bilibili_stream_not_found));
            return;
        }
        setupInfoDisplay(video);
    }

    private boolean selectAndStartBilibiliStream(long positionMs, boolean playWhenReady) {
        if (bilibiliStreamInfo == null) {
            return false;
        }
        VideoStream selectedVideo = selectBilibiliVideoStream(bilibiliStreamInfo);
        AudioStream selectedAudio = selectBilibiliAudioStream(bilibiliStreamInfo);
        if (selectedVideo == null || selectedAudio == null) {
            return false;
        }
        bilibiliVideoStream = selectedVideo;
        bilibiliAudioStream = selectedAudio;
        int sourceCount = getBilibiliSourceCount();
        bilibiliSourceIndex = Math.max(0, Math.min(bilibiliSourceIndex, sourceCount - 1));
        updateBilibiliPlaybackMenu();
        appendPlaybackDiagnostic("Bilibili Media3 selected resolution="
                + selectedVideo.getResolution() + " codec=" + selectedVideo.getCodec()
                + " source=" + (bilibiliSourceIndex + 1) + "/" + sourceCount, null);
        Logger.i(this, "Starting Bilibili Media3 resolution=%s codec=%s source=%d/%d",
                selectedVideo.getResolution(), selectedVideo.getCodec(),
                bilibiliSourceIndex + 1, sourceCount);
        startBilibiliPlayback(selectedVideo, selectedAudio,
                bilibiliStreamInfo.getDuration(), positionMs, playWhenReady);
        return true;
    }

    @Nullable
    private VideoStream selectBilibiliVideoStream(@NonNull StreamInfo streamInfo) {
        List<VideoStream> streams = new ArrayList<>(streamInfo.getVideoOnlyStreams());
        streams.addAll(streamInfo.getVideoStreams());
        if (streams.isEmpty()) {
            return null;
        }
        int targetHeight = SkyTubeApp.getPreferenceManager().getInt(
                BILIBILI_PREF_HEIGHT, BILIBILI_DEFAULT_HEIGHT);
        String preferredCodec = SkyTubeApp.getPreferenceManager().getString(
                BILIBILI_PREF_CODEC, "avc1");
        VideoStream best = chooseBilibiliVideo(streams, targetHeight, preferredCodec);
        return best != null ? best : chooseBilibiliVideo(streams, targetHeight, null);
    }

    @Nullable
    private static VideoStream chooseBilibiliVideo(@NonNull List<VideoStream> streams,
                                                   int targetHeight,
                                                   @Nullable String preferredCodec) {
        VideoStream bestBelow = null;
        VideoStream bestAbove = null;
        for (VideoStream stream : streams) {
            if (!stream.isUrl() || stream.getHeight() <= 0
                    || (preferredCodec != null
                    && !preferredCodec.equals(bilibiliCodecFamily(stream.getCodec())))) {
                continue;
            }
            int height = stream.getHeight();
            if (height <= targetHeight
                    && (bestBelow == null || height > bestBelow.getHeight()
                    || (height == bestBelow.getHeight()
                    && stream.getBitrate() > bestBelow.getBitrate()))) {
                bestBelow = stream;
            } else if (height > targetHeight
                    && (bestAbove == null || height < bestAbove.getHeight()
                    || (height == bestAbove.getHeight()
                    && stream.getBitrate() < bestAbove.getBitrate()))) {
                bestAbove = stream;
            }
        }
        return bestBelow != null ? bestBelow : bestAbove;
    }

    @Nullable
    private static AudioStream selectBilibiliAudioStream(@NonNull StreamInfo streamInfo) {
        AudioStream best = null;
        for (AudioStream stream : streamInfo.getAudioStreams()) {
            if (stream.isUrl() && (best == null
                    || stream.getAverageBitrate() > best.getAverageBitrate())) {
                best = stream;
            }
        }
        return best;
    }

    @NonNull
    private static String bilibiliCodecFamily(@Nullable String codec) {
        String normalized = codec != null ? codec.toLowerCase(Locale.ROOT) : "";
        return normalized.startsWith("hev1") || normalized.startsWith("hvc1")
                || normalized.startsWith("hevc") ? "hvc1" : "avc1";
    }

    private int getBilibiliSourceCount() {
        if (bilibiliVideoStream == null) {
            return 1;
        }
        int videoCount = BilibiliService.get().getMediaUrlVariants(
                bilibiliVideoStream.getContent()).size();
        int audioCount = bilibiliAudioStream != null
                ? BilibiliService.get().getMediaUrlVariants(
                bilibiliAudioStream.getContent()).size() : 1;
        return Math.max(1, Math.max(videoCount, audioCount));
    }

    @NonNull
    private static String getBilibiliSourceUrl(@NonNull String primaryUrl, int sourceIndex) {
        List<String> urls = BilibiliService.get().getMediaUrlVariants(primaryUrl);
        return urls.get(Math.min(Math.max(0, sourceIndex), urls.size() - 1));
    }

    private void startBilibiliPlayback(@NonNull VideoStream videoStream,
                                       @Nullable AudioStream audioStream,
                                       long durationSeconds,
                                       long positionMs,
                                       boolean playWhenReady) {
        if (fragmentBinding == null) {
            return;
        }
        if (sabrPlaybackActive) {
            stopSabrPlayback();
        }
        if (bilibiliPlaybackActive) {
            stopBilibiliPlayback();
        }
        bilibiliPlaybackActive = true;
        bilibiliPlaybackStarting = true;
        updateBilibiliPlaybackMenu();
        hideTvRecommendations();
        if (player != null) {
            player.setPlayWhenReady(false);
            player.stop();
        }

        ensureBilibiliPlayer();
        fragmentBinding.sabrPlayerFrame.setVisibility(View.VISIBLE);
        enableSabrControlOverlay();
        fragmentBinding.loadingVideoView.setVisibility(View.VISIBLE);
        long startPosition = Math.max(0L, positionMs);
        String videoUrl = getBilibiliSourceUrl(videoStream.getContent(), bilibiliSourceIndex);
        String audioUrl = audioStream != null
                ? getBilibiliSourceUrl(audioStream.getContent(), bilibiliSourceIndex) : null;
        appendPlaybackDiagnostic("Bilibili Media3 DASH start positionMs=" + startPosition
                + " source=" + (bilibiliSourceIndex + 1) + "/" + getBilibiliSourceCount()
                + " videoHost=" + Uri.parse(videoUrl).getHost()
                + " audioHost=" + (audioUrl != null ? Uri.parse(audioUrl).getHost() : "none")
                + " videoInit=" + videoStream.getInitStart() + "-" + videoStream.getInitEnd()
                + " videoIndex=" + videoStream.getIndexStart() + "-" + videoStream.getIndexEnd()
                + " audioInit=" + (audioStream != null
                ? audioStream.getInitStart() + "-" + audioStream.getInitEnd() : "none")
                + " audioIndex=" + (audioStream != null
                ? audioStream.getIndexStart() + "-" + audioStream.getIndexEnd() : "none"), null);
        try {
            bilibiliPlayer.start(videoStream, audioStream, videoUrl, audioUrl,
                    durationSeconds, startPosition, playWhenReady);
        } catch (java.io.IOException error) {
            appendPlaybackDiagnostic("Bilibili Media3 DASH manifest error", error);
            stopBilibiliPlayback();
            videoPlaybackError(error.getMessage() != null
                    ? error.getMessage() : "Unable to create Bilibili DASH source");
            return;
        }
        playNextVideoWhenReady = false;
    }

    private void ensureBilibiliPlayer() {
        if (bilibiliPlayer != null) {
            return;
        }
        bilibiliPlayer = new BilibiliPlaybackController(
                requireContext(),
                fragmentBinding.sabrPlayerFrame,
                fragmentBinding.sabrSurfaceView,
                new BilibiliPlaybackController.Listener() {
                    @Override
                    public void onReady() {
                        if (!bilibiliPlaybackActive || fragmentBinding == null) {
                            return;
                        }
                        bilibiliPlaybackStarting = false;
                        fragmentBinding.loadingVideoView.setVisibility(View.GONE);
                        updateSabrControls();
                        updateBilibiliPlaybackMenu();
                        appendPlaybackDiagnostic("Bilibili Media3 ready", null);
                    }

                    @Override
                    public void onPlayingChanged(boolean playing) {
                        if (!bilibiliPlaybackActive || bilibiliPlaybackStarting) {
                            return;
                        }
                        videoIsPlaying = playing;
                        updateSabrControls();
                        preventDeviceSleeping(playing);
                        recommendationHandler.removeCallbacks(watchedProgressCheck);
                        if (playing) {
                            recordCurrentPlayback();
                            recommendationHandler.postDelayed(watchedProgressCheck, 10_000L);
                            if (!waitingToAutoPlay) {
                                hideTvRecommendations();
                            }
                            if (playbackStateListener != null) {
                                playbackStateListener.started();
                            }
                        } else if (bilibiliPlayer != null && bilibiliPlayer.isReady()) {
                            watchedProgressCheck.run();
                            if (playbackStateListener != null) {
                                playbackStateListener.paused();
                            }
                            if (fragmentBinding != null && isVisible()) {
                                showTvRecommendationsForPause();
                            }
                        }
                    }

                    @Override
                    public void onEnded() {
                        if (!bilibiliPlaybackActive) {
                            return;
                        }
                        videoIsPlaying = false;
                        preventDeviceSleeping(false);
                        if (playbackStateListener != null) {
                            playbackStateListener.ended();
                        }
                        if (!tryPlayNextBilibiliEpisode()) {
                            beginRecommendedAutoPlay();
                        }
                    }

                    @Override
                    public void onStalled(long positionMs) {
                        if (!bilibiliPlaybackActive) {
                            return;
                        }
                        recommendationHandler.post(() -> retryBilibiliPlayback(
                                "buffering timeout", positionMs, null));
                    }

                    @Override
                    public void onError(@NonNull Throwable error) {
                        if (!bilibiliPlaybackActive) {
                            return;
                        }
                        long position = bilibiliPlayer != null
                                ? bilibiliPlayer.getCurrentPosition() : 0L;
                        Logger.e(YouTubePlayerV2Fragment.this,
                                "Bilibili Media3 playback failed at %d ms: %s", position, error);
                        appendPlaybackDiagnostic(
                                "Bilibili Media3 failed positionMs=" + position, error);
                        recommendationHandler.post(() -> retryBilibiliPlayback(
                                "player error", position, error));
                    }
                });
    }

    private void retryBilibiliPlayback(@NonNull String reason, long positionMs,
                                       @Nullable Throwable error) {
        if (!bilibiliPlaybackActive || bilibiliVideoStream == null
                || bilibiliStreamInfo == null) {
            return;
        }
        if (bilibiliStallRetryCount >= BILIBILI_MAX_STALL_RETRIES) {
            stopBilibiliPlayback();
            videoPlaybackError(error != null && error.getMessage() != null
                    ? error.getMessage() : getString(R.string.bilibili_playback_stalled));
            return;
        }
        bilibiliStallRetryCount++;
        int sourceCount = getBilibiliSourceCount();
        bilibiliSourceIndex = (bilibiliSourceIndex + 1) % sourceCount;
        appendPlaybackDiagnostic("Bilibili retry reason=" + reason
                + " retry=" + bilibiliStallRetryCount + "/" + BILIBILI_MAX_STALL_RETRIES
                + " source=" + (bilibiliSourceIndex + 1) + "/" + sourceCount
                + " positionMs=" + positionMs, error);
        Toast.makeText(getContext(), getString(R.string.bilibili_switching_source,
                bilibiliSourceIndex + 1, sourceCount), Toast.LENGTH_SHORT).show();
        startBilibiliPlayback(bilibiliVideoStream, bilibiliAudioStream,
                bilibiliStreamInfo.getDuration(), positionMs, true);
    }

    private void stopBilibiliPlayback() {
        recommendationHandler.removeCallbacks(sabrControlsProgressUpdater);
        bilibiliPlaybackActive = false;
        bilibiliPlaybackStarting = false;
        videoIsPlaying = false;
        float speed = bilibiliPlayer != null
                ? bilibiliPlayer.getPlaybackSpeed()
                : SkyTubeApp.getSettings().getDefaultPlaybackSpeed();
        if (bilibiliPlayer != null) {
            bilibiliPlayer.stop();
        }
        if (playbackSpeedController != null) {
            playbackSpeedController.setAlternatePlaybackTarget(null);
            playbackSpeedController.setPlaybackSpeed(speed);
        }
        if (fragmentBinding != null) {
            fragmentBinding.sabrPlayerFrame.setVisibility(View.GONE);
            restoreLegacyPlayerLayer();
            fragmentBinding.playerView.requestFocus();
        }
        updateBilibiliPlaybackMenu();
    }

    private boolean isAlternatePlaybackActive() {
        return sabrPlaybackActive || bilibiliPlaybackActive;
    }

    private boolean isAlternatePlaybackReady() {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            return bilibiliPlayer.isReady();
        }
        return sabrPlaybackActive && sabrPlayer != null && sabrPlayer.isReady();
    }

    private long getAlternateBufferedPosition() {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            return bilibiliPlayer.getBufferedPosition();
        }
        return sabrPlayer != null ? sabrPlayer.getBufferedPosition() : 0L;
    }

    private float getAlternatePlaybackSpeed() {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            return bilibiliPlayer.getPlaybackSpeed();
        }
        return sabrPlayer != null ? sabrPlayer.getPlaybackSpeed() : 1f;
    }

    private boolean getAlternatePlayWhenReady() {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            return bilibiliPlayer.getPlayWhenReady();
        }
        return sabrPlayer != null && sabrPlayer.getPlayWhenReady();
    }

    private void setAlternatePlaybackSpeed(float speed) {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            bilibiliPlayer.setPlaybackSpeed(speed);
        } else if (sabrPlaybackActive && sabrPlayer != null) {
            sabrPlayer.setPlaybackSpeed(speed);
        }
    }

    private void enableSabrControlOverlay() {
        if (fragmentBinding == null || !isAlternatePlaybackActive()) {
            return;
        }
        float currentSpeed = playbackSpeedController != null
                ? playbackSpeedController.getPlaybackSpeed()
                : SkyTubeApp.getSettings().getDefaultPlaybackSpeed();
        setAlternatePlaybackSpeed(currentSpeed);
        if (playbackSpeedController != null) {
            playbackSpeedController.setAlternatePlaybackTarget(
                    new PlaybackSpeedController.AlternatePlaybackTarget() {
                        @Override
                        public float getPlaybackSpeed() {
                            return getAlternatePlaybackSpeed();
                        }

                        @Override
                        public void setPlaybackSpeed(float speed) {
                            setAlternatePlaybackSpeed(speed);
                        }
                    });
        }

        View legacyVideoSurface = fragmentBinding.playerView.getVideoSurfaceView();
        if (legacyVideoSurface != null) {
            legacyVideoSurface.setVisibility(View.INVISIBLE);
        }
        fragmentBinding.playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT);
        fragmentBinding.playerView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        fragmentBinding.sabrPlayerFrame.bringToFront();
        fragmentBinding.playerView.bringToFront();
        fragmentBinding.loadingVideoView.bringToFront();
        fragmentBinding.indicatorView.bringToFront();
        fragmentBinding.desDrawer.bringToFront();
        fragmentBinding.commentsDrawer.bringToFront();
        showTvControlsAndFocus();
        recommendationHandler.removeCallbacks(sabrControlsProgressUpdater);
        recommendationHandler.post(sabrControlsProgressUpdater);
    }

    public void showCleanTvPlayback() {
        if (fragmentBinding == null) {
            return;
        }
        pausePlaybackChoicesVisible = false;
        hideTvRecommendations();
        hideBilibiliEpisodes();
        View focused = getActivity() != null ? getActivity().getCurrentFocus() : null;
        if (focused != null && isDescendant(fragmentBinding.getRoot(), focused)) {
            focused.clearFocus();
        }
        fragmentBinding.playerView.hideController();
        fragmentBinding.playerView.setFocusable(true);
        fragmentBinding.playerView.setFocusableInTouchMode(true);
        fragmentBinding.playerView.requestFocus();
    }

    private void restoreLegacyPlayerLayer() {
        if (fragmentBinding == null) {
            return;
        }
        View legacyVideoSurface = fragmentBinding.playerView.getVideoSurfaceView();
        if (legacyVideoSurface != null) {
            legacyVideoSurface.setVisibility(View.VISIBLE);
        }
        fragmentBinding.playerView.setShutterBackgroundColor(android.graphics.Color.BLACK);
        fragmentBinding.playerView.setBackgroundColor(android.graphics.Color.BLACK);
    }

    private void updateSabrControls() {
        if (fragmentBinding == null || !isAlternatePlaybackActive()) {
            return;
        }
        long position = Math.max(0L, getActivePlaybackPosition());
        long duration = getActivePlaybackDuration();
        long buffered = Math.max(position, getAlternateBufferedPosition());

        TextView positionView = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_position);
        TextView durationView = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_duration);
        if (positionView != null) {
            positionView.setText(formatPlaybackTime(position));
        }
        if (durationView != null) {
            durationView.setText(formatPlaybackTime(duration));
        }

        View progressView = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_progress);
        if (progressView instanceof com.google.android.exoplayer2.ui.TimeBar) {
            com.google.android.exoplayer2.ui.TimeBar timeBar =
                    (com.google.android.exoplayer2.ui.TimeBar) progressView;
            timeBar.setDuration(duration > 0L ? duration : 0L);
            timeBar.setPosition(position);
            timeBar.setBufferedPosition(duration > 0L ? Math.min(buffered, duration) : buffered);
        }

        View play = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_play);
        View pause = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_pause);
        // Reflect the requested state immediately, including while Media3 is buffering.
        boolean playing = getAlternatePlayWhenReady();
        if (play != null) {
            play.setVisibility(playing ? View.GONE : View.VISIBLE);
        }
        if (pause != null) {
            pause.setVisibility(playing ? View.VISIBLE : View.GONE);
        }
    }

    private String formatPlaybackTime(long timeMs) {
        if (timeMs == C.TIME_UNSET || timeMs < 0L) {
            return "--:--";
        }
        long totalSeconds = timeMs / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0L
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    private boolean tryStartYouTubeIFramePlayback(@NonNull String reason) {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q
                || iframePlaybackActive
                || iframePlaybackAttempted
                || fragmentBinding == null
                || youTubeVideo == null
                || youTubeVideo.getId() == null) {
            return false;
        }

        iframePlaybackAttempted = true;
        iframePlaybackActive = true;
        cancelHighResolutionStartupTimeout();
        cancelIFrameStartupTimeout();
        hideTvRecommendations();

        long position = player != null ? Math.max(0L, player.getCurrentPosition()) : 0L;
        if (player != null) {
            player.setPlayWhenReady(false);
            player.stop();
        }

        ensureIFramePlayer();
        fragmentBinding.playerView.setVisibility(View.GONE);
        fragmentBinding.youtubeIframePlayer.setVisibility(View.VISIBLE);
        fragmentBinding.youtubeIframePlayer.requestFocus();
        fragmentBinding.loadingVideoView.setVisibility(View.VISIBLE);
        appendPlaybackDiagnostic("YouTube IFrame fallback reason=" + reason
                + " startPositionMs=" + position, null);
        Logger.i(this, "Starting YouTube IFrame fallback reason=%s positionMs=%d",
                reason, position);
        iframePlayer.load(youTubeVideo.getId(), position);

        iframeStartupTimeout = () -> {
            if (iframePlaybackActive) {
                Logger.e(YouTubePlayerV2Fragment.this,
                        "YouTube IFrame startup timed out after %d ms",
                        IFRAME_STARTUP_TIMEOUT_MS);
                fallbackFromYouTubeIFrame(-2);
            }
        };
        recommendationHandler.postDelayed(iframeStartupTimeout, IFRAME_STARTUP_TIMEOUT_MS);
        return true;
    }

    private void ensureIFramePlayer() {
        if (iframePlayer != null) {
            return;
        }
        iframePlayer = new YouTubeIFramePlayerController(
                fragmentBinding.youtubeIframePlayer,
                new YouTubeIFramePlayerController.Listener() {
                    @Override
                    public void onReady() {
                        if (!iframePlaybackActive || fragmentBinding == null) {
                            return;
                        }
                        cancelIFrameStartupTimeout();
                        fragmentBinding.loadingVideoView.setVisibility(View.GONE);
                        Logger.i(YouTubePlayerV2Fragment.this,
                                "YouTube IFrame player ready");
                        appendPlaybackDiagnostic("YouTube IFrame ready", null);
                        Toast.makeText(getContext(),
                                R.string.youtube_web_player_started,
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onStateChanged(int state) {
                        if (!iframePlaybackActive) {
                            return;
                        }
                        Logger.i(YouTubePlayerV2Fragment.this,
                                "YouTube IFrame state=%d", state);
                        videoIsPlaying = state == 1;
                        if (videoIsPlaying) {
                            recordCurrentPlayback();
                        }
                        preventDeviceSleeping(videoIsPlaying);
                        if (playbackStateListener != null) {
                            if (state == 1) {
                                playbackStateListener.started();
                            } else if (state == 2) {
                                playbackStateListener.paused();
                            } else if (state == 0) {
                                playbackStateListener.ended();
                            }
                        }
                        if (state == 0) {
                            beginRecommendedAutoPlay();
                        }
                    }

                    @Override
                    public void onQualityChanged(@NonNull String quality) {
                        Logger.i(YouTubePlayerV2Fragment.this,
                                "YouTube IFrame quality=%s", quality);
                        appendPlaybackDiagnostic(
                                "YouTube IFrame quality=" + quality, null);
                    }

                    @Override
                    public void onError(int errorCode) {
                        fallbackFromYouTubeIFrame(errorCode);
                    }
                });
    }

    private void cancelIFrameStartupTimeout() {
        if (iframeStartupTimeout != null) {
            recommendationHandler.removeCallbacks(iframeStartupTimeout);
            iframeStartupTimeout = null;
        }
    }

    private void fallbackFromYouTubeIFrame(int errorCode) {
        if (!iframePlaybackActive || fragmentBinding == null) {
            return;
        }
        long position = iframePlayer != null ? iframePlayer.getCurrentPositionMs() : 0L;
        Logger.e(this, "YouTube IFrame failed error=%d positionMs=%d",
                errorCode, position);
        appendPlaybackDiagnostic("YouTube IFrame failed error=" + errorCode
                + " positionMs=" + position, null);
        stopYouTubeIFramePlayback();

        if (iframeNativeReturnVideoUri == null) {
            videoPlaybackError("YouTube web player failed with error " + errorCode);
            return;
        }
        stableFallbackAttempted = stableFallbackVideoUri != null;
        datasourceBuilder.play(
                iframeNativeReturnVideoUri,
                iframeNativeReturnAudioUri,
                iframeNativeReturnStreamInfo);
        player.seekTo(position);
        player.setPlayWhenReady(true);
        fragmentBinding.loadingVideoView.setVisibility(View.GONE);
        Toast.makeText(getContext(),
                R.string.youtube_web_player_failed,
                Toast.LENGTH_LONG).show();
    }

    private void stopYouTubeIFramePlayback() {
        cancelIFrameStartupTimeout();
        iframePlaybackActive = false;
        videoIsPlaying = false;
        if (iframePlayer != null) {
            iframePlayer.stop();
        }
        if (fragmentBinding != null) {
            fragmentBinding.youtubeIframePlayer.setVisibility(View.GONE);
            fragmentBinding.playerView.setVisibility(View.VISIBLE);
            fragmentBinding.playerView.requestFocus();
        }
    }

    private void fallbackToStableStream(@NonNull String reason) {
        fallbackToStableStream(reason, player != null
                ? Math.max(0L, player.getCurrentPosition()) : 0L);
    }

    private void fallbackToStableStream(@NonNull String reason, long positionMs) {
        if (player == null || stableFallbackVideoUri == null || stableFallbackAttempted) {
            return;
        }
        cancelHighResolutionStartupTimeout();
        stableFallbackAttempted = true;
        Logger.i(this,
                "High-resolution playback %s; falling back to stable stream: %s",
                reason, stableFallbackVideoUri);
        player.stop();
        datasourceBuilder.play(
                stableFallbackVideoUri,
                null,
                stableFallbackStreamInfo);
        if (positionMs > 0L) {
            player.seekTo(positionMs);
        }
        player.setPlayWhenReady(true);
    }

    @Nullable
    private static Uri findCombinedStreamUri(@NonNull StreamInfo streamInfo) {
        for (VideoStream stream : streamInfo.getVideoStreams()) {
            if (!stream.isVideoOnly() && stream.isUrl() && isHttpUrl(stream.getContent())) {
                return Uri.parse(stream.getContent());
            }
        }
        return null;
    }

    private static boolean isIosMediaUrl(@Nullable Uri uri) {
        return uri != null && YoutubeParsingHelper.isIosStreamingUrl(uri.toString());
    }

    private static boolean isHttpUrl(@Nullable String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    private void videoPlaybackError(String errorMessage) {
        appendPlaybackDiagnostic("videoPlaybackError message=" + errorMessage, null);
        Context ctx = getContext();
        if (ctx == null) {
            Logger.e(YouTubePlayerV2Fragment.this, "Error during getting stream: %s", errorMessage);
            return;
        }
        new SkyTubeMaterialDialog(ctx)
                .content(errorMessage)
                .title(R.string.error_video_play)
                .cancelable(false)
                .onPositive((dialog, which) -> closeActivity())
                .show();

    }

    private void openAsLiveStream() {
        // else, if the video is a LIVE STREAM
        // video is live:  ask the user if he wants to play the video using an other app
        Context ctx = getContext();
        if (ctx != null) {
            new SkyTubeMaterialDialog(ctx)
                    .onNegativeOrCancel((dialog) -> closeActivity())
                    .content(R.string.warning_live_video)
                    .title(R.string.error_video_play)
                    .onPositive((dialog, which) -> {
                        youTubeVideo.playVideoExternally(getContext())
                                .subscribe(status -> closeActivity());
                    })
                    .show();
        }
    }

    private void attemptInternalLivePlayback() {
        Context ctx = getContext();
        if (ctx == null || youTubeVideo == null) {
            appendPlaybackDiagnostic("attemptInternalLivePlayback missing context/video", null);
            openAsLiveStream();
            return;
        }
        appendPlaybackDiagnostic("attemptInternalLivePlayback start", null);
        fragmentBinding.loadingVideoView.setVisibility(View.VISIBLE);
        compositeDisposable.add(
                Single.fromCallable(() -> NewPipeService.get().getLiveStreamUrlsByVideoId(youTubeVideo.getId()))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(urls -> {
                            fragmentBinding.loadingVideoView.setVisibility(View.GONE);
                            Uri uri = Uri.parse(urls.getPreferredUrl());
                            appendPlaybackDiagnostic("attemptInternalLivePlayback success uri=" + uri, null);
                            Logger.i(this, ">> PLAYING LIVE INTERNALLY: %s", uri);
                            playVideo(uri, null, null);
                            setupInfoDisplay(youTubeVideo);
                        }, err -> {
                            Logger.e(this, "Unable to play live stream internally: " + err.getMessage(), err);
                            appendPlaybackDiagnostic("attemptInternalLivePlayback error", err);
                            fragmentBinding.loadingVideoView.setVisibility(View.GONE);
                            openAsLiveStream();
                        }));
    }

    private boolean shouldOpenAsLiveStream() {
        return youTubeVideo != null
                && youTubeVideo.isLiveStream();
    }

    private boolean shouldFallbackToLivePlayback(@Nullable Throwable throwable) {
        return shouldOpenAsLiveStream() || throwable instanceof StreamExtractException;
    }

    /**
     * Play video.
     *
     * @param videoUri   The Uri of the video that is going to be played.
     * @param audioUri   The Uri of the audio part that is going to be played. Can be null.
     * @param streamInfo Additional information about the stream.
     */
    private void playVideo(Uri videoUri, @Nullable Uri audioUri, @Nullable StreamInfo streamInfo) {
        appendPlaybackDiagnostic("playVideo uri=" + videoUri + " audioUri=" + audioUri, null);
        datasourceBuilder.play(videoUri, audioUri, streamInfo);
        if (playerInitialPosition > 0) {
            player.seekTo(playerInitialPosition);
        }
        if (playNextVideoWhenReady) {
            playNextVideoWhenReady = false;
            player.setPlayWhenReady(true);
            Logger.i(this, "Recommendation selected; new video will start playing immediately");
        }
    }

    private void appendPlaybackDiagnostic(@NonNull String stage, @Nullable Throwable throwable) {
        StringBuilder builder = new StringBuilder()
                .append(new java.util.Date()).append('\n')
                .append("scope=Playback").append('\n')
                .append("stage=").append(stage).append('\n');
        if (youTubeVideo != null) {
            builder.append("videoId=").append(youTubeVideo.getId()).append('\n')
                    .append("title=").append(youTubeVideo.getTitle()).append('\n')
                    .append("isLive=").append(youTubeVideo.isLiveStream()).append('\n');
        }
        if (throwable != null) {
            builder.append("error=").append(throwable.getClass().getName()).append(": ")
                    .append(throwable.getMessage()).append('\n');
        }
        builder.append("---\n");
        DiagnosticFileLogger.append(DiagnosticFileLogger.DEBUG_LOG_FILE_NAME, builder.toString());
    }

    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        DatabaseTasks.updateDownloadedVideoMenu(youTubeVideo, menu);
        final MenuItem subscribeChannel = menu.findItem(R.id.subscribe_channel);
        final MenuItem openChannel = menu.findItem(R.id.open_channel);
        if (youTubeVideo != null && youTubeVideo.getChannelId() != null) {
            if (subscribeChannel != null) {
                subscribeChannel.setVisible(true);
            }
            if (openChannel != null) {
                openChannel.setVisible(true);
            }
        } else {
            if (subscribeChannel != null) {
                subscribeChannel.setVisible(false);
            }
			if (openChannel != null) {
				openChannel.setVisible(false);
			}
		}
		updateAudioTrackMenu();
        updateBilibiliEpisodesMenu();
        updateBilibiliPlaybackMenu();
	}

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_youtube_player, menu);

		this.menu = menu;
        MenuItem webPlayerItem = menu.findItem(R.id.menu_use_youtube_web_player);
        if (webPlayerItem != null) {
            webPlayerItem.setVisible(Build.VERSION.SDK_INT == Build.VERSION_CODES.Q);
        }
		updateAudioTrackMenu();
        updateBilibiliEpisodesMenu();
        updateBilibiliPlaybackMenu();
        menu.findItem(R.id.disable_gestures).setChecked(playerViewGestureHandler.disableGestures);

        listener.onOptionsMenuCreated(menu);
        fragmentBinding.playerView.post(this::configureTvPlayerFocus);

        // Will now check if the video is bookmarked or not (and then update the menu accordingly).
        //
        // youTubeVideo might be null if we have only passed the video URL to this fragment (i.e.
        // the app is still trying to construct youTubeVideo in the background).
        if (youTubeVideo != null) {
            compositeDisposable.add(DatabaseTasks.isVideoBookmarked(youTubeVideo.getId(), menu));
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Context context = getContext();
        if (actionHandler.handleChannelActions(context, youTubeChannel, item.getItemId())) {
            return true;
        }
        switch (item.getItemId()) {
            case R.id.menu_reload_video:
                seekActivePlaybackTo(0L);
                return true;

            case R.id.menu_open_video_with:
                pause();
                compositeDisposable.add(youTubeVideo.playVideoExternally(context).subscribe());
                return true;

            case R.id.menu_use_youtube_web_player:
                return tryStartYouTubeIFramePlayback("manual menu");

            case R.id.share:
                pause();
                youTubeVideo.shareVideo(context);
                return true;

            case R.id.copyurl:
                youTubeVideo.copyUrl(context);
                return true;

            case R.id.bookmark_video:
                compositeDisposable.add(youTubeVideo.bookmarkVideo(context, menu).subscribe());
                return true;

            case R.id.unbookmark_video:
                compositeDisposable.add(youTubeVideo.unbookmarkVideo(context, menu).subscribe());
                return true;

            case R.id.view_thumbnail:
                Intent i = new Intent(getActivity(), ThumbnailViewerActivity.class);
                i.putExtra(ThumbnailViewerActivity.YOUTUBE_VIDEO, youTubeVideo);
                startActivity(i);
                return true;

            case R.id.download_video:
                final Policy decision = new MobileNetworkWarningDialog(context)
                        .showDownloadWarning(youTubeVideo);

                if (decision == Policy.ALLOW) {
                    youTubeVideo.downloadVideo(context).subscribe();
                }
                return true;
            case R.id.disable_gestures:
                boolean disableGestures = !item.isChecked();
                item.setChecked(disableGestures);
                SkyTubeApp.getSettings().setDisableGestures(disableGestures);
                playerViewGestureHandler.setDisableGestures(disableGestures);
                return true;
			case R.id.video_repeat_toggle:
				boolean repeat = !item.isChecked();
				player.setRepeatMode(repeat ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF);
				item.setChecked(repeat);
				return true;
			case R.id.menu_audio_track:
				showAudioTrackDialog();
				return true;
            case R.id.menu_bilibili_episodes:
                showBilibiliEpisodesForTv();
                return true;
            case R.id.menu_bilibili_quality:
                showBilibiliQualityDialog();
                return true;
            case R.id.menu_bilibili_codec:
                showBilibiliCodecDialog();
                return true;
            case R.id.menu_bilibili_source:
                showBilibiliSourceDialog();
                return true;
			default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Called when the options menu is closed.
     *
     * <p>The Navigation Bar is displayed when the Option Menu is visible.  Hence the objective of
     * this method is to hide the Navigation Bar once the Options Menu is hidden.</p>
     */
    public void onMenuClosed() {
        hideNavigationBar();
    }


    /**
     * Will asynchronously retrieve additional video information such as channel avatar ...etc
     */
    private void fetchVideoInformation() {
        if (youTubeVideo.isBilibili()) {
            youTubeChannel = youTubeVideo.getChannel();
            videoDescriptionBinding.videoDescSubscribeButton.setVisibility(View.GONE);
            if (youTubeChannel != null) {
                Glide.with(requireContext())
                        .load(youTubeChannel.getThumbnailUrl())
                        .apply(new RequestOptions().placeholder(
                                R.drawable.channel_thumbnail_default))
                        .into(videoDescriptionBinding.videoDescChannelThumbnailImageView);
            }
            return;
        }
        videoDescriptionBinding.videoDescSubscribeButton.setVisibility(View.VISIBLE);
        // get Channel info (e.g. avatar...etc) task
        compositeDisposable.add(
                DatabaseTasks.getChannelInfo(requireContext(), youTubeVideo.getChannelId(), false)
                        .subscribe(newPersistentChannel -> {
                            youTubeChannel = newPersistentChannel.channel();
                            videoDescriptionBinding.videoDescSubscribeButton.setChannelInfo(newPersistentChannel);

                            if (youTubeChannel != null) {
                                Glide.with(requireContext())
                                        .load(youTubeChannel.getThumbnailUrl())
                                        .apply(new RequestOptions().placeholder(R.drawable.channel_thumbnail_default))
                                        .into(videoDescriptionBinding.videoDescChannelThumbnailImageView);

                            }
                        })
        );

        if (SkyTubeApp.getSettings().isSponsorblockEnabled()) {
            compositeDisposable.add(
                    SBTasks.retrieveSponsorblockSegmentsCtx(requireContext(), youTubeVideo.getVideoId())
                            .subscribe(segments -> {
                                Log.d(TAG, "Received SB Info with " + segments.getSegments().size() + " segments for duration of " + segments.getVideoDuration());
                                sponsorBlockVideoInfo = segments;
                                initSponsorBlock();
                            }, Functions.ON_ERROR_MISSING, () -> {
                                Log.d(TAG, "No SB info received for " + youTubeVideo.getVideoId());
                            })
            );
        }
    }

    private long getActivePlaybackPosition() {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            return bilibiliPlayer.getCurrentPosition();
        }
        if (sabrPlaybackActive && sabrPlayer != null) {
            return sabrPlayer.getCurrentPosition();
        }
        if (iframePlaybackActive && iframePlayer != null) {
            return iframePlayer.getCurrentPositionMs();
        }
        return player != null ? Math.max(0L, player.getCurrentPosition()) : 0L;
    }

    private long getActivePlaybackDuration() {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            return bilibiliPlayer.getDuration();
        }
        if (sabrPlaybackActive && sabrPlayer != null) {
            return sabrPlayer.getDuration();
        }
        return player != null ? player.getDuration() : C.TIME_UNSET;
    }

    private boolean getActivePlayWhenReady() {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            return bilibiliPlayer.getPlayWhenReady();
        }
        if (sabrPlaybackActive && sabrPlayer != null) {
            return sabrPlayer.getPlayWhenReady();
        }
        if (iframePlaybackActive && iframePlayer != null) {
            return iframePlayer.isPlaying();
        }
        return player != null && player.getPlayWhenReady();
    }

    private void seekActivePlaybackTo(long positionMs) {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            bilibiliPlayer.seekTo(positionMs);
        } else if (sabrPlaybackActive && sabrPlayer != null) {
            sabrPlayer.seekTo(positionMs);
        } else if (iframePlaybackActive && iframePlayer != null) {
            iframePlayer.seekTo(positionMs);
        } else if (player != null) {
            player.seekTo(positionMs);
        }
    }

    @Override
    public void videoPlaybackStopped() {
        if (bilibiliPlaybackActive) {
            saveVideoPosition();
            stopBilibiliPlayback();
        } else if (sabrPlaybackActive) {
            saveVideoPosition();
            stopSabrPlayback();
        } else if (iframePlaybackActive) {
            saveVideoPosition();
            stopYouTubeIFramePlayback();
        } else {
            player.stop();
            saveVideoPosition();
        }
    }

    private void saveVideoPosition() {
        if (youTubeVideo == null) {
            return;
        }
        long position = getActivePlaybackPosition();
        compositeDisposable.add(
                PlaybackStatusDb.getPlaybackStatusDb()
                        .setVideoPositionInBackground(youTubeVideo, position));
    }

    private void recordCurrentPlayback() {
        if (playbackHistoryRecordedForCurrentVideo || youTubeVideo == null) {
            return;
        }
        playbackHistoryRecordedForCurrentVideo = true;
        compositeDisposable.add(PlaybackStatusDb.getPlaybackStatusDb()
                .recordPlaybackInBackground(youTubeVideo));
    }

    @Override
    public void onDestroy() {
        cancelRecommendationCountdown();
        cancelHighResolutionStartupTimeout();
        cancelIFrameStartupTimeout();
        cancelSabrStartupTimeout();
        recommendationHandler.removeCallbacks(watchedProgressCheck);
        if (recommendationPrefetchDisposable != null) {
            recommendationPrefetchDisposable.dispose();
            recommendationPrefetchDisposable = null;
        }
        compositeDisposable.clear();
        if (iframePlayer != null) {
            iframePlayer.destroy();
            iframePlayer = null;
        }
        if (sabrPlayer != null) {
            sabrPlaybackActive = false;
            sabrPlayer.release();
            sabrPlayer = null;
        }
        if (bilibiliPlayer != null) {
            bilibiliPlaybackActive = false;
            bilibiliPlayer.release();
            bilibiliPlayer = null;
        }
        super.onDestroy();
        // stop the player from playing (when this fragment is going to be destroyed) and clean up
        player.stop();
        player.release();
        player = null;
        fragmentBinding.playerView.setPlayer(null);
        videoDescriptionBinding.videoDescSubscribeButton.clearBackgroundTasks();
        fragmentBinding = null;
        videoDescriptionBinding = null;
    }

    @Override
    public boolean canNavigateTo(ContentId contentId) {
        if (contentId instanceof VideoId) {
            VideoId newVideoId = (VideoId) contentId;
            if (videoId.isSameContent(newVideoId)) {
                // same video, maybe different timestamp?
                Integer timestamp = newVideoId.getTimestamp();
                if (timestamp != null) {
                    seekActivePlaybackTo(timestamp.longValue() * 1000L);
                }
            } else {
                openVideo(newVideoId);
            }
            return true;
        }
        return false;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * This will handle any gesture swipe event performed by the user on the player view.
     */
    class PlayerViewGestureHandler extends PlayerViewGestureDetector {
        private boolean isControllerVisible = true;
        private VideoBrightness videoBrightness;
        private float startVolumePercent = -1.0f;
        private long startVideoTime = -1;

        /**
         * Enable/Disable video gestures based on user preferences.
         */
        private boolean disableGestures;

        private static final int MAX_VIDEO_STEP_TIME = 60 * 1000;

        PlayerViewGestureHandler(Settings settings) {
            super(getContext(), settings);

            this.disableGestures = settings.isDisableGestures();
            videoBrightness = new VideoBrightness(getActivity(), disableGestures);
        }

        void initView() {
            fragmentBinding.playerView.setControllerVisibilityListener(visibility -> {
                isControllerVisible = (visibility == View.VISIBLE);
                switch (visibility) {
                    case View.VISIBLE: {
                        showNavigationBar();
                        if (fragmentBinding != null) {
                            fragmentBinding.playerView.getOverlayFrameLayout().setVisibility(View.VISIBLE);
                        }
                        break;
                    }
                    case View.GONE: {
                        hideNavigationBar();
                        if (fragmentBinding != null) {
                            fragmentBinding.playerView.getOverlayFrameLayout().setVisibility(View.GONE);
                        }
                        break;
                    }
                }
            });
        }

        @Override
        public void onCommentsGesture() {
            if (SkyTubeApp.isConnected(requireContext())) {
                fragmentBinding.commentsDrawer.animateOpen();
            } else {
                Toast.makeText(requireContext(),
                        getString(R.string.error_get_comments_no_network),
                        Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onVideoDescriptionGesture() {
            fragmentBinding.desDrawer.animateOpen();
        }

        @Override
        public void onDoubleTap() {
            // if the user is playing a video...
            if (player.getPlayWhenReady()) {
                // pause video - without showing the controller automatically
                boolean controllerAutoshow = fragmentBinding.playerView.getControllerAutoShow();
                fragmentBinding.playerView.setControllerAutoShow(false);
                pause();
                fragmentBinding.playerView.setControllerAutoShow(controllerAutoshow);
            } else {
                // play video
                player.setPlayWhenReady(true);
                // This is to force that the automatic hiding of the controller is re-triggered.
                if (isControllerVisible) {
                    fragmentBinding.playerView.showController();
                }
            }
        }

        @Override
        public boolean onSingleTap() {
            return showOrHideHud();
        }

        /**
         * Hide or display the HUD depending if the HUD is currently visible or not.
         */
        private boolean showOrHideHud() {
            if (fragmentBinding.commentsDrawer.isOpened()) {
                fragmentBinding.commentsDrawer.animateClose();
                return !isControllerVisible;
            }

            if (fragmentBinding.desDrawer.isOpened()) {
                fragmentBinding.desDrawer.animateClose();
                return !isControllerVisible;
            }

            if (isControllerVisible) {
                fragmentBinding.playerView.hideController();
            } else {
                fragmentBinding.playerView.showController();
            }

            return false;
        }

        @Override
        public void onGestureDone() {
            videoBrightness.onGestureDone();
            startVolumePercent = -1.0f;
            startVideoTime = -1;
            hideIndicator();
        }

        @Override
        public void adjustBrightness(double adjustPercent) {
            if (disableGestures) {
                return;
            }

            // adjust the video's brightness
            videoBrightness.setVideoBrightness(adjustPercent, getActivity());

            // set indicator
            fragmentBinding.indicatorImageView.setImageResource(R.drawable.ic_brightness);
            fragmentBinding.indicatorTextView.setText(videoBrightness.getBrightnessString());

            // Show indicator. It will be hidden once onGestureDone will be called
            showIndicator();
        }


        @Override
        public void adjustVolumeLevel(double adjustPercent) {
            if (disableGestures) {
                return;
            }

            // We are setting volume percent to a value that should be from -1.0 to 1.0. We need to limit it here for these values first
            if (adjustPercent < -1.0f) {
                adjustPercent = -1.0f;
            } else if (adjustPercent > 1.0f) {
                adjustPercent = 1.0f;
            }

            AudioManager audioManager = ContextCompat.getSystemService(requireContext(), AudioManager.class);
            final int STREAM = AudioManager.STREAM_MUSIC;

            // Max volume will return INDEX of volume not the percent. For example, on my device it is 15
            int maxVolume = AudioManagerCompat.getStreamMaxVolume(audioManager, STREAM);
            if (maxVolume == 0) return;

            if (startVolumePercent < 0) {
                // We are getting actual volume index (NOT volume but index). It will be >= 0.
                int curVolume = audioManager.getStreamVolume(STREAM);
                // And counting percents of maximum volume we have now
                startVolumePercent = curVolume * 1.0f / maxVolume;
            }
            // Should be >= 0 and <= 1
            double targetPercent = startVolumePercent + adjustPercent;
            if (targetPercent > 1.0f) {
                targetPercent = 1.0f;
            } else if (targetPercent < 0) {
                targetPercent = 0;
            }

            // Calculating index. Test values are 15 * 0.12 = 1 ( because it's int)
            int index = (int) (maxVolume * targetPercent);
            if (index > maxVolume) {
                index = maxVolume;
            } else if (index < 0) {
                index = 0;
            }
            audioManager.setStreamVolume(STREAM, index, 0);

            fragmentBinding.indicatorImageView.setImageResource(R.drawable.ic_volume);
            fragmentBinding.indicatorTextView.setText(index * 100 / maxVolume + "%");

            // Show indicator. It will be hidden once onGestureDone will be called
            showIndicator();
        }

        @Override
        public void adjustVideoPosition(double adjustPercent, boolean forwardDirection) {
            if (disableGestures) {
                return;
            }

            long totalTime = player.getDuration();

            if (adjustPercent < -1.0f) {
                adjustPercent = -1.0f;
            } else if (adjustPercent > 1.0f) {
                adjustPercent = 1.0f;
            }

            if (startVideoTime < 0) {
                startVideoTime = player.getCurrentPosition();
            }
            // adjustPercent: value from -1 to 1.
            double positiveAdjustPercent = Math.max(adjustPercent, -adjustPercent);
            // End of line makes seek speed not linear
            long targetTime = startVideoTime + (long) (MAX_VIDEO_STEP_TIME * adjustPercent * (positiveAdjustPercent / 0.1));
            if (targetTime > totalTime) {
                targetTime = totalTime;
            }
            if (targetTime < 0) {
                targetTime = 0;
            }

            String targetTimeString = formatDuration(targetTime / 1000);

            if (forwardDirection) {
                fragmentBinding.indicatorImageView.setImageResource(R.drawable.ic_forward);
            } else {
                fragmentBinding.indicatorImageView.setImageResource(R.drawable.ic_rewind);
            }
            fragmentBinding.indicatorTextView.setText(targetTimeString);

            showIndicator();

            player.seekTo(targetTime);
        }


        @Override
        public Rect getPlayerViewRect() {
            return new Rect(fragmentBinding.playerView.getLeft(), fragmentBinding.playerView.getTop(),
                    fragmentBinding.playerView.getRight(), fragmentBinding.playerView.getBottom());
        }

        private void showIndicator() {
            fragmentBinding.indicatorView.setVisibility(View.VISIBLE);
        }

        private void hideIndicator() {
            fragmentBinding.indicatorView.setVisibility(View.GONE);
        }

        /**
         * Returns a (localized) string for the given duration (in seconds).
         *
         * @param duration
         * @return a (localized) string for the given duration (in seconds).
         */
        private String formatDuration(long duration) {
            long h = duration / 3600;
            long m = (duration - h * 3600) / 60;
            long s = duration - (h * 3600 + m * 60);
            String durationValue;

            if (h == 0) {
                durationValue = String.format(Locale.getDefault(), "%1$02d:%2$02d", m, s);
            } else {
                durationValue = String.format(Locale.getDefault(), "%1$d:%2$02d:%3$02d", h, m, s);
            }

            return durationValue;
        }

        public void setDisableGestures(boolean disableGestures) {
            this.disableGestures = disableGestures;
            this.videoBrightness.setDisableGestures(disableGestures);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////


    /**
     * Adjust video's brightness.  Once the brightness is adjust, it is saved in the preferences to
     * be used when a new video is played.
     */
    private static class VideoBrightness {

        /**
         * Current video brightness.
         */
        private float brightness;
        /**
         * Initial video brightness.
         */
        private float initialBrightness;
        private boolean disableGestures;

        private static final String BRIGHTNESS_LEVEL_PREF = SkyTubeApp.getStr(R.string.pref_key_brightness_level);


        /**
         * Constructor:  load the previously saved video brightness from the preference and set it.
         *
         * @param activity Activity.
         */
        public VideoBrightness(final Activity activity, final boolean disableGestures) {
            loadBrightnessFromPreference();
            initialBrightness = brightness;
            this.disableGestures = disableGestures;

            setVideoBrightness(0, activity);
        }

        public void setDisableGestures(boolean disableGestures) {
            this.disableGestures = disableGestures;
        }

        /**
         * Set the video brightness.  Once the video brightness is updated, save it in the preference.
         *
         * @param adjustPercent Percentage.
         * @param activity      Activity.
         */
        public void setVideoBrightness(double adjustPercent, final Activity activity) {
            if (disableGestures) {
                return;
            }

            // We are setting brightness percent to a value that should be from -1.0 to 1.0. We need to limit it here for these values first
            if (adjustPercent < -1.0f) {
                adjustPercent = -1.0f;
            } else if (adjustPercent > 1.0f) {
                adjustPercent = 1.0f;
            }

            // set the brightness instance variable
            setBrightness(initialBrightness + (float) adjustPercent);
            // adjust the video brightness as per this.brightness
            adjustVideoBrightness(activity);
            // save brightness to the preference
            saveBrightnessToPreference();
        }


        /**
         * Adjust the video brightness.
         *
         * @param activity Current activity.
         */
        private void adjustVideoBrightness(final Activity activity) {
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            lp.screenBrightness = brightness;
            activity.getWindow().setAttributes(lp);
        }


        /**
         * Saves {@link #brightness} to preference.
         */
        private void saveBrightnessToPreference() {
            SharedPreferences.Editor editor = SkyTubeApp.getPreferenceManager().edit();
            editor.putFloat(BRIGHTNESS_LEVEL_PREF, brightness);
            editor.apply();
            Logger.d(this, "BRIGHTNESS: %f", brightness);
        }


        /**
         * Loads the brightness from preference and set the {@link #brightness} instance variable.
         */
        private void loadBrightnessFromPreference() {
            final float brightnessPref = SkyTubeApp.getPreferenceManager().getFloat(BRIGHTNESS_LEVEL_PREF, 1);
            setBrightness(brightnessPref);
        }


        /**
         * Set the {@link #brightness} instance variable.
         *
         * @param brightness Brightness (from 0.0 to 1.0).
         */
        private void setBrightness(float brightness) {
            if (brightness < 0) {
                brightness = 0;
            } else if (brightness > 1) {
                brightness = 1;
            }

            this.brightness = brightness;
        }


        /**
         * @return Brightness as string:  e.g. "21%"
         */
        public String getBrightnessString() {
            return ((int) (brightness * 100)) + "%";
        }


        /**
         * To be called once the swipe gesture is done/completed.
         */
        public void onGestureDone() {
            initialBrightness = brightness;
        }

    }

    private void startPlaybackForVideo(@NonNull YouTubeVideo video, boolean resumeSavedPosition) {
        startPlaybackForVideo(video, resumeSavedPosition, null);
    }

    private void startPlaybackForVideo(@NonNull YouTubeVideo video, boolean resumeSavedPosition,
                                       @Nullable StreamInfo prefetchedStream) {
        cancelRecommendationCountdown();
        cancelHighResolutionStartupTimeout();
        cancelSabrStartupTimeout();
        if (sabrPlaybackActive) {
            stopSabrPlayback();
        }
        if (bilibiliPlaybackActive) {
            stopBilibiliPlayback();
        }
        bilibiliStreamInfo = null;
        bilibiliVideoStream = null;
        bilibiliAudioStream = null;
        bilibiliSourceIndex = 0;
        bilibiliStallRetryCount = 0;
        sabrPlaybackAttempted = false;
        sabrPreferredVideoItag = 0;
        hlsFallbackVideoUri = null;
        hlsFallbackAudioUri = null;
        hlsFallbackStreamInfo = null;
        hlsFallbackAttempted = false;
        stableFallbackVideoUri = null;
        stableFallbackStreamInfo = null;
        stableFallbackAttempted = false;
        if (iframePlaybackActive) {
            stopYouTubeIFramePlayback();
        }
        iframePlaybackAttempted = false;
        iframeNativeReturnVideoUri = null;
        iframeNativeReturnAudioUri = null;
        iframeNativeReturnStreamInfo = null;
        hideTvRecommendations();
        playNextVideoWhenReady = !resumeSavedPosition;
        if (!resumeSavedPosition && player != null) {
            player.stop();
        }
        setYouTubeVideo(video);
        autoPlayingNextVideo = false;
        watchedThresholdRecorded = false;
        playbackHistoryRecordedForCurrentVideo = false;
        recommendationHandler.removeCallbacks(watchedProgressCheck);
        playerInitialPosition = 0;
        sponsorBlockVideoInfo = null;
        youTubeChannel = null;
        commentsAdapter = null;

        if (fragmentBinding != null) {
            fragmentBinding.commentsDrawer.close();
            fragmentBinding.desDrawer.close();
            fragmentBinding.loadingVideoView.setVisibility(View.VISIBLE);
            if (fragmentBinding.commentsExpandableListView != null) {
                fragmentBinding.commentsExpandableListView.setAdapter((BaseExpandableListAdapter) null);
            }
        }
        if (videoDescriptionBinding != null) {
            videoDescriptionBinding.videoDescSubscribeButton.clearBackgroundTasks();
            videoDescriptionBinding.videoDescChannelThumbnailImageView
                    .setImageResource(R.drawable.channel_thumbnail_default);
        }

        setupInfoDisplay(video);
        fetchVideoInformation();
        loadRecommendations(video);
        loadBilibiliEpisodes(video);

        if (menu != null) {
            compositeDisposable.add(DatabaseTasks.isVideoBookmarked(video.getId(), menu));
        }

        if (resumeSavedPosition) {
            new ResumeVideoTask(getContext(), video.getId(), position -> {
                playerInitialPosition = position;
                YouTubePlayerV2Fragment.this.loadVideo();
            }).ask();
        } else if (prefetchedStream != null) {
            appendPlaybackDiagnostic("using prefetched recommendation stream videoId=" + video.getId(), null);
            playResolvedStream(prefetchedStream, video);
        } else {
            loadVideo();
        }
    }

    private void loadBilibiliEpisodes(@NonNull YouTubeVideo video) {
        if (!video.isBilibili() || video.getBilibiliBvid() == null) {
            clearBilibiliEpisodes();
            return;
        }

        String bvid = video.getBilibiliBvid();
        if (bvid.equals(bilibiliEpisodesBvid) && !bilibiliEpisodes.isEmpty()) {
            syncCurrentBilibiliEpisode(video);
            updateBilibiliEpisodesMenu();
            if (isTvEpisodesVisible()) {
                renderBilibiliEpisodes(true);
            }
            return;
        }

        final int generation = ++bilibiliEpisodeGeneration;
        bilibiliEpisodesBvid = bvid;
        bilibiliSeriesSource = video;
        bilibiliEpisodesLoading = true;
        bilibiliEpisodes.clear();
        currentBilibiliEpisodeIndex = 0;
        selectedBilibiliEpisodeIndex = 0;
        updateBilibiliEpisodesMenu();
        if (isTvEpisodesVisible()) {
            renderBilibiliEpisodes(false);
        }

        compositeDisposable.add(Single.fromCallable(
                        () -> BilibiliService.get().getVideoEpisodes(bvid))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(episodes -> {
                    if (generation != bilibiliEpisodeGeneration || youTubeVideo == null
                            || !bvid.equals(youTubeVideo.getBilibiliBvid())) {
                        return;
                    }
                    bilibiliEpisodesLoading = false;
                    bilibiliEpisodes.clear();
                    bilibiliEpisodes.addAll(episodes);
                    syncCurrentBilibiliEpisode(youTubeVideo);
                    updateBilibiliEpisodesMenu();
                    Logger.i(this, "Bilibili episodes ready: bvid=%s count=%d current=%d",
                            bvid, bilibiliEpisodes.size(), currentBilibiliEpisodeIndex + 1);
                    if (isTvEpisodesVisible()) {
                        renderBilibiliEpisodes(focusBilibiliEpisodesWhenShown);
                    }
                }, error -> {
                    if (generation != bilibiliEpisodeGeneration) {
                        return;
                    }
                    bilibiliEpisodesLoading = false;
                    Logger.e(this, "Unable to load Bilibili episodes: "
                            + error.getMessage(), error);
                    updateBilibiliEpisodesMenu();
                    if (isTvEpisodesVisible()) {
                        renderBilibiliEpisodes(false);
                    }
                }));
    }

    private void clearBilibiliEpisodes() {
        bilibiliEpisodeGeneration++;
        bilibiliEpisodesLoading = false;
        focusBilibiliEpisodesWhenShown = false;
        bilibiliEpisodesBvid = null;
        bilibiliSeriesSource = null;
        bilibiliEpisodes.clear();
        currentBilibiliEpisodeIndex = 0;
        selectedBilibiliEpisodeIndex = 0;
        hideBilibiliEpisodes();
        updateBilibiliEpisodesMenu();
    }

    private void syncCurrentBilibiliEpisode(@NonNull YouTubeVideo video) {
        String cid = video.getBilibiliCid();
        int index = 0;
        if (cid != null && !cid.isEmpty()) {
            for (int i = 0; i < bilibiliEpisodes.size(); i++) {
                if (cid.equals(bilibiliEpisodes.get(i).cid)) {
                    index = i;
                    break;
                }
            }
        } else if (!bilibiliEpisodes.isEmpty()) {
            BilibiliService.Episode first = bilibiliEpisodes.get(0);
            video.setBilibiliSource(video.getBilibiliBvid(), first.cid);
            video.setId(BilibiliService.VIDEO_PREFIX + video.getBilibiliBvid() + ":" + first.cid);
        }
        currentBilibiliEpisodeIndex = index;
        selectedBilibiliEpisodeIndex = index;
    }

    private void updateBilibiliEpisodesMenu() {
        if (menu == null) {
            return;
        }
        MenuItem episodesItem = menu.findItem(R.id.menu_bilibili_episodes);
        if (episodesItem != null) {
            episodesItem.setVisible(bilibiliEpisodes.size() > 1);
            if (bilibiliEpisodes.size() > 1) {
                episodesItem.setTitle(getString(R.string.player_episode_position,
                        currentBilibiliEpisodeIndex + 1, bilibiliEpisodes.size()));
            }
        }
    }

    public boolean showBilibiliEpisodesForTv() {
        return showBilibiliEpisodesForTv(true);
    }

    private boolean showBilibiliEpisodesForTv(boolean focusCurrent) {
        if (fragmentBinding == null || youTubeVideo == null || !youTubeVideo.isBilibili()) {
            return false;
        }
        if (!bilibiliEpisodesLoading && bilibiliEpisodes.size() <= 1) {
            return false;
        }

        waitingToAutoPlay = false;
        cancelRecommendationCountdown();
        focusBilibiliEpisodesWhenShown = focusCurrent;
        View panel = fragmentBinding.playerView.findViewById(R.id.tvEpisodesPanel);
        if (panel == null) {
            return false;
        }
        fragmentBinding.playerView.setControllerShowTimeoutMs(0);
        panel.setVisibility(View.VISIBLE);
        fragmentBinding.playerView.showController();
        renderBilibiliEpisodes(focusCurrent);
        return true;
    }

    public void hideBilibiliEpisodes() {
        if (fragmentBinding == null) {
            return;
        }
        View panel = fragmentBinding.playerView.findViewById(R.id.tvEpisodesPanel);
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        fragmentBinding.playerView.setControllerShowTimeoutMs(5_000);
    }

    public void hideTvPlaybackChoices() {
        pausePlaybackChoicesVisible = false;
        hideTvRecommendations();
        hideBilibiliEpisodes();
    }

    private void renderBilibiliEpisodes(boolean focusCurrent) {
        if (fragmentBinding == null) {
            return;
        }
        LinearLayout list = fragmentBinding.playerView.findViewById(R.id.tvEpisodesList);
        TextView title = fragmentBinding.playerView.findViewById(R.id.tvEpisodesTitle);
        if (list == null || title == null) {
            return;
        }
        int expectedCount = bilibiliEpisodesLoading ? 0 : bilibiliEpisodes.size();
        boolean alreadyRendered = renderedBilibiliEpisodeGeneration == bilibiliEpisodeGeneration
                && renderedBilibiliEpisodeCount == expectedCount
                && renderedBilibiliCurrentEpisode == currentBilibiliEpisodeIndex
                && renderedBilibiliEpisodesLoading == bilibiliEpisodesLoading
                && list.getChildCount() == expectedCount;
        if (bilibiliEpisodesLoading) {
            title.setText(R.string.player_episodes_loading);
            if (!alreadyRendered) {
                list.removeAllViews();
                rememberRenderedBilibiliEpisodes(expectedCount);
            }
            return;
        }
        if (bilibiliEpisodes.isEmpty()) {
            title.setText(R.string.player_episode_load_error);
            if (!alreadyRendered) {
                list.removeAllViews();
                rememberRenderedBilibiliEpisodes(expectedCount);
            }
            return;
        }

        updateBilibiliEpisodeTitle(title);
        if (alreadyRendered) {
            focusBilibiliEpisodeIfRequested(list, focusCurrent);
            return;
        }
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < bilibiliEpisodes.size(); i++) {
            final int index = i;
            BilibiliService.Episode episode = bilibiliEpisodes.get(i);
            TextView card = (TextView) inflater.inflate(
                    R.layout.player_episode_item, list, false);
            card.setText(getString(R.string.player_episode_item,
                    episode.page, episode.title));
            card.setSelected(i == currentBilibiliEpisodeIndex);
            card.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) {
                    selectedBilibiliEpisodeIndex = index;
                    TextView panelTitle = fragmentBinding != null
                            ? fragmentBinding.playerView.findViewById(R.id.tvEpisodesTitle) : null;
                    updateBilibiliEpisodeTitle(panelTitle);
                }
            });
            card.setOnClickListener(view -> playBilibiliEpisode(index));
            list.addView(card);
        }
        rememberRenderedBilibiliEpisodes(expectedCount);
        configureTvPlayerFocus();
        focusBilibiliEpisodeIfRequested(list, focusCurrent);
    }

    private void rememberRenderedBilibiliEpisodes(int count) {
        renderedBilibiliEpisodeGeneration = bilibiliEpisodeGeneration;
        renderedBilibiliEpisodeCount = count;
        renderedBilibiliCurrentEpisode = currentBilibiliEpisodeIndex;
        renderedBilibiliEpisodesLoading = bilibiliEpisodesLoading;
    }

    private void focusBilibiliEpisodeIfRequested(@NonNull LinearLayout list,
                                                  boolean focusCurrent) {
        if (!focusCurrent || list.getChildCount() == 0) {
            return;
        }
        int index = Math.min(selectedBilibiliEpisodeIndex, list.getChildCount() - 1);
        list.post(() -> {
            if (index >= 0 && index < list.getChildCount()) {
                list.getChildAt(index).requestFocus();
            }
        });
    }

    private void updateBilibiliEpisodeTitle(@Nullable TextView title) {
        if (title == null || bilibiliEpisodes.isEmpty()) {
            return;
        }
        title.setText(getString(R.string.player_episode_position,
                selectedBilibiliEpisodeIndex + 1, bilibiliEpisodes.size()));
    }

    private void playBilibiliEpisode(int index) {
        if (index < 0 || index >= bilibiliEpisodes.size() || youTubeVideo == null) {
            return;
        }
        if (index == currentBilibiliEpisodeIndex) {
            hideTvPlaybackChoices();
            if (isPlaying()) {
                showCleanTvPlayback();
            } else {
                showTvControlsAndFocus();
            }
            return;
        }

        BilibiliService.Episode episode = bilibiliEpisodes.get(index);
        YouTubeVideo source = bilibiliSeriesSource != null
                ? bilibiliSeriesSource : youTubeVideo;
        YouTubeVideo nextVideo = BilibiliService.get().createEpisodeVideo(source, episode);
        appendPlaybackDiagnostic("Bilibili episode selected page=" + episode.page
                + " cid=" + episode.cid, null);
        compositeDisposable.add(PlaybackStatusDb.getPlaybackStatusDb()
                .setVideoWatchedStatusInBackground(youTubeVideo, true)
                .subscribe());
        currentBilibiliEpisodeIndex = index;
        selectedBilibiliEpisodeIndex = index;
        hideTvPlaybackChoices();
        startPlaybackForVideo(nextVideo, false);
    }

    private boolean tryPlayNextBilibiliEpisode() {
        if (youTubeVideo == null || !youTubeVideo.isBilibili()
                || bilibiliEpisodes.size() <= 1) {
            return false;
        }
        syncCurrentBilibiliEpisode(youTubeVideo);
        int next = currentBilibiliEpisodeIndex + 1;
        if (next >= bilibiliEpisodes.size()) {
            return false;
        }
        Logger.i(this, "Bilibili episode ended; auto-playing %d/%d",
                next + 1, bilibiliEpisodes.size());
        playBilibiliEpisode(next);
        return true;
    }

    private void beginRecommendedAutoPlay() {
        if (autoPlayingNextVideo || waitingToAutoPlay || youTubeVideo == null) {
            return;
        }
        waitingToAutoPlay = true;
        compositeDisposable.add(
                PlaybackStatusDb.getPlaybackStatusDb()
                        .setVideoWatchedStatusInBackground(youTubeVideo, true)
                        .subscribe()
        );
        showTvRecommendations(true, true);
        if (!recommendationsLoading && recommendations.isEmpty()) {
            loadRecommendations(youTubeVideo);
        }
        startRecommendationCountdownWhenReady();
    }

    private void loadRecommendations(@NonNull YouTubeVideo currentVideo) {
        final int generation = ++recommendationGeneration;
        recommendationsLoading = true;
        recommendations.clear();
        selectedRecommendationIndex = 0;
        renderRecommendations();
        compositeDisposable.add(Single.fromCallable(() -> getFilteredRecommendations(currentVideo))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(videos -> {
                    if (generation != recommendationGeneration || fragmentBinding == null) {
                        return;
                    }
                    recommendationsLoading = false;
                    recommendations.clear();
                    recommendations.addAll(videos);
                    Logger.i(YouTubePlayerV2Fragment.this,
                            "Player recommendations ready: %d", recommendations.size());
                    renderRecommendations();
                    if (waitingToAutoPlay) {
                        showTvRecommendations(true, true);
                        startRecommendationCountdownWhenReady();
                    } else {
                        View panel = fragmentBinding.playerView.findViewById(R.id.tvRecommendationsPanel);
                        if (panel != null && panel.getVisibility() == View.VISIBLE) {
                            showTvRecommendations(false, focusRecommendationsWhenShown);
                        }
                    }
                }, error -> {
                    if (generation != recommendationGeneration || fragmentBinding == null) {
                        return;
                    }
                    recommendationsLoading = false;
                    Logger.e(YouTubePlayerV2Fragment.this,
                            "Unable to load player recommendations: " + error.getMessage(), error);
                    renderRecommendations();
                }));
    }

    @NonNull
    private List<YouTubeVideo> getFilteredRecommendations(@NonNull YouTubeVideo currentVideo) throws Exception {
        PersonalizedRecommendationRanker ranker = new PersonalizedRecommendationRanker();
        if (currentVideo.isBilibili()) {
            BilibiliService.VideoPage page = BilibiliService.get().getChannelVideos(
                    currentVideo.getChannelId().getRawId(), null);
            List<YouTubeVideo> channelVideos = new ArrayList<>();
            for (CardData card : page.videos) {
                if (card instanceof YouTubeVideo) {
                    channelVideos.add((YouTubeVideo) card);
                }
            }
            return ranker.rankPlayback(
                    currentVideo,
                    applyRecommendationFilters(currentVideo, channelVideos));
        }
        List<YouTubeVideo> candidates;
        try {
            candidates = NewPipeService.get().getRelatedVideos(currentVideo.getId());
        } catch (Exception relatedError) {
            Logger.e(this, "Unable to obtain YouTube related videos; trying channel fallback", relatedError);
            candidates = Collections.emptyList();
        }

        List<YouTubeVideo> filtered = applyRecommendationFilters(currentVideo, candidates);
        if (filtered.size() >= MIN_RECOMMENDATION_COUNT || currentVideo.getChannelId() == null) {
            return ranker.rankPlayback(currentVideo, filtered);
        }

        try {
            List<YouTubeVideo> channelVideos =
                    NewPipeService.get().getVideosFromFeedOrFromChannel(currentVideo.getChannelId());
            List<YouTubeVideo> combined = new ArrayList<>(candidates);
            if (channelVideos != null) {
                combined.addAll(channelVideos);
            }
            List<YouTubeVideo> supplemented = applyRecommendationFilters(currentVideo, combined);
            return ranker.rankPlayback(currentVideo,
                    supplemented.isEmpty() ? filtered : supplemented);
        } catch (Exception channelError) {
            Logger.e(this, "Unable to supplement recommendations from the channel", channelError);
            return ranker.rankPlayback(currentVideo, filtered);
        }
    }

    @NonNull
    private List<YouTubeVideo> applyRecommendationFilters(@NonNull YouTubeVideo currentVideo,
                                                           @Nullable List<YouTubeVideo> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<CardData> cards = new ArrayList<>(candidates);
        List<CardData> protectedCards = new VideoBlocker().filter(cards, false);
        List<YouTubeVideo> allowed = new ArrayList<>();
        List<YouTubeVideo> alreadyWatched = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        PlaybackStatusDb history = PlaybackStatusDb.getPlaybackStatusDb();
        for (CardData card : protectedCards) {
            if (!(card instanceof YouTubeVideo)) {
                continue;
            }
            YouTubeVideo candidate = (YouTubeVideo) card;
            if (candidate.getId() == null || currentVideo.getId().equals(candidate.getId())
                    || !ids.add(candidate.getId())) {
                continue;
            }
            if (history.getVideoWatchedStatus(candidate.getId()).isFullyWatched()) {
                alreadyWatched.add(candidate);
            } else {
                allowed.add(candidate);
            }
        }
        Logger.i(this, "Recommendation filter raw=%d protected=%d unseen=%d watched=%d",
                candidates.size(), protectedCards.size(), allowed.size(), alreadyWatched.size());
        if (allowed.size() < MIN_RECOMMENDATION_COUNT) {
            int watchedNeeded = MIN_RECOMMENDATION_COUNT - allowed.size();
            allowed.addAll(alreadyWatched.subList(0, Math.min(watchedNeeded, alreadyWatched.size())));
        }
        return allowed;
    }

    public void showTvRecommendationsForPause() {
        if (iframePlaybackActive) {
            return;
        }
        waitingToAutoPlay = false;
        cancelRecommendationCountdown();
        if (pausePlaybackChoicesVisible) {
            updateSabrControls();
            return;
        }
        pausePlaybackChoicesVisible = true;
        showTvRecommendations(false, false);
        showBilibiliEpisodesForTv(false);
        configureTvPlayerFocus();
        showTvControlsAndFocus();
    }

    public void showTvRecommendationsDuringPlayback() {
        if (!isPlaying() || iframePlaybackActive) {
            return;
        }
        pausePlaybackChoicesVisible = false;
        waitingToAutoPlay = false;
        cancelRecommendationCountdown();
        showTvRecommendations(false, true);
        showBilibiliEpisodesForTv(false);
        configureTvPlayerFocus();
    }

    public void stopRecommendationCountdownForTvNavigation() {
        if (!waitingToAutoPlay || fragmentBinding == null) {
            return;
        }
        View panel = fragmentBinding.playerView.findViewById(R.id.tvRecommendationsPanel);
        if (panel == null || panel.getVisibility() != View.VISIBLE) {
            return;
        }
        waitingToAutoPlay = false;
        cancelRecommendationCountdown();
        updateRecommendationTitle();
        Logger.i(this, "Recommendation autoplay countdown cancelled by TV navigation");
    }

    public void hideTvRecommendations() {
        if (fragmentBinding == null) {
            return;
        }
        View panel = fragmentBinding.playerView.findViewById(R.id.tvRecommendationsPanel);
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        fragmentBinding.playerView.setControllerShowTimeoutMs(5_000);
    }

    private void showTvRecommendations(boolean autoPlay, boolean focusRecommendations) {
        if (fragmentBinding == null || iframePlaybackActive) {
            return;
        }
        focusRecommendationsWhenShown = focusRecommendations;
        View panel = fragmentBinding.playerView.findViewById(R.id.tvRecommendationsPanel);
        if (panel == null) {
            return;
        }
        fragmentBinding.playerView.setControllerShowTimeoutMs(0);
        panel.setVisibility(View.VISIBLE);
        fragmentBinding.playerView.showController();
        renderRecommendations();
        configureTvPlayerFocus();
        if (focusRecommendations && !recommendations.isEmpty()) {
            LinearLayout list = panel.findViewById(R.id.tvRecommendationsList);
            int index = Math.min(selectedRecommendationIndex, list.getChildCount() - 1);
            if (index >= 0) {
                list.getChildAt(index).requestFocus();
            }
        } else if (!focusRecommendations) {
            showTvControlsAndFocus();
        }
        if (autoPlay) {
            updateRecommendationTitle();
        }
    }

    private void renderRecommendations() {
        if (fragmentBinding == null) {
            return;
        }
        LinearLayout list = fragmentBinding.playerView.findViewById(R.id.tvRecommendationsList);
        TextView title = fragmentBinding.playerView.findViewById(R.id.tvRecommendationsTitle);
        if (list == null || title == null) {
            return;
        }
        int expectedCount = recommendationsLoading ? 0 : recommendations.size();
        boolean alreadyRendered = renderedRecommendationGeneration == recommendationGeneration
                && renderedRecommendationCount == expectedCount
                && renderedRecommendationsLoading == recommendationsLoading
                && list.getChildCount() == expectedCount;
        if (recommendationsLoading) {
            title.setText(R.string.player_recommendations_loading);
            if (!alreadyRendered) {
                list.removeAllViews();
                rememberRenderedRecommendations(expectedCount);
            }
            return;
        }
        if (recommendations.isEmpty()) {
            title.setText(R.string.player_no_recommendations);
            if (!alreadyRendered) {
                list.removeAllViews();
                rememberRenderedRecommendations(expectedCount);
            }
            return;
        }
        updateRecommendationTitle();
        if (alreadyRendered) {
            return;
        }
        list.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < recommendations.size(); i++) {
            final int index = i;
            YouTubeVideo video = recommendations.get(i);
            View card = inflater.inflate(R.layout.player_recommendation_item, list, false);
            TextView cardTitle = card.findViewById(R.id.recommendationTitle);
            ImageView thumbnail = card.findViewById(R.id.recommendationThumbnail);
            cardTitle.setText(video.getTitle());
            Glide.with(this)
                    .load(video.getThumbnailUrl())
                    .apply(new RequestOptions().placeholder(R.drawable.thumbnail_default))
                    .into(thumbnail);
            card.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) {
                    selectedRecommendationIndex = index;
                    prefetchRecommendation(video);
                }
            });
            card.setOnClickListener(view -> playSelectedRecommendation(index));
            list.addView(card);
        }
        rememberRenderedRecommendations(expectedCount);
        configureTvPlayerFocus();
        prefetchRecommendation(recommendations.get(
                Math.min(selectedRecommendationIndex, recommendations.size() - 1)));
    }

    private void rememberRenderedRecommendations(int count) {
        renderedRecommendationGeneration = recommendationGeneration;
        renderedRecommendationCount = count;
        renderedRecommendationsLoading = recommendationsLoading;
    }

    private void prefetchRecommendation(@NonNull YouTubeVideo video) {
        String videoId = video.getId();
        if (videoId == null || videoId.equals(recommendationPrefetchVideoId)) {
            return;
        }
        if (recommendationPrefetchDisposable != null) {
            recommendationPrefetchDisposable.dispose();
        }
        recommendationPrefetchVideoId = videoId;
        prefetchedRecommendationStream = null;
        prefetchedRecommendationVideo = null;
        recommendationPrefetchDisposable = YouTubeTasks.getDesiredStream(video,
                new GetDesiredStreamListener() {
                    @Override
                    public void onGetDesiredStream(StreamInfo streamInfo, YouTubeVideo resolvedVideo) {
                        if (videoId.equals(recommendationPrefetchVideoId)) {
                            prefetchedRecommendationStream = streamInfo;
                            prefetchedRecommendationVideo = resolvedVideo;
                            Logger.i(YouTubePlayerV2Fragment.this,
                                    "Recommendation stream prefetched: %s", videoId);
                        }
                    }

                    @Override
                    public void onGetDesiredStreamError(Throwable throwable) {
                        if (videoId.equals(recommendationPrefetchVideoId)) {
                            recommendationPrefetchVideoId = null;
                            prefetchedRecommendationStream = null;
                            prefetchedRecommendationVideo = null;
                        }
                        Logger.e(YouTubePlayerV2Fragment.this,
                                "Recommendation stream prefetch failed: " + videoId, throwable);
                    }
                }).subscribe();
    }

    private void startRecommendationCountdownWhenReady() {
        if (!waitingToAutoPlay || recommendationsLoading || recommendations.isEmpty()
                || recommendationCountdown != null) {
            return;
        }
        autoPlaySecondsRemaining = AUTO_PLAY_NEXT_SECONDS;
        recommendationCountdown = new Runnable() {
            @Override
            public void run() {
                if (!waitingToAutoPlay || fragmentBinding == null) {
                    cancelRecommendationCountdown();
                    return;
                }
                updateRecommendationTitle();
                if (autoPlaySecondsRemaining <= 0) {
                    int index = Math.min(selectedRecommendationIndex, recommendations.size() - 1);
                    playSelectedRecommendation(index);
                    return;
                }
                autoPlaySecondsRemaining--;
                recommendationHandler.postDelayed(this, 1000L);
            }
        };
        recommendationHandler.post(recommendationCountdown);
    }

    private void updateRecommendationTitle() {
        if (fragmentBinding == null) {
            return;
        }
        TextView title = fragmentBinding.playerView.findViewById(R.id.tvRecommendationsTitle);
        if (title != null) {
            title.setText(waitingToAutoPlay
                    ? getString(R.string.player_up_next_countdown, Math.max(0, autoPlaySecondsRemaining))
                    : getString(R.string.player_recommendations));
        }
    }

    private void playSelectedRecommendation(int index) {
        if (index < 0 || index >= recommendations.size() || autoPlayingNextVideo) {
            return;
        }
        YouTubeVideo nextVideo = recommendations.get(index);
        autoPlayingNextVideo = true;
        waitingToAutoPlay = false;
        cancelRecommendationCountdown();
        hideTvPlaybackChoices();
        autoPlayResolvedVideo(nextVideo);
    }

    private void cancelRecommendationCountdown() {
        if (recommendationCountdown != null) {
            recommendationHandler.removeCallbacks(recommendationCountdown);
            recommendationCountdown = null;
        }
    }

    private void autoPlayResolvedVideo(@NonNull YouTubeVideo nextVideo) {
        appendPlaybackDiagnostic("recommendation selected; start stream lookup immediately videoId="
                + nextVideo.getId(), null);
        if (nextVideo.getId() != null && nextVideo.getId().equals(recommendationPrefetchVideoId)
                && prefetchedRecommendationStream != null) {
            StreamInfo streamInfo = prefetchedRecommendationStream;
            YouTubeVideo resolvedVideo = prefetchedRecommendationVideo != null
                    ? prefetchedRecommendationVideo : nextVideo;
            recommendationPrefetchVideoId = null;
            prefetchedRecommendationStream = null;
            prefetchedRecommendationVideo = null;
            if (recommendationPrefetchDisposable != null) {
                recommendationPrefetchDisposable.dispose();
                recommendationPrefetchDisposable = null;
            }
            startPlaybackForVideo(resolvedVideo, false, streamInfo);
        } else {
            startPlaybackForVideo(nextVideo, false);
        }
    }

    public boolean isTvPlaybackReady() {
        return (player != null || iframePlaybackActive || isAlternatePlaybackActive())
                && fragmentBinding != null;
    }

    public void seekByForTv(long offsetMs) {
        if (!isTvPlaybackReady()) {
            return;
        }

        if (iframePlaybackActive && iframePlayer != null) {
            iframePlayer.seekTo(iframePlayer.getCurrentPositionMs() + offsetMs);
            return;
        }
        long targetPosition = Math.max(0L, getActivePlaybackPosition() + offsetMs);
        long duration = getActivePlaybackDuration();
        if (duration != C.TIME_UNSET && duration > 0L) {
            targetPosition = Math.min(targetPosition, duration);
        }
        seekActivePlaybackTo(targetPosition);
        showTvControls();
    }

    public void showTvControls() {
        if (iframePlaybackActive) {
            return;
        }
        if (fragmentBinding != null) {
            if (isAlternatePlaybackActive()) {
                updateSabrControls();
            }
            fragmentBinding.playerView.showController();
            fragmentBinding.playerView.requestFocus();
        }
    }

    public void showTvControlsAndFocus() {
        if (fragmentBinding == null || iframePlaybackActive) {
            return;
        }
        configureTvPlayerFocus();
        if (isAlternatePlaybackActive()) {
            updateSabrControls();
        }
        fragmentBinding.playerView.showController();
        fragmentBinding.playerView.postDelayed(() -> {
            View playPause = fragmentBinding.playerView.findViewById(
                    com.google.android.exoplayer2.ui.R.id.exo_pause);
            if (playPause == null || playPause.getVisibility() != View.VISIBLE) {
                playPause = fragmentBinding.playerView.findViewById(
                        com.google.android.exoplayer2.ui.R.id.exo_play);
            }
            if (playPause != null) {
                playPause.setFocusableInTouchMode(true);
                playPause.requestFocus();
            }
        }, 150L);
    }

    public boolean isTvControlFocused() {
        if (fragmentBinding == null || getActivity() == null) {
            return false;
        }
        View focused = getActivity().getCurrentFocus();
        if (isAlternatePlaybackActive()) {
            View recommendationPanel = fragmentBinding.playerView.findViewById(
                    R.id.tvRecommendationsPanel);
            View episodesPanel = fragmentBinding.playerView.findViewById(R.id.tvEpisodesPanel);
            return (recommendationPanel != null
                    && isDescendant(recommendationPanel, focused))
                    || (episodesPanel != null && isDescendant(episodesPanel, focused));
        }
        View controls = fragmentBinding.playerView.findViewById(R.id.exo_controls);
        View overlay = fragmentBinding.playerView.findViewById(R.id.controlOverlay);
        View toolbar = fragmentBinding.playerView.findViewById(R.id.toolbar);
        while (focused != null) {
            if (focused == controls || focused == overlay || focused == toolbar) {
                return true;
            }
            focused = focused.getParent() instanceof View ? (View) focused.getParent() : null;
        }
        return false;
    }

    public boolean isTvRecommendationsVisible() {
        if (fragmentBinding == null) {
            return false;
        }
        View recommendationPanel = fragmentBinding.playerView.findViewById(
                R.id.tvRecommendationsPanel);
        return recommendationPanel != null
                && recommendationPanel.getVisibility() == View.VISIBLE;
    }

    public boolean isTvEpisodesVisible() {
        if (fragmentBinding == null) {
            return false;
        }
        View episodesPanel = fragmentBinding.playerView.findViewById(R.id.tvEpisodesPanel);
        return episodesPanel != null && episodesPanel.getVisibility() == View.VISIBLE;
    }

    public boolean isTvEpisodeFocused(@Nullable View focused) {
        if (fragmentBinding == null || focused == null) {
            return false;
        }
        View episodesPanel = fragmentBinding.playerView.findViewById(R.id.tvEpisodesPanel);
        return episodesPanel != null && episodesPanel.getVisibility() == View.VISIBLE
                && isDescendant(episodesPanel, focused);
    }

    public boolean isTvRecommendationFocused(@Nullable View focused) {
        if (fragmentBinding == null || focused == null) {
            return false;
        }
        View recommendationPanel = fragmentBinding.playerView.findViewById(
                R.id.tvRecommendationsPanel);
        return recommendationPanel != null
                && recommendationPanel.getVisibility() == View.VISIBLE
                && isDescendant(recommendationPanel, focused);
    }

    public boolean shouldHandleTvRemoteKeys(@Nullable View focused) {
        if (fragmentBinding == null) {
            return false;
        }
        if (iframePlaybackActive) {
            return true;
        }
        if (focused == null) {
            return true;
        }
        return isDescendant(fragmentBinding.playerView, focused);
    }

    public boolean activateFocusedSabrControl(@Nullable View focused) {
        if (!isAlternatePlaybackActive() || fragmentBinding == null || focused == null) {
            return false;
        }
        View rew = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_rew);
        View ffwd = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_ffwd);
        View play = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_play);
        View pause = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_pause);
        if (focused == rew) {
            seekByForTv(-10_000L);
            return true;
        }
        if (focused == ffwd) {
            seekByForTv(10_000L);
            return true;
        }
        if (focused == play || focused == pause) {
            if (isPlaying()) {
                pause();
                showTvRecommendationsForPause();
            } else {
                play();
                hideTvRecommendations();
                showTvControlsAndFocus();
            }
            return true;
        }
        if (focused.getId() == R.id.playbackSpeed) {
            focused.performClick();
            return true;
        }
        Toolbar toolbar = fragmentBinding.getRoot().findViewById(R.id.toolbar);
        if (toolbar != null && isDescendant(toolbar, focused)) {
            focused.performClick();
            return true;
        }
        return false;
    }

    public boolean hideTvOverlayOrControls() {
        if (fragmentBinding == null) {
            return false;
        }
        if (fragmentBinding.commentsDrawer.isOpened()) {
            fragmentBinding.commentsDrawer.animateClose();
            return true;
        }
        if (fragmentBinding.desDrawer.isOpened()) {
            fragmentBinding.desDrawer.animateClose();
            return true;
        }
        View episodesPanel = fragmentBinding.playerView.findViewById(R.id.tvEpisodesPanel);
        View recommendationPanel = fragmentBinding.playerView.findViewById(R.id.tvRecommendationsPanel);
        boolean episodesVisible = episodesPanel != null
                && episodesPanel.getVisibility() == View.VISIBLE;
        boolean recommendationsVisible = recommendationPanel != null
                && recommendationPanel.getVisibility() == View.VISIBLE;
        if (episodesVisible || recommendationsVisible) {
            stopRecommendationCountdownForTvNavigation();
            hideTvPlaybackChoices();
            if (isPlaying()) {
                showCleanTvPlayback();
            } else {
                showTvControlsAndFocus();
            }
            return true;
        }
        if (playerViewGestureHandler != null && playerViewGestureHandler.isControllerVisible) {
            showCleanTvPlayback();
            return true;
        }
        return false;
    }

    private void configureTvPlayerFocus() {
        if (fragmentBinding == null) {
            return;
        }

        Toolbar toolbar = fragmentBinding.getRoot().findViewById(R.id.toolbar);
        List<View> toolbarActions = collectVisibleToolbarActionViews(toolbar);
        View navigationButton = toolbarActions.isEmpty() ? null : toolbarActions.get(0);

        View speed = getPlaybackSpeedTextView();
        View rew = fragmentBinding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_rew);
        View play = fragmentBinding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_play);
        View pause = fragmentBinding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_pause);
        View ffwd = fragmentBinding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_ffwd);
        View progress = fragmentBinding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_progress);
        LinearLayout recommendationList =
                fragmentBinding.playerView.findViewById(R.id.tvRecommendationsList);
        LinearLayout episodeList =
                fragmentBinding.playerView.findViewById(R.id.tvEpisodesList);
        View selectedRecommendation = null;
        if (recommendationList != null && recommendationList.isShown()
                && recommendationList.getChildCount() > 0) {
            int index = Math.min(selectedRecommendationIndex,
                    recommendationList.getChildCount() - 1);
            selectedRecommendation = recommendationList.getChildAt(Math.max(0, index));
        }
        View selectedEpisode = null;
        if (episodeList != null && episodeList.isShown() && episodeList.getChildCount() > 0) {
            int index = Math.min(selectedBilibiliEpisodeIndex,
                    episodeList.getChildCount() - 1);
            selectedEpisode = episodeList.getChildAt(Math.max(0, index));
        }

        View primaryControl = pause != null && pause.getVisibility() == View.VISIBLE ? pause : play;
        View rightToolbarAction = toolbarActions.isEmpty() ? null : toolbarActions.get(toolbarActions.size() - 1);
        View midToolbarAction = toolbarActions.size() > 1 ? toolbarActions.get(toolbarActions.size() - 2) : rightToolbarAction;

        if (speed != null) {
            speed.setFocusable(true);
            speed.setFocusableInTouchMode(true);
            speed.setForeground(ContextCompat.getDrawable(requireContext(), R.drawable.tv_control_focus));
            bindDirectionalFocus(speed, midToolbarAction, primaryControl, null, null);
        }
        bindDirectionalFocus(rew, navigationButton, progress, null, primaryControl);
        bindDirectionalFocus(primaryControl,
                selectedRecommendation != null ? selectedRecommendation
                        : (selectedEpisode != null ? selectedEpisode : speed),
                progress, rew, ffwd);
        bindDirectionalFocus(ffwd, speed, progress, primaryControl, null);
        bindDirectionalFocus(progress, primaryControl, null, rew, ffwd);

        if (recommendationList != null && recommendationList.isShown()) {
            for (int i = 0; i < recommendationList.getChildCount(); i++) {
                View card = recommendationList.getChildAt(i);
                View previous = i > 0 ? recommendationList.getChildAt(i - 1) : null;
                View next = i + 1 < recommendationList.getChildCount()
                        ? recommendationList.getChildAt(i + 1) : null;
                View episodeAbove = episodeList != null && episodeList.isShown()
                        && episodeList.getChildCount() > 0
                        ? episodeList.getChildAt(Math.min(i, episodeList.getChildCount() - 1))
                        : speed;
                bindDirectionalFocus(card, episodeAbove, primaryControl, previous, next);
            }
        }
        if (episodeList != null && episodeList.isShown()) {
            for (int i = 0; i < episodeList.getChildCount(); i++) {
                View card = episodeList.getChildAt(i);
                View previous = i > 0 ? episodeList.getChildAt(i - 1) : card;
                View next = i + 1 < episodeList.getChildCount()
                        ? episodeList.getChildAt(i + 1) : card;
                View recommendationBelow = recommendationList != null
                        && recommendationList.isShown()
                        && recommendationList.getChildCount() > 0
                        ? recommendationList.getChildAt(
                                Math.min(i, recommendationList.getChildCount() - 1))
                        : primaryControl;
                bindDirectionalFocus(card, speed, recommendationBelow, previous, next);
            }
        }

        if (navigationButton != null) {
            bindDirectionalFocus(navigationButton, null, rew, null, null);
        }
        if (midToolbarAction != null) {
            bindDirectionalFocus(midToolbarAction, null, speed, navigationButton, rightToolbarAction);
        }
        if (rightToolbarAction != null) {
            bindDirectionalFocus(rightToolbarAction, null, speed, midToolbarAction, null);
        }

        if (toolbar != null) {
            final View toolbarDownTarget = speed != null ? speed : primaryControl;
            TvFocusHelper.prepareToolbarNavigation(toolbar, toolbarDownTarget);
        }
    }

    public boolean openTvOverflowMenuIfFocused(@Nullable View focused) {
        if (menu == null || focused == null) {
            return false;
        }
        final Toolbar toolbar = fragmentBinding != null ? fragmentBinding.getRoot().findViewById(R.id.toolbar) : null;
        if (toolbar == null) {
            return false;
        }
        final List<View> actions = collectVisibleToolbarActionViews(toolbar);
        if (actions.isEmpty() || actions.get(actions.size() - 1) != focused) {
            return false;
        }
        showTvOverflowMenuDialog();
        return true;
    }

    public boolean handleTvDirectionalFocus(@Nullable View focused, int keyCode) {
        if (fragmentBinding == null || focused == null) {
            return false;
        }
        final Toolbar toolbar = fragmentBinding.getRoot().findViewById(R.id.toolbar);
        if (toolbar == null) {
            return false;
        }

        final List<View> actions = collectVisibleToolbarActionViews(toolbar);
        if (actions.isEmpty()) {
            return false;
        }

        final View navigationButton = actions.get(0);
        final View reloadButton = actions.size() > 1 ? actions.get(actions.size() - 2) : null;
        final View overflowButton = actions.get(actions.size() - 1);
        final View speed = getPlaybackSpeedTextView();
        final View rew = fragmentBinding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_rew);
        final View primaryControl = resolvePrimaryControl();
        final View ffwd = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_ffwd);
        final View progress = fragmentBinding.playerView.findViewById(
                com.google.android.exoplayer2.ui.R.id.exo_progress);

        final int focusedId = focused.getId();
        if (focusedId == R.id.playbackSpeed) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return requestFocus(reloadButton != null ? reloadButton : navigationButton);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return requestFocus(primaryControl);
            }
            return false;
        }

        if (isAlternatePlaybackActive() && focused == rew) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return requestFocus(navigationButton);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return requestFocus(progress);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return requestFocus(primaryControl);
            }
            return false;
        }
        if (isAlternatePlaybackActive() && focused == primaryControl) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                View choice = getSelectedVisiblePlayerChoice();
                return requestFocus(choice != null ? choice : speed);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return requestFocus(progress);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return requestFocus(rew);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return requestFocus(ffwd);
            }
            return false;
        }
        if (isAlternatePlaybackActive() && focused == ffwd) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return requestFocus(speed);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return requestFocus(progress);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return requestFocus(primaryControl);
            }
            return false;
        }
        if (isAlternatePlaybackActive() && focused == progress) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return requestFocus(primaryControl);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return requestFocus(rew);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return requestFocus(ffwd);
            }
            return false;
        }

        if (reloadButton != null && focused == reloadButton) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return requestFocus(overflowButton);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return requestFocus(navigationButton);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return requestFocus(speed != null ? speed : primaryControl);
            }
            return false;
        }

        if (focused == overflowButton) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return requestFocus(reloadButton != null ? reloadButton : navigationButton);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return requestFocus(speed != null ? speed : primaryControl);
            }
            return false;
        }

        if (focused == navigationButton) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && reloadButton != null) {
                return requestFocus(reloadButton);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return requestFocus(rew != null ? rew : primaryControl);
            }
            return false;
        }

        return false;
    }

    @Nullable
    private View getSelectedVisiblePlayerChoice() {
        if (fragmentBinding == null) {
            return null;
        }
        LinearLayout recommendations =
                fragmentBinding.playerView.findViewById(R.id.tvRecommendationsList);
        if (recommendations != null && recommendations.isShown()
                && recommendations.getChildCount() > 0) {
            int index = Math.min(selectedRecommendationIndex,
                    recommendations.getChildCount() - 1);
            return recommendations.getChildAt(Math.max(0, index));
        }
        LinearLayout episodes = fragmentBinding.playerView.findViewById(R.id.tvEpisodesList);
        if (episodes != null && episodes.isShown() && episodes.getChildCount() > 0) {
            int index = Math.min(selectedBilibiliEpisodeIndex, episodes.getChildCount() - 1);
            return episodes.getChildAt(Math.max(0, index));
        }
        return null;
    }

    private List<View> collectVisibleToolbarActionViews(@Nullable View view) {
        final List<View> actions = new ArrayList<>();
        collectVisibleToolbarActionViews(view, actions);
        Collections.sort(actions, Comparator.comparingInt((View action) -> {
            final int[] location = new int[2];
            action.getLocationOnScreen(location);
            return location[0];
        }));
        return actions;
    }

    private void collectVisibleToolbarActionViews(@Nullable View view, @NonNull List<View> result) {
        if (view == null || !view.isShown()) {
            return;
        }
        if (view instanceof ViewGroup) {
            final int before = result.size();
            final ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectVisibleToolbarActionViews(group.getChildAt(i), result);
            }
            if (!(view instanceof Toolbar) && result.size() > before) {
                return;
            }
        }
        if ((view.isClickable() || view.getContentDescription() != null || view.hasOnClickListeners())
                && !(view instanceof Toolbar)) {
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setForeground(ContextCompat.getDrawable(requireContext(), R.drawable.tv_control_focus));
            result.add(view);
        }
    }

    private void showTvOverflowMenuDialog() {
        if (menu == null || getContext() == null) {
            return;
        }
        final List<MenuItem> items = new ArrayList<>();
        final List<CharSequence> titles = new ArrayList<>();
        collectVisibleMenuItems(menu, items, titles);
        if (titles.isEmpty()) {
            return;
        }
        new SkyTubeMaterialDialog(getContext())
                .title(R.string.app_name)
                .items(titles)
                .itemsCallback((dialog, itemView, which, text) -> {
                    onOptionsItemSelected(items.get(which));
                })
                .show();
    }

    private void collectVisibleMenuItems(Menu sourceMenu, List<MenuItem> items, List<CharSequence> titles) {
        for (int i = 0; i < sourceMenu.size(); i++) {
            final MenuItem item = sourceMenu.getItem(i);
            if (!item.isVisible()) {
                continue;
            }
            if (item.hasSubMenu()) {
                collectVisibleMenuItems(item.getSubMenu(), items, titles);
            } else if (item.getItemId() != R.id.menu_reload_video) {
                items.add(item);
                titles.add(item.getTitle());
            }
        }
    }

    private void bindDirectionalFocus(@Nullable View source, @Nullable View up, @Nullable View down,
                                      @Nullable View left, @Nullable View right) {
        if (source == null) {
            return;
        }
        source.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                return requestFocus(up);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return requestFocus(down);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                return requestFocus(left);
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                return requestFocus(right);
            }
            return false;
        });
    }

    private boolean requestFocus(@Nullable View target) {
        return target != null && target.isShown() && target.requestFocus();
    }

    private boolean isDescendant(@Nullable View root, @Nullable View child) {
        View current = child;
        while (current != null) {
            if (current == root) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    @Nullable
    private View resolvePrimaryControl() {
        final View pause = fragmentBinding != null
                ? fragmentBinding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_pause) : null;
        if (pause != null && pause.getVisibility() == View.VISIBLE) {
            return pause;
        }
        return fragmentBinding != null
                ? fragmentBinding.playerView.findViewById(com.google.android.exoplayer2.ui.R.id.exo_play) : null;
    }

    @Override
    public YouTubeVideo getYouTubeVideo() {
        return youTubeVideo;
    }

    @Override
    public int getCurrentVideoPosition() {
        return (int) getActivePlaybackPosition();
    }

    @Override
    public boolean isPlaying() {
        if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            // For remote toggling, buffering with play requested is still the playing state.
            return bilibiliPlayer.getPlayWhenReady();
        }
        if (sabrPlaybackActive && sabrPlayer != null) {
            return sabrPlayer.isPlaying();
        }
        return iframePlaybackActive && iframePlayer != null
                ? iframePlayer.isPlaying() : videoIsPlaying;
    }

    @Override
    public void pause() {
        if (iframePlaybackActive && iframePlayer != null) {
            iframePlayer.pause();
        } else if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            bilibiliPlayer.setPlayWhenReady(false);
            updateSabrControls();
        } else if (sabrPlaybackActive && sabrPlayer != null) {
            sabrPlayer.setPlayWhenReady(false);
            updateSabrControls();
        } else {
            player.setPlayWhenReady(false);
        }
    }

    @Override
    public void play() {
        if (iframePlaybackActive && iframePlayer != null) {
            iframePlayer.play();
        } else if (bilibiliPlaybackActive && bilibiliPlayer != null) {
            bilibiliPlayer.setPlayWhenReady(true);
            updateSabrControls();
        } else if (sabrPlaybackActive && sabrPlayer != null) {
            sabrPlayer.setPlayWhenReady(true);
            updateSabrControls();
        } else {
            player.setPlayWhenReady(true);
        }
    }

    @Override
    public void setPlaybackStateListener(final PlaybackStateListener listener) {
        playbackStateListener = listener;
    }

}
