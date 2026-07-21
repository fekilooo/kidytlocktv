package free.rm.skytube.gui.fragments;

import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import free.rm.skytube.R;
import free.rm.skytube.app.EventBus;
import free.rm.skytube.app.FeedUpdateTask;
import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.app.utils.WeaklyReferencedMap;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.YouTube.newpipe.ChannelId;
import free.rm.skytube.databinding.FragmentMainBinding;
import free.rm.skytube.databinding.SubsDrawerBinding;
import free.rm.skytube.gui.activities.BaseActivity;
import free.rm.skytube.gui.businessobjects.TvFocusHelper;
import free.rm.skytube.gui.businessobjects.adapters.SubsAdapter;
import free.rm.skytube.gui.businessobjects.views.TvGridRecyclerView;
import free.rm.skytube.gui.businessobjects.fragments.FragmentEx;

public class MainFragment extends FragmentEx {
	// Constants for saving the state of this Fragment's child Fragments
	public static final String FEATURED_VIDEOS_FRAGMENT = "MainFragment.featuredVideosFragment";
	public static final String MOST_POPULAR_VIDEOS_FRAGMENT = "MainFragment.mostPopularVideosFragment";
	public static final String SUBSCRIPTIONS_FEED_FRAGMENT = "MainFragment.subscriptionsFeedFragment";
	public static final String BOOKMARKS_FRAGMENT = "MainFragment.bookmarksFragment";
	public static final String DOWNLOADED_VIDEOS_FRAGMENT = "MainFragment.downloadedVideosFragment";
	public static final String SHOULD_SELECTED_FEED_TAB = "MainFragment.SHOULD_SELECTED_FEED_TAB";

	private static final int TOP_LIST_INDEX = 0;
	private static final int TAB_GRID_FOCUS_RETRY_COUNT = 8;
	private static final long TAB_GRID_FOCUS_RETRY_DELAY_MS = 50L;

	private FragmentMainBinding fragmentBinding;
	private SubsDrawerBinding subsDrawerBinding;
	private TvGridRecyclerView lockedVideoGrid;

	private SubsAdapter subsAdapter = null;
	private SimplePagerAdapter videosPagerAdapter = null;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		fragmentBinding = FragmentMainBinding.inflate(inflater, container, false);
		subsDrawerBinding = fragmentBinding.subsDrawer;

		// For the non-oss version, when using a Chromecast, returning to this fragment from another fragment that uses
		// CoordinatorLayout results in the SlidingUpPanel to be positioned improperly. We need to redraw the panel
		// to fix this. The oss version just has a no-op method.
		((BaseActivity) getActivity()).redrawPanel();

		// setup the toolbar / actionbar
		setSupportActionBar(fragmentBinding.toolbar);
		getSupportActionBar().setDisplayHomeAsUpEnabled(false);

		// indicate that this fragment has an action bar menu
		setHasOptionsMenu(true);

