/*
 * SkyTube
 * Copyright (C) 2015  Ramon Mifsud
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

package free.rm.skytube.gui.activities;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Menu;
import android.view.MenuItem;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.mikepenz.actionitembadge.library.ActionItemBadge;
import com.mikepenz.actionitembadge.library.utils.BadgeStyle;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import free.rm.skytube.BuildConfig;
import free.rm.skytube.R;
import free.rm.skytube.app.EventBus;
import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.businessobjects.DiagnosticFileLogger;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubePlaylist;
import free.rm.skytube.businessobjects.YouTube.VideoBlocker;
import free.rm.skytube.businessobjects.YouTube.newpipe.ChannelId;
import free.rm.skytube.businessobjects.db.DownloadedVideosDb;
import free.rm.skytube.businessobjects.db.SearchHistoryDb;
import free.rm.skytube.businessobjects.db.SearchHistoryTable;
import free.rm.skytube.businessobjects.bilibili.BilibiliService;
import free.rm.skytube.databinding.DialogEnterVideoUrlBinding;
import free.rm.skytube.gui.businessobjects.BlockedVideosDialog;
import free.rm.skytube.gui.businessobjects.CleanerDialog;
import free.rm.skytube.gui.businessobjects.PinUtils;
import free.rm.skytube.gui.businessobjects.TvFocusHelper;
import free.rm.skytube.gui.businessobjects.adapters.SearchHistoryCursorAdapter;
import free.rm.skytube.gui.businessobjects.fragments.BaseVideosGridFragment;
import free.rm.skytube.gui.businessobjects.fragments.FragmentEx;
import free.rm.skytube.gui.businessobjects.updates.UpdatesCheckerTask;
import free.rm.skytube.gui.fragments.ChannelBrowserFragment;
import free.rm.skytube.gui.fragments.MainFragment;
import free.rm.skytube.gui.fragments.PlaylistVideosFragment;
import free.rm.skytube.gui.fragments.SearchVideoGridFragment;

/**
 * Main activity (launcher).  This activity holds {@link free.rm.skytube.gui.fragments.VideosGridFragment}.
 * Do NOT change this activity's superclass, as it needs to be {@link free.rm.skytube.gui.activities.BaseActivity} in order
 * for Chromecast support to work (on the Extra variant - OSS variant's BaseActivity just has empty no-op methods for
 * the Chromecast specific functionality)
 */
public class MainActivity extends BaseActivity {
	private static final int RECORD_AUDIO_PERMISSION_REQUEST = 4201;

