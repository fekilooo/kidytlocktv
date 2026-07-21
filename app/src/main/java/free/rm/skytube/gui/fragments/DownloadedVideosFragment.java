package free.rm.skytube.gui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;

import free.rm.skytube.R;
import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.VideoCategory;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.db.PlaybackStatusDb;
import free.rm.skytube.businessobjects.interfaces.VideoPlayStatusUpdateListener;
import free.rm.skytube.databinding.FragmentDownloadsBinding;
import free.rm.skytube.gui.businessobjects.adapters.PlaybackHistoryAdapter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** The home playback-history tab. Records are retained for 72 hours. */
public class DownloadedVideosFragment extends VideosGridFragment
        implements VideoPlayStatusUpdateListener {
    private FragmentDownloadsBinding binding;
    private PlaybackHistoryAdapter historyAdapter;
    private final CompositeDisposable disposables = new CompositeDisposable();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDownloadsBinding.inflate(inflater, container, false);
        gridviewBinding = binding.videosGridview;
        historyAdapter = new PlaybackHistoryAdapter();

        gridviewBinding.gridView.setHasFixedSize(true);
        gridviewBinding.gridView.setLayoutManager(new GridLayoutManager(requireContext(), 1));
        gridviewBinding.gridView.setAdapter(historyAdapter);
        gridviewBinding.swipeRefreshLayout.setEnabled(false);
        PlaybackStatusDb.getPlaybackStatusDb().addListener(this);
        loadHistory();
        return binding.getRoot();
    }

    private void loadHistory() {
        if (binding == null) {
            return;
        }
        disposables.add(Single.fromCallable(() -> PlaybackStatusDb.getPlaybackStatusDb()
                        .getRecentPlaybackHistory())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(entries -> {
                    if (binding == null || historyAdapter == null) {
                        return;
                    }
                    historyAdapter.setList(entries);
                    setListVisible(!entries.isEmpty());
                }, error -> Logger.e(this, "Unable to load playback history", error)));
    }

    private void setListVisible(boolean visible) {
        gridviewBinding.swipeRefreshLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        binding.noDownloadedVideosText.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onVideoStatusUpdated(CardData video) {
        loadHistory();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadHistory();
    }

    @Override
    public void onFragmentSelected() {
        super.onFragmentSelected();
        loadHistory();
    }

    @Override
    public void onRefresh() {
        loadHistory();
    }

    @Override
    public void onDestroyView() {
        PlaybackStatusDb.getPlaybackStatusDb().removeListener(this);
        disposables.clear();
        historyAdapter = null;
        binding = null;
        super.onDestroyView();
    }

    @Override
    protected VideoCategory getVideoCategory() {
        return null;
    }

    @Override
    public String getFragmentName() {
        return SkyTubeApp.getStr(R.string.playback_history);
    }

    @Override
    public int getPriority() {
        return 4;
    }

    @Override
    public String getBundleKey() {
        return MainFragment.DOWNLOADED_VIDEOS_FRAGMENT;
    }
}
