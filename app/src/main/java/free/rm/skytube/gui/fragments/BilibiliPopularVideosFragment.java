package free.rm.skytube.gui.fragments;

import free.rm.skytube.R;
import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.businessobjects.VideoCategory;

public final class BilibiliPopularVideosFragment extends VideosGridFragment {
    @Override
    protected VideoCategory getVideoCategory() {
        return VideoCategory.BILIBILI_POPULAR;
    }

    @Override
    public String getFragmentName() {
        return SkyTubeApp.getStr(R.string.bilibili_popular);
    }

    @Override
    public int getPriority() {
        return 3;
    }

    @Override
    public String getBundleKey() {
        // Keep the existing key so users' visible/default tab preferences remain valid.
        return MainFragment.BOOKMARKS_FRAGMENT;
    }
}
