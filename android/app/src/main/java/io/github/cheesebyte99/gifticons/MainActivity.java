package io.github.cheesebyte99.gifticons;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 우리 기프티콘 — 저장소 맨 위의 웹 파일(index.html 등)을 앱 안에 넣어 WebView 로 띄운다.
 *
 * 페이지는 https://cheesebyte99.github.io/our-gifticons/ 주소로 열리지만,
 * 이 경로의 요청은 WebViewAssetLoader 가 앱 안의 파일로 바로 돌려준다(인터넷 없이도 화면이 열림).
 * 웹판과 출처(origin)가 같아 초대 링크를 브라우저로 열어도 같은 기프티콘함에 들어간다.
 *
 * 앱에서만 더하는 것
 * - 푸시 알림(FCM): app/google-services.json 이 있을 때
 * - 다른 앱(카카오톡 등)에서 기프티콘 이미지를 「공유 → 우리 기프티콘」으로 바로 넣기
 * - 이미지 저장: 휴대폰 「사진/우리기프티콘」 폴더
 */
public class MainActivity extends Activity {

    static final String HOST = "cheesebyte99.github.io";
    static final String BASE_PATH = "/our-gifticons/";
    static final String BASE_URL = "https://" + HOST + BASE_PATH;

    private static final int REQ_FILE = 8;
    private static final int REQ_STORAGE = 9;
    private static final int REQ_NOTIFICATIONS = 7;
    private static final Pattern JOIN_IN_TEXT = Pattern.compile("[?&]join=([A-Za-z0-9-]{16,24})");
    private static final int MAX_SHARED = 10;
    private static final int MAX_SHARED_BYTES = 15 * 1024 * 1024;

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private WebViewAssetLoader assetLoader;
    /** 공유받은 이미지 — 페이지가 takeSharedImages() 로 가져간다 */
    private final List<Uri> sharedImages = new ArrayList<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.app_bg));
        webView = new WebView(this);
        webView.setBackgroundColor(getColor(R.color.app_bg));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyInsets(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage·IndexedDB: 로그인·기기에 담아 둔 기프티콘
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " OurGifticonsApp/1");

        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler(BASE_PATH, new GiftPathHandler(this))
                .build();

        webView.addJavascriptInterface(new Bridge(), "AndroidApp");
        webView.setWebChromeClient(new GiftChrome());
        webView.setWebViewClient(new GiftClient());

        Push.createChannel(this);
        Push.listener = token -> runOnUiThread(() -> sendTokenToPage(token));
        Push.refreshToken(this);

        collectSharedImages(getIntent());
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (webView.getUrl() == null) {
            webView.loadUrl(urlForIntent(getIntent()));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String join = joinFromIntent(intent);
        if (join != null) {
            webView.loadUrl(BASE_URL + "?join=" + join);
            return;
        }
        if (collectSharedImages(intent)) {
            webView.evaluateJavascript("window.__onSharedImages && window.__onSharedImages()", null);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                result = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) result[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    private final class GiftChrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(Intent.createChooser(pick, getString(R.string.pick_images)), REQ_FILE);
                return true;
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATIONS) return;
        boolean granted = Push.hasPermission(this);
        webView.evaluateJavascript("window.__onPushPermission && window.__onPushPermission('"
                + (granted ? "granted" : "denied") + "')", null);
        if (granted) {
            String t = Push.savedToken(this);
            if (!t.isEmpty()) sendTokenToPage(t); else Push.refreshToken(this);
        }
    }

    private void sendTokenToPage(String token) {
        if (webView == null || token == null || token.isEmpty() || !Push.hasPermission(this)) return;
        webView.evaluateJavascript("window.__onPushToken && window.__onPushToken('"
                + token.replace("\\", "").replace("'", "") + "')", null);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Push.listener = null;
        webView.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // 열린 창(추가·크게 보기·설정)이 있으면 닫고, 없으면 앱을 나간다.
        webView.evaluateJavascript("(window.__onBack && window.__onBack()) ? true : false",
                value -> {
                    if (!"true".equals(value)) MainActivity.super.onBackPressed();
                });
    }

    /** 상태바·내비게이션 바·키보드 영역만큼 WebView 를 감싼 틀을 안쪽으로 밀어 넣는다(Android 15 전체 화면 대응). */
    private static void applyInsets(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private static String urlForIntent(Intent intent) {
        String join = joinFromIntent(intent);
        return join != null ? BASE_URL + "?join=" + join : BASE_URL;
    }

    /** 초대 링크(VIEW) 또는 공유받은 글(SEND)에서 초대 코드를 꺼낸다. */
    static String joinFromIntent(Intent intent) {
        if (intent == null) return null;
        String text = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            text = intent.getData().toString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction()) && "text/plain".equals(intent.getType())) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (text == null) return null;
        Matcher m = JOIN_IN_TEXT.matcher(text);
        return m.find() ? m.group(1).toUpperCase().replace("-", "") : null;
    }

    /** 「공유 → 우리 기프티콘」으로 받은 이미지들을 모아 둔다. 받았으면 true */
    @SuppressWarnings("deprecation")
    private boolean collectSharedImages(Intent intent) {
        if (intent == null || intent.getType() == null || !intent.getType().startsWith("image/")) return false;
        List<Uri> found = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) found.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) found.addAll(list);
        }
        if (found.isEmpty()) {
            ClipData clip = intent.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
                if (clip.getItemAt(i).getUri() != null) found.add(clip.getItemAt(i).getUri());
            }
        }
        synchronized (sharedImages) {
            for (Uri u : found) if (sharedImages.size() < MAX_SHARED) sharedImages.add(u);
        }
        return !found.isEmpty();
    }

    private String readAsDataUrl(Uri uri) throws IOException {
        ContentResolver r = getContentResolver();
        String mime = r.getType(uri);
        if (mime == null || !mime.startsWith("image/")) mime = "image/jpeg";
        try (InputStream in = r.openInputStream(uri)) {
            if (in == null) throw new IOException("open failed");
            byte[] bytes = readAll(in, MAX_SHARED_BYTES);
            return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
        }
    }

    // ---------------------------------------------------------------- 이미지 저장

    private void toast(int res) {
        runOnUiThread(() -> Toast.makeText(this, res, Toast.LENGTH_SHORT).show());
    }

    /** data: 주소의 이미지를 휴대폰 「사진/우리기프티콘」 폴더에 저장한다. */
    void saveImage(String url, String name) {
        if (url == null || !url.startsWith("data:image/")) return;
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return; // 허락받은 뒤 다시 누르면 저장된다
        }
        new Thread(() -> {
            try {
                int comma = url.indexOf(',');
                int semi = url.indexOf(';');
                String mime = url.substring(5, semi > 0 && semi < comma ? semi : comma);
                byte[] bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
                String file = (name != null && !name.isEmpty())
                        ? name.replaceAll("[\\\\/:*?\"<>|]", "_")
                        : "기프티콘-" + System.currentTimeMillis() + ".jpg";
                writeToPictures(file, mime, bytes);
                toast(R.string.saved);
            } catch (Exception e) {
                toast(R.string.save_failed);
            }
        }).start();
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) throw new IOException("too large");
        }
        return out.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private void writeToPictures(String file, String mime, byte[] bytes) throws IOException {
        String folder = "우리기프티콘";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, file);
            v.put(MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folder);
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("insert failed");
            try (OutputStream out = r.openOutputStream(uri)) {
                if (out == null) throw new IOException("open failed");
                out.write(bytes);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            r.update(uri, v, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), folder);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
            File f = new File(dir, file);
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
            }
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{mime}, null);
        }
    }

    // ---------------------------------------------------------------- 웹 연결

    /** /our-gifticons/ 아래 요청을 앱 안의 www/ 파일로 돌려준다. */
    private static final class GiftPathHandler implements WebViewAssetLoader.PathHandler {
        private final Context context;

        GiftPathHandler(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public WebResourceResponse handle(@NonNull String path) {
            if (path.isEmpty()) path = "index.html";
            if (path.contains("..")) return null;
            String mime;
            if (path.endsWith(".html")) mime = "text/html";
            else if (path.endsWith(".js")) mime = "text/javascript";
            else if (path.endsWith(".svg")) mime = "image/svg+xml";
            else if (path.endsWith(".png")) mime = "image/png";
            else if (path.endsWith(".webmanifest")) mime = "application/manifest+json";
            else return null; // 그 밖의 경로는 실제 인터넷에서 받는다
            try {
                InputStream in = context.getAssets().open("www/" + path);
                return new WebResourceResponse(mime, "utf-8", in);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private final class GiftClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return assetLoader.shouldInterceptRequest(request.getUrl());
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (HOST.equals(uri.getHost()) && uri.getPath() != null
                    && uri.getPath().startsWith(BASE_PATH.substring(0, BASE_PATH.length() - 1))) {
                return false; // 기프티콘함 안의 이동은 앱에서
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri)); // 그 밖의 링크는 브라우저로
            } catch (ActivityNotFoundException ignored) {
                // 열 앱이 없으면 무시
            }
            return true;
        }
    }

    /** 페이지에서 window.AndroidApp 으로 부르는 기능. */
    private final class Bridge {
        /** 이 기기의 FCM 토큰(아직 없거나 알림을 못 쓰면 빈 문자열) */
        @JavascriptInterface
        public String getPushToken() {
            if (!Push.enabled()) return "";
            String t = Push.savedToken(MainActivity.this);
            if (t.isEmpty()) Push.refreshToken(MainActivity.this);
            return t;
        }

        /** 'granted' | 'default' | 'unavailable' */
        @JavascriptInterface
        public String pushPermission() {
            if (!Push.enabled()) return "unavailable";
            return Push.hasPermission(MainActivity.this) ? "granted" : "default";
        }

        @JavascriptInterface
        public void requestPushPermission() {
            runOnUiThread(() -> {
                if (Push.hasPermission(MainActivity.this)) {
                    onRequestPermissionsResult(REQ_NOTIFICATIONS, new String[0], new int[0]);
                } else if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                } else {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } catch (ActivityNotFoundException ignored) {
                        // 무시
                    }
                    onRequestPermissionsResult(REQ_NOTIFICATIONS, new String[0], new int[0]);
                }
            });
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, getString(R.string.share_title)));
            });
        }

        @JavascriptInterface
        public void saveImage(String url, String name) {
            runOnUiThread(() -> MainActivity.this.saveImage(url, name));
        }

        /** 공유받은 이미지들을 data: 주소 JSON 배열로 넘기고 비운다 */
        @JavascriptInterface
        public String takeSharedImages() {
            List<Uri> list;
            synchronized (sharedImages) {
                list = new ArrayList<>(sharedImages);
                sharedImages.clear();
            }
            JSONArray out = new JSONArray();
            for (Uri u : list) {
                try {
                    out.put(readAsDataUrl(u));
                } catch (Exception e) {
                    // 읽지 못한 이미지는 건너뛴다
                }
            }
            return out.toString();
        }
    }
}
