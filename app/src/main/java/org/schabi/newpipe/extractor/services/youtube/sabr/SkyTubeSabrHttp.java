package org.schabi.newpipe.extractor.services.youtube.sabr;

import org.schabi.newpipe.extractor.downloader.StreamingResponse;
import org.schabi.newpipe.extractor.localization.Localization;

import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Streaming transport kept separate from NewPipe's global downloader so SABR can coexist with the
 * legacy extractor and avoid buffering large media batches into memory.
 */
final class SkyTubeSabrHttp {
    private static final String TAG = "SkyTubeSabrHttp";
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) "
                    + "Gecko/20100101 Firefox/128.0";
    private static final OkHttpClient CLIENT = createClient();

    private SkyTubeSabrHttp() {
    }

    private static OkHttpClient createClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS);
        return builder.build();
    }

    static StreamingResponse postStreaming(final String url,
                                            final Map<String, List<String>> headers,
                                            final byte[] data,
                                            final Localization localization) throws IOException {
        Request.Builder request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(null, data == null ? new byte[0] : data));
        if (!containsHeader(headers, "User-Agent")) {
            request.header("User-Agent", DEFAULT_USER_AGENT);
        }
        if (headers != null) {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                if (header.getKey() == null || header.getValue() == null) {
                    continue;
                }
                for (String value : header.getValue()) {
                    if (value != null) {
                        request.addHeader(header.getKey(), value);
                    }
                }
            }
        }

        final Request builtRequest = request.build();
        final long startedAt = System.currentTimeMillis();
        Log.d(TAG, "POST start host=" + builtRequest.url().host()
                + " bytes=" + (data == null ? 0 : data.length));
        final Response response = CLIENT.newCall(builtRequest).execute();
        Log.d(TAG, "POST headers host=" + builtRequest.url().host()
                + " status=" + response.code()
                + " protocol=" + response.protocol()
                + " contentType=" + response.header("Content-Type")
                + " elapsedMs=" + (System.currentTimeMillis() - startedAt));
        final ResponseBody body = response.body();
        final InputStream stream = body == null
                ? new ByteArrayInputStream(new byte[0])
                : body.byteStream();
        return new StreamingResponse(response.code(), response.headers().toMultimap(), stream);
    }

    static StreamingResponse getStreaming(final String url,
                                           final Map<String, List<String>> headers,
                                           final Localization localization,
                                           final long timeoutMs) throws IOException {
        final Request.Builder request = new Request.Builder().url(url).get();
        addHeaders(request, headers);
        final OkHttpClient client = timeoutMs > 0
                ? CLIENT.newBuilder()
                        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .build()
                : CLIENT;
        final Response response = client.newCall(request.build()).execute();
        final ResponseBody body = response.body();
        final InputStream stream = body == null
                ? new ByteArrayInputStream(new byte[0])
                : body.byteStream();
        return new StreamingResponse(response.code(), response.headers().toMultimap(), stream);
    }

    private static void addHeaders(final Request.Builder request,
                                   final Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            if (header.getKey() == null || header.getValue() == null) {
                continue;
            }
            for (String value : header.getValue()) {
                if (value != null) {
                    request.addHeader(header.getKey(), value);
                }
            }
        }
    }

    private static boolean containsHeader(final Map<String, List<String>> headers,
                                          final String name) {
        if (headers == null) {
            return false;
        }
        for (String headerName : headers.keySet()) {
            if (name.equalsIgnoreCase(headerName)) {
                return true;
            }
        }
        return false;
    }
}
