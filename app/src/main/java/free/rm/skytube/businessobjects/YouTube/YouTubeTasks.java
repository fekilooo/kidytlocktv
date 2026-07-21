package free.rm.skytube.businessobjects.YouTube;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import free.rm.skytube.R;
import free.rm.skytube.app.EventBus;
import free.rm.skytube.app.SkyTubeApp;
import free.rm.skytube.app.Utils;
import free.rm.skytube.businessobjects.DiagnosticFileLogger;
import free.rm.skytube.businessobjects.Logger;
import free.rm.skytube.businessobjects.TLSSocketFactory;
import free.rm.skytube.businessobjects.VideoCategory;
import free.rm.skytube.businessobjects.YouTube.POJOs.CardData;
import free.rm.skytube.businessobjects.YouTube.POJOs.PersistentChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeAPIKey;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeChannel;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubePlaylist;
import free.rm.skytube.businessobjects.YouTube.POJOs.YouTubeVideo;
import free.rm.skytube.businessobjects.YouTube.newpipe.ChannelId;
import free.rm.skytube.businessobjects.YouTube.newpipe.ContentId;
import free.rm.skytube.businessobjects.YouTube.newpipe.NewPipeException;
import free.rm.skytube.businessobjects.YouTube.newpipe.NewPipeService;
import free.rm.skytube.businessobjects.YouTube.newpipe.PlaylistPager;
import free.rm.skytube.businessobjects.db.LocalChannelTable;
import free.rm.skytube.businessobjects.db.SubscriptionsDb;
import free.rm.skytube.businessobjects.bilibili.BilibiliService;
import free.rm.skytube.businessobjects.interfaces.GetDesiredStreamListener;
import free.rm.skytube.businessobjects.model.Status;
import free.rm.skytube.gui.businessobjects.adapters.PlaylistsGridAdapter;
import free.rm.skytube.gui.businessobjects.adapters.VideoGridAdapter;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.CompletableSubject;

import static free.rm.skytube.app.SkyTubeApp.getContext;

/**
 * Contains YouTube-related tasks to be carried out asynchronously.
 */
public class YouTubeTasks {
    private static final String TAG = YouTubeTasks.class.getSimpleName();
    private static final Scheduler scheduler = Schedulers.from(Executors.newFixedThreadPool(4));

    public interface ChannelPlaylistFetcher {
        void reset();
        YouTubeChannel getChannel();
        List<YouTubePlaylist> getNextPlaylists() throws IOException, ExtractionException, NewPipeException;
    }
    private YouTubeTasks() { }

    public static Single<Integer> refreshAllSubscriptions(Context context, @Nullable Consumer<List<ChannelId>> subscriptionListConsumer, @Nullable Consumer<Integer> newVideosFound) {
        Single<List<ChannelId>>  subscriptionList = SubscriptionsDb.getSubscriptionsDb().getSubscribedChannelIdsAsync();
        if (subscriptionListConsumer!= null) {
            subscriptionList = subscriptionList.observeOn(AndroidSchedulers.mainThread())
                    .doOnSuccess(list -> subscriptionListConsumer.accept(list))
                    .observeOn(Schedulers.io());
        }
        return subscriptionList
                .flatMap(channelIds -> refreshSubscriptions(channelIds, newVideosFound))
                .doOnError(error -> {
                    SkyTubeApp.notifyUserOnError(context, error);
                })
                .doOnSuccess(changed -> {
                    Log.i("YouTubeTasks", "refreshAllSubscriptions: " + changed);
                    SkyTubeApp.getSettings().updateFeedsLastUpdateTime();
                });
    }

    private static Single<Integer> refreshSubscriptions(@NonNull List<ChannelId> channelIds, @Nullable Consumer<Integer> newVideosFound) {
        List<ChannelId> youtubeChannelIds = channelIds.stream()
                .filter(channelId -> !BilibiliService.isChannelId(channelId.getRawId()))
                .collect(Collectors.toList());
        if (youtubeChannelIds.isEmpty()) {
            return Single.just(0);
        }
        if (SkyTubeApp.getSettings().isUseNewPipe() || !YouTubeAPIKey.get().isUserApiKeySet()) {
            return YouTubeTasks.getBulkSubscriptionVideos(youtubeChannelIds, newVideosFound);
        } else {
            return YouTubeTasks.getSubscriptionVideos(youtubeChannelIds, newVideosFound);
        }
    }

