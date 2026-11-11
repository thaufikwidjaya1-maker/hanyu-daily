package com.hanyudaily.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 42;
    private static final String LOCAL_HOST = "app.hanyudaily.local";
    private static final String LOCAL_URL = "https://" + LOCAL_HOST + "/index.html";
    private static final String CDN_HOST = "cdn.jsdelivr.net";
    private static final String CDN_PREFIX = "/gh/dcmshi/hsk-study-guide@main/";

    private WebView webView;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private boolean pendingRecognition = false;
    private long lastUserInteractionMs = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView.setWebContentsDebuggingEnabled(false);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                tts.setSpeechRate(0.88f);
                tts.setPitch(1.0f);
            }
        });

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(true);
        }

        webView.setWebViewClient(new LockedWebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(request::deny);
            }
        });

        webView.addJavascriptInterface(new NativeBridge(), "HanyuNative");
        webView.loadUrl(LOCAL_URL);
    }

    @Override
    public void onUserInteraction() {
        lastUserInteractionMs = SystemClock.elapsedRealtime();
        super.onUserInteraction();
    }

    private final class LockedWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !isLocalUrl(request.getUrl());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();

            if (isLocalUrl(uri)) {
                return localAssetResponse(uri);
            }

            if (isAllowedCdnRequest(request)) {
                return null;
            }

            return forbiddenResponse();
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Uri uri = Uri.parse(url);
            if (!isLocalUrl(uri)) {
                view.stopLoading();
            }
            super.onPageStarted(view, url, favicon);
        }
    }

    private boolean isLocalUrl(Uri uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && LOCAL_HOST.equalsIgnoreCase(uri.getHost());
    }

    private boolean isAllowedCdnRequest(WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (uri == null) return false;
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        if (!CDN_HOST.equalsIgnoreCase(uri.getHost())) return false;
        String path = uri.getPath();
        return path != null
                && path.startsWith(CDN_PREFIX)
                && path.endsWith(".csv");
    }

    private WebResourceResponse localAssetResponse(Uri uri) {
        String path = uri.getPath();
        if (path == null || path.equals("/") || path.equals("/index.html")) {
            path = "index.html";
        } else {
            path = path.startsWith("/") ? path.substring(1) : path;
        }

        if (path.contains("..")) return forbiddenResponse();

        try {
            InputStream stream = getAssets().open("www/" + path);
            String mime = mimeFor(path);
            return new WebResourceResponse(mime, "UTF-8", stream);
        } catch (IOException e) {
            return notFoundResponse();
        }
    }

    private String mimeFor(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private WebResourceResponse forbiddenResponse() {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Forbidden",
                Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0])
        );
    }

    private WebResourceResponse notFoundResponse() {
        return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not Found",
                Collections.emptyMap(),
                new ByteArrayInputStream(new byte[0])
        );
    }

    public class NativeBridge {
        @JavascriptInterface
        public void speak(String text) {
            if (text == null) return;
            final String safeText = text.length() > 500 ? text.substring(0, 500) : text;
            runOnUiThread(() -> {
                if (tts != null) {
                    tts.stop();
                    tts.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, "hanyu-daily");
                }
            });
        }

        @JavascriptInterface
        public void startRecognition() {
            runOnUiThread(() -> {
                long age = SystemClock.elapsedRealtime() - lastUserInteractionMs;
                if (age < 0 || age > 3000L) {
                    jsError("Tekan tombol speaking terlebih dahulu untuk mengaktifkan mikrofon.");
                    return;
                }
                ensureAudioPermissionAndStart();
            });
        }
    }

    private void ensureAudioPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingRecognition = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        startNativeRecognition();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && pendingRecognition) {
            pendingRecognition = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startNativeRecognition();
            } else {
                jsError("Izin mikrofon ditolak. Kamu tetap bisa menjawab keras-keras lalu cek contoh jawaban.");
            }
        }
    }

    private void startNativeRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            jsError("Speech recognition tidak tersedia di perangkat ini.");
            return;
        }

        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                jsError("Mic tidak berhasil menangkap jawaban. Coba lagi atau cek contoh jawaban.");
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) jsResult(list.get(0));
                else jsError("Belum ada ucapan yang terbaca. Coba lagi.");
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        recognizer.startListening(intent);
    }

    private void jsResult(String text) {
        String quoted = JSONObject.quote(text);
        runOnUiThread(() -> webView.evaluateJavascript(
                "if(window.onNativeSpeechResult)window.onNativeSpeechResult(" + quoted + ");", null));
    }

    private void jsError(String message) {
        String quoted = JSONObject.quote(message);
        runOnUiThread(() -> webView.evaluateJavascript(
                "if(window.onNativeSpeechError)window.onNativeSpeechError(" + quoted + ");", null));
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (webView != null) {
            webView.removeJavascriptInterface("HanyuNative");
            webView.destroy();
        }
        super.onDestroy();
    }
}
