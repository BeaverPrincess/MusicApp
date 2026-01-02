package com.example.musicapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.DragEvent;
import android.view.View;
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
import android.widget.RadioButton;
import android.widget.RadioGroup;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_AUDIO_PERMISSION = 1001;

    private TextView txtStatus;
    private Button btnPrev, btnPlayPause, btnNext;

    private RecyclerView rvQueue, rvLibrary;
    private QueueAdapter queueAdapter;
    private SongsAdapter songsAdapter;

    // Queue: index 0 = currently playing (cannot be removed)
    private final ArrayList<Song> queueSongs = new ArrayList<>();
    private final ArrayList<Song> librarySongs = new ArrayList<>();
    private int currentIndex = -1;

    private MediaPlayer mediaPlayer;
    private boolean isPrepared = false;
    private RadioGroup rgLibraryMode;
    private RadioButton rbSongs, rbPlaylists;

    private PlaylistsAdapter playlistsAdapter;
    private final ArrayList<Playlist> playlists = new ArrayList<>();

    private enum LibraryMode { SONGS, PLAYLISTS }
    private LibraryMode libraryMode = LibraryMode.SONGS;

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

        // Queue RV
        rvQueue.setLayoutManager(new LinearLayoutManager(this));
        rgLibraryMode = findViewById(R.id.rgLibraryMode);
        rbSongs = findViewById(R.id.rbSongs);
        rbPlaylists = findViewById(R.id.rbPlaylists);
        queueAdapter = new QueueAdapter(queueSongs);
        rvQueue.setAdapter(queueAdapter);

        // Library RV
        rvLibrary.setLayoutManager(new LinearLayoutManager(this));
        songsAdapter = new SongsAdapter(librarySongs, (position, song) -> {
            currentIndex = position;

            // Clicking a library song: play immediately and reset queue to just this song
            queueSongs.clear();
            queueSongs.add(song);
            queueAdapter.notifyDataSetChanged();

            playSong(song, true); // no try/catch needed now
        });
        rvLibrary.setAdapter(songsAdapter);

        playlistsAdapter = new PlaylistsAdapter(playlists, (position, playlist) -> {
            txtStatus.setText("Playlist feature coming soon: " + playlist.name);
        });

        // Default mode
        setLibraryMode(LibraryMode.SONGS);

        rgLibraryMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbSongs) {
                setLibraryMode(LibraryMode.SONGS);
            } else if (checkedId == R.id.rbPlaylists) {
                setLibraryMode(LibraryMode.PLAYLISTS);
            }
        });

        btnPlayPause.setOnClickListener(v -> togglePlayPause());
        btnPrev.setOnClickListener(v -> playPrevious());
        btnNext.setOnClickListener(v -> playNext());

        setupDragAndDrop();
        requestAudioPermissionIfNeeded();
    }

    private void setupDragAndDrop() {
        View root = findViewById(R.id.main);

        // Root listens so we can detect "dropped outside the queue"
        root.setOnDragListener((v, event) -> {
            Object ls = event.getLocalState();
            if (!(ls instanceof DragData)) return true;
            DragData d = (DragData) ls;

            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;

                case DragEvent.ACTION_DROP:
                    // If dragging FROM queue and dropped OUTSIDE rvQueue -> remove (except first item)
                    if (DragData.SOURCE_QUEUE.equals(d.source) && d.position > 0) {
                        if (!isDropInsideView(rvQueue, v, event)) {
                            if (d.position < queueSongs.size()) {
                                queueSongs.remove(d.position);
                                queueAdapter.notifyItemRemoved(d.position);
                            }
                            return true; // we handled the drop
                        }
                    }
                    return false; // let rvQueue handle drops inside it

                case DragEvent.ACTION_DRAG_ENDED:
                    return true;
            }
            return true;
        });

        rvQueue.setOnDragListener((v, event) -> {
            Object ls = event.getLocalState();
            if (!(ls instanceof DragData)) return false;
            DragData d = (DragData) ls;

            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return DragData.SOURCE_LIBRARY.equals(d.source) || DragData.SOURCE_QUEUE.equals(d.source);

                case DragEvent.ACTION_DROP: {
                    int targetPos = getDropPositionInQueue(event.getX(), event.getY());

                    if (DragData.SOURCE_LIBRARY.equals(d.source)) {
                        // insert AFTER currently playing (index 0). If queue is empty, insert at 0.
                        int insertPos = queueSongs.isEmpty() ? 0 : Math.max(1, targetPos);
                        if (insertPos > queueSongs.size()) insertPos = queueSongs.size();

                        queueSongs.add(insertPos, d.song);
                        queueAdapter.notifyItemInserted(insertPos);
                        return true;
                    }

                    if (DragData.SOURCE_QUEUE.equals(d.source)) {
                        int from = d.position;
                        if (from <= 0 || from >= queueSongs.size()) return true; // can't move first

                        int to = Math.max(1, targetPos);
                        if (to >= queueSongs.size()) to = queueSongs.size() - 1;
                        if (to == from) return true;

                        Song moved = queueSongs.remove(from);
                        queueSongs.add(to, moved);
                        queueAdapter.notifyItemMoved(from, to);
                        return true;
                    }

                    return false;
                }

                default:
                    return true;
            }
        });
    }

    private boolean isDropInsideView(View targetView, View rootView, DragEvent event) {
        int[] rootLoc = new int[2];
        rootView.getLocationOnScreen(rootLoc);

        float rawX = event.getX() + rootLoc[0];
        float rawY = event.getY() + rootLoc[1];

        int[] targetLoc = new int[2];
        targetView.getLocationOnScreen(targetLoc);

        float left = targetLoc[0];
        float top = targetLoc[1];
        float right = left + targetView.getWidth();
        float bottom = top + targetView.getHeight();

        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom;
    }

    private void setLibraryMode(LibraryMode mode) {
        libraryMode = mode;

        if (mode == LibraryMode.SONGS) {
            txtLibraryTitleSet("All songs (newest first)");
            rvLibrary.setAdapter(songsAdapter);
            songsAdapter.notifyDataSetChanged();
        } else {
            txtLibraryTitleSet("Playlists");
            rvLibrary.setAdapter(playlistsAdapter);

            // Placeholder content (so the screen isn't empty)
            if (playlists.isEmpty()) {
                playlists.add(new Playlist(1, "Favorites", 0));
                playlists.add(new Playlist(2, "Gym Mix", 0));
                playlists.add(new Playlist(3, "Chill", 0));
            }

            playlistsAdapter.notifyDataSetChanged();
        }
    }

    private void txtLibraryTitleSet(String text) {
        TextView title = findViewById(R.id.txtLibraryTitle);
        title.setText(text);
    }


    private int getDropPositionInQueue(float x, float y) {
        View child = rvQueue.findChildViewUnder(x, y);
        if (child == null) return queueSongs.size(); // drop at end
        int pos = rvQueue.getChildAdapterPosition(child);
        return (pos == RecyclerView.NO_POSITION) ? queueSongs.size() : pos;
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
                txtStatus.setText("No music found. Put an MP3 in Internal storage > Music.");
                setControlsEnabled(false);
                songsAdapter.notifyDataSetChanged();
                queueSongs.clear();
                queueAdapter.notifyDataSetChanged();
                return;
            }

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);

            do {
                long id = cursor.getLong(idCol);
                String name = cursor.getString(nameCol);

                // DATE_ADDED is seconds since epoch -> convert to millis
                long dateAddedMillis = cursor.getLong(dateCol) * 1000L;

                Uri uri = Uri.withAppendedPath(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        String.valueOf(id)
                );

                librarySongs.add(new Song(id, name, uri, dateAddedMillis));
            } while (cursor.moveToNext());

            songsAdapter.notifyDataSetChanged();

            // Default: newest first, queue = that song
            currentIndex = 0;
            Song first = librarySongs.get(0);

            queueSongs.clear();
            queueSongs.add(first);
            queueAdapter.notifyDataSetChanged();

            txtStatus.setText("Loaded: " + first.name);
            setControlsEnabled(true);

            // Prepare (don’t autoplay)
            playSong(first, false);

        } catch (Exception e) {
            txtStatus.setText("Error loading music: " + e.getMessage());
            setControlsEnabled(false);
            songsAdapter.notifyDataSetChanged();
            queueSongs.clear();
            queueAdapter.notifyDataSetChanged();
        }
    }

    private void setControlsEnabled(boolean enabled) {
        btnPlayPause.setEnabled(enabled);
        btnPrev.setEnabled(enabled);
        btnNext.setEnabled(enabled);
    }

    // FIX: no "throws IOException" anymore. We handle it inside.
    private void playSong(Song s, boolean autoPlay) {
        releasePlayer();

        mediaPlayer = new MediaPlayer();
        isPrepared = false;

        try {
            mediaPlayer.setDataSource(this, s.uri);
        } catch (IOException e) {
            txtStatus.setText("Error playing: " + e.getMessage());
            releasePlayer();
            return;
        }

        mediaPlayer.setOnPreparedListener(mp -> {
            isPrepared = true;
            if (autoPlay) {
                mp.start();
                btnPlayPause.setText("Pause");
            } else {
                btnPlayPause.setText("Play");
            }
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            // Auto-advance if queue has next
            if (queueSongs.size() > 1) {
                queueSongs.remove(0);
                queueAdapter.notifyItemRemoved(0);

                Song next = queueSongs.get(0);
                txtStatus.setText("Loaded: " + next.name);
                playSong(next, true);
            } else {
                btnPlayPause.setText("Play");
            }
        });

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
        if (queueSongs.isEmpty()) return;

        // If queue has next, advance in queue
        if (queueSongs.size() > 1) {
            queueSongs.remove(0);
            queueAdapter.notifyItemRemoved(0);

            Song next = queueSongs.get(0);
            txtStatus.setText("Loaded: " + next.name);
            playSong(next, true);
            return;
        }

        // Otherwise fallback: cycle through library
        if (!librarySongs.isEmpty()) {
            currentIndex = (currentIndex + 1) % librarySongs.size();
            Song s = librarySongs.get(currentIndex);

            queueSongs.clear();
            queueSongs.add(s);
            queueAdapter.notifyDataSetChanged();

            txtStatus.setText("Loaded: " + s.name);
            playSong(s, true);
        }
    }

    private void playPrevious() {
        if (librarySongs.isEmpty()) return;

        currentIndex = (currentIndex - 1 + librarySongs.size()) % librarySongs.size();
        Song s = librarySongs.get(currentIndex);

        queueSongs.clear();
        queueSongs.add(s);
        queueAdapter.notifyDataSetChanged();

        txtStatus.setText("Loaded: " + s.name);
        playSong(s, true);
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
