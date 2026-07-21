package free.rm.skytube.gui.businessobjects;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import java.util.Locale;

import free.rm.skytube.businessobjects.Logger;

/**
 * Small wrapper around the official YouTube IFrame Player API.
 */
public final class YouTubeIFramePlayerController {
    private static final String BRIDGE_NAME = "SkyTubeBridge";

    public interface Listener {
        void onReady();

        void onStateChanged(int state);

        void onQualityChanged(@NonNull String quality);

        void onError(int errorCode);
    }

    private final WebView webView;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final JavaScriptBridge bridge = new JavaScriptBridge();
    private long currentPositionMs;
    private int playerState = -1;
    private boolean destroyed;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public YouTubeIFramePlayerController(@NonNull WebView webView,
                                         @NonNull Listener listener) {
        this.webView = webView;
        this.listener = listener;

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setBackgroundColor(Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Logger.e(YouTubeIFramePlayerController.this,
                            "IFrame page load failed: %s", error);
                    notifyError(-1);
                }
            }
        });
        webView.addJavascriptInterface(bridge, BRIDGE_NAME);
    }

    public void load(@NonNull String videoId, long startPositionMs) {
        if (destroyed) {
            return;
        }
        currentPositionMs = Math.max(0L, startPositionMs);
        String safeVideoId = videoId.replaceAll("[^A-Za-z0-9_-]", "");
        String origin = "https://" + webView.getContext().getPackageName();
        String html = buildPlayerHtml(
                safeVideoId,
                currentPositionMs / 1000L,
                origin);
        Logger.i(this, "Loading YouTube IFrame video=%s startMs=%d origin=%s",
                safeVideoId, currentPositionMs, origin);
        webView.loadDataWithBaseURL(origin + "/", html, "text/html", "UTF-8", null);
    }

    public void play() {
        evaluate("skytubePlay()");
    }

    public void pause() {
        evaluate("skytubePause()");
    }

    public void seekTo(long positionMs) {
        currentPositionMs = Math.max(0L, positionMs);
        evaluate(String.format(Locale.US, "skytubeSeek(%.3f)", currentPositionMs / 1000d));
    }

    public long getCurrentPositionMs() {
        return currentPositionMs;
    }

    public boolean isPlaying() {
        return playerState == 1;
    }

    public void stop() {
        if (destroyed) {
            return;
        }
        evaluate("skytubeStop()");
        webView.stopLoading();
        webView.loadUrl("about:blank");
        playerState = -1;
    }

    public void destroy() {
        destroyed = true;
        webView.removeJavascriptInterface(BRIDGE_NAME);
        webView.stopLoading();
        webView.loadUrl("about:blank");
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.destroy();
    }

    private void evaluate(@NonNull String script) {
        if (!destroyed) {
            webView.evaluateJavascript("javascript:" + script, null);
        }
    }

    private void notifyError(int errorCode) {
        mainHandler.post(() -> {
            if (!destroyed) {
                listener.onError(errorCode);
            }
        });
    }

    private static String buildPlayerHtml(@NonNull String videoId, long startSeconds,
                                          @NonNull String encodedOrigin) {
        return "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,"
                + "maximum-scale=1,user-scalable=no\">"
                + "<style>html,body,#player{width:100%;height:100%;margin:0;padding:0;"
                + "overflow:hidden;background:#000}</style></head><body>"
                + "<div id=\"player\"></div>"
                + "<script src=\"https://www.youtube.com/iframe_api\"></script>"
                + "<script>"
                + "var player=null,timer=null;"
                + "function callBridge(name,value){try{SkyTubeBridge[name](value);}catch(e){}}"
                + "function onYouTubeIframeAPIReady(){player=new YT.Player('player',{"
                + "videoId:'" + videoId + "',"
                + "playerVars:{autoplay:1,controls:1,disablekb:1,fs:0,playsinline:1,rel:0,"
                + "start:" + startSeconds + ",origin:'" + encodedOrigin + "'},"
                + "events:{onReady:function(e){callBridge('onReady','');e.target.playVideo();"
                + "timer=setInterval(function(){try{callBridge('onTimeUpdate',"
                + "String(player.getCurrentTime()));}catch(x){}},1000);},"
                + "onStateChange:function(e){callBridge('onStateChanged',String(e.data));},"
                + "onPlaybackQualityChange:function(e){callBridge('onQualityChanged',"
                + "String(e.data));},"
                + "onError:function(e){callBridge('onError',String(e.data));}}});}"
                + "function skytubePlay(){if(player&&player.playVideo)player.playVideo();}"
                + "function skytubePause(){if(player&&player.pauseVideo)player.pauseVideo();}"
                + "function skytubeSeek(s){if(player&&player.seekTo)player.seekTo(s,true);}"
                + "function skytubeStop(){if(timer)clearInterval(timer);"
                + "if(player&&player.stopVideo)player.stopVideo();}"
                + "</script></body></html>";
    }

    public final class JavaScriptBridge {
        @JavascriptInterface
        public void onReady(String ignored) {
            mainHandler.post(() -> {
                if (!destroyed) {
                    listener.onReady();
                }
            });
        }

        @JavascriptInterface
        public void onStateChanged(String state) {
            try {
                playerState = Integer.parseInt(state);
            } catch (NumberFormatException ignored) {
                return;
            }
            mainHandler.post(() -> {
                if (!destroyed) {
                    listener.onStateChanged(playerState);
                }
            });
        }

        @JavascriptInterface
        public void onQualityChanged(String quality) {
            mainHandler.post(() -> {
                if (!destroyed) {
                    listener.onQualityChanged(quality);
                }
            });
        }

        @JavascriptInterface
        public void onTimeUpdate(String seconds) {
            try {
                currentPositionMs = Math.max(0L,
                        Math.round(Double.parseDouble(seconds) * 1000d));
            } catch (NumberFormatException ignored) {
                // Keep the last valid playback position.
            }
        }

        @JavascriptInterface
        public void onError(String errorCode) {
            try {
                notifyError(Integer.parseInt(errorCode));
            } catch (NumberFormatException ignored) {
                notifyError(-1);
            }
        }
    }
}
