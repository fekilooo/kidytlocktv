package free.rm.skytube.businessobjects;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

/**
 * This is an extension of the SSLSocketFactory which enables TLS 1.2 and 1.1.
 * Created for usage on Android 4.1-4.4 devices, which haven't enabled those by default.
 *
 * Based on TLSSocketFactoryCompat from NewPipe.
 */
public class TLSSocketFactory extends SSLSocketFactory {
    private static final String TAG = "TLSSocketFactory";
    private static TLSSocketFactory instance = null;

    private SSLSocketFactory internalSSLSocketFactory;

    public static TLSSocketFactory getInstance() throws NoSuchAlgorithmException, KeyManagementException {
        if (instance != null) {
            return instance;
        }
        return instance = new TLSSocketFactory();
    }

    TLSSocketFactory() throws KeyManagementException, NoSuchAlgorithmException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        internalSSLSocketFactory = context.getSocketFactory();
    }

    public static void installCompatIfNeeded() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            return;
        }
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(getInstance());
            Log.i(TAG, "Installed TLS compatibility socket factory for SDK " + Build.VERSION.SDK_INT);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            Log.e(TAG, "Setup failed: " + e.getMessage(), e);
        }
    }

    public static void configureOkHttpBuilder(OkHttpClient.Builder builder) {
        builder.retryOnConnectionFailure(true);
        builder.connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS));

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
            return;
        }

        builder.protocols(Collections.singletonList(Protocol.HTTP_1_1));
        try {
            final X509TrustManager trustManager = getDefaultTrustManager();
            builder.sslSocketFactory(getInstance(), trustManager);
            Log.i(TAG, "Configured OkHttp TLS compatibility for SDK " + Build.VERSION.SDK_INT);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            Log.e(TAG, "Unable to configure OkHttp TLS compatibility: " + e.getMessage(), e);
        }
    }

    public static String getRuntimeSummary() {
        final String providerName = getTopSecurityProviderName();
        final String webViewPackage = getCurrentWebViewPackage();
        try {
            final TLSSocketFactory socketFactory = getInstance();
            final SSLSocket socket = (SSLSocket) socketFactory.createSocket();
            try {
                return "sdk=" + Build.VERSION.SDK_INT
                        + ", release=" + Build.VERSION.RELEASE
                        + ", model=" + Build.MANUFACTURER + "/" + Build.MODEL
                        + ", provider=" + providerName
                        + ", supportedTls=" + Arrays.toString(socket.getSupportedProtocols())
                        + ", enabledTls=" + Arrays.toString(socket.getEnabledProtocols())
                        + ", webView=" + webViewPackage;
            } finally {
                socket.close();
            }
        } catch (Exception e) {
            return "sdk=" + Build.VERSION.SDK_INT
                    + ", release=" + Build.VERSION.RELEASE
                    + ", model=" + Build.MANUFACTURER + "/" + Build.MODEL
                    + ", provider=" + providerName
                    + ", webView=" + webViewPackage
                    + ", tlsStateError=" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static void logRuntimeSummary() {
        Log.i(TAG, "TLS runtime summary: " + getRuntimeSummary());
    }

    private static X509TrustManager getDefaultTrustManager() throws NoSuchAlgorithmException, KeyManagementException {
        try {
            final TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            final TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (trustManagers.length == 1 && trustManagers[0] instanceof X509TrustManager) {
                return (X509TrustManager) trustManagers[0];
            }
            throw new KeyManagementException("Unexpected default trust managers: " + Arrays.toString(trustManagers));
        } catch (java.security.KeyStoreException e) {
            throw new KeyManagementException("Unable to initialize default trust manager", e);
        }
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return internalSSLSocketFactory.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return internalSSLSocketFactory.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return enableTLSOnSocket(internalSSLSocketFactory.createSocket());
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enableTLSOnSocket(internalSSLSocketFactory.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        return enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
        return enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enableTLSOnSocket(internalSSLSocketFactory.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return enableTLSOnSocket(internalSSLSocketFactory.createSocket(address, port, localAddress, localPort));
    }

    private Socket enableTLSOnSocket(Socket socket) {
        if (socket instanceof SSLSocket) {
            String[] protocols = filterTLS((SSLSocket) socket);
            if (protocols.length > 0) {
                ((SSLSocket) socket).setEnabledProtocols(protocols);
            }
        }
        return socket;
    }

    private static final String TLS_v1 = "TLSv1";
    private static final String TLS_v1_1 = "TLSv1.1";
    private static final String TLS_v1_2 = "TLSv1.2";
    private static final String TLS_v1_3 = "TLSv1.3";
    private static final String[] TLS_VERSIONS = {TLS_v1_3, TLS_v1_2, TLS_v1_1, TLS_v1 };

    private static String[] filterTLS(SSLSocket sslSocket) {
        List<String> supported = Arrays.asList(sslSocket.getSupportedProtocols());
        Log.i(TAG, "is TLS enabled in server:" + supported);
        List<String> result = new ArrayList<>();
        for (String tlsVersion : TLS_VERSIONS) {
            if (supported.contains(tlsVersion)) {
                result.add(tlsVersion);
            }
        }
        Log.i(TAG, "Enabled protocols: " + result);
        return result.toArray(new String[0]);
    }

    private static String getTopSecurityProviderName() {
        final Provider[] providers = Security.getProviders();
        if (providers == null || providers.length == 0) {
            return "none";
        }
        final Provider provider = providers[0];
        return provider.getName() + "/" + provider.getVersion();
    }

    private static String getCurrentWebViewPackage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "unsupported";
        }
        try {
            final PackageInfo packageInfo = WebView.getCurrentWebViewPackage();
            if (packageInfo == null) {
                return "none";
            }
            return packageInfo.packageName + "/" + packageInfo.versionName;
        } catch (Throwable throwable) {
            return "error:" + throwable.getClass().getSimpleName();
        }
    }
}
