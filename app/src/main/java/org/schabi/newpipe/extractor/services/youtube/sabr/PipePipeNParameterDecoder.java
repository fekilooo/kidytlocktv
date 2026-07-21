package org.schabi.newpipe.extractor.services.youtube.sabr;

import android.util.Log;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

/**
 * Isolated PipePipe decoder used only for SABR URLs. The legacy extractor and its normal stream
 * URL handling remain unchanged.
 */
final class PipePipeNParameterDecoder {
    private static final String TAG = "PipePipeNDecoder";
    private static final String IFRAME_URL = "https://www.youtube.com/iframe_api";
    private static final String DECODER_URL = "https://api.pipepipe.dev/decoder/decode";
    private static final String USER_AGENT = "PipePipe/5.2.4";
    private static final long PLAYER_ID_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final Pattern ESCAPED_PLAYER_PATTERN =
            Pattern.compile("player\\\\/([a-z0-9]{8})\\\\/");
    private static final Pattern PLAIN_PLAYER_PATTERN =
            Pattern.compile("player/([a-z0-9]{8})/");
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private static volatile String cachedPlayerId;
    private static volatile long playerIdExpiresAt;

    private PipePipeNParameterDecoder() {
    }

    @Nonnull
    static String decode(@Nonnull final String encryptedN) throws ParsingException {
        final String playerId = resolvePlayerId();
        final String cacheKey = playerId + ':' + encryptedN;
        final String cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            final String url = DECODER_URL + "?player=" + playerId + "&n="
                    + URLEncoder.encode(encryptedN, StandardCharsets.UTF_8.name());
            final Map<String, List<String>> headers = new HashMap<>();
            headers.put("User-Agent", Collections.singletonList(USER_AGENT));
            final Response response = NewPipe.getDownloader().get(url, headers);
            if (response.responseCode() < 200 || response.responseCode() >= 300) {
                throw new ParsingException("PipePipe decoder HTTP " + response.responseCode());
            }
            final JsonObject root = JsonParser.object().from(response.responseBody());
            final JsonArray responses = root.getArray("responses");
            if (!"result".equals(root.getString("type"))
                    || responses == null || responses.isEmpty()) {
                throw new ParsingException("PipePipe decoder returned no result");
            }
            final JsonObject result = responses.getObject(0);
            final JsonObject data = result == null ? null : result.getObject("data");
            final String decoded = data == null ? null : data.getString(encryptedN);
            if (!"result".equals(result == null ? null : result.getString("type"))
                    || decoded == null || decoded.isEmpty()) {
                throw new ParsingException("PipePipe decoder returned an empty n parameter");
            }
            CACHE.put(cacheKey, decoded);
            Log.d(TAG, "Decoded SABR n parameter inputLength=" + encryptedN.length()
                    + " outputLength=" + decoded.length());
            return decoded;
        } catch (final ParsingException e) {
            throw e;
        } catch (final Exception e) {
            throw new ParsingException("PipePipe n parameter decoding failed", e);
        }
    }

    @Nonnull
    private static synchronized String resolvePlayerId() throws ParsingException {
        final long now = System.currentTimeMillis();
        if (cachedPlayerId != null && now < playerIdExpiresAt) {
            return cachedPlayerId;
        }
        try {
            final Response response = NewPipe.getDownloader().get(IFRAME_URL);
            final String body = response.responseBody();
            Matcher matcher = ESCAPED_PLAYER_PATTERN.matcher(body);
            if (!matcher.find()) {
                matcher = PLAIN_PLAYER_PATTERN.matcher(body);
                if (!matcher.find()) {
                    throw new ParsingException("Could not find YouTube player ID");
                }
            }
            cachedPlayerId = matcher.group(1);
            playerIdExpiresAt = now + PLAYER_ID_TTL_MS;
            return cachedPlayerId;
        } catch (final ParsingException e) {
            throw e;
        } catch (final Exception e) {
            throw new ParsingException("Could not load YouTube player ID", e);
        }
    }
}
