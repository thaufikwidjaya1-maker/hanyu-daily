package com.hanyudaily.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO = 42;
    private WebView webView;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private boolean pendingRecognition = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "HanyuNative");
        webView.loadUrl("file:///android_asset/www/index.html");
    }

    public class NativeBridge {
        @JavascriptInterface
        public void speak(String text) {
            runOnUiThread(() -> {
                if (tts != null) {
                    tts.stop();
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hanyu-daily");
                }
            });
        }

        @JavascriptInterface
        public void startRecognition() {
            runOnUiThread(() -> ensureAudioPermissionAndStart());
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
            @Override public void onError(int error) { jsError("Mic tidak berhasil menangkap jawaban. Coba lagi atau cek contoh jawaban."); }
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
        if (recognizer != null) recognizer.destroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