		final ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(false);
			actionBar.setHomeButtonEnabled(false);
		}

		if (subsAdapter == null) {
			subsAdapter = new SubsAdapter(getActivity(), subsDrawerBinding.subsDrawerProgressBar);
		}
		subsAdapter.setMoveFocusToVideos(this::focusCurrentVideoGrid);

		subsDrawerBinding.subsDrawerRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
		subsDrawerBinding.subsDrawerRecyclerView.setAdapter(subsAdapter);

		videosPagerAdapter = new SimplePagerAdapter(getChildFragmentManager());

		final int tabCount = videosPagerAdapter.getCount();
		fragmentBinding.pager.setOffscreenPageLimit(tabCount > 3 ? tabCount - 1 : tabCount);
		fragmentBinding.pager.setAdapter(videosPagerAdapter);

		fragmentBinding.tabLayout.setupWithViewPager(fragmentBinding.pager);

		fragmentBinding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
			@Override
			public void onTabSelected(TabLayout.Tab tab) {
				View previousFocus = getActivity() != null ? getActivity().getCurrentFocus() : null;
				boolean previousGridFocused = findVideoGridAncestor(previousFocus) != null;
				releaseVideoGridNavigationMode();
				fragmentBinding.pager.setCurrentItem(tab.getPosition());
				if (previousGridFocused) {
					fragmentBinding.tabLayout.post(() -> {
						if (fragmentBinding != null) {
							TvFocusHelper.focusSelectedTab(fragmentBinding.tabLayout);
						}
					});
				}
			}

			@Override
			public void onTabUnselected(TabLayout.Tab tab) {
				VideosGridFragment fragment = videosPagerAdapter.getFragmentFrom(tab, false);
				if (fragment instanceof MostPopularVideosFragment) {
					((MostPopularVideosFragment) fragment)
							.refreshAfterNextExplicitTabSelection();
				}
			}

			@Override
			public void onTabReselected(TabLayout.Tab tab) {
				//When current tab reselected scroll to the top of the video list
				VideosGridFragment fragment = videosPagerAdapter.getFragmentFrom(tab, true);
				if (fragment != null && fragment.gridviewBinding != null) {
					fragment.gridviewBinding.gridView.smoothScrollToPosition(TOP_LIST_INDEX);
				} else {
					Logger.i(MainFragment.this, "onTabReselected: %s - %s failed fragment is %s", tab.getPosition(), tab.getText(), fragment);
				}
			}
		});

		// select the default tab:  the default tab is defined by the user through the Preferences
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getActivity());

		// If the app is being opened via the Notification that new videos from Subscribed channels have been found, select the Subscriptions Feed Fragment
		Bundle args = getArguments();
		if (args != null && args.getBoolean(SHOULD_SELECTED_FEED_TAB, false)) {
			fragmentBinding.pager.setCurrentItem(videosPagerAdapter.getIndexOf(SUBSCRIPTIONS_FEED_FRAGMENT));
		} else {
			String defaultTab = sp.getString(getString(R.string.pref_key_default_tab_name), null);
			String[] tabListValues = getTabListValues();

			if (defaultTab == null) {
				int defaultTabNum = Integer.parseInt(sp.getString(getString(R.string.pref_key_default_tab), "0"));
				defaultTab = tabListValues[defaultTabNum];
				sp.edit().putString(getString(R.string.pref_key_default_tab_name), defaultTab).apply();
			}

			// Create a list of non-hidden fragments in order to default to the proper tab
			Set<String> hiddenFragments = SkyTubeApp.getSettings().getHiddenTabs();
			List<String> shownFragmentList = new ArrayList<>();
			for (final String tabListValue : tabListValues) {
				if (!hiddenFragments.contains(tabListValue))
					shownFragmentList.add(tabListValue);
			}
			fragmentBinding.pager.setCurrentItem(shownFragmentList.indexOf(defaultTab));
		}

		// Set the current viewpager fragment as selected, as when the Activity is recreated, the Fragment
		// won't know that it's selected. When the Feeds fragment is the default tab, this will prevent the
		// refresh dialog from showing when an automatic refresh happens.
		videosPagerAdapter.selectTabAtPosition(fragmentBinding.pager.getCurrentItem());

		EventBus.getInstance().registerMainFragment(this);
		return fragmentBinding.getRoot();
	}

	private static String[] getTabListValues() {
		return SkyTubeApp.getStringArray(R.array.tab_list_values);
	}

	@Override
	public void onDestroyView() {
		if (lockedVideoGrid != null) {
			lockedVideoGrid.setDpadNavigationLocked(false);
			lockedVideoGrid = null;
		}
		videosPagerAdapter = null;
		subsDrawerBinding.subsDrawerRecyclerView.setAdapter(null); // cleanup the reference from the SubsAdapter back to the view
		fragmentBinding = null;
		subsDrawerBinding = null;
		super.onDestroyView();
	}

	@Override
	public void onDestroy() {
		// this should happen after onSaveInstanceState
		super.onDestroy();
	}

	@Override
	public void onResume() {
		super.onResume();

		// when the MainFragment is resumed (e.g. after Preferences is minimized), inform the
		// current fragment that it is selected.
		if (videosPagerAdapter != null && fragmentBinding != null) {
			Logger.d(this, "MAINFRAGMENT RESUMED " + fragmentBinding.tabLayout.getSelectedTabPosition());
			videosPagerAdapter.selectTabAtPosition(fragmentBinding.tabLayout.getSelectedTabPosition());
		}
		restoreLockedVideoGridFocus();
		// If the selectedFragment is not the subscriptionsFeedFragment, try out refreshing the subs in the background
		if (SkyTubeApp.getSettings().isFullRefreshTimely()) {
			FeedUpdateTask.getInstance().start(this.getContext());
		}
	}

	@Override
	public void onPause() {
		super.onPause();
//		subsAdapter.setContext(null);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		return super.onOptionsItemSelected(item);
	}

	public void refreshTabs(EventBus.SettingChange settingChange) {
		Logger.i(this, "refreshTabs called");
		switch (settingChange) {
			case HIDE_TABS: {
				videosPagerAdapter.updateVisibleTabs(fragmentBinding.tabLayout);
				break;
			}
			case CONTENT_COUNTRY: {
				videosPagerAdapter.notifyDataSetChanged();
				break;
			}
			case SUBSCRIPTION_LIST_CHANGED: {
				subsAdapter.refreshSubsList();
				break;
			}
			default:
				break;
		}
	}

	public void notifyChangeChannelNewVideosStatus(ChannelId channelId, boolean newVideos) {
		subsAdapter.changeChannelNewVideosStatus(channelId, newVideos);
	}

	public void notifyChannelRemoved(ChannelId channelId) {
		subsAdapter.removeChannel(channelId);
	}

	public class SimplePagerAdapter extends FragmentPagerAdapter {
		private final WeaklyReferencedMap<String, VideosGridFragment> instantiatedFragments = new WeaklyReferencedMap<>();
		private final List<String> visibleTabs = new ArrayList<>();
		private VideosGridFragment primaryItem;

		public SimplePagerAdapter(FragmentManager fm) {
			// TODO: Investigate, if we need this
			super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
			setupVisibleTabs();
		}

		synchronized void setupVisibleTabs() {
			visibleTabs.clear();
			Set<String> hiddenTabs = SkyTubeApp.getSettings().getHiddenTabs();
			for (String key : getTabListValues()) {
				if (hiddenTabs.contains(key)) {
					instantiatedFragments.remove(key);
				} else {
					visibleTabs.add(key);
				}
			}
		}

		synchronized void updateVisibleTabs(TabLayout pager) {
			final int currentItem = pager.getSelectedTabPosition();
			String oldSelection = getTabKey(currentItem);
			setupVisibleTabs();
			notifyDataSetChanged();
			if (oldSelection != null && !isVisible(oldSelection)) {
				int newSelection = Math.min(visibleTabs.size() - 1, currentItem);
				if (newSelection>=0) {
					TabLayout.Tab tab = pager.getTabAt(newSelection);
					pager.selectTab(tab);
				}
			}
		}

		@Override
		public void setPrimaryItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
			super.setPrimaryItem(container, position, object);
			if (object != primaryItem) {
				if (primaryItem != null) {
					primaryItem.onFragmentUnselected();
					primaryItem = null;
				}
				if (object instanceof VideosGridFragment) {
					primaryItem = (VideosGridFragment) object;
					primaryItem.onFragmentSelected();
				}
			}

		}

		@Override
		public int getItemPosition(@NonNull Object object) {
			VideosGridFragment fragment = (VideosGridFragment) object;
			String key = fragment.getBundleKey();
			int currPos = visibleTabs.indexOf(key);
			return currPos < 0 ? POSITION_NONE : currPos;
		}

		@Override
		public synchronized Fragment getItem(int position) {
			String key = getTabKey(position);
			if (key != null) {
				return createOrGetFromCache(key, true);
			}
			return null;
		}

		private VideosGridFragment createOrGetFromCache(String key, boolean create) {
			VideosGridFragment fragment = instantiatedFragments.get(key);
			if (fragment == null && create){
				fragment = create(key);
				instantiatedFragments.put(key, fragment);
			}
			return fragment;
		}

		private VideosGridFragment create(String key) {
			// add fragments to list:  do NOT forget to ***UPDATE*** @string/tab_list and @string/tab_list_values
			if (MOST_POPULAR_VIDEOS_FRAGMENT.equals(key)) {
				return new MostPopularVideosFragment();
			}
			if (FEATURED_VIDEOS_FRAGMENT.equals(key)) {
				return new FeaturedVideosFragment();
			}
			if (SUBSCRIPTIONS_FEED_FRAGMENT.equals(key)) {
				return new SubscriptionsFeedFragment();
			}
			if (BOOKMARKS_FRAGMENT.equals(key)) {
				return new BilibiliPopularVideosFragment();
			}
			if (DOWNLOADED_VIDEOS_FRAGMENT.equals(key)) {
				return new DownloadedVideosFragment();
			}
			return null;
		}

		@Override
		public synchronized int getCount() {
			return visibleTabs.size();
		}

		@Override
		public synchronized void destroyItem(final ViewGroup container, final int position, final Object object) {
			super.destroyItem(container, position, object);
			instantiatedFragments.remove(getTabKey(position));
		}

		private String getTabKey(int position) {
			if (0 <= position && position < visibleTabs.size()) {
				return visibleTabs.get(position);
			}
			return null;
		}

		private int getIndexOf(String fragmentName) {
			return visibleTabs.indexOf(fragmentName);
		}

		private synchronized VideosGridFragment getFragmentFrom(int position, boolean createIfNotFound) {
			String key = getTabKey(position);
			return createOrGetFromCache(key, createIfNotFound);
		}

		private synchronized VideosGridFragment getFragmentFrom(TabLayout.Tab tab, boolean createIfNotFound) {
			return getFragmentFrom(tab.getPosition(), createIfNotFound);
		}

		public synchronized VideosGridFragment selectTabAtPosition(int position) {
			VideosGridFragment fragment = getFragmentFrom(position, true);
			if (fragment != null) {
				fragment.onFragmentSelected();
			}
			return fragment;
		}

		public synchronized VideosGridFragment getPrimaryItem() {
			return primaryItem;
		}

		@Override
		public synchronized CharSequence getPageTitle(int position) {
			String tabKey = getTabKey(position);
			if (tabKey != null) {
				return SkyTubeApp.getFragmentNames().getName(tabKey);
			}
			return "Unknown";
		}

		private boolean isVisible(String key) {
			return getIndexOf(key) >= 0;
		}
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
//		videosPagerAdapter.onSaveInstanceState(outState);

		super.onSaveInstanceState(outState);
	}

	/**
	 * Returns true if the subscriptions drawer is opened.
	 */
	public boolean isDrawerOpen() {
		return false;
	}


	/**
	 * Close the subscriptions drawer.
	 */
	public void closeDrawer() {
		// The subscription list is a permanent sibling of the main content.
	}

	/** Opens the subscriptions drawer when the TV remote confirms the toolbar navigation icon. */
	public boolean openDrawerFromToolbarIfFocused(View focused) {
		return false;
	}

	private void focusCurrentVideoGrid() {
		focusSelectedVideoGridWhenReady(TAB_GRID_FOCUS_RETRY_COUNT);
	}

	private void focusSelectedVideoGridWhenReady(int retriesRemaining) {
		if (fragmentBinding == null || videosPagerAdapter == null) {
			return;
		}
		VideosGridFragment current = getSelectedVideosGridFragment(true);
		TvGridRecyclerView grid = current != null && current.gridviewBinding != null
				? current.gridviewBinding.gridView : findVisibleVideoGrid(fragmentBinding.pager);
		if (grid == null) {
			if (retriesRemaining > 0) {
				fragmentBinding.pager.postDelayed(
						() -> focusSelectedVideoGridWhenReady(retriesRemaining - 1),
						TAB_GRID_FOCUS_RETRY_DELAY_MS);
			}
			return;
		}
		setVideoGridNavigationMode(grid, true);
		grid.requestPreferredChildFocus();
	}

	private void restoreLockedVideoGridFocus() {
		if (lockedVideoGrid == null || fragmentBinding == null || subsDrawerBinding == null) {
			return;
		}
		setVideoGridNavigationMode(lockedVideoGrid, true);
		lockedVideoGrid.post(lockedVideoGrid::restoreLockedNavigationFocus);
		lockedVideoGrid.postDelayed(lockedVideoGrid::restoreLockedNavigationFocus, 160L);
	}

	public boolean handleVideoGridNavigation(@NonNull KeyEvent event) {
		if (fragmentBinding == null) {
			return false;
		}

		View focused = requireActivity().getCurrentFocus();
		boolean tabsFocused = isDescendant(fragmentBinding.tabLayout, focused);
		if (tabsFocused && event.getAction() == KeyEvent.ACTION_DOWN
				&& event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
			focusSelectedVideoGridWhenReady(TAB_GRID_FOCUS_RETRY_COUNT);
			return true;
		}

		boolean subscriptionsFocused =
				isDescendant(subsDrawerBinding.subsDrawerRecyclerView, focused);
		if (subscriptionsFocused) {
			releaseVideoGridNavigationMode();
			if (event.getAction() == KeyEvent.ACTION_DOWN
					&& event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
				focusCurrentVideoGrid();
				return true;
			}
			// Let each subscription row handle Up/Down before any grid fallback consumes it.
			return false;
		}

		TvGridRecyclerView focusedGrid = findVideoGridAncestor(focused);
		if (focusedGrid != null) {
			if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
				setVideoGridNavigationMode(focusedGrid, true);
				return focusedGrid.handleLockedDpadDown(event);
			}
			if (event.getAction() == KeyEvent.ACTION_DOWN
					&& event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
				setVideoGridNavigationMode(focusedGrid, false);
				return false;
			}
		}

		VideosGridFragment current = getSelectedVideosGridFragment(false);
		if (current == null || current.gridviewBinding == null) {
			if (event.getAction() == KeyEvent.ACTION_DOWN
					&& event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
				focusSelectedVideoGridWhenReady(TAB_GRID_FOCUS_RETRY_COUNT);
				return true;
			}
			return false;
		}

		if (focusedGrid != null && focusedGrid != current.gridviewBinding.gridView
				&& event.getAction() == KeyEvent.ACTION_DOWN
				&& event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
			focusSelectedVideoGridWhenReady(TAB_GRID_FOCUS_RETRY_COUNT);
			return true;
		}

		if (event.getAction() == KeyEvent.ACTION_DOWN
				&& event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
				&& current.gridviewBinding.gridView.containsView(focused)) {
			setVideoGridNavigationMode(current.gridviewBinding.gridView, false);
			return false;
		}
		if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
				&& current.gridviewBinding.gridView.containsView(focused)) {
			setVideoGridNavigationMode(current.gridviewBinding.gridView, true);
			return current.gridviewBinding.gridView.handleLockedDpadDown(event);
		}
		return current.gridviewBinding.gridView.handleLockedDpadDown(event);
	}

	@Nullable
	private TvGridRecyclerView findVisibleVideoGrid(@NonNull View view) {
		if (view instanceof TvGridRecyclerView) {
			Rect visibleBounds = new Rect();
			if (view.isShown() && view.getGlobalVisibleRect(visibleBounds)
					&& visibleBounds.width() > 0 && visibleBounds.height() > 0) {
				return (TvGridRecyclerView) view;
			}
		}
		if (view instanceof ViewGroup) {
			ViewGroup group = (ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) {
				TvGridRecyclerView grid = findVisibleVideoGrid(group.getChildAt(i));
				if (grid != null) {
					return grid;
				}
			}
		}
		return null;
	}

	private void setVideoGridNavigationMode(@NonNull TvGridRecyclerView grid, boolean locked) {
		if (lockedVideoGrid != null && lockedVideoGrid != grid) {
			lockedVideoGrid.setDpadNavigationLocked(false);
		}
		grid.setDpadNavigationLocked(locked);
		lockedVideoGrid = locked ? grid : null;

		subsDrawerBinding.subsDrawerRecyclerView.setDescendantFocusability(locked
				? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
		subsDrawerBinding.subsDrawerRecyclerView.setFocusable(!locked);
		subsDrawerBinding.subsDrawerRecyclerView.setFocusableInTouchMode(!locked);
	}

	private void releaseVideoGridNavigationMode() {
		if (lockedVideoGrid != null) {
			lockedVideoGrid.setDpadNavigationLocked(false);
			lockedVideoGrid = null;
		}
		if (subsDrawerBinding != null) {
			subsDrawerBinding.subsDrawerRecyclerView.setDescendantFocusability(
					ViewGroup.FOCUS_AFTER_DESCENDANTS);
			subsDrawerBinding.subsDrawerRecyclerView.setFocusable(true);
			subsDrawerBinding.subsDrawerRecyclerView.setFocusableInTouchMode(true);
		}
	}

	@Nullable
	private static TvGridRecyclerView findVideoGridAncestor(@Nullable View candidate) {
		View current = candidate;
		while (current != null) {
			if (current instanceof TvGridRecyclerView) {
				return (TvGridRecyclerView) current;
			}
			current = current.getParent() instanceof View ? (View) current.getParent() : null;
		}
		return null;
	}

	private static boolean isDescendant(@NonNull ViewGroup ancestor, View candidate) {
		if (candidate == null) {
			return false;
		}
		View current = candidate;
		while (current != null) {
			if (current == ancestor) {
				return true;
			}
			if (!(current.getParent() instanceof View)) {
				return false;
			}
			current = (View) current.getParent();
		}
		return false;
	}

	/**
	 * Refresh the SubscriptionsFeedFragment's feed.
	 */
	public void refreshSubscriptionsFeedVideos() {
		SubscriptionsFeedFragment subscriptionsFeedFragment = (SubscriptionsFeedFragment) videosPagerAdapter.createOrGetFromCache(SUBSCRIPTIONS_FEED_FRAGMENT, false);
		if (subscriptionsFeedFragment != null) {
			subscriptionsFeedFragment.refreshFeedFromCache();
		}
	}

	public VideosGridFragment getCurrentVideosGridFragment() {
		return getSelectedVideosGridFragment(false);
	}

	@Nullable
	private VideosGridFragment getSelectedVideosGridFragment(boolean createIfNotFound) {
		if (videosPagerAdapter == null || fragmentBinding == null) {
			return null;
		}
		VideosGridFragment selected = videosPagerAdapter.getFragmentFrom(
				fragmentBinding.pager.getCurrentItem(), createIfNotFound);
		return selected != null ? selected : videosPagerAdapter.getPrimaryItem();
	}
}
