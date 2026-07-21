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

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import free.rm.skytube.businessobjects.Logger;

/**
 * Applies the request shape used by current NewPipe versions to visionOS progressive streams.
 */
final class VisionOsPlaybackDataSource implements DataSource {
    private static final byte[] POST_BODY = new byte[]{0x78, 0};
    private static final AtomicLong REQUEST_NUMBER = new AtomicLong();

    static final class Factory implements DataSource.Factory {
        private final DefaultHttpDataSourceFactory delegateFactory;

        Factory(String userAgent, TransferListener transferListener) {
            delegateFactory = new DefaultHttpDataSourceFactory(userAgent, transferListener);
        }

        @Override
        public DataSource createDataSource() {
            return new VisionOsPlaybackDataSource(delegateFactory.createDataSource());
        }
    }

    private final HttpDataSource delegate;
    private long openedAtMs;
    private long bytesRead;
    private long requestNumber = -1;

    private VisionOsPlaybackDataSource(HttpDataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        delegate.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        DataSpec request = dataSpec;
        if (isYoutubePostRequest(dataSpec.uri)) {
            Uri requestUri = dataSpec.uri;
            if (isYoutubeVideoPlayback(dataSpec.uri)) {
                requestNumber = REQUEST_NUMBER.getAndIncrement();
                requestUri = dataSpec.uri.buildUpon()
                        .appendQueryParameter("rn", Long.toString(requestNumber))
                        .build();
            }
            request = new DataSpec(
                    requestUri,
                    DataSpec.HTTP_METHOD_POST,
                    POST_BODY,
                    dataSpec.absoluteStreamPosition,
                    dataSpec.position,
                    dataSpec.length,
                    dataSpec.key,
                    dataSpec.flags);
            delegate.setRequestProperty("TE", "trailers");
        }
        openedAtMs = android.os.SystemClock.elapsedRealtime();
        bytesRead = 0;
        Logger.i(this, "Open visionOS stream rn=%s position=%s length=%s method=%s",
                requestNumber, request.position, request.length, request.getHttpMethodString());
        return delegate.open(request);
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        int read = delegate.read(buffer, offset, readLength);
        if (read > 0) {
            bytesRead += read;
        }
        return read;
    }

    @Override
    public Uri getUri() {
        return delegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return delegate.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } finally {
            if (openedAtMs > 0) {
                Logger.i(this, "Close visionOS stream rn=%s bytes=%s elapsedMs=%s",
                        requestNumber, bytesRead,
                        android.os.SystemClock.elapsedRealtime() - openedAtMs);
            }
            openedAtMs = 0;
            bytesRead = 0;
            requestNumber = -1;
        }
    }

    private static boolean isYoutubeVideoPlayback(Uri uri) {
        String path = uri.getPath();
        return path != null && path.startsWith("/videoplayback");
    }

    private static boolean isYoutubePostRequest(Uri uri) {
        String path = uri.getPath();
        return path != null
                && (path.startsWith("/videoplayback")
                || path.contains("/api/manifest/hls"));
    }
}
