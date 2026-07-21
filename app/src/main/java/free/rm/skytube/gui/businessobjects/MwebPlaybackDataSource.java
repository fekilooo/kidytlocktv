/*
 * SkyTube
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation (version 3 of the License).
 */

package free.rm.skytube.gui.businessobjects;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import free.rm.skytube.businessobjects.Logger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Splits open-ended MWEB media requests into finite ranges.
 *
 * Current GoogleVideo MWEB URLs reject "Range: bytes=N-" with HTTP 403 while
 * Android's old ExoPlayer version uses that form for progressive media.
 */
final class MwebPlaybackDataSource implements DataSource {
    private static final long CHUNK_SIZE_BYTES = 8L * 1024L * 1024L;
    private static final int REQUEST_MODE_QUERY_RANGE = 0;
    private static final int REQUEST_MODE_HEADER_RANGE = 1;
    private static final int REQUEST_MODE_QUERY_AND_HEADER_RANGE = 2;
    private static final int REQUEST_MODE_YOUTUBE_POST = 3;
    private static final byte[] YOUTUBE_POST_BODY = new byte[]{0x78, 0};
    private static final ExecutorService URL_REFRESH_EXECUTOR =
            Executors.newCachedThreadPool();

    interface UriRefresher {
        Uri refresh(Uri currentUri) throws IOException;
    }

    interface Http403Listener {
        void onHttp403(Uri mediaUri, long position);
    }

    static final class Factory implements DataSource.Factory {
        private final OkHttpClient client;
        private final String userAgent;
        private final UriRefresher uriRefresher;
        private final Http403Listener http403Listener;

        Factory(String userAgent, TransferListener transferListener) {
            this(userAgent, transferListener, null, null);
        }

        Factory(String userAgent, TransferListener transferListener, UriRefresher uriRefresher) {
            this(userAgent, transferListener, uriRefresher, null);
        }

