package com.bravetv.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.*;

import java.util.*;

public class MainActivity extends Activity {

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progressBar;
    private TextView statusText;
    private LinearLayout navBar;

    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
        "ad.youtube.com",
        "ads.youtube.com",
        "googleadservices.com",
        "googlesyndication.com",
        "doubleclick.net",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "adservice.google.com.mx",
        "imasdk.googleapis.com",
        "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com",
        "www.googleadservices.com",
        "analytics.google.com",
        "google-analytics.com",
        "stats.g.doubleclick.net",
        "cm.g.doubleclick.net",
        "ad.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "pubads.g.doubleclick.net",
        "connect.facebook.net",
        "graph.facebook.com",
        "outbrain.com",
        "taboola.com",
        "moatads.com",
        "amazon-adsystem.com",
        "adsafeprotected.com",
        "2mdn.net",
        "adnxs.com"
    ));

    private static final Set<String> AD_URL_PATTERNS = new HashSet<>(Arrays.asList(
        "/pagead/",
        "/ads/",
        "/adview",
        "ad_type=",
        "doubleclick",
        "/ptracking",
        "/get_video_info?",
        "googlevideo.com/videoplayback?ctier=L",
        "youtube.com/api/stats/ads",
        "youtube.com/pagead",
        "youtube.com/ptracking"
    ));

    private boolean isAdUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null) {
                if (AD_HOSTS.contains(host)) return true;
                for (String adHost : AD_HOSTS) {
                    if (host.endsWith("." + adHost) || host.equals(adHost)) return true;
                }
            }
            for (String pattern : AD_URL_PATTERNS) {
                if (url.contains(pattern)) return true;
            }
        } catch (Exception e) { }
        return false;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        webView    = findViewById(R.id.webView);
        urlBar     = findViewById(R.id.urlBar);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        navBar     = findViewById(R.id.navBar);
        setupWebView();
        setupButtons();
        loadUrl("https://www.youtube.com/tv");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) " +
            "AppleWebKit/538.1 (KHTML, like Gecko) " +
            "Version/6.0 TV Safari/538.1"
        );

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (isAdUrl(url)) {
                    return new WebResourceResponse(
                        "text/plain", "utf-8",
                        new java.io.ByteArrayInputStream("".getBytes())
                    );
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                urlBar.setText(url);
                String css =
                    "ytd-display-ad-renderer," +
                    "ytd-promoted-sparkles-web-renderer," +
                    "ytd-promoted-video-renderer," +
                    "ytd-search-pyv-renderer," +
                    ".ytp-ad-module," +
                    ".ytp-ad-overlay-container," +
                    ".ytp-ad-text-overlay," +
                    ".video-ads," +
                    "#player-ads," +
                    "ytm-promoted-sparkles-web-renderer" +
                    "{ display: none !important; }";
                String js = "var style = document.createElement('style');" +
                    "style.innerHTML = '" + css + "';" +
                    "document.head.appendChild(style);";
                view.evaluateJavascript(js, null);
                String skipJs =
                    "setInterval(function(){" +
                    "  var skip = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button');" +
                    "  if(skip){ skip.click(); }" +
                    "  var adBadge = document.querySelector('.ad-showing');" +
                    "  if(adBadge){" +
                    "    var video = document.querySelector('video');" +
                    "    if(video){ video.currentTime = video.duration; }" +
                    "  }" +
                    "}, 300);";
                view.evaluateJavascript(skipJs, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                navBar.setVisibility(View.GONE);
                webView.setVisibility(View.GONE);
                LinearLayout container = findViewById(R.id.fullscreenContainer);
                container.addView(view);
                container.setVisibility(View.VISIBLE);
            }

            @Override
            public void onHideCustomView() {
                LinearLayout container = findViewById(R.id.fullscreenContainer);
                container.removeAllViews();
                container.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                navBar.setVisibility(View.VISIBLE);
            }
        });

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();
    }

    private void setupButtons() {
        findViewById(R.id.btnYoutube).setOnClickListener(v ->
            loadUrl("https://www.youtube.com/tv"));
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        findViewById(R.id.btnForward).setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        findViewById(R.id.btnRefresh).setOnClickListener(v ->
            webView.reload());
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            String url = urlBar.getText().toString().trim();
            if (!url.startsWith("http")) url = "https://" + url;
            loadUrl(url);
            return true;
        });
    }

    private void loadUrl(String url) {
        webView.loadUrl(url);
        urlBar.setText(url);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (webView.canGoBack()) { webView.goBack(); return true; }
                break;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onPause() { super.onPause(); webView.onPause(); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); }
    @Override protected void onDestroy() { super.onDestroy(); webView.destroy(); }
}