    /**
     * An asynchronous task that will retrieve YouTube playlists for a specific channel and display
     * them in the supplied adapter.
     */
    public static Maybe<List<YouTubePlaylist>> getChannelPlaylists(@NonNull Context ctx,
                                                                   @NonNull ChannelPlaylistFetcher channelPlaylistFetcher,
                                                                   @NonNull PlaylistsGridAdapter playlistsGridAdapter,
                                                                   boolean shouldReset) {
        if (shouldReset) {
            channelPlaylistFetcher.reset();
            playlistsGridAdapter.clearList();
        }
        return Single.fromCallable(channelPlaylistFetcher::getNextPlaylists)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(throwable -> {
                    Log.e(TAG, "Error:" + throwable.getLocalizedMessage(), throwable);
                    SkyTubeApp.notifyUserOnError(ctx, throwable);
                })
                .doOnSuccess(playlistsGridAdapter::appendList)
                .onErrorComplete();
    }

    /**
     * A task that returns the videos of the channels the user has subscribed to. Used to detect if
     * new videos have been published since last time the user used the app.
     */
    private static Single<Integer> getBulkSubscriptionVideos(@NonNull List<ChannelId> channelIds, @Nullable Consumer<Integer> newVideosFound) {
        final SubscriptionsDb subscriptionsDb = SubscriptionsDb.getSubscriptionsDb();
        final AtomicBoolean changed = new AtomicBoolean(false);
        final AtomicReference<ReCaptchaException> recaptcha = new AtomicReference<>();
        return Flowable.fromIterable(channelIds)
                .flatMapSingle(channelId ->
                        Single.fromCallable(() -> {
                            SkyTubeApp.nonUiThread();
                            if (recaptcha.get() != null) {
                                Log.i(TAG, "Re-captcha needed, done for now");
                                return 0;
                            }
                            Map<String, Long> alreadyKnownVideos = subscriptionsDb.getSubscribedChannelVideosByChannelToTimestamp(channelId);
                            List<YouTubeVideo> newVideos = fetchVideos(subscriptionsDb, alreadyKnownVideos, channelId);
                            List<YouTubeVideo> detailedList = new ArrayList<>();
                            if (!newVideos.isEmpty()) {
                                PersistentChannel dbChannel = subscriptionsDb.getCachedChannel(channelId);
                                for (YouTubeVideo vid : newVideos) {
                                    try {
                                        final YouTubeVideo details = NewPipeService.get().getDetails(vid.getId());
                                        if (vid.getPublishTimestampExact()) {
                                            details.setPublishTimestamp(vid.getPublishTimestamp());
                                            details.setPublishTimestampExact(vid.getPublishTimestampExact());
                                        }
                                        details.setChannel(dbChannel.channel());
                                        detailedList.add(details);
                                    } catch (ReCaptchaException reCaptchaException) {
                                        recaptcha.set(reCaptchaException);
                                        Log.e(TAG, String.format("ReCaptcha error: %s, open %s to solve", reCaptchaException.getMessage(), reCaptchaException.getUrl()));
                                        return 0;
                                    } catch (ExtractionException | IOException e) {
                                        String errorMsg = String.format("Error during parsing video page for id=%s, channel: %s - name: '%s' msg:%s", vid.getId(), vid.getSafeChannelId(), vid.getSafeChannelName(), e.getMessage());
                                        Log.e(TAG, errorMsg, e);
                                    }
                                }
                                changed.compareAndSet(false, true);
                                subscriptionsDb.saveChannelVideos(detailedList, dbChannel, true);
                            }
                            return detailedList.size();
                        })
                                .subscribeOn(scheduler)
                                .observeOn(AndroidSchedulers.mainThread())
                                .doOnSuccess(newYouTubeVideos -> {
                                    if (newVideosFound != null) {
                                        newVideosFound.accept(newYouTubeVideos);
                                    }
                                    EventBus.getInstance().notifyChannelNewVideos(channelId, newYouTubeVideos);
                                })
                )
                .collect(Collectors.summingInt(Integer::intValue))
                .map(result -> {
                    ReCaptchaException reCaptchaException = recaptcha.get();
                    if (reCaptchaException != null) {
                        throw reCaptchaException;
                    }
                    return result;
                }).subscribeOn(Schedulers.io());
    }

