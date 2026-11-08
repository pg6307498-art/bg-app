package com.example.bgvideo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EditText urlInput;
    private TextView statusText;
    private PlayerView playerView;
    private WebView webView;

    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    toast("Notifications are off, so lock-screen controls will not appear");
                }
            });

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            setStatus(isPlaying ? "Playing (muted)" : "Paused");
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_BUFFERING) setStatus("Buffering...");
            else if (state == Player.STATE_ENDED) setStatus("Finished");
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            setStatus("Playback error: " + error.getErrorCodeName());
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.url_input);
        statusText = findViewById(R.id.status_text);
        playerView = findViewById(R.id.player_view);
        webView = findViewById(R.id.web_view);
        Button loadBtn = findViewById(R.id.load_btn);
        Button stopBtn = findViewById(R.id.stop_btn);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) toast("Page failed to load");
            }
        });

        loadBtn.setOnClickListener(v -> loadUrl());
        stopBtn.setOnClickListener(v -> stopPlayback());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        // Android 13+: runtime permission needed to show the media notification
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        webView.onResume();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(playerListener);
                if (controller.getMediaItemCount() > 0) {
                    playerView.setVisibility(View.VISIBLE);
                    playerView.setPlayer(controller); // reattach to ongoing playback
                }
            } catch (Exception e) {
                setStatus("Could not connect to the player service");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        // Playback continues in PlaybackService; only the UI detaches here.
        playerView.setPlayer(null);
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        webView.onPause();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        webView.loadUrl("about:blank");
        webView.destroy();
        super.onDestroy();
    }

    // ---------- Load ----------

    private void loadUrl() {
        String raw = urlInput.getText().toString().trim();
        if (raw.isEmpty()) {
            setStatus("Enter a URL first");
            return;
        }
        if (raw.toLowerCase(Locale.ROOT).startsWith("http://")) {
            setStatus("Only https:// links are allowed");
            return;
        }
        if (!raw.toLowerCase(Locale.ROOT).startsWith("https://")) {
            raw = "https://" + raw;
        }
        if (raw.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|webm|m3u8)([?#].*)?$")) {
            playDirect(raw);
        } else {
            showWebPage(raw);
        }
    }

    private void playDirect(String url) {
        if (controller == null) {
            toast("Player is still connecting, try again");
            return;
        }
        webView.loadUrl("about:blank");
        webView.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        playerView.setPlayer(controller);

        controller.setMediaItem(MediaItem.fromUri(url));
        controller.prepare();
        controller.play();
        setStatus("Loading...");
    }

    private void showWebPage(String raw) {
        if (controller != null) {
            controller.pause();
        }
        playerView.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);

        Uri uri = Uri.parse(raw);
        String ytId = youTubeId(uri);
        String igEmbed = instagramEmbed(uri);

        if (!ytId.isEmpty()) {
            String html = "<html><body style='margin:0;background:#000'>"
                    + "<iframe width='100%' height='100%' src='https://www.youtube.com/embed/" + ytId
                    + "?playsinline=1' frameborder='0' "
                    + "allow='autoplay; encrypted-media; picture-in-picture' allowfullscreen></iframe>"
                    + "</body></html>";
            webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null);
            setStatus("YouTube embed (plays only while the app is open)");
        } else if (!igEmbed.isEmpty()) {
            webView.loadUrl(igEmbed);
            setStatus("Instagram embed (public posts only)");
        } else {
            webView.loadUrl(raw);
            setStatus("Page loaded (browsing only)");
        }
    }

    private void stopPlayback() {
        if (controller != null) {
            controller.stop();
            controller.clearMediaItems();
        }
        playerView.setVisibility(View.GONE);
        setStatus("Stopped");
    }

    // ---------- URL helpers ----------

    private String youTubeId(Uri u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        if (host.startsWith("m.")) host = host.substring(2);
        List<String> seg = u.getPathSegments();
        String id = "";
        if (host.equals("youtu.be")) {
            if (!seg.isEmpty()) id = seg.get(0);
        } else if (host.equals("youtube.com")) {
            String v = u.getQueryParameter("v");
            if (v != null) {
                id = v;
            } else if (seg.size() >= 2 && (seg.get(0).equals("shorts")
                    || seg.get(0).equals("embed") || seg.get(0).equals("live"))) {
                id = seg.get(1);
            }
        }
        return id.matches("[A-Za-z0-9_-]{6,20}") ? id : "";
    }

    private String instagramEmbed(Uri u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);
        if (!host.equals("instagram.com")) return "";
        List<String> seg = u.getPathSegments();
        for (int i = 0; i < seg.size() - 1; i++) {
            String s = seg.get(i);
            if (s.equals("p") || s.equals("reel") || s.equals("tv")) {
                return "https://www.instagram.com/" + s + "/" + seg.get(i + 1) + "/embed/";
            }
        }
        return "";
    }

    // ---------- UI helpers ----------

    private void setStatus(String s) {
        statusText.setText("Status: " + s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