        Factory(String userAgent, TransferListener transferListener,
                UriRefresher uriRefresher, Http403Listener http403Listener) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true);
            client = builder.build();
            this.userAgent = userAgent;
            this.uriRefresher = uriRefresher;
            this.http403Listener = http403Listener;
        }

        @Override
        public DataSource createDataSource() {
            return new MwebPlaybackDataSource(
                    client, userAgent, uriRefresher, http403Listener);
        }
    }

    private final OkHttpClient client;
    private final String userAgent;
    private final UriRefresher uriRefresher;
    private final Http403Listener http403Listener;
    private DataSpec originalDataSpec;
    private Uri currentMediaUri;
    private Response response;
    private InputStream inputStream;
    private Uri resolvedUri;
    private Map<String, List<String>> responseHeaders = Collections.emptyMap();
    private long nextPosition;
    private long requestedBytesRemaining;
    private long currentChunkBytesRemaining;
    private long totalLength = C.LENGTH_UNSET;
    private long openedAtPosition;
    private long bytesReadSinceOpen;
    private long requestNumber;
    private Future<Uri> pendingRefreshedUri;
    private boolean opened;

    private MwebPlaybackDataSource(OkHttpClient client, String userAgent,
                                   UriRefresher uriRefresher,
                                   Http403Listener http403Listener) {
        this.client = client;
        this.userAgent = userAgent;
        this.uriRefresher = uriRefresher;
        this.http403Listener = http403Listener;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        // The dedicated source is only used on Android 10 for current MWEB media URLs.
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        originalDataSpec = dataSpec;
        currentMediaUri = dataSpec.uri;
        nextPosition = dataSpec.position;
        openedAtPosition = dataSpec.position;
        bytesReadSinceOpen = 0;
        requestedBytesRemaining = dataSpec.length;
        totalLength = getContentLength(dataSpec.uri);
        opened = true;
        if (isIosRequest() && nextPosition > 0 && uriRefresher != null) {
            currentMediaUri = uriRefresher.refresh(currentMediaUri);
        }
        openNextChunk();

        if (requestedBytesRemaining != C.LENGTH_UNSET) {
            return requestedBytesRemaining;
        }
        return totalLength == C.LENGTH_UNSET ? C.LENGTH_UNSET : totalLength - nextPosition;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
            return 0;
        }

        while (hasMoreData()) {
            if (currentChunkBytesRemaining == 0) {
                closeResponse();
                openNextChunk();
            }

            int bytesToRead = (int) Math.min(readLength, currentChunkBytesRemaining);
            if (requestedBytesRemaining != C.LENGTH_UNSET) {
                bytesToRead = (int) Math.min(bytesToRead, requestedBytesRemaining);
            }
            int bytesRead;
            try {
                bytesRead = inputStream.read(buffer, offset, bytesToRead);
            } catch (IOException error) {
                Logger.e(this, "MWEB read failed itag=%s position=%s bytesRead=%s",
                        getItag(), nextPosition, bytesReadSinceOpen, error);
                throw error;
            }
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                currentChunkBytesRemaining = 0;
                continue;
            }

            nextPosition += bytesRead;
            bytesReadSinceOpen += bytesRead;
            currentChunkBytesRemaining -= bytesRead;
            if (requestedBytesRemaining != C.LENGTH_UNSET) {
                requestedBytesRemaining -= bytesRead;
            }
            return bytesRead;
        }
        return C.RESULT_END_OF_INPUT;
    }

    @Override
    public Uri getUri() {
        return resolvedUri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return responseHeaders;
    }

    @Override
    public void close() throws IOException {
        try {
            closeResponse();
        } finally {
            Logger.i(this, "Closed MWEB source itag=%s start=%s end=%s bytesRead=%s total=%s",
                    getItag(), openedAtPosition, nextPosition, bytesReadSinceOpen, totalLength);
            originalDataSpec = null;
            currentMediaUri = null;
            if (pendingRefreshedUri != null) {
                pendingRefreshedUri.cancel(true);
                pendingRefreshedUri = null;
            }
            opened = false;
            currentChunkBytesRemaining = 0;
        }
    }

    private boolean hasMoreData() {
        return opened
                && requestedBytesRemaining != 0
                && (totalLength == C.LENGTH_UNSET || nextPosition < totalLength);
    }

    private void openNextChunk() throws IOException {
        long chunkLength = CHUNK_SIZE_BYTES;
        if (requestedBytesRemaining != C.LENGTH_UNSET) {
            chunkLength = Math.min(chunkLength, requestedBytesRemaining);
        }
        if (totalLength != C.LENGTH_UNSET) {
            chunkLength = Math.min(chunkLength, totalLength - nextPosition);
        }
        if (chunkLength <= 0) {
            currentChunkBytesRemaining = 0;
            return;
        }

        long endPosition = nextPosition + chunkLength - 1;
        if (isIosRequest() && nextPosition > openedAtPosition && uriRefresher != null) {
            currentMediaUri = getRefreshedMediaUri();
            requestNumber = 0;
        }
        int lastResponseCode = 0;
        long currentRequestNumber = requestNumber++;
        int[] requestModes = isIosRequest()
                ? new int[]{
                        REQUEST_MODE_YOUTUBE_POST,
                        REQUEST_MODE_HEADER_RANGE,
                        REQUEST_MODE_QUERY_RANGE}
                : new int[]{
                        REQUEST_MODE_QUERY_RANGE,
                        REQUEST_MODE_HEADER_RANGE,
                        REQUEST_MODE_QUERY_AND_HEADER_RANGE};
        for (int requestMode : requestModes) {
            Request request = buildRangeRequest(
                    nextPosition,
                    endPosition,
                    currentRequestNumber,
                    requestMode);
            response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                Logger.i(this, "MWEB request accepted itag=%s mode=%s protocol=%s host=%s",
                        getItag(), getRequestModeName(requestMode), response.protocol(),
                        response.request().url().host());
                break;
            }

            lastResponseCode = response.code();
            String responsePreview = "";
            try {
                responsePreview = response.peekBody(1024).string();
            } catch (IOException ignored) {
                // The HTTP status and request mode are enough when the error body is unavailable.
            }
            Logger.e(this,
                    "MWEB request rejected itag=%s mode=%s code=%s protocol=%s host=%s body=%s",
                    getItag(), getRequestModeName(requestMode), lastResponseCode,
                    response.protocol(), response.request().url().host(), responsePreview);
            closeResponse();
        }
        if (response == null || !response.isSuccessful()) {
            if (lastResponseCode == 403 && http403Listener != null && currentMediaUri != null) {
                http403Listener.onHttp403(currentMediaUri, nextPosition);
            }
            throw new IOException("MWEB media request returned HTTP " + lastResponseCode
                    + " for all finite range modes");
        }

        ResponseBody body = response.body();
        if (body == null) {
            closeResponse();
            throw new IOException("MWEB media response has no body");
        }
        inputStream = body.byteStream();
        resolvedUri = Uri.parse(response.request().url().toString());
        responseHeaders = response.headers().toMultimap();
        long openedLength = body.contentLength();
        currentChunkBytesRemaining =
                openedLength < 0 ? chunkLength : openedLength;
        updateTotalLength(responseHeaders);
        Logger.i(this, "Opened MWEB range itag=%s position=%s length=%s total=%s",
                getItag(), nextPosition, currentChunkBytesRemaining, totalLength);
        prefetchNextMediaUri();
    }

    private Request buildRangeRequest(long startPosition, long endPosition,
                                      long currentRequestNumber, int requestMode) {
        Uri requestUri = currentMediaUri;
        if (requestMode == REQUEST_MODE_QUERY_RANGE
                || requestMode == REQUEST_MODE_QUERY_AND_HEADER_RANGE) {
            requestUri = requestUri.buildUpon()
                    .appendQueryParameter("range", startPosition + "-" + endPosition)
                    .build();
        }
        if (requestMode == REQUEST_MODE_YOUTUBE_POST) {
            requestUri = requestUri.buildUpon()
                    .appendQueryParameter("rn", Long.toString(currentRequestNumber))
                    .build();
        }

        Request.Builder requestBuilder = new Request.Builder().url(requestUri.toString());
        if (!isIosRequest()) {
            requestBuilder
                    .header("Accept-Encoding", "identity")
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/");
        }
        if (requestMode == REQUEST_MODE_HEADER_RANGE
                || requestMode == REQUEST_MODE_QUERY_AND_HEADER_RANGE) {
            requestBuilder.header("Range", "bytes=" + startPosition + "-" + endPosition);
        }
        if (requestMode == REQUEST_MODE_YOUTUBE_POST) {
            requestBuilder
                    .header("Range", "bytes=" + startPosition + "-" + endPosition)
                    .header("TE", "trailers")
                    .header("Accept-Encoding", "identity")
                    .post(RequestBody.create(null, YOUTUBE_POST_BODY));
        }
        if (userAgent != null && !userAgent.isEmpty()) {
            requestBuilder.header("User-Agent", userAgent);
        }
        return requestBuilder.build();
    }

    private boolean isIosRequest() {
        return originalDataSpec != null
                && "IOS".equalsIgnoreCase(originalDataSpec.uri.getQueryParameter("c"));
    }

    private void prefetchNextMediaUri() {
        if (!isIosRequest() || uriRefresher == null || pendingRefreshedUri != null) {
            return;
        }
        Uri uriToRefresh = currentMediaUri;
        pendingRefreshedUri = URL_REFRESH_EXECUTOR.submit(
                () -> uriRefresher.refresh(uriToRefresh));
    }

    private Uri getRefreshedMediaUri() throws IOException {
        Future<Uri> refresh = pendingRefreshedUri;
        pendingRefreshedUri = null;
        if (refresh == null) {
            return uriRefresher.refresh(currentMediaUri);
        }
        try {
            Uri refreshedUri = refresh.get();
            if (refreshedUri == null) {
                throw new IOException("Refreshed iOS media URL is missing");
            }
            return refreshedUri;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while refreshing iOS media URL", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            }
            throw new IOException("Unable to refresh iOS media URL", cause);
        }
    }

    private static String getRequestModeName(int requestMode) {
        switch (requestMode) {
            case REQUEST_MODE_QUERY_RANGE:
                return "query";
            case REQUEST_MODE_HEADER_RANGE:
                return "header";
            case REQUEST_MODE_QUERY_AND_HEADER_RANGE:
                return "query+header";
            case REQUEST_MODE_YOUTUBE_POST:
                return "post+rn+header";
            default:
                return "unknown";
        }
    }

    private void closeResponse() {
        if (response != null) {
            response.close();
        }
        response = null;
        inputStream = null;
    }

    private String getItag() {
        return originalDataSpec == null ? null : originalDataSpec.uri.getQueryParameter("itag");
    }

    private static long getContentLength(Uri uri) {
        String contentLength = uri.getQueryParameter("clen");
        if (contentLength == null) {
            return C.LENGTH_UNSET;
        }
        try {
            return Long.parseLong(contentLength);
        } catch (NumberFormatException ignored) {
            return C.LENGTH_UNSET;
        }
    }

    private void updateTotalLength(Map<String, List<String>> headers) {
        if (headers == null || totalLength != C.LENGTH_UNSET) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Content-Range".equalsIgnoreCase(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                int slash = value == null ? -1 : value.lastIndexOf('/');
                if (slash < 0 || slash == value.length() - 1) {
                    continue;
                }
                try {
                    totalLength = Long.parseLong(value.substring(slash + 1));
                    return;
                } catch (NumberFormatException ignored) {
                    // Keep streaming with finite chunks even if the total is unavailable.
                }
            }
        }
    }
}
