package com.example.musicapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;

import java.io.IOException;
import java.util.ArrayList;
import android.view.KeyEvent;

public class PlaybackService extends Service {

    public static final String ACTION_TOGGLE = "com.example.musicapp.action.TOGGLE";
    public static final String ACTION_NEXT   = "com.example.musicapp.action.NEXT";
    public static final String ACTION_PREV   = "com.example.musicapp.action.PREV";
    public static final String ACTION_PLAY_SONG = "com.example.musicapp.action.PLAY_SONG";

    public static final String ACTION_STATE_CHANGED = "com.example.musicapp.action.STATE_CHANGED";

    public static final String EXTRA_SONG_ID = "extra_song_id";

    private static final String CHANNEL_ID = "music_playback";
    private static final int NOTIF_ID = 42;

    private final IBinder binder = new LocalBinder();

    private MediaPlayer mediaPlayer;
    private boolean isPrepared = false;

    private final ArrayList<Song> queueSongs = new ArrayList<>();
    private final ArrayList<Song> librarySongs = new ArrayList<>();
    private int currentIndex = -1;

    private MediaSessionCompat mediaSession;

    // -----------------------
    // Audio focus
    // -----------------------
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // API 26+
    private boolean resumeOnFocusGain = false;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                if (isPlaying()) pauseInternal(true, false);
                abandonAudioFocus();
                resumeOnFocusGain = false;
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (isPlaying()) {
                    resumeOnFocusGain = true;
                    pauseInternal(true, false);
                }
                break;

