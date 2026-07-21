package org.schabi.newpipe.extractor.utils;

import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Android-compatible replacement for the upstream utility class. */
public final class Utils {
    public static final String HTTP = "http://";
    public static final String HTTPS = "https://";
    private static final String UTF_8 = "UTF-8";
    private static final Pattern M_PATTERN = Pattern.compile("(https?)?://m\\.");
    private static final Pattern WWW_PATTERN = Pattern.compile("(https?)?://www\\.");

    private Utils() {
    }

    public static String encodeUrlUtf8(final String string) {
        try {
            return URLEncoder.encode(string, UTF_8);
        } catch (final UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static String decodeUrlUtf8(final String url) {
        try {
            return URLDecoder.decode(url, UTF_8);
        } catch (final UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Nonnull
    public static String removeNonDigitCharacters(@Nonnull final String toRemove) {
        return toRemove.replaceAll("\\D+", "");
    }

    public static long mixedNumberWordToLong(final String numberWord)
            throws NumberFormatException, ParsingException {
        String multiplier = "";
        try {
            multiplier = Parser.matchGroup("[\\d]+([\\.,][\\d]+)?([KMBkmb])+", numberWord, 2);
        } catch (final ParsingException ignored) {
        }
        final double count = Double.parseDouble(
                Parser.matchGroup1("([\\d]+([\\.,][\\d]+)?)", numberWord).replace(",", "."));
        switch (multiplier.toUpperCase()) {
            case "K":
                return (long) (count * 1e3);
            case "M":
                return (long) (count * 1e6);
            case "B":
                return (long) (count * 1e9);
            default:
                return (long) count;
        }
    }

    public static void checkUrl(final String pattern, final String url) throws ParsingException {
        checkUrl(Pattern.compile(pattern), url);
    }

    public static void checkUrl(final Pattern pattern, final String url) throws ParsingException {
        if (isNullOrEmpty(url)) {
            throw new IllegalArgumentException("Url can't be null or empty");
        }
        if (!Parser.isMatch(pattern, url.toLowerCase())) {
            throw new ParsingException("Url doesn't match the pattern");
        }
    }

    public static String replaceHttpWithHttps(final String url) {
        if (url == null) {
            return null;
        }
        if (url.startsWith(HTTP)) {
            return HTTPS + url.substring(HTTP.length());
        }
        return url;
    }

    @Nullable
    public static String getQueryValue(@Nonnull final URL url, final String parameterName) {
        final String urlQuery = url.getQuery();
        if (urlQuery != null) {
            for (final String param : urlQuery.split("&")) {
                final String[] params = param.split("=", 2);
                if (params.length == 2 && decodeUrlUtf8(params[0]).equals(parameterName)) {
                    return decodeUrlUtf8(params[1]);
                }
            }
        }
        return null;
    }

    @Nonnull
    public static URL stringToURL(final String url) throws MalformedURLException {
        try {
            return new URL(url);
        } catch (final MalformedURLException e) {
            if (e.getMessage() != null && e.getMessage().equals("no protocol: " + url)) {
                return new URL(HTTPS + url);
            }
            throw e;
        }
    }

    public static boolean isHTTP(@Nonnull final URL url) {
        final String protocol = url.getProtocol();
        if (!protocol.equals("http") && !protocol.equals("https")) {
            return false;
        }
        return url.getPort() == -1 || url.getPort() == url.getDefaultPort();
    }

    public static String removeMAndWWWFromUrl(final String url) {
        if (M_PATTERN.matcher(url).find()) {
            return url.replace("m.", "");
        }
        if (WWW_PATTERN.matcher(url).find()) {
            return url.replace("www.", "");
        }
        return url;
    }

    @Nonnull
    public static String removeUTF8BOM(@Nonnull final String string) {
        String result = string;
        if (result.startsWith("\uFEFF")) {
            result = result.substring(1);
        }
        if (result.endsWith("\uFEFF")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    @Nonnull
    public static String getBaseUrl(final String url) throws ParsingException {
        try {
            final URL uri = stringToURL(url);
            return uri.getProtocol() + "://" + uri.getAuthority();
        } catch (final MalformedURLException e) {
            final String message = e.getMessage();
            if (message != null && message.startsWith("unknown protocol: ")) {
                return message.substring("unknown protocol: ".length());
            }
            throw new ParsingException("Malformed url: " + url, e);
        }
    }

    public static String followGoogleRedirectIfNeeded(final String url) {
        try {
            final URL decoded = stringToURL(url);
            if (decoded.getHost().contains("google") && decoded.getPath().equals("/url")) {
                return decodeUrlUtf8(Parser.matchGroup1("&url=([^&]+)(?:&|$)", url));
            }
        } catch (final Exception ignored) {
        }
        return url;
    }

    public static boolean isNullOrEmpty(final String string) {
        return string == null || string.isEmpty();
    }

    public static boolean isNullOrEmpty(final Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public static <K, V> boolean isNullOrEmpty(final Map<K, V> map) {
        return map == null || map.isEmpty();
    }

    public static boolean isBlank(final String string) {
        if (string == null) {
            return true;
        }
        for (int i = 0; i < string.length(); i++) {
            if (!Character.isWhitespace(string.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Nonnull
    public static String join(final String delimiter,
                              final String mapJoin,
                              @Nonnull final Map<? extends CharSequence,
                                      ? extends CharSequence> elements) {
        return elements.entrySet().stream()
                .map(entry -> entry.getKey() + mapJoin + entry.getValue())
                .collect(Collectors.joining(delimiter));
    }

    @Nonnull
    public static String nonEmptyAndNullJoin(final CharSequence delimiter,
                                             final String... elements) {
        return Arrays.stream(elements)
                .filter(string -> !isNullOrEmpty(string) && !string.equals("null"))
                .collect(Collectors.joining(delimiter));
    }

    @Nonnull
    public static String getStringResultFromRegexArray(@Nonnull final String input,
                                                       @Nonnull final String[] regexes)
            throws Parser.RegexException {
        return getStringResultFromRegexArray(input, regexes, 0);
    }

    @Nonnull
    public static String getStringResultFromRegexArray(@Nonnull final String input,
                                                       @Nonnull final Pattern[] regexes)
            throws Parser.RegexException {
        return getStringResultFromRegexArray(input, regexes, 0);
    }

    @Nonnull
    public static String getStringResultFromRegexArray(@Nonnull final String input,
                                                       @Nonnull final String[] regexes,
                                                       final int group)
            throws Parser.RegexException {
        return getStringResultFromRegexArray(input,
                Arrays.stream(regexes)
                        .filter(Objects::nonNull)
                        .map(Pattern::compile)
                        .toArray(Pattern[]::new),
                group);
    }

    @Nonnull
    public static String getStringResultFromRegexArray(@Nonnull final String input,
                                                       @Nonnull final Pattern[] regexes,
                                                       final int group)
            throws Parser.RegexException {
        for (final Pattern regex : regexes) {
            try {
                final String result = Parser.matchGroup(regex, input, group);
                if (result != null) {
                    return result;
                }
            } catch (final Parser.RegexException ignored) {
            }
        }
        throw new Parser.RegexException("No regex matched the input on group " + group);
    }
}
