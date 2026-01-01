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
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_AUDIO_PERMISSION = 1001;

    private TextView txtStatus;
    private Button btnPlayPause;

    private RecyclerView rvQueue;
    private RecyclerView rvLibrary;

    private final List<String> queueItems = new ArrayList<>();
    private QueueAdapter queueAdapter;

    private final List<Song> librarySongs = new ArrayList<>();
    private SongsAdapter songsAdapter;

    private MediaPlayer mediaPlayer;
    private Song currentSong;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtStatus = findViewById(R.id.txtStatus);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        rvQueue = findViewById(R.id.rvQueue);
        rvLibrary = findViewById(R.id.rvLibrary);

        // RecyclerViews setup
        rvQueue.setLayoutManager(new LinearLayoutManager(this));
        queueAdapter = new QueueAdapter(queueItems);
        rvQueue.setAdapter(queueAdapter);

        rvLibrary.setLayoutManager(new LinearLayoutManager(this));
        songsAdapter = new SongsAdapter(librarySongs, song -> {
            loadSong(song);
        });
        rvLibrary.setAdapter(songsAdapter);

        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPlayPause.setEnabled(false);

        requestAudioPermissionIfNeeded();
    }

    private void requestAudioPermissionIfNeeded() {
        String perm = (Build.VERSION.SDK_INT >= 33)
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            loadLibrary();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_AUDIO_PERMISSION);
        }
    }

    private void loadLibrary() {
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
            if (cursor == null || !cursor.moveToFirst()) {
                txtStatus.setText("No music found. Put an MP3 in Music and make sure MediaStore sees it.");
                btnPlayPause.setEnabled(false);
                songsAdapter.notifyDataSetChanged();

                queueItems.clear();
                queueAdapter.notifyDataSetChanged();
                return;
            }

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);

            do {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);
                long dateAdded = cursor.getLong(dateCol);

                Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                librarySongs.add(new Song(uri, name, dateAdded));
            } while (cursor.moveToNext());

            songsAdapter.notifyDataSetChanged();

            // Default: play newest (first item)
            loadSong(librarySongs.get(0));

        } catch (Exception e) {
            txtStatus.setText("Error loading library: " + e.getMessage());
            btnPlayPause.setEnabled(false);
        }
    }

    private void loadSong(Song song) {
        currentSong = song;

        txtStatus.setText("Loaded: " + song.name);
        btnPlayPause.setEnabled(true);
        btnPlayPause.setText("Play");

        // Queue: for now, only currently loaded song
        queueItems.clear();
        queueItems.add(song.name);
        queueAdapter.notifyDataSetChanged();

        try {
            preparePlayer(song.uri);
        } catch (IOException e) {
            txtStatus.setText("Error preparing player: " + e.getMessage());
            btnPlayPause.setEnabled(false);
        }
    }

    private void preparePlayer(Uri uri) throws IOException {
        releasePlayer();

        mediaPlayer = new MediaPlayer();
        mediaPlayer.setDataSource(this, uri);

        mediaPlayer.setOnPreparedListener(mp -> {
            // Ready
        });

        mediaPlayer.setOnCompletionListener(mp -> btnPlayPause.setText("Play"));

        mediaPlayer.prepareAsync();
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;

        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            btnPlayPause.setText("Play");
        } else {
            mediaPlayer.start();
            btnPlayPause.setText("Pause");
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
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
                loadLibrary();
            } else {
                txtStatus.setText("Permission denied. Can't read music files.");
                btnPlayPause.setEnabled(false);
            }
        }
    }
}
