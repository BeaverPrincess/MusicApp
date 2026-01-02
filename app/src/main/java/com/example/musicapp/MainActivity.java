package com.example.musicapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_AUDIO_PERMISSION = 1001;

    private TextView txtStatus;
    private Button btnPrev, btnPlayPause, btnNext;

    private RecyclerView rvQueue, rvLibrary;
    private QueueAdapter queueAdapter;
    private SongsAdapter songsAdapter;

    private final ArrayList<String> queueItems = new ArrayList<>();
    private final ArrayList<Song> librarySongs = new ArrayList<>();
    private int currentIndex = -1;

    private MediaPlayer mediaPlayer;
    private boolean isPrepared = false;

    // For now: queue is just "currently playing"
    private boolean usingSingleItemQueue = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txtStatus);
        btnPrev = findViewById(R.id.btnPrev);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnNext = findViewById(R.id.btnNext);

        rvQueue = findViewById(R.id.rvQueue);
        rvLibrary = findViewById(R.id.rvLibrary);

        // --- RecyclerViews setup ---
        rvQueue.setLayoutManager(new LinearLayoutManager(this));
        queueAdapter = new QueueAdapter(queueItems);
        rvQueue.setAdapter(queueAdapter);

        rvLibrary.setLayoutManager(new LinearLayoutManager(this));
        songsAdapter = new SongsAdapter(librarySongs, (position, song) -> {
            currentIndex = position;
            playAtIndex(currentIndex, true);
        });
        rvLibrary.setAdapter(songsAdapter);
        // --------------------------

        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPrev.setOnClickListener(v -> playPrevious());
        btnNext.setOnClickListener(v -> playNext());

        requestAudioPermissionIfNeeded();
    }

    private void requestAudioPermissionIfNeeded() {
        String perm = (Build.VERSION.SDK_INT >= 33)
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadLibraryNewestFirst();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_AUDIO_PERMISSION);
        }
    }

    private void loadLibraryNewestFirst() {
        librarySongs.clear();

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME
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
            if (cursor == null || !cursor.moveToFirst()) {
                txtStatus.setText("No music found. Put an MP3 in Internal storage > Music.");
                setControlsEnabled(false);

                // update UI lists
                songsAdapter.notifyDataSetChanged();
                setQueueToCurrent();
                return;
            }

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);

            do {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                Uri uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        String.valueOf(id)
                );
                librarySongs.add(new Song(id, name, uri));
            } while (cursor.moveToNext());

            // tell RecyclerView data changed
            songsAdapter.notifyDataSetChanged();

            // Default: newest first
            currentIndex = 0;
            txtStatus.setText("Loaded: " + librarySongs.get(currentIndex).name);
            setControlsEnabled(true);

            // queue shows currently loaded track
            setQueueToCurrent();

            // Prepare (don’t autoplay)
            preparePlayer(librarySongs.get(currentIndex).uri, false);

        } catch (Exception e) {
            txtStatus.setText("Error loading music: " + e.getMessage());
            setControlsEnabled(false);

            songsAdapter.notifyDataSetChanged();
            setQueueToCurrent();
        }
    }

    private void setQueueToCurrent() {
        queueItems.clear();
        if (currentIndex >= 0 && currentIndex < librarySongs.size()) {
            queueItems.add(librarySongs.get(currentIndex).name);
        }
        if (queueAdapter != null) queueAdapter.notifyDataSetChanged();
    }

    private void setControlsEnabled(boolean enabled) {
        btnPlayPause.setEnabled(enabled);
        btnPrev.setEnabled(enabled);
        btnNext.setEnabled(enabled);
    }

    private void preparePlayer(Uri uri, boolean autoPlay) throws IOException {
        releasePlayer();

        mediaPlayer = new MediaPlayer();
        isPrepared = false;

        mediaPlayer.setDataSource(this, uri);
        mediaPlayer.setOnPreparedListener(mp -> {
            isPrepared = true;
            if (autoPlay) {
                mp.start();
                btnPlayPause.setText("Pause");
            }
        });
        mediaPlayer.setOnCompletionListener(mp -> btnPlayPause.setText("Play"));
        mediaPlayer.prepareAsync();
    }

    private void togglePlayPause() {
        if (mediaPlayer == null || !isPrepared) return;

        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            btnPlayPause.setText("Play");
        } else {
            mediaPlayer.start();
            btnPlayPause.setText("Pause");
        }
    }

    private void playNext() {
        if (librarySongs.isEmpty()) return;

        if (usingSingleItemQueue) {
            currentIndex = (currentIndex + 1) % librarySongs.size();
            playAtIndex(currentIndex, true);
        }
    }

    private void playPrevious() {
        if (librarySongs.isEmpty()) return;

        if (usingSingleItemQueue) {
            currentIndex = (currentIndex - 1 + librarySongs.size()) % librarySongs.size();
            playAtIndex(currentIndex, true);
        }
    }

    private void playAtIndex(int index, boolean autoPlay) {
        if (index < 0 || index >= librarySongs.size()) return;

        Song s = librarySongs.get(index);
        txtStatus.setText("Loaded: " + s.name);
        btnPlayPause.setText("Play");

        // update queue display
        setQueueToCurrent();

        try {
            preparePlayer(s.uri, autoPlay);
        } catch (IOException e) {
            txtStatus.setText("Error playing: " + e.getMessage());
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isPrepared = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadLibraryNewestFirst();
            } else {
                txtStatus.setText("Permission denied. Can't read music files.");
                setControlsEnabled(false);
            }
        }
    }
}
