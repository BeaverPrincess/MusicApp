package com.example.musicapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioAttributes;
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

public class PlaybackService extends Service {

    public static final String ACTION_TOGGLE = "com.example.musicapp.action.TOGGLE";
    public static final String ACTION_NEXT   = "com.example.musicapp.action.NEXT";
    public static final String ACTION_PREV   = "com.example.musicapp.action.PREV";
    public static final String ACTION_PLAY_SONG = "com.example.musicapp.action.PLAY_SONG";

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

        mediaSession = new MediaSessionCompat(this, "PlaybackService");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { ensurePreparedThenPlay(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { playNext(); }
            @Override public void onSkipToPrevious() { playPrevious(); }
            @Override public void onStop() { stopSelf(); }
        });
        mediaSession.setActive(true);

        // Optional: load library here so Next/Prev works even if Activity never binds.
        loadLibraryNewestFirst();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

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

    // --------- Public API used by MainActivity (bound service) ---------

    public void setLibrarySongs(ArrayList<Song> songs) {
        librarySongs.clear();
        if (songs != null) librarySongs.addAll(songs);
        // keep index sane if possible
        if (!queueSongs.isEmpty()) syncCurrentIndexToSong(queueSongs.get(0));
    }

    public void setQueueSongs(ArrayList<Song> songs) {
        queueSongs.clear();
        if (songs != null) queueSongs.addAll(songs);
        if (!queueSongs.isEmpty()) syncCurrentIndexToSong(queueSongs.get(0));
        updateNotification();
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
        if (mediaPlayer.isPlaying()) pause();
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

        if (Build.VERSION.SDK_INT >= 21) {
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
        } else {
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }

        try {
            mediaPlayer.setDataSource(this, s.uri);
        } catch (IOException e) {
            releasePlayer();
            return;
        }

        mediaPlayer.setOnPreparedListener(mp -> {
            isPrepared = true;
            if (autoPlay) mp.start();
            updateNotification(); // shows correct play/pause icon
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            // Same behavior as your app: queue next if exists, otherwise continue in library
            if (queueSongs.size() > 1) {
                queueSongs.remove(0);
                Song next = queueSongs.get(0);
                playSong(next, true);
            } else {
                playNext();
            }
        });

        mediaPlayer.prepareAsync();

        // Foreground service so playback + controls survive lockscreen/background
        updateNotification();
    }

    private void pause() {
        if (mediaPlayer != null && isPrepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updateNotification();
        }
    }

    private void ensurePreparedThenPlay() {
        if (mediaPlayer != null && isPrepared && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            updateNotification();
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isPrepared = false;
    }

    // --------- Queue/library helpers ---------

    private void setQueueToSingleSong(Song s) {
        queueSongs.clear();
        queueSongs.add(s);
        syncCurrentIndexToSong(s);
        updateNotification();
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
                .setSmallIcon(R.drawable.ic_launcher_foreground) // you can replace with a music icon
                .setContentTitle(title)
                .setContentText(playing ? "Playing" : "Paused")
                .setContentIntent(contentPi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // <-- shows on lock screen
                .setOngoing(playing)
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevPi)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", togglePi)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPi)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));

        Notification notif = b.build();

        // Start or update foreground
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

    // --------- Library load fallback (optional but useful) ---------

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
}
