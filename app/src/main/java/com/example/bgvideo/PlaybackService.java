package com.example.bgvideo;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Foreground media service. The player lives here, not in the Activity,
 * so playback continues when the app is in the background or the screen is off.
 * MediaSessionService posts the media notification, starts/stops foreground
 * automatically and exposes lock-screen Play/Pause controls.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlaybackService extends MediaSessionService {

    private static final String CHANNEL_ID = "video_playback";

    private ExoPlayer player;
    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();

        // Notification channel (created by the provider, API 26+)
        setMediaNotificationProvider(
                new DefaultMediaNotificationProvider.Builder(this)
                        .setChannelId(CHANNEL_ID)
                        .setChannelName(R.string.channel_name)
                        .build());

        player = new ExoPlayer.Builder(this)
                // false = do not take audio focus (video is muted)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), false)
                .setHandleAudioBecomingNoisy(true)
                // keeps CPU and Wi-Fi awake only while playing
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.setVolume(0f); // video without audio

        PendingIntent openApp = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(openApp)
                .build();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    // User swiped the app away: stop playback and the service (saves battery)
    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        player.pause();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