	/** Fragment that shows Videos from a specific Playlist */
	private VideoBlockerPlugin      videoBlockerPlugin;
	private SearchView searchView;
	private MenuItem searchMenuItem;
	private AutoCompleteTextView searchTextField;
	private SearchHistoryCursorAdapter searchHistoryAdapter;
	private boolean suppressSearchHistoryFocusRestore;
	private Menu optionsMenu;
	private SpeechRecognizer speechRecognizer;
	private boolean voiceRecognitionActive;
	private boolean searchKeyHeld;
	private boolean searchVoiceGestureActive;
	private boolean centerVoiceHoldActive;
	private final ActivityResultLauncher<Intent> exportLogsLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(),
			this::onExportLogsFilePicked);

	/** Set to true of the UpdatesCheckerTask has run; false otherwise. */
	private static boolean updatesCheckerTaskRan = false;

	public static final String ACTION_VIEW_CHANNEL = "MainActivity.ViewChannel";
	public static final String ACTION_VIEW_FEED = "MainActivity.ViewFeed";
	public static final String ACTION_VIEW_PLAYLIST = "MainActivity.ViewPlaylist";
	private static final String MAIN_FRAGMENT   = "MainActivity.MainFragment";
	private static final String SEARCH_FRAGMENT = "MainActivity.SearchFragment";
	private static final String CHANNEL_BROWSER_FRAGMENT = "MainActivity.ChannelBrowserFragment";
	private static final String PLAYLIST_VIDEOS_FRAGMENT = "MainActivity.PlaylistVideosFragment";
	private static final String VIDEO_BLOCKER_PLUGIN = "MainActivity.VideoBlockerPlugin";

	private static final String MAIN_FRAGMENT_TAG = MAIN_FRAGMENT + ".Tag";
	private static final String CHANNEL_BROWSER_FRAGMENT_TAG = CHANNEL_BROWSER_FRAGMENT + ".Tag";
	private static final String PLAYLIST_VIDEOS_FRAGMENT_TAG = PLAYLIST_VIDEOS_FRAGMENT + ".Tag";
	private static final String SEARCH_FRAGMENT_TAG = SEARCH_FRAGMENT + ".Tag";
	private static final String[] FRAGMENTS = {MAIN_FRAGMENT, SEARCH_FRAGMENT, CHANNEL_BROWSER_FRAGMENT, PLAYLIST_VIDEOS_FRAGMENT};


	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Logger.i(this, "AppID: %s - flavor: %s buildType: %s version: %s (%s)", BuildConfig.APPLICATION_ID, BuildConfig.FLAVOR, BuildConfig.BUILD_TYPE, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);

		// check for updates (one time only)
		if (!updatesCheckerTaskRan) {
			new UpdatesCheckerTask(this, false).executeInParallel();
			updatesCheckerTaskRan = true;
		}

		EventBus.getInstance().registerMainActivityListener(this);

		SkyTubeApp.setFeedUpdateInterval(SkyTubeApp.getSettings().getFeedUpdaterInterval());
		// Delete any missing downloaded videos
		new DownloadedVideosDb.RemoveMissingVideosTask().executeInParallel();

		setContentView(binding.getRoot());

		// The Extra variant needs to initialize some Fragments that are used for Chromecast control. This is done in onLayoutSet of BaseActivity.
		// The OSS variant has a no-op version of this method, since it doesn't need to do anything else here.
		onLayoutSet();

		if(binding.fragmentContainer != null) {
			handleIntent(getIntent());
		}

		if (savedInstanceState != null) {
			// restore the video blocker plugin
			this.videoBlockerPlugin = (VideoBlockerPlugin) savedInstanceState.getSerializable(VIDEO_BLOCKER_PLUGIN);
			this.videoBlockerPlugin.setActivity(this);
		} else {
			this.videoBlockerPlugin = new VideoBlockerPlugin(this);
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		Logger.i(MainActivity.this, "onNewIntent "+ intent +" old "+getIntent());
		setIntent(intent);
		Logger.i(MainActivity.this, "---> "+getIntent());
		handleIntent(intent);
	}

	@Override
	protected void onResumeFragments() {
		super.onResumeFragments();
		Logger.i(MainActivity.this, "onResumeFragments "+getIntent());
	}

	private void handleIntent(Intent intent) {
		// If this Activity was called to view a particular channel, display that channel via ChannelBrowserFragment, instead of MainFragment
		String action = intent.getAction();
		Logger.i(MainActivity.this, "Action is : " + action);
		initMainFragment(action);
		if(ACTION_VIEW_CHANNEL.equals(action)) {
			YouTubeChannel channel = (YouTubeChannel) intent.getSerializableExtra(ChannelBrowserFragment.CHANNEL_OBJ);
			Logger.i(MainActivity.this, "Channel found: " + channel);
			onChannelClick(channel, false);
		} else if(ACTION_VIEW_PLAYLIST.equals(action)) {
			YouTubePlaylist playlist = (YouTubePlaylist) intent.getSerializableExtra(PlaylistVideosFragment.PLAYLIST_OBJ);
			Logger.i(MainActivity.this, "playlist found: " + playlist);
			onPlaylistClick(playlist, false);
		}
	}

	private void initMainFragment(String action) {
		MainFragment mainFragment = getMainFragment();
		if(mainFragment == null) {
			Logger.i(MainActivity.this,"initMainFragment called "+action);
			mainFragment = new MainFragment();
			// If we're coming here via a click on the Notification that new videos for subscribed channels have been found, make sure to
			// select the Feed tab.
			if(action != null && action.equals(ACTION_VIEW_FEED)) {
				Bundle args = new Bundle();
				args.putBoolean(MainFragment.SHOULD_SELECTED_FEED_TAB, true);
				mainFragment.setArguments(args);
			}
			getSupportFragmentManager().beginTransaction().add(R.id.fragment_container, mainFragment, MAIN_FRAGMENT_TAG).commit();
		} else {
			Logger.i(MainActivity.this, "mainFragment already exists, action:"+action+ " fragment:"
					+mainFragment +", manager:"+mainFragment.getParentFragmentManager() +", support="+getSupportFragmentManager());
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		final FragmentManager supportFragmentManager = getSupportFragmentManager();

		for (String fragmentName : FRAGMENTS) {
			Fragment fragment = supportFragmentManager.findFragmentByTag(fragmentName + ".Tag");
			if (fragment != null && fragment.isVisible()) {
				putFragment(supportFragmentManager, outState, fragmentName, fragment);
			}
		}

		// save the video blocker plugin
		outState.putSerializable(VIDEO_BLOCKER_PLUGIN, videoBlockerPlugin);
	}

	private void putFragment(FragmentManager fragmentManager,  Bundle bundle, @NonNull String key,
						@NonNull Fragment fragment) {
		if (fragment.getParentFragmentManager() != fragmentManager) {
			Logger.e(MainActivity.this, "Error fragment has a different FragmentManager than expected: Fragment=" + fragment + ", manager=" + fragmentManager + ", Fragment.manager=" + fragment.getParentFragmentManager() + " for key=" + key);
		} else {
			fragmentManager.putFragment(bundle, key, fragment);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		invalidateOptionsMenu();

		// Activity may be destroyed when the devices is rotated, so we need to make sure that the
		// channel play list is holding a reference to the activity being currently in use...
		ChannelBrowserFragment channelBrowserFragment = getChannelBrowserFragment();
		if (channelBrowserFragment != null) {
			channelBrowserFragment.getChannelPlaylistsFragment().setMainActivityListener(this);
		}
	}

	private ChannelBrowserFragment getChannelBrowserFragment() {
		Fragment fragment = getSupportFragmentManager().findFragmentByTag(CHANNEL_BROWSER_FRAGMENT_TAG);
		if (fragment != null) {
			if (fragment instanceof ChannelBrowserFragment) {
				return (ChannelBrowserFragment) fragment;
			} else {
				Logger.e(MainActivity.this, "Unexpected fragment: "+fragment);
			}
		}
		return null;
	}

	private MainFragment getMainFragment() {
		Fragment fragment = getSupportFragmentManager().findFragmentByTag(MAIN_FRAGMENT_TAG);
		if (fragment != null) {
			if (fragment instanceof MainFragment) {
				return (MainFragment) fragment;
			} else {
				Logger.e(MainActivity.this, "Unexpected fragment: "+fragment);
			}
		}
		return null;
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		super.onCreateOptionsMenu(menu);
		getMenuInflater().inflate(R.menu.main_activity_menu, menu);
		optionsMenu = menu;

		// The TV edition keeps the blocker counter hidden unless explicitly enabled.
		MenuItem blockerItem = menu.findItem(R.id.menu_blocker);
		boolean showBlockerIcon = SkyTubeApp.getSettings().isBlockerToolbarIconVisible();
		blockerItem.setVisible(showBlockerIcon);
		if (showBlockerIcon) {
			videoBlockerPlugin.setupIconForToolBar(menu);
		}

		onOptionsMenuCreated(menu);

		// setup the SearchView (actionbar)
		searchMenuItem = menu.findItem(R.id.menu_search);
		searchView = (SearchView) searchMenuItem.getActionView();

		searchView.setQueryHint(getString(R.string.search_videos));

		searchTextField = searchView.findViewById(androidx.appcompat.R.id.search_src_text);
		searchTextField.setThreshold(0);
		searchView.setOnQueryTextFocusChangeListener((view, hasFocus) -> {
			if (!hasFocus && !suppressSearchHistoryFocusRestore) {
				restoreSearchHistoryAfterKeyboardDismiss();
			}
		});
		searchMenuItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
			@Override
			public boolean onMenuItemActionExpand(MenuItem item) {
				suppressSearchHistoryFocusRestore = false;
				searchTextField.setShowSoftInputOnFocus(true);
				if (searchHistoryAdapter != null) {
					searchHistoryAdapter.setSelectedPosition(-1);
				}
				return true;
			}

			@Override
			public boolean onMenuItemActionCollapse(MenuItem item) {
				suppressSearchHistoryFocusRestore = true;
				searchTextField.dismissDropDown();
				if (searchHistoryAdapter != null) {
					searchHistoryAdapter.setSelectedPosition(-1);
				}
				return true;
			}
		});
		View.OnLongClickListener voiceSearchFallback = view -> {
			centerVoiceHoldActive = startVoiceRecognition();
			return true;
		};
		searchView.setOnLongClickListener(voiceSearchFallback);
		searchTextField.setOnLongClickListener(voiceSearchFallback);

		// ... and change/init the cursor... but not clear the search area, so the user can modify the previous one.
		searchHistoryAdapter = getSearchHistoryAdapter(searchView);
		searchView.setOnSuggestionListener(new SearchView.OnSuggestionListener() {
			@Override
			public boolean onSuggestionSelect(int position) {
				return false;
			}

			@Override
			public boolean onSuggestionClick(int position) {
				return searchHistoryAdapter.activateSuggestion(position);
			}
		});

		// set the query hints to be equal to the previously searched text
		searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
			@Override
			public boolean onQueryTextChange(final String newText) {
				if (newText == null) {
					return false;
				}
				return true;
			}

			@Override
			public boolean onQueryTextSubmit(String query) {
				if(!SkyTubeApp.getSettings().isDisableSearchHistory()) {
					// Save this search string into the Search History Database (for Suggestions)
					SearchHistoryDb.getSearchHistoryDb().insertSearchText(query).subscribe();
				}

				displaySearchResults(query, searchView);

				return true;
			}
		});

		return true;
	}

    private synchronized SearchHistoryCursorAdapter getSearchHistoryAdapter(final SearchView searchView) {
        SearchHistoryCursorAdapter searchHistoryCursorAdapter = (SearchHistoryCursorAdapter) searchView.getSuggestionsAdapter();
        if (searchHistoryCursorAdapter == null) {
            searchHistoryCursorAdapter = new SearchHistoryCursorAdapter(getBaseContext(),
                    R.layout.search_hint,
                    new String[]{SearchHistoryTable.COL_SEARCH_TEXT},
                    new int[]{android.R.id.text1},
                    0);
            searchHistoryCursorAdapter.setSearchHistoryClickListener(query -> displaySearchResults(query, searchView));
            searchView.setSuggestionsAdapter(searchHistoryCursorAdapter);
        }
        return searchHistoryCursorAdapter;
    }

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.menu_blocker:
				videoBlockerPlugin.onMenuBlockerIconClicked();
				return true;
			case R.id.menu_preferences:
				openPreferences();
				return true;
			case R.id.menu_refresh_videos:
				refreshCurrentVideoGrid();
				return true;
			case R.id.menu_voice_search:
				startVoiceRecognition();
				return true;
			case R.id.menu_enter_video_url:
				displayEnterVideoUrlDialog();
				return true;
			case R.id.menu_clean_downloads:
				new CleanerDialog(this).show();
				return true;
			case R.id.menu_export_logs:
				exportDiagnosticLogs();
				return true;
			case android.R.id.home:
				Fragment mainFragment = getMainFragment();
				if(mainFragment == null || !mainFragment.isVisible()) {
					onBackPressed();
					return true;
				}
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			TvFocusHelper.focusInitialContent(this);
		}
	}

	private void openPreferences() {
		Runnable launchPreferences = () -> {
			Intent intent = new Intent(this, PreferencesActivity.class);
			intent.putExtra(PreferencesActivity.PIN_VERIFIED, true);
			startActivity(intent);
		};

		if (SkyTubeApp.getSettings().isPinSet()) {
			PinUtils.promptForPin(this, launchPreferences, null);
		} else {
			launchPreferences.run();
		}
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (handleSearchSuggestionDpad(event)) {
			return true;
		}
		MainFragment focusedMainFragment = getMainFragment();
		if (focusedMainFragment != null
				&& focusedMainFragment.isVisible()
				&& focusedMainFragment.handleVideoGridNavigation(event)) {
			return true;
		}
		if (event.getAction() == KeyEvent.ACTION_DOWN
				&& (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
				|| event.getKeyCode() == KeyEvent.KEYCODE_ENTER
				|| event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
			MainFragment mainFragment = getMainFragment();
			if (mainFragment != null && mainFragment.openDrawerFromToolbarIfFocused(getCurrentFocus())) {
				return true;
			}
			if (isToolbarOverflowFocused(getCurrentFocus())) {
				showTvMainOptionsDialog();
				return true;
			}
		}
		if (TvFocusHelper.handleMainNavigation(this, event)) {
			return true;
		}
		int keyCode = event.getKeyCode();
		if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_ASSIST) {
			if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
				startVoiceRecognition();
			} else if (event.getAction() == KeyEvent.ACTION_UP) {
				stopVoiceRecognition();
			}
			return true;
		}

		if (keyCode == KeyEvent.KEYCODE_SEARCH) {
			if (event.getAction() == KeyEvent.ACTION_DOWN) {
				if (event.getRepeatCount() == 0) {
					searchKeyHeld = true;
				} else if (searchKeyHeld && !searchVoiceGestureActive) {
					searchVoiceGestureActive = startVoiceRecognition();
				}
			} else if (event.getAction() == KeyEvent.ACTION_UP) {
				if (searchVoiceGestureActive) {
					stopVoiceRecognition();
				} else {
					expandSearchField();
				}
				searchKeyHeld = false;
				searchVoiceGestureActive = false;
			}
			return true;
		}

		if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER && searchView != null && searchView.hasFocus()) {
			if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
				if (!centerVoiceHoldActive) {
					centerVoiceHoldActive = startVoiceRecognition();
				}
				return true;
			}
			if (event.getAction() == KeyEvent.ACTION_UP && centerVoiceHoldActive) {
				stopVoiceRecognition();
				centerVoiceHoldActive = false;
				return true;
			}
		}

		return super.dispatchKeyEvent(event);
	}

	private boolean handleSearchSuggestionDpad(KeyEvent event) {
		if (searchTextField == null || searchHistoryAdapter == null
				|| !searchTextField.isPopupShowing()) {
			return false;
		}
		int keyCode = event.getKeyCode();
		boolean handledKey = keyCode == KeyEvent.KEYCODE_DPAD_UP
				|| keyCode == KeyEvent.KEYCODE_DPAD_DOWN
				|| keyCode == KeyEvent.KEYCODE_DPAD_CENTER
				|| keyCode == KeyEvent.KEYCODE_ENTER
				|| keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
		if (!handledKey) {
			return false;
		}
		if (event.getAction() != KeyEvent.ACTION_DOWN) {
			return true;
		}

		int count = searchHistoryAdapter.getCount();
		if (count <= 0) {
			return true;
		}
		int selected = searchTextField.getListSelection();
		switch (event.getKeyCode()) {
			case KeyEvent.KEYCODE_DPAD_DOWN:
				selected = selected < 0 ? 0 : Math.min(count - 1, selected + 1);
				searchHistoryAdapter.setSelectedPosition(selected);
				searchTextField.setListSelection(selected);
				return true;
			case KeyEvent.KEYCODE_DPAD_UP:
				selected = selected < 0 ? 0 : Math.max(0, selected - 1);
				searchHistoryAdapter.setSelectedPosition(selected);
				searchTextField.setListSelection(selected);
				return true;
			case KeyEvent.KEYCODE_DPAD_CENTER:
			case KeyEvent.KEYCODE_ENTER:
			case KeyEvent.KEYCODE_NUMPAD_ENTER:
				return searchHistoryAdapter.activateSuggestion(
						selected < 0 ? 0 : selected);
			default:
				return false;
		}
	}

	private void restoreSearchHistoryAfterKeyboardDismiss() {
		if (searchTextField == null) {
			return;
		}
		searchTextField.postDelayed(() -> {
			if (suppressSearchHistoryFocusRestore || searchMenuItem == null
					|| !searchMenuItem.isActionViewExpanded()) {
				return;
			}
			searchTextField.setShowSoftInputOnFocus(false);
			searchTextField.requestFocus();
			searchTextField.showDropDown();
			if (searchHistoryAdapter != null && searchHistoryAdapter.getCount() > 0) {
				searchHistoryAdapter.setSelectedPosition(-1);
				searchTextField.clearListSelection();
			}
		}, 150L);
	}

	private boolean startVoiceRecognition() {
		if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
				!= PackageManager.PERMISSION_GRANTED) {
			requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_PERMISSION_REQUEST);
			Toast.makeText(this, R.string.voice_search_permission_required, Toast.LENGTH_LONG).show();
			return false;
		}
		if (!SpeechRecognizer.isRecognitionAvailable(this)) {
			Toast.makeText(this, R.string.voice_search_unavailable, Toast.LENGTH_LONG).show();
			return false;
		}

		expandSearchField();
		if (speechRecognizer == null) {
			speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
			speechRecognizer.setRecognitionListener(voiceRecognitionListener);
		}
		if (voiceRecognitionActive) {
			speechRecognizer.cancel();
		}

		Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
		intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
		intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
		intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
		intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
		voiceRecognitionActive = true;
		speechRecognizer.startListening(intent);
		return true;
	}

	private void stopVoiceRecognition() {
		if (speechRecognizer != null && voiceRecognitionActive) {
			speechRecognizer.stopListening();
			voiceRecognitionActive = false;
		}
	}

	private void expandSearchField() {
		if (searchMenuItem != null) {
			searchMenuItem.expandActionView();
		}
		if (searchTextField != null) {
			suppressSearchHistoryFocusRestore = false;
			searchTextField.setShowSoftInputOnFocus(true);
			searchTextField.requestFocus();
			searchTextField.setSelection(searchTextField.getText().length());
		} else if (searchView != null) {
			searchView.requestFocus();
		}
	}

	private void applyVoiceResults(Bundle results, boolean submit) {
		List<String> matches = results == null
				? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
		if (matches != null && !matches.isEmpty() && searchView != null) {
			searchView.setQuery(matches.get(0), submit);
		} else if (submit) {
			Toast.makeText(this, R.string.voice_search_no_result, Toast.LENGTH_SHORT).show();
		}
	}

	private final RecognitionListener voiceRecognitionListener = new RecognitionListener() {
		@Override
		public void onReadyForSpeech(Bundle params) {
			Toast.makeText(MainActivity.this, R.string.voice_search_listening, Toast.LENGTH_SHORT).show();
		}

		@Override public void onBeginningOfSpeech() { }
		@Override public void onRmsChanged(float rmsdB) { }
		@Override public void onBufferReceived(byte[] buffer) { }

		@Override
		public void onEndOfSpeech() {
			voiceRecognitionActive = false;
		}

		@Override
		public void onError(int error) {
			voiceRecognitionActive = false;
			if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
				Toast.makeText(MainActivity.this, R.string.voice_search_no_result, Toast.LENGTH_SHORT).show();
			}
		}

		@Override
		public void onResults(Bundle results) {
			voiceRecognitionActive = false;
			applyVoiceResults(results, true);
		}

		@Override
		public void onPartialResults(Bundle partialResults) {
			applyVoiceResults(partialResults, false);
		}

		@Override public void onEvent(int eventType, Bundle params) { }
	};

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
			@NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST
				&& (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
			Toast.makeText(this, R.string.voice_search_permission_required, Toast.LENGTH_LONG).show();
		}
	}

	@Override
	protected void onDestroy() {
		if (speechRecognizer != null) {
			speechRecognizer.destroy();
			speechRecognizer = null;
		}
		super.onDestroy();
	}


	/**
	 * Display the Enter Video URL dialog.
	 */
	private void displayEnterVideoUrlDialog() {
		final DialogEnterVideoUrlBinding dialogBinding
				= DialogEnterVideoUrlBinding.inflate(getLayoutInflater());

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setView(dialogBinding.getRoot())
				.setTitle(R.string.enter_video_url)
				.setPositiveButton(R.string.play, (clickedDialog, which) -> {
					// get the inputted URL string
					final String videoUrl = dialogBinding.dialogUrlEdittext.getText().toString();

					// play the video
					SkyTubeApp.openUrl(MainActivity.this, videoUrl, true);
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
		dialog.setOnShowListener(ignored -> TvFocusHelper.prepareDialog(dialog));

		// paste whatever there is in the clipboard (hopefully it is a video url)
		CharSequence charSequence = getClipboardItem();
		if (charSequence != null) {
			dialogBinding.dialogUrlEdittext.setText(charSequence.toString());
		}

		// clear URL edittext button
		dialogBinding.dialogUrlClearButton.setOnClickListener(v ->
				dialogBinding.dialogUrlEdittext.setText(""));
	}

	private void showTvMainOptionsDialog() {
		final List<CharSequence> titles = new ArrayList<>();
		final List<Integer> itemIds = new ArrayList<>();
		addMainDialogItem(titles, itemIds, R.id.menu_enter_video_url);
		addMainDialogItem(titles, itemIds, R.id.menu_refresh_videos);
		addMainDialogItem(titles, itemIds, R.id.menu_clean_downloads);
		addMainDialogItem(titles, itemIds, R.id.menu_export_logs);
		addMainDialogItem(titles, itemIds, R.id.menu_preferences);
		if (titles.isEmpty()) {
			return;
		}
		new free.rm.skytube.gui.businessobjects.SkyTubeMaterialDialog(this)
				.title(R.string.app_name)
				.items(titles)
				.itemsCallback((dialog, itemView, which, text) -> {
					onOptionsItemSelected(optionsMenu.findItem(itemIds.get(which)));
				})
				.show();
	}

	private void addMainDialogItem(List<CharSequence> titles, List<Integer> itemIds, int menuId) {
		if (optionsMenu == null) {
			return;
		}
		MenuItem item = optionsMenu.findItem(menuId);
		if (item != null && item.isVisible()) {
			titles.add(item.getTitle());
			itemIds.add(menuId);
		}
	}

	private boolean isToolbarOverflowFocused(View focused) {
		Toolbar toolbar = findToolbarAncestor(focused);
		return toolbar != null && TvFocusHelper.findLastToolbarAction(toolbar) == focused;
	}

	private Toolbar findToolbarAncestor(View view) {
		View current = view;
		while (current != null) {
			if (current instanceof Toolbar) {
				return (Toolbar) current;
			}
			current = current.getParent() instanceof View ? (View) current.getParent() : null;
		}
		return null;
	}

	private void refreshCurrentVideoGrid() {
		BaseVideosGridFragment currentGrid = getVisibleVideoGridFragment();
		if (currentGrid != null) {
			currentGrid.requestRefresh();
		}
	}

	private void exportDiagnosticLogs() {
		Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
		intent.setType("text/plain");
		intent.putExtra(Intent.EXTRA_TITLE, buildCombinedLogFileName());
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
				| Intent.FLAG_GRANT_WRITE_URI_PERMISSION
				| Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
		exportLogsLauncher.launch(intent);
	}

	private void onExportLogsFilePicked(ActivityResult result) {
		if (result.getResultCode() != RESULT_OK || result.getData() == null || result.getData().getData() == null) {
			return;
		}

		final Uri destinationUri = result.getData().getData();
		try {
			final int takeFlags = result.getData().getFlags()
					& (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
			getContentResolver().takePersistableUriPermission(destinationUri, takeFlags);
		} catch (SecurityException securityException) {
			Logger.e(this, securityException, "Unable to persist export logs permission");
		}

		DiagnosticFileLogger.ExportResult resultInfo = DiagnosticFileLogger.exportCombinedLogsToUri(this, destinationUri);
		showExportLogsResult(resultInfo, destinationUri.toString());
	}

	private void showExportLogsResult(DiagnosticFileLogger.ExportResult result, String exportLocation) {
		StringBuilder message = new StringBuilder();
		message.append("Current log location:\n")
				.append(result.sourceDir != null ? result.sourceDir.getAbsolutePath() : "Unavailable")
				.append("\n\nSelected export location:\n")
				.append(exportLocation)
				.append("\n\nFiles:\n");
		for (String file : result.exportedFiles) {
			message.append(file).append('\n');
		}

		ClipboardManager clipboardManager = ContextCompat.getSystemService(this, ClipboardManager.class);
		if (clipboardManager != null) {
			clipboardManager.setPrimaryClip(ClipData.newPlainText("SkyTube logs", exportLocation));
		}

		new AlertDialog.Builder(this)
				.setTitle("Diagnostic logs")
				.setMessage(message.toString().trim())
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private String buildCombinedLogFileName() {
		return "skytube_logs_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
	}

	private BaseVideosGridFragment getVisibleVideoGridFragment() {
		for (Fragment fragment : getSupportFragmentManager().getFragments()) {
			if (fragment == null || !fragment.isVisible()) {
				continue;
			}
			if (fragment instanceof BaseVideosGridFragment) {
				return (BaseVideosGridFragment) fragment;
			}
			if (fragment instanceof MainFragment) {
				Fragment current = ((MainFragment) fragment).getCurrentVideosGridFragment();
				if (current instanceof BaseVideosGridFragment && current.isVisible()) {
					return (BaseVideosGridFragment) current;
				}
			}
		}
		return null;
	}


	/**
	 * Return the last item stored in the clipboard.
	 *
	 * @return	{@link CharSequence}
	 */
	private CharSequence getClipboardItem() {
		CharSequence clipboardText = null;
		ClipboardManager clipboardManager = ContextCompat.getSystemService(this, ClipboardManager.class);

		// if the clipboard contain data ...
		if (clipboardManager != null  &&  clipboardManager.hasPrimaryClip()) {
			ClipData.Item item = clipboardManager.getPrimaryClip().getItemAt(0);

			// gets the clipboard as text.
			clipboardText = item.coerceToText(this);
		}

		return clipboardText;
	}

	/**
	 * For the extra variant, if the Chromecast Controller is visible and expanded, we want to collapse it. So, we must
	 * intercept onBackPressed to make sure it doesn't return us to the homescreen. shouldMinimizeOnBack will take care
	 * of this - on the Extra variant, if the Chromecast Controller is visible and expanded, it will collapse it, and
	 * return false, thus the app will not exit nor will it return to the homescreen. If it's collapsed, or not visible,
	 * it will return true, which then will check if the mainFragment is visible (as opposed to searchFragment). If it is,
	 * it will return to the home screen without exiting, otherwise it will do super.onBackPressed (so in searchFragment,
	 * it will exit from that and return to mainFragment).
	 *
	 * On the OSS variant, shouldMinimizeOnBack will always return true, and the normal checks for mainFragment being visible
	 * will be done.
	 */
	@Override
	public void onBackPressed() {
		if(shouldMinimizeOnBack()) {
			MainFragment mainFragment = getMainFragment();
			if (mainFragment != null && mainFragment.isVisible()) {
				// If the Subscriptions Drawer is open, close it instead of minimizing the app.
				if (mainFragment.isDrawerOpen()) {
					mainFragment.closeDrawer();
				} else {
					showExitConfirmationDialog();
				}
			} else {
				super.onBackPressed();
			}
		}
	}

	private void showExitConfirmationDialog() {
		final AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(R.string.app_name)
				.setMessage(R.string.confirm_exit_app)
				.setNegativeButton(android.R.string.no, null)
				.setPositiveButton(android.R.string.yes,
						(ignored, which) -> minimizeToHome())
				.create();
		dialog.setOnShowListener(ignored -> {
			TvFocusHelper.prepareDialog(dialog);
			View cancelButton = dialog.getButton(
					android.content.DialogInterface.BUTTON_NEGATIVE);
			if (cancelButton != null) {
				cancelButton.post(cancelButton::requestFocus);
			}
		});
		dialog.show();
	}

	private void minimizeToHome() {
		Intent startMain = new Intent(Intent.ACTION_MAIN);
		startMain.addCategory(Intent.CATEGORY_HOME);
		startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(startMain);
	}

	private void switchToFragment(FragmentEx fragment, boolean addToBackStack, String tag) {
		FragmentManager manager = getSupportFragmentManager();
		if (manager.isDestroyed()) {
			Logger.e(this,"FragmentManager is destroyed, unable to add "+fragment);
			return;
		}
		FragmentTransaction transaction = manager.beginTransaction();

		transaction.replace(R.id.fragment_container, fragment, tag);
		if(addToBackStack) {
			transaction.addToBackStack(null);
		}
		transaction.commit();
	}


	public void onChannelClick(YouTubeChannel channel, boolean addToBackStack) {
		Bundle args = new Bundle();
		args.putSerializable(ChannelBrowserFragment.CHANNEL_OBJ, channel);
		switchToChannelBrowserFragment(args, addToBackStack);
	}

	@Override
	public void onChannelClick(ChannelId channelId) {
		if (BilibiliService.isChannelId(channelId.getRawId())) {
			onChannelClick(BilibiliService.get().createChannel(channelId.getRawId()), true);
			return;
		}
		Bundle args = new Bundle();
		args.putString(ChannelBrowserFragment.CHANNEL_ID, channelId.getRawId());
		switchToChannelBrowserFragment(args, true);
	}

	private void switchToChannelBrowserFragment(Bundle args, boolean addToBackStack) {
		ChannelBrowserFragment channelBrowserFragment = new ChannelBrowserFragment();
		channelBrowserFragment.getChannelPlaylistsFragment().setMainActivityListener(this);
		channelBrowserFragment.setArguments(args);
		switchToFragment(channelBrowserFragment, addToBackStack, CHANNEL_BROWSER_FRAGMENT_TAG);
	}

	@Override
	public void onPlaylistClick(YouTubePlaylist playlist) {
		onPlaylistClick(playlist, true);
	}

	private void onPlaylistClick(YouTubePlaylist playlist, boolean addToBackStack) {
		PlaylistVideosFragment playlistVideosFragment = new PlaylistVideosFragment();
		Bundle args = new Bundle();
		args.putSerializable(PlaylistVideosFragment.PLAYLIST_OBJ, playlist);
		playlistVideosFragment.setArguments(args);
		switchToFragment(playlistVideosFragment, addToBackStack, PLAYLIST_VIDEOS_FRAGMENT_TAG);
	}


	/**
	 * Hide the virtual keyboard and then switch to the Search Video Grid Fragment with the selected
	 * query to search for videos.
	 *
	 * @param query Query text submitted by the user.
	 */
	private void displaySearchResults(String query, @NonNull final View searchView) {
		// hide the keyboard
		suppressSearchHistoryFocusRestore = true;
		if (searchTextField != null) {
			searchTextField.dismissDropDown();
		}
		searchView.clearFocus();
		searchView.postDelayed(() -> suppressSearchHistoryFocusRestore = false, 500L);

		// open SearchVideoGridFragment and display the results
		SearchVideoGridFragment searchVideoGridFragment = new SearchVideoGridFragment();
		Bundle bundle = new Bundle();
		bundle.putString(SearchVideoGridFragment.QUERY, query);
		searchVideoGridFragment.setArguments(bundle);
		switchToFragment(searchVideoGridFragment, true, SEARCH_FRAGMENT_TAG);
	}

	//////////////////////////////////////////////////////////////////////////////////////////


	/**
	 * A module/"plugin"/icon that displays the total number of blocked videos.
	 */
	private static class VideoBlockerPlugin implements VideoBlocker.VideoBlockerListener,
			BlockedVideosDialog.BlockedVideosDialogListener,
			Serializable {

		private ArrayList<VideoBlocker.BlockedVideo> blockedVideos = new ArrayList<>();
		private transient AppCompatActivity activity = null;


		VideoBlockerPlugin(AppCompatActivity activity) {
			// notify this class whenever a video is blocked...
			VideoBlocker.setVideoBlockerListener(this);
			this.activity = activity;
		}


		public void setActivity(AppCompatActivity activity) {
			this.activity = activity;
		}


		@Override
		public void onVideoBlocked(VideoBlocker.BlockedVideo blockedVideo) {
			blockedVideos.add(blockedVideo);
			activity.invalidateOptionsMenu();
		}


		/**
		 * Setup the video blocker notification icon which will be displayed in the tool bar.
 		 */
		void setupIconForToolBar(final Menu menu) {
			final Drawable blocker = AppCompatResources.getDrawable(activity, R.drawable.ic_blocker)
					.mutate();
			DrawableCompat.setTint(blocker, Color.WHITE);
			if (getTotalBlockedVideos() > 0) {
				// display a red bubble containing the number of blocked videos
				ActionItemBadge.update(activity, menu.findItem(R.id.menu_blocker), blocker,
						ActionItemBadge.BadgeStyles.RED, getTotalBlockedVideos());
			} else {
				// Else, set the bubble to transparent.  This is required so that when the user
				// clicks on the icon, the app will be able to detect such click and displays the
				// BlockedVideosDialog (otherwise, the ActionItemBadge would just ignore such clicks.
				ActionItemBadge.update(activity,
						menu.findItem(R.id.menu_blocker), blocker,
						new BadgeStyle(BadgeStyle.Style.DEFAULT,
								com.mikepenz.actionitembadge.library.R.layout.menu_action_item_badge,
								Color.TRANSPARENT, Color.TRANSPARENT, Color.WHITE),
						"");
			}
		}


		void onMenuBlockerIconClicked() {
			new BlockedVideosDialog(activity, this, blockedVideos).show();
		}


		@Override
		public void onClearBlockedVideos() {
			blockedVideos.clear();
			activity.invalidateOptionsMenu();
		}


		/**
		 * @return Total number of blocked videos.
		 */
		private int getTotalBlockedVideos() {
			return blockedVideos.size();
		}

	}

	/**
	 * This will tell the SubscriptionsFeedFragment (which lives in MainFragment) that it should refresh its video grid.
	 * This happens when a channel is subscribed to/unsubscribed from. This is called from {@link free.rm.skytube.gui.fragments.ChromecastMiniControllerFragment}.
	 */
	@Override
	public void refreshSubscriptionsFeedVideos() {
		SkyTubeApp.getSettings().setRefreshSubsFeedFromCache(false);
		MainFragment mainFragment = getMainFragment();
		if (mainFragment != null) {
			mainFragment.refreshSubscriptionsFeedVideos();
		}
	}

}