    private static List<YouTubeVideo> fetchVideos(@NonNull SubscriptionsDb subscriptionsDb,
                                                  @NonNull Map<String, Long> alreadyKnownVideos,
                                                  @NonNull ChannelId channelId) {
        try {
            List<YouTubeVideo> videos = NewPipeService.get().getVideosFromFeedOrFromChannel(channelId);
            // If we found a video which is already added to the db, no need to check the videos after,
            // assume, they are older, and already seen
            videos.removeIf(video -> {
                Long storedTs = alreadyKnownVideos.get(video.getId());
                if (storedTs != null && Boolean.TRUE.equals(video.getPublishTimestampExact()) && !storedTs.equals(video.getPublishTimestamp())) {
                    // the freshly retrieved video contains an exact, and different publish timestamp
                    subscriptionsDb.setPublishTimestamp(video);
                    Log.i(TAG, String.format("Updating publish timestamp for %s - %s with %s",
                            video.getId(), video.getTitle(), new Date(video.getPublishTimestamp())));
                }
                return storedTs != null;
            });
            return videos;
        } catch (NewPipeException e) {
            handleNewPipeException(channelId, e);
            return Collections.emptyList();
        }
    }

    private static void handleNewPipeException(@NonNull ChannelId channelId, @NonNull NewPipeException e) {
        if (e.getCause() instanceof AccountTerminatedException) {
            Log.e(TAG, "Account terminated for "+ channelId +" error: "+e.getMessage(), e);
            SubscriptionsDb.getSubscriptionsDb().setChannelState(channelId, Status.ACCOUNT_TERMINATED);
        } else if (e.getCause() instanceof ContentNotAvailableException) {
            Log.e(TAG, "Channel doesn't exists "+ channelId +" error: "+e.getMessage(), e);
            SubscriptionsDb.getSubscriptionsDb().setChannelState(channelId, Status.NOT_EXISTS);
        } else {
            Log.e(TAG, "Error during fetching channel page for " + channelId + ",msg:" + e.getMessage(), e);
        }
    }

