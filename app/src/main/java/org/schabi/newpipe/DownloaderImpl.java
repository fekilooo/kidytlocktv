package org.schabi.newpipe;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Minimal compatibility adapter used by PipePipe's local BotGuard token generator.
 */
public final class DownloaderImpl {
    private static final DownloaderImpl INSTANCE = new DownloaderImpl();

    private DownloaderImpl() {
    }

    public static DownloaderImpl getInstance() {
        return NewPipe.getDownloader() == null ? null : INSTANCE;
    }

    public Response get(final String url, final Map<String, List<String>> headers)
            throws IOException, ReCaptchaException {
        return NewPipe.getDownloader().get(url, headers);
    }

    public Response post(final String url,
                         final Map<String, List<String>> headers,
                         final byte[] body) throws IOException, ReCaptchaException {
        return NewPipe.getDownloader().post(url, headers, body);
    }
}
