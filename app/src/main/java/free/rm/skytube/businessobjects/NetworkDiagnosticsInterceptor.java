package free.rm.skytube.businessobjects;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class NetworkDiagnosticsInterceptor implements Interceptor {
    private static final long MAX_BODY_PREVIEW_BYTES = 8192L;

    @Override
    @NonNull
    public Response intercept(@NonNull Chain chain) throws IOException {
        final Request request = chain.request();
        final long startedAtNs = System.nanoTime();

        try {
            final Response response = chain.proceed(request);
            logResponse(request, response, System.nanoTime() - startedAtNs, null);
            return response;
        } catch (IOException e) {
            logResponse(request, null, System.nanoTime() - startedAtNs, e);
            throw e;
        }
    }

    private void logResponse(@NonNull Request request,
                             Response response,
                             long elapsedNs,
                             IOException networkError) {
        final StringBuilder builder = new StringBuilder()
                .append(new Date()).append('\n')
                .append("runtime=").append(TLSSocketFactory.getRuntimeSummary()).append('\n')
                .append("request=").append(request.method()).append(' ').append(request.url()).append('\n')
                .append("elapsedMs=").append(TimeUnit.NANOSECONDS.toMillis(elapsedNs)).append('\n')
                .append("requestHeaders=").append(sanitizeHeaders(request.headers())).append('\n');

        if (response != null) {
            builder.append("status=").append(response.code()).append(' ').append(response.message()).append('\n')
                    .append("protocol=").append(response.protocol()).append('\n')
                    .append("responseUrl=").append(response.request().url()).append('\n')
                    .append("responseHeaders=").append(sanitizeHeaders(response.headers())).append('\n');

            final String preview = peekBody(response);
            if (!preview.isEmpty()) {
                builder.append("bodyPreview=").append(preview).append('\n');
            }
        }

        if (networkError != null) {
            builder.append("networkError=").append(networkError.getClass().getName())
                    .append(": ").append(networkError.getMessage()).append('\n');
        }

        builder.append("---\n");
        DiagnosticFileLogger.appendHttp(builder.toString());
    }

    @NonNull
    private static String sanitizeHeaders(@NonNull Headers headers) {
        final StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            final String name = headers.name(i);
            builder.append(name).append('=');
            if (isSensitiveHeader(name)) {
                builder.append("<redacted>");
            } else {
                builder.append(headers.value(i));
            }
        }
        return builder.append('}').toString();
    }

    private static boolean isSensitiveHeader(@NonNull String name) {
        final String lower = name.toLowerCase();
        return lower.contains("authorization") || lower.contains("cookie");
    }

    @NonNull
    private static String peekBody(@NonNull Response response) {
        try {
            final ResponseBody responseBody = response.peekBody(MAX_BODY_PREVIEW_BYTES);
            if (responseBody == null) {
                return "";
            }
            final MediaType mediaType = responseBody.contentType();
            if (mediaType != null && !isTextLike(mediaType)) {
                return "";
            }
            final String body = responseBody.string();
            if (body == null || body.isEmpty()) {
                return "";
            }
            return body.replace('\n', ' ').replace('\r', ' ');
        } catch (Exception e) {
            return "<peek-failed:" + e.getClass().getSimpleName() + ": " + e.getMessage() + ">";
        }
    }

    private static boolean isTextLike(@NonNull MediaType mediaType) {
        final String type = mediaType.type();
        if ("text".equalsIgnoreCase(type)) {
            return true;
        }
        final String subtype = mediaType.subtype();
        return subtype != null && (subtype.contains("json")
                || subtype.contains("xml")
                || subtype.contains("html")
                || subtype.contains("javascript")
                || subtype.contains("form"));
    }
}