            case AudioManager.AUDIOFOCUS_GAIN:
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false;
                    ensurePreparedThenPlay();
                }
                break;
        }
    };

    public class LocalBinder extends Binder {
        public PlaybackService getService() { return PlaybackService.this; }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // -----------------------
        // MediaSession (earphones / Bluetooth controls)
        // -----------------------
        mediaSession = new MediaSessionCompat(this, "PlaybackService");

        // IMPORTANT: tells Android we handle headset media buttons + transport controls
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        // IMPORTANT: connect receiver so ACTION_MEDIA_BUTTON gets routed correctly
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(this, MediaButtonReceiver.class);
        PendingIntent mediaButtonPi = PendingIntent.getBroadcast(
                this,
                0,
                mediaButtonIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        mediaSession.setMediaButtonReceiver(mediaButtonPi);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                KeyEvent ke = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ke == null) return super.onMediaButtonEvent(mediaButtonIntent);

                // Only handle ACTION_DOWN to avoid double-trigger
                if (ke.getAction() != KeyEvent.ACTION_DOWN) return true;

                switch (ke.getKeyCode()) {
                    case KeyEvent.KEYCODE_HEADSETHOOK:
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        togglePlayPause();
                        return true;

                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                        ensurePreparedThenPlay();
                        return true;

                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        pauseInternal(false, true);
                        return true;

                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        playNext();
                        return true;

                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                        playPrevious();
                        return true;
                }
                return super.onMediaButtonEvent(mediaButtonIntent);
            }

            @Override public void onPlay() { ensurePreparedThenPlay(); }
            @Override public void onPause() { pauseInternal(false, true); }
            @Override public void onSkipToNext() { playNext(); }
            @Override public void onSkipToPrevious() { playPrevious(); }
            @Override public void onStop() {
                pauseInternal(false, true);
                stopSelf();
            }
        });

        mediaSession.setActive(true);

        // Optional: load library so Next/Prev works even if Activity never binds.
        loadLibraryNewestFirst();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        // -----------------------
        // NEW: Earphone / Bluetooth media button handling
        // If a headset sends PLAY/PAUSE/NEXT/PREV, Android delivers ACTION_MEDIA_BUTTON.
        // This forwards it into MediaSession callbacks above.
        // -----------------------
        if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            MediaButtonReceiver.handleIntent(mediaSession, intent);
            return START_STICKY;
        }

        // Your existing actions
        String action = intent.getAction();
        if (ACTION_TOGGLE.equals(action)) {
            togglePlayPause();
        } else if (ACTION_NEXT.equals(action)) {
            playNext();
        } else if (ACTION_PREV.equals(action)) {
            playPrevious();
        } else if (ACTION_PLAY_SONG.equals(action)) {
            long songId = intent.getLongExtra(EXTRA_SONG_ID, -1L);
            if (songId != -1L) {
                Song s = findInLibraryById(songId);
                if (s != null) {
                    setQueueToSingleSong(s);
                    playSong(s, true);
                }
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        abandonAudioFocus();
        releasePlayer();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        stopForeground(true);
    }

    public ArrayList<Song> getQueueSnapshot() {
        return new ArrayList<>(queueSongs);
    }

    public Song getCurrentSong() {
        return queueSongs.isEmpty() ? null : queueSongs.get(0);
    }

    // --------- Public API used by MainActivity ---------

    public void setLibrarySongs(ArrayList<Song> songs) {
        librarySongs.clear();
        if (songs != null) librarySongs.addAll(songs);
        if (!queueSongs.isEmpty()) syncCurrentIndexToSong(queueSongs.get(0));
    }

    public void setQueueSongs(ArrayList<Song> songs) {
        queueSongs.clear();
        if (songs != null) queueSongs.addAll(songs);
        if (!queueSongs.isEmpty()) syncCurrentIndexToSong(queueSongs.get(0));
        updateNotification();
        broadcastStateChanged();
    }

    public void playFromQueueHead(boolean autoPlay) {
        if (queueSongs.isEmpty()) return;
        playSong(queueSongs.get(0), autoPlay);
    }

    public boolean isPlaying() {
        return mediaPlayer != null && isPrepared && mediaPlayer.isPlaying();
    }

    public void togglePlayPause() {
        if (mediaPlayer == null || !isPrepared) return;
        if (mediaPlayer.isPlaying()) pauseInternal(false, true);
        else ensurePreparedThenPlay();
    }

    public void playNext() {
        if (queueSongs.isEmpty()) return;

        syncCurrentIndexToSong(queueSongs.get(0));

        if (queueSongs.size() > 1) {
            queueSongs.remove(0);
            Song next = queueSongs.get(0);
            playSong(next, true);
            return;
        }

        if (!librarySongs.isEmpty()) {
            currentIndex = (currentIndex + 1) % librarySongs.size();
            Song s = librarySongs.get(currentIndex);
            setQueueToSingleSong(s);
            playSong(s, true);
        }
    }

    public void playPrevious() {
        if (librarySongs.isEmpty()) return;

        currentIndex = (currentIndex - 1 + librarySongs.size()) % librarySongs.size();
        Song s = librarySongs.get(currentIndex);
        setQueueToSingleSong(s);
        playSong(s, true);
    }

    // --------- Core playback ---------

    private void playSong(Song s, boolean autoPlay) {
        syncCurrentIndexToSong(s);
        releasePlayer();

        mediaPlayer = new MediaPlayer();
        isPrepared = false;

        AudioAttributes attrs = null;

        if (Build.VERSION.SDK_INT >= 21) {
            attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mediaPlayer.setAudioAttributes(attrs);
        } else {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }

        try {
            mediaPlayer.setDataSource(this, s.uri);
        } catch (IOException e) {
            releasePlayer();
            return;
        }

        final AudioAttributes finalAttrs = attrs;

        mediaPlayer.setOnPreparedListener(mp -> {
            isPrepared = true;

            if (autoPlay) {
                boolean focusGranted = requestAudioFocus(finalAttrs);
                if (focusGranted) mp.start();
            }

            updateNotification();
            broadcastStateChanged();
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            if (queueSongs.size() > 1) {
                queueSongs.remove(0);
                Song next = queueSongs.get(0);
                playSong(next, true);
            } else {
                playNext();
            }
        });

        mediaPlayer.prepareAsync();
        updateNotification();
        broadcastStateChanged();
    }

    private void pauseInternal(boolean fromFocusLoss, boolean abandonFocus) {
        if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        updateNotification();
        broadcastStateChanged();

        if (abandonFocus) abandonAudioFocus();
        if (!fromFocusLoss) resumeOnFocusGain = false;
    }

    private void ensurePreparedThenPlay() {
        if (mediaPlayer == null || !isPrepared) return;
        if (mediaPlayer.isPlaying()) return;

        boolean focusGranted = requestAudioFocus(buildFocusAudioAttributesIfNeeded());
        if (!focusGranted) return;

        mediaPlayer.start();
        updateNotification();
        broadcastStateChanged();
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isPrepared = false;
    }

    // --------- Audio focus helpers ---------

    private AudioAttributes buildFocusAudioAttributesIfNeeded() {
        if (Build.VERSION.SDK_INT < 21) return null;
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    private boolean requestAudioFocus(AudioAttributes attrs) {
        if (audioManager == null) return true;

        if (Build.VERSION.SDK_INT >= 26) {
            if (attrs == null) attrs = buildFocusAudioAttributesIfNeeded();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .setAudioAttributes(attrs)
                    .setWillPauseWhenDucked(true)
                    .build();

            int res = audioManager.requestAudioFocus(audioFocusRequest);
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;

        } else {
            int res = audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            }
        } else {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
    }

    // --------- Queue/library helpers ---------

    private void setQueueToSingleSong(Song s) {
        queueSongs.clear();
        queueSongs.add(s);
        syncCurrentIndexToSong(s);
        updateNotification();
        broadcastStateChanged();
    }

    private void syncCurrentIndexToSong(Song s) {
        if (s == null) return;
        for (int i = 0; i < librarySongs.size(); i++) {
            if (librarySongs.get(i).id == s.id) {
                currentIndex = i;
                return;
            }
        }
    }

    private Song findInLibraryById(long id) {
        for (Song s : librarySongs) if (s.id == id) return s;
        return null;
    }

    // --------- Notification / lock screen controls ---------

    private void updateNotification() {
        Song current = queueSongs.isEmpty() ? null : queueSongs.get(0);
        String title = (current == null) ? "Nothing loaded" : current.name;

        boolean playing = isPlaying();

        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(
                this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        PendingIntent prevPi = actionPi(ACTION_PREV, 1);
        PendingIntent togglePi = actionPi(ACTION_TOGGLE, 2);
        PendingIntent nextPi = actionPi(ACTION_NEXT, 3);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(playing ? "Playing" : "Paused")
                .setContentIntent(contentPi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevPi)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", togglePi)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPi)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));

        Notification notif = b.build();
        startForeground(NOTIF_ID, notif);
    }

    private PendingIntent actionPi(String action, int reqCode) {
        Intent i = new Intent(this, PlaybackService.class);
        i.setAction(action);
        return PendingIntent.getService(
                this, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "Music playback",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    // --------- Library load fallback ---------

    private void loadLibraryNewestFirst() {
        librarySongs.clear();

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATE_ADDED
        };

        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        String sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
        )) {
            if (cursor == null || !cursor.moveToFirst()) return;

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);

            do {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                long dateAddedMillis = cursor.getLong(dateCol) * 1000L;

                Uri uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        String.valueOf(id)
                );

                librarySongs.add(new Song(id, name, uri, dateAddedMillis));
            } while (cursor.moveToNext());
        } catch (Exception ignored) {}
    }

    // --------- Broadcast to Activity ---------

    private void broadcastStateChanged() {
        Intent i = new Intent(ACTION_STATE_CHANGED);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }
}