    /**
     * Task to asynchronously get videos for a specific channel.
     */
    private static Single<List<YouTubeVideo>> getChannelVideos(@NonNull ChannelId channelId,
                                                            @Nullable Long publishedAfter,
                                                            boolean filterSubscribedVideos,
                                                            @Nullable Consumer<Integer> newVideosFound) {
        if (!YouTubeAPIKey.get().isUserApiKeySet()) {
            throw new IllegalStateException("Only valid if custom YouTube key is set!");
        }
        final SubscriptionsDb db = SubscriptionsDb.getSubscriptionsDb();
        return Single.fromCallable(() -> {
            final GetChannelVideosFull getChannelVideosInterface = new GetChannelVideosFull();
            getChannelVideosInterface.init();
            getChannelVideosInterface.setPublishedAfter(publishedAfter != null
                    ? publishedAfter : ZonedDateTime.now().minusMonths(1).toInstant().toEpochMilli());
            getChannelVideosInterface.setChannelQuery(channelId, filterSubscribedVideos);
            return getChannelVideosInterface.getNextVideos();
        })
                .onErrorReturnItem(Collections.emptyList())
                .map(videos -> {
                    List<YouTubeVideo> realVideos = new ArrayList<>(videos.size());
                    for (CardData cd : videos) {
                        if (cd instanceof YouTubeVideo) {
                            realVideos.add((YouTubeVideo) cd);
                        }
                    }
                    PersistentChannel channel = db.getCachedChannel(channelId);
                    db.saveChannelVideos(realVideos, channel, true);
                    return realVideos;
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSuccess(newYouTubeVideos -> {
                    if (newVideosFound != null) {
                        newVideosFound.accept(newYouTubeVideos.size());
                    }
                    EventBus.getInstance().notifyChannelNewVideos(channelId, newYouTubeVideos.size());
                })
                .doOnError(throwable ->
                    Toast.makeText(getContext(),
                        String.format(getContext().getString(R.string.could_not_get_videos),
                        db.getCachedChannel(channelId).channel().getTitle()),
                        Toast.LENGTH_LONG).show()
                );
    }

    /**
     * An asynchronous task that will retrieve a YouTube playlist for a specified playlist URL.
     */
    public static Single<YouTubePlaylist> getPlaylist(@NonNull Context context, @NonNull String playlistId) {
        return Single.fromCallable(() -> {
            final PlaylistPager pager = NewPipeService.get().getPlaylistPager(playlistId);
            final List<YouTubeVideo> firstPage = pager.getNextPageAsVideos();
            return pager.getPlaylist();
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(throwable -> SkyTubeApp.notifyUserOnError(context, throwable));
    }

    /**
     * A task that returns the videos of channel the user has subscribed too. Used to detect if new
     * videos have been published since last time the user used the app.
     */
    private static Single<Integer> getSubscriptionVideos(@NonNull List<ChannelId> channelIds, @Nullable Consumer<Integer> newVideosFound) {
        /*
         * Get the last time all subscriptions were updated, and only fetch videos that were published after this.
         * Any new channels that have been subscribed to since the last time this refresh was done will have any
         * videos published after the last published time stored in the database, so we don't need to worry about missing
         * any.
         */
        final Long publishedAfter = SkyTubeApp.getSettings().getFeedsLastUpdateTime();
        final AtomicBoolean changed = new AtomicBoolean(false);

        return Flowable.fromIterable(channelIds)
            .flatMapSingle(channelId ->
                YouTubeTasks.getChannelVideos(channelId, publishedAfter, true, newVideosFound)
                    .doOnSuccess(videos -> {
                        if (!videos.isEmpty()) {
                            changed.compareAndSet(false, true);
                        }
                        EventBus.getInstance().notifyChannelNewVideos(channelId, videos.size());
                    })
                    .doOnError(throwable ->
                        Log.e(TAG, "Interrupt in semaphore.acquire:" + throwable.getMessage(), throwable)
                    )
                )
                .collect(Collectors.summingInt(videos -> videos.size()))
                .subscribeOn(Schedulers.io());
    }

    /**
     * Task that gets a video's description.
     */
    public static Single<String> getVideoDescription(@NonNull YouTubeVideo youTubeVideo) {
        return Single.fromCallable(() -> {
            if (youTubeVideo.getDescription() != null) {
                return youTubeVideo.getDescription();
            }
            final YouTubeVideo freshDetails = NewPipeService.get().getDetails(youTubeVideo.getId());
            youTubeVideo.setDescription(freshDetails.getDescription());
            youTubeVideo.setLikeDislikeCount(freshDetails.getLikeCountNumber(), freshDetails.getDislikeCountNumber());
            return youTubeVideo.getDescription();
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .onErrorReturn(throwable -> {
                    Log.e(TAG, "Unable to get video details, where id=" + youTubeVideo.getId(), throwable);
                    return SkyTubeApp.getStr(R.string.error_get_video_desc);
                });
    }

    /**
     * An asynchronous task that will, from the given video URL, get the details of the video (e.g. video name,
     * likes, etc).
     */
    public static Maybe<YouTubeVideo> getVideoDetails(@NonNull Context context,
                                                      @NonNull ContentId content) {
        return Maybe.fromCallable(() -> NewPipeService.get().getDetails(content.getId()))
                .subscribeOn(Schedulers.io())
                .map(video -> {
                    Log.i(TAG, "Update video :" + video.getTitle() +
                            " - like:" + video.getLikeCountNumber() + " dislike:" + video.getDislikeCountNumber() + " view : "+video.getViewsCountInt());
                    SubscriptionsDb.getSubscriptionsDb().updateVideo(video);
                    return video;
                })
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(throwable -> {
                    Log.e(TAG, "Unable to get video details, where id=" + content, throwable);
                    SkyTubeApp.notifyUserOnError(context, throwable);
                })
                .onErrorComplete();
    }

    /**
     * An asynchronous task that will, from the given video URL, get the details of the video (e.g. video name,
     * likes, etc).
     */
    public static Maybe<YouTubeVideo> getVideoDetails(@NonNull Context context,
                                                      @NonNull Intent intent) {
        final ContentId content = SkyTubeApp.getUrlFromIntent(context, intent);
        Utils.isTrue(content.getType() == StreamingService.LinkType.STREAM, "Content is a video:"+content);
        return getVideoDetails(context, content);
    }

    /**
     * Task to setup the appropriate Uri for the streams for the given YouTube video.
     */
    public static Completable getDesiredStream(@NonNull YouTubeVideo youTubeVideo,
                                                    @NonNull GetDesiredStreamListener listener) {
        return Single.fromCallable(() -> youTubeVideo.isBilibili()
                        ? BilibiliService.get().getStreamInfo(youTubeVideo)
                        : NewPipeService.get().getStreamInfoByVideoId(youTubeVideo.getId()))
                .subscribeOn(Schedulers.io())
                .timeout(20, TimeUnit.SECONDS)
                .map(streamInfo -> {
                    youTubeVideo.updateFromStreamInfo(streamInfo);
                    if (!youTubeVideo.isBilibili()) {
                        SubscriptionsDb.getSubscriptionsDb().updateVideo(youTubeVideo);
                    }
                    return streamInfo;
                })
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(listener::onGetDesiredStreamError)
                .onErrorComplete()
                .flatMapCompletable(streamInfo -> {
                    listener.onGetDesiredStream(streamInfo, youTubeVideo);
                    return CompletableSubject.create();
                });
    }

    public static Maybe<Long> getDislikeCountFromApi(@NonNull String videoId) {
        return Maybe.fromCallable(() -> NewPipeService.get().getDislikeCountFromApi(videoId))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * An asynchronous task that will retrieve YouTube videos and display them in the supplied Adapter.
     *
     * @param getYouTubeVideos The object that does the actual fetching of videos.
     * @param videoGridAdapter The grid adapter the videos will be added to.
     * @param swipeRefreshLayout The layout which shows animation about the refresh process.
     * @param clearList Clear the list before adding new values to it.
     */
    public static Maybe<List<CardData>> getYouTubeVideos(@NonNull GetYouTubeVideos getYouTubeVideos,
                                                         @NonNull VideoGridAdapter videoGridAdapter,
                                                         @Nullable SwipeRefreshLayout swipeRefreshLayout,
                                                         boolean clearList) {
        getYouTubeVideos.resetKey();
        final YouTubeChannel channel = videoGridAdapter.getYouTubeChannel();
        final Context context = videoGridAdapter.getContext();
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(true);
        }
        final boolean subscriptionFeedVideos = videoGridAdapter.getCurrentVideoCategory() == VideoCategory.SUBSCRIPTIONS_FEED_VIDEOS;

        Maybe<List<CardData>> task = Maybe.fromCallable(() -> {
            // get videos from YouTube or the database.
            final List<CardData> videosList;

            if (clearList && subscriptionFeedVideos) {
                final int currentSize = videoGridAdapter.getItemCount();
                List<CardData> result = new ArrayList<>(currentSize);
                boolean hasNew;
                do {
                    final List<CardData> nextVideos = getYouTubeVideos.getNextVideos();
                    hasNew = !nextVideos.isEmpty();
                    result.addAll(nextVideos);
                } while(result.size() < currentSize && hasNew);
                videosList = result;
            } else {
                videosList = getYouTubeVideos.getNextVideos();
            }

            if (videosList != null) {
                final List<CardData> playableVideos = filterUpcomingVideos(videosList);
                if (isEnhancedDiagnosticCategory(videoGridAdapter.getCurrentVideoCategory())) {
                    writeStageDiagnosticLog(videoGridAdapter.getCurrentVideoCategory(), videoGridAdapter,
                            "Background transform: raw=" + videosList.size()
                                    + ", playable=" + playableVideos.size()
                                    + ", filtering=" + videoGridAdapter.getCurrentVideoCategory().isVideoFilteringEnabled()
                                    + ", fetcher=" + getYouTubeVideos.getClass().getSimpleName());
                }

                // filter videos
                final List<CardData> filteredVideos;
                if (videoGridAdapter.getCurrentVideoCategory().isVideoFilteringEnabled()) {
                    final boolean isSearchResults =
                            videoGridAdapter.getCurrentVideoCategory() == VideoCategory.SEARCH_QUERY;
                    filteredVideos = new VideoBlocker().filter(playableVideos, isSearchResults);
                } else {
                    filteredVideos = playableVideos;
                }
                Logger.i(TAG, "Fetched cards: category=%s raw=%s filtered=%s filtering=%s fetcher=%s",
                        videoGridAdapter.getCurrentVideoCategory(),
                        videosList.size(),
                        filteredVideos.size(),
                        videoGridAdapter.getCurrentVideoCategory().isVideoFilteringEnabled(),
                        getYouTubeVideos.getClass().getSimpleName());
                if (isEnhancedDiagnosticCategory(videoGridAdapter.getCurrentVideoCategory())) {
                    writeStageDiagnosticLog(videoGridAdapter.getCurrentVideoCategory(), videoGridAdapter,
                            "Background transform finished: filtered=" + filteredVideos.size()
                                    + ", channelSubscribed=" + (channel != null && channel.isUserSubscribed()));
                }

                // This is not used for subscriptionFeedVideos
                if (channel != null && channel.isUserSubscribed()) {
                    for (CardData video : filteredVideos) {
                        if (video instanceof YouTubeVideo) {
                            channel.addYouTubeVideo((YouTubeVideo) video);
                        }
                    }
                    SubscriptionsDb db = SubscriptionsDb.getSubscriptionsDb();
                    PersistentChannel persistentChannel = db.getCachedChannel(channel.getChannelId());
                    db.saveChannelVideos(channel.getYouTubeVideos(), persistentChannel, false);
                }
                return filteredVideos;
            } else {
                return Collections.<CardData>emptyList();
            }

        });

        final VideoCategory category = videoGridAdapter.getCurrentVideoCategory();
        if (isEnhancedDiagnosticCategory(category)) {
            final String scope = getDiagnosticScope(category);
            final String subject = getDiagnosticSubject(videoGridAdapter);
            Logger.i(TAG, "[%s] Starting fetch: subject=%s clear=%s currentItems=%s",
                    scope, subject, clearList, videoGridAdapter.getItemCount());
            writeStageDiagnosticLog(category, videoGridAdapter,
                    "Starting fetch: clear=" + clearList
                            + ", currentItems=" + videoGridAdapter.getItemCount()
                            + ", fetcher=" + getYouTubeVideos.getClass().getSimpleName());
            task = task.doOnSubscribe(disposable -> Logger.i(TAG,
                            "[%s] Background fetch subscribed: subject=%s adapterItems=%s",
                            scope, subject, videoGridAdapter.getItemCount()))
                    .doOnSubscribe(disposable -> writeStageDiagnosticLog(category, videoGridAdapter,
                            "Background fetch subscribed: adapterItems=" + videoGridAdapter.getItemCount()))
                    .doOnSuccess(videos -> Logger.i(TAG, "[%s] Background fetch completed: subject=%s items=%s",
                            scope, subject, videos != null ? videos.size() : 0))
                    .doOnSuccess(videos -> writeStageDiagnosticLog(category, videoGridAdapter,
                            "Background fetch completed: items=" + (videos != null ? videos.size() : 0)))
                    .doOnError(error -> Logger.e(TAG, error,
                            "[%s] Background fetch failed: subject=%s message=%s",
                            scope, subject, error.getMessage()))
                    .doOnError(error -> writeStageDiagnosticLog(category, videoGridAdapter,
                            "Background fetch failed: " + error.getClass().getSimpleName()
                                    + ": " + error.getMessage()));
        }

        return task
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .onErrorReturn(error -> {
                    RuntimeException displayError = buildVideoLoadDisplayException(videoGridAdapter, error);
                    writeDiagnosticLog(context, category, videoGridAdapter, error);
                    SkyTubeApp.notifyUserOnError(context, displayError);
                    return Collections.emptyList();
                })
                .doOnSuccess(videosList -> {
                    SkyTubeApp.notifyUserOnError(context, getYouTubeVideos.getLastException());
                    if (isEnhancedDiagnosticCategory(category)) {
                        Logger.i(TAG, "[%s] Main-thread append starting: subject=%s clear=%s incoming=%s before=%s",
                                getDiagnosticScope(category), getDiagnosticSubject(videoGridAdapter),
                                clearList, videosList.size(), videoGridAdapter.getItemCount());
                        writeStageDiagnosticLog(category, videoGridAdapter,
                                "Main-thread append starting: clear=" + clearList
                                        + ", incoming=" + videosList.size()
                                        + ", before=" + videoGridAdapter.getItemCount());
                    }
                    Logger.i(TAG, "Appending cards to grid: category=%s clear=%s append=%s before=%s",
                            videoGridAdapter.getCurrentVideoCategory(), clearList, videosList.size(), videoGridAdapter.getItemCount());

                    if (clearList) {
                        videoGridAdapter.clearList();
                    }
                    videoGridAdapter.appendList(videosList);
                    Logger.i(TAG, "Cards appended: category=%s after=%s",
                            videoGridAdapter.getCurrentVideoCategory(), videoGridAdapter.getItemCount());
                    videoGridAdapter.notifyVideoGridUpdated();
                    if (isEnhancedDiagnosticCategory(category)) {
                        Logger.i(TAG, "[%s] Main-thread append finished: subject=%s after=%s",
                                getDiagnosticScope(category), getDiagnosticSubject(videoGridAdapter),
                                videoGridAdapter.getItemCount());
                        writeStageDiagnosticLog(category, videoGridAdapter,
                                "Main-thread append finished: after=" + videoGridAdapter.getItemCount());
                    }
                })
                .doOnTerminate(() -> {
                    if(swipeRefreshLayout != null) {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                });
    }

    private static List<CardData> filterUpcomingVideos(@NonNull List<CardData> videosList) {
        final List<CardData> filtered = new ArrayList<>(videosList.size());
        int skippedUpcoming = 0;
        for (CardData cardData : videosList) {
            if (cardData instanceof YouTubeVideo && ((YouTubeVideo) cardData).isUpcoming()) {
                skippedUpcoming++;
                continue;
            }
            filtered.add(cardData);
        }
        if (skippedUpcoming > 0) {
            Logger.i(TAG, "Filtered %s upcoming videos that are not playable yet", skippedUpcoming);
        }
        return filtered;
    }

    private static RuntimeException buildVideoLoadDisplayException(@NonNull VideoGridAdapter videoGridAdapter,
                                                                   @NonNull Throwable error) {
        final VideoCategory category = videoGridAdapter.getCurrentVideoCategory();
        if (isEnhancedDiagnosticCategory(category)) {
            final String subject = getDiagnosticSubject(videoGridAdapter);
            final String detail = describeVideoListError(error);
            final String logPath = DiagnosticFileLogger.getAbsoluteLogPath(DiagnosticFileLogger.DEBUG_LOG_FILE_NAME);
            final String message = String.format(Locale.US,
                    "[%s] Unable to load %s. %s Debug log saved to %s.",
                    getDiagnosticScope(category), subject, detail, logPath);
            return new RuntimeException(message, error);
        }
        return new RuntimeException(error.getMessage(), error);
    }

    private static boolean isEnhancedDiagnosticCategory(@Nullable VideoCategory category) {
        return category == VideoCategory.CHANNEL_VIDEOS
                || category == VideoCategory.MOST_POPULAR
                || category == VideoCategory.SEARCH_QUERY
                || category == VideoCategory.PLAYLIST_VIDEOS
                || category == VideoCategory.MIXED_PLAYLIST_VIDEOS;
    }

    private static String getDiagnosticScope(@NonNull VideoCategory category) {
        switch (category) {
            case CHANNEL_VIDEOS:
                return "ChannelVideos";
            case MOST_POPULAR:
                return "TrendingVideos";
            case SEARCH_QUERY:
                return "SearchResults";
            case PLAYLIST_VIDEOS:
            case MIXED_PLAYLIST_VIDEOS:
                return "PlaylistVideos";
            default:
                return "VideoList";
        }
    }

    private static String getDiagnosticSubject(@NonNull VideoGridAdapter videoGridAdapter) {
        final VideoCategory category = videoGridAdapter.getCurrentVideoCategory();
        if (category == VideoCategory.CHANNEL_VIDEOS) {
            return getChannelDisplayName(videoGridAdapter);
        }
        if (category == VideoCategory.MOST_POPULAR) {
            return "trending videos";
        }
        if (category == VideoCategory.SEARCH_QUERY) {
            return "search results";
        }
        if (category == VideoCategory.PLAYLIST_VIDEOS || category == VideoCategory.MIXED_PLAYLIST_VIDEOS) {
            return "playlist videos";
        }
        return "video list";
    }

    private static String getChannelDisplayName(@NonNull VideoGridAdapter videoGridAdapter) {
        final YouTubeChannel channel = videoGridAdapter.getYouTubeChannel();
        if (channel == null || Utils.isEmpty(channel.getTitle())) {
            return "this channel";
        }
        return channel.getTitle();
    }

    private static String describeVideoListError(@NonNull Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        if (error instanceof TimeoutException || current instanceof TimeoutException) {
            return "Timed out after 15 seconds while waiting for the YouTube list response.";
        }

        final String message = current.getMessage() != null ? current.getMessage() : error.getMessage();
        if (message == null) {
            return "Unknown extractor or network error.";
        }

        final String lower = message.toLowerCase(Locale.US);
        if (lower.contains("403")) {
            return "The service returned HTTP 403 for this request.";
        }
        if (lower.contains("ssl") || lower.contains("handshake") || lower.contains("certificate")) {
            return "TLS/SSL handshake failed while contacting YouTube.";
        }
        if (lower.contains("resolve host") || lower.contains("unable to resolve host")) {
            return "DNS lookup failed or internet access is unavailable.";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "The network request timed out before the list was loaded.";
        }
        if (lower.contains("recaptcha")) {
            return "YouTube requested a reCAPTCHA challenge for this request.";
        }
        return "Extractor/network error: " + message;
    }

    private static void writeDiagnosticLog(@NonNull Context context,
                                           @NonNull VideoCategory category,
                                           @NonNull VideoGridAdapter videoGridAdapter,
                                           @NonNull Throwable error) {
        final String scope = getDiagnosticScope(category);
        final String subject = getDiagnosticSubject(videoGridAdapter);
        final String description = describeVideoListError(error);
        final StringBuilder builder = new StringBuilder();
        builder.append(new Date()).append('\n');
        builder.append("scope=").append(scope).append('\n');
        builder.append("subject=").append(subject).append('\n');
        builder.append("category=").append(category).append('\n');
        builder.append("message=").append(description).append('\n');
        builder.append("runtime=").append(TLSSocketFactory.getRuntimeSummary()).append('\n');

        Throwable current = error;
        while (current != null) {
            builder.append("cause=").append(current.getClass().getName()).append(": ")
                    .append(current.getMessage()).append('\n');
            current = current.getCause();
        }
        builder.append("---\n");

        DiagnosticFileLogger.append(DiagnosticFileLogger.DEBUG_LOG_FILE_NAME, builder.toString());
        Logger.i(TAG, "[%s] Diagnostic log saved to %s", scope,
                DiagnosticFileLogger.getAbsoluteLogPath(DiagnosticFileLogger.DEBUG_LOG_FILE_NAME));
    }

    private static void writeStageDiagnosticLog(@NonNull VideoCategory category,
                                                @NonNull VideoGridAdapter videoGridAdapter,
                                                @NonNull String message) {
        final StringBuilder builder = new StringBuilder();
        builder.append(new Date()).append('\n');
        builder.append("scope=").append(getDiagnosticScope(category)).append('\n');
        builder.append("subject=").append(getDiagnosticSubject(videoGridAdapter)).append('\n');
        builder.append("category=").append(category).append('\n');
        builder.append("stage=").append(message).append('\n');
        builder.append("runtime=").append(TLSSocketFactory.getRuntimeSummary()).append('\n');
        builder.append("---\n");
        DiagnosticFileLogger.append(DiagnosticFileLogger.DEBUG_LOG_FILE_NAME, builder.toString());
    }
}
