package com.example.musicapp;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.net.Uri;
import android.view.DragEvent;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_AUDIO_PERMISSION = 1001;
    private static final int REQ_NOTIF_PERMISSION = 1002;

    // UI
    private TextView txtStatus;
    private TextView txtLibraryTitle;
    private Button btnPrev, btnPlayPause, btnNext;
    private Button btnPlaylistBack;
    private RadioGroup rgLibraryMode;
    private RadioButton rbSongs, rbPlaylists;
    private RecyclerView rvQueue, rvLibrary;

    // Adapters
    private QueueAdapter queueAdapter;
    private SongsAdapter songsAdapter;          // All songs
    private SongsAdapter playlistSongsAdapter;  // Songs inside a playlist
    private PlaylistsAdapter playlistsAdapter;

    // Data
    private final ArrayList<Song> queueSongs = new ArrayList<>();
    private final ArrayList<Song> librarySongs = new ArrayList<>();
    private final ArrayList<Playlist> playlists = new ArrayList<>();
    private final Map<Long, ArrayList<Song>> playlistToSongs = new HashMap<>();
    private final ArrayList<Song> playlistViewSongs = new ArrayList<>();

    private Playlist currentPlaylist = null;
    private int currentIndex = -1;

    // Service
    private PlaybackService playbackService;
    private boolean serviceBound = false;

    private enum LibraryMode { SONGS, PLAYLISTS }
    private LibraryMode libraryMode = LibraryMode.SONGS;

    private enum Screen { MAIN, PLAYLIST_DETAIL }
    private Screen screen = Screen.MAIN;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PlaybackService.LocalBinder b = (PlaybackService.LocalBinder) service;
            playbackService = b.getService();
            serviceBound = true;

            // Always push library so Next/Prev on lockscreen matches your real library ordering
            playbackService.setLibrarySongs(librarySongs);

            // Pull queue from service (source of truth: service can change it via lockscreen)
            ArrayList<Song> svcQueue = playbackService.getQueueSnapshot();
            if (svcQueue != null && !svcQueue.isEmpty()) {
                queueSongs.clear();
                queueSongs.addAll(svcQueue);
                queueAdapter.notifyDataSetChanged();

                Song current = playbackService.getCurrentSong();
                if (current != null) {
                    updateLoadedStatus(current);
                    syncCurrentIndexToSong(current);
                }
                setControlsEnabled(true);
                refreshPlayPauseText();
            } else {
                // Service has no queue yet; push whatever the Activity currently has
                playbackService.setQueueSongs(queueSongs);

                if (!queueSongs.isEmpty()) {
                    Song s = queueSongs.get(0);
                    updateLoadedStatus(s);
                    setControlsEnabled(true);
                    // Don’t force autoplay here; just load if needed
                    playbackService.playFromQueueHead(false);
                    refreshPlayPauseText();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            playbackService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupRecyclerViews();
        setupAdapters();
        setupListeners();
        setupDragAndDrop();

        setLibraryMode(LibraryMode.SONGS);

        requestNotificationPermissionIfNeeded();
        requestAudioPermissionIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Ensure service exists and bind to it
        Intent i = new Intent(this, PlaybackService.class);
        startService(i);
        bindService(i, serviceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    // -----------------------
    // Init / setup
    // -----------------------

    private void bindViews() {
        txtStatus = findViewById(R.id.txtStatus);
        txtLibraryTitle = findViewById(R.id.txtLibraryTitle);

        btnPrev = findViewById(R.id.btnPrev);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnNext = findViewById(R.id.btnNext);

        btnPlaylistBack = findViewById(R.id.btnPlaylistBack);

        rgLibraryMode = findViewById(R.id.rgLibraryMode);
        rbSongs = findViewById(R.id.rbSongs);
        rbPlaylists = findViewById(R.id.rbPlaylists);

        rvQueue = findViewById(R.id.rvQueue);
        rvLibrary = findViewById(R.id.rvLibrary);
    }

    private void setupRecyclerViews() {
        rvQueue.setLayoutManager(new LinearLayoutManager(this));
        rvLibrary.setLayoutManager(new LinearLayoutManager(this));

        queueAdapter = new QueueAdapter(queueSongs);
        rvQueue.setAdapter(queueAdapter);
    }

    private void setupAdapters() {
        // All songs
        songsAdapter = new SongsAdapter(
                librarySongs,
                (position, song) -> {
                    currentIndex = position;

                    // Clicking a library song: play immediately and reset queue to just this song
                    setQueueToSingleSong(song);
                    updateLoadedStatus(song);
                    setControlsEnabled(true);

                    syncQueueToService();
                    playHeadInService(true);
                },
                this::showSongHoldMenu
        );

        // Playlist detail list (no long press)
        playlistSongsAdapter = new SongsAdapter(
                playlistViewSongs,
                (position, song) -> playFromPlaylist(position),
                null
        );

        // Playlists list
        playlistsAdapter = new PlaylistsAdapter(
                playlists,
                (position, playlist) -> openPlaylist(playlist)
        );
    }

    private void setupListeners() {
        btnPlaylistBack.setOnClickListener(v -> closePlaylist());

        rgLibraryMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (screen == Screen.PLAYLIST_DETAIL) return; // ignore toggle inside playlist
            if (checkedId == R.id.rbSongs) setLibraryMode(LibraryMode.SONGS);
            else if (checkedId == R.id.rbPlaylists) setLibraryMode(LibraryMode.PLAYLISTS);
        });

        btnPlayPause.setOnClickListener(v -> {
            if (!serviceBound) return;
            playbackService.togglePlayPause();
            refreshPlayPauseText();
        });

        btnNext.setOnClickListener(v -> {
            if (!serviceBound) return;
            playbackService.playNext();

            // Pull fresh queue after service advanced
            pullQueueFromServiceAndRefreshUI();
        });

        btnPrev.setOnClickListener(v -> {
            if (!serviceBound) return;
            playbackService.playPrevious();

            // Pull fresh queue after service moved
            pullQueueFromServiceAndRefreshUI();
        });
    }

    // -----------------------
    // Screen / mode switching
    // -----------------------

    private void setLibraryMode(LibraryMode mode) {
        libraryMode = mode;
        screen = Screen.MAIN;

        btnPlaylistBack.setVisibility(View.GONE);
        rgLibraryMode.setVisibility(View.VISIBLE);

        if (mode == LibraryMode.SONGS) {
            txtLibraryTitle.setText("All songs (newest first)");
            rvLibrary.setAdapter(songsAdapter);
            songsAdapter.notifyDataSetChanged();
        } else {
            txtLibraryTitle.setText("Playlists");
            rvLibrary.setAdapter(playlistsAdapter);

            seedPlaylistsIfEmpty();
            refreshAllPlaylistCounts();
            playlistsAdapter.notifyDataSetChanged();
        }
    }

    private void openPlaylist(Playlist playlist) {
        screen = Screen.PLAYLIST_DETAIL;
        currentPlaylist = playlist;

        btnPlaylistBack.setVisibility(View.VISIBLE);
        rgLibraryMode.setVisibility(View.GONE);

        txtLibraryTitle.setText(playlist.name);

        playlistViewSongs.clear();
        ArrayList<Song> stored = playlistToSongs.get(playlist.id);
        if (stored != null) playlistViewSongs.addAll(stored);

        rvLibrary.setAdapter(playlistSongsAdapter);
        playlistSongsAdapter.notifyDataSetChanged();
    }

    private void closePlaylist() {
        if (screen != Screen.PLAYLIST_DETAIL) return;

        currentPlaylist = null;
        screen = Screen.MAIN;

        // Return to playlists list
        rbPlaylists.setChecked(true);
        setLibraryMode(LibraryMode.PLAYLISTS);
    }

    private void seedPlaylistsIfEmpty() {
        // Placeholder playlists
        if (playlists.isEmpty()) {
            playlists.add(new Playlist(1, "Favorites", 0));
            playlists.add(new Playlist(2, "Gym Mix", 0));
            playlists.add(new Playlist(3, "Chill", 0));
        }
    }

    // -----------------------
    // Playlist playback
    // -----------------------

    private void playFromPlaylist(int clickedPos) {
        if (clickedPos < 0 || clickedPos >= playlistViewSongs.size()) return;

        // Replace whole queue with that playlist, starting from clicked song
        buildQueueFromListStartingAt(playlistViewSongs, clickedPos);
        queueAdapter.notifyDataSetChanged();

        if (!queueSongs.isEmpty()) {
            Song first = queueSongs.get(0);
            updateLoadedStatus(first);
            setControlsEnabled(true);

            syncQueueToService();
            playHeadInService(true);
        }
    }

    private void buildQueueFromListStartingAt(ArrayList<Song> list, int startPos) {
        queueSongs.clear();
        for (int i = startPos; i < list.size(); i++) queueSongs.add(list.get(i));
        for (int i = 0; i < startPos; i++) queueSongs.add(list.get(i));
    }

    // -----------------------
    // Long press menu (All songs)
    // -----------------------

    private void showSongHoldMenu(Song song) {
        new AlertDialog.Builder(this)
                .setTitle(song.name)
                .setItems(new CharSequence[]{"Add to queue", "Add to playlist"}, (dlg, which) -> {
                    if (which == 0) addSongToQueue(song);
                    else showPlaylistPicker(song);
                })
                .show();
    }

    private void addSongToQueue(Song song) {
        int insertPos = queueSongs.size();
        queueSongs.add(song);
        queueAdapter.notifyItemInserted(insertPos);

        syncQueueToService();

        Toast.makeText(this, "Added to queue: " + song.name, Toast.LENGTH_SHORT).show();
    }

    private void showPlaylistPicker(Song song) {
        if (playlists.isEmpty()) {
            Toast.makeText(this, "No playlists yet", Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence[] names = new CharSequence[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) names[i] = playlists.get(i).name;

        new AlertDialog.Builder(this)
                .setTitle("Add to playlist")
                .setItems(names, (dlg, which) -> addSongToPlaylist(song, playlists.get(which)))
                .show();
    }

    private void addSongToPlaylist(Song song, Playlist playlist) {
        ArrayList<Song> list = playlistToSongs.get(playlist.id);
        if (list == null) {
            list = new ArrayList<>();
            playlistToSongs.put(playlist.id, list);
        }

        // Prevent duplicates by song.id
        for (Song s : list) {
            if (s.id == song.id) {
                Toast.makeText(this, "Already in " + playlist.name, Toast.LENGTH_SHORT).show();
                return;
            }
        }

        list.add(song);
        playlist.songCount = list.size();

        // If we are currently viewing this playlist, update the displayed list immediately
        if (screen == Screen.PLAYLIST_DETAIL && currentPlaylist != null && currentPlaylist.id == playlist.id) {
            playlistViewSongs.clear();
            playlistViewSongs.addAll(list);
            playlistSongsAdapter.notifyDataSetChanged();
        }

        // If playlists list is on screen, update counts
        if (screen == Screen.MAIN && libraryMode == LibraryMode.PLAYLISTS) {
            playlistsAdapter.notifyDataSetChanged();
        }

        Toast.makeText(this, "Added to " + playlist.name, Toast.LENGTH_SHORT).show();
    }

    private void refreshAllPlaylistCounts() {
        for (Playlist p : playlists) {
            ArrayList<Song> list = playlistToSongs.get(p.id);
            p.songCount = (list == null) ? 0 : list.size();
        }
    }

    // -----------------------
    // Drag & drop: ONLY queue items can be dragged; dropping outside removes (except first)
    // -----------------------

    private void setupDragAndDrop() {
        View root = findViewById(R.id.main);

        root.setOnDragListener((v, event) -> {
            Object ls = event.getLocalState();
            if (!(ls instanceof DragData)) return true;
            DragData d = (DragData) ls;

            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;

                case DragEvent.ACTION_DROP:
                    if (DragData.SOURCE_QUEUE.equals(d.source) && d.position > 0) {
                        if (!isDropInsideView(rvQueue, v, event)) {
                            if (d.position < queueSongs.size()) {
                                queueSongs.remove(d.position);
                                queueAdapter.notifyItemRemoved(d.position);
                                syncQueueToService();
                            }
                            return true;
                        }
                    }
                    return false;

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
                    return DragData.SOURCE_QUEUE.equals(d.source);

                case DragEvent.ACTION_DROP: {
                    int targetPos = getDropPositionInQueue(event.getX(), event.getY());

                    if (DragData.SOURCE_QUEUE.equals(d.source)) {
                        int from = d.position;
                        if (from <= 0 || from >= queueSongs.size()) return true;

                        int to = Math.max(1, targetPos);
                        if (to >= queueSongs.size()) to = queueSongs.size() - 1;
                        if (to == from) return true;

                        Song moved = queueSongs.remove(from);
                        queueSongs.add(to, moved);
                        queueAdapter.notifyItemMoved(from, to);

                        syncQueueToService();
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

    private int getDropPositionInQueue(float x, float y) {
        View child = rvQueue.findChildViewUnder(x, y);
        if (child == null) return queueSongs.size();
        int pos = rvQueue.getChildAdapterPosition(child);
        return (pos == RecyclerView.NO_POSITION) ? queueSongs.size() : pos;
    }

    // -----------------------
    // Permissions + media loading
    // -----------------------

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;

        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQ_NOTIF_PERMISSION
        );
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
                handleNoMusicFound();
                return;
            }

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

            songsAdapter.notifyDataSetChanged();

            // Push library to service so lockscreen next/prev uses correct list
            if (serviceBound) playbackService.setLibrarySongs(librarySongs);

            // Only auto-initialize queue if service has nothing loaded
            boolean serviceHasQueue = serviceBound
                    && playbackService.getQueueSnapshot() != null
                    && !playbackService.getQueueSnapshot().isEmpty();

            if (!serviceHasQueue) {
                currentIndex = 0;
                Song first = librarySongs.get(0);

                setQueueToSingleSong(first);
                updateLoadedStatus(first);
                setControlsEnabled(true);

                syncQueueToService();
                playHeadInService(false); // load only, no autoplay
                refreshPlayPauseText();
            } else {
                // If service already playing, keep UI in sync
                pullQueueFromServiceAndRefreshUI();
            }

        } catch (Exception e) {
            txtStatus.setText("Error loading music: " + e.getMessage());
            setControlsEnabled(false);
            songsAdapter.notifyDataSetChanged();
            queueSongs.clear();
            queueAdapter.notifyDataSetChanged();
        }
    }

    private void handleNoMusicFound() {
        txtStatus.setText("No music found. Put an MP3 in Internal storage > Music.");
        setControlsEnabled(false);

        songsAdapter.notifyDataSetChanged();
        queueSongs.clear();
        queueAdapter.notifyDataSetChanged();

        if (serviceBound) playbackService.setQueueSongs(queueSongs);
    }

    private void setControlsEnabled(boolean enabled) {
        btnPlayPause.setEnabled(enabled);
        btnPrev.setEnabled(enabled);
        btnNext.setEnabled(enabled);
    }

    // -----------------------
    // Playback / queue helpers (service-backed)
    // -----------------------

    private void setQueueToSingleSong(Song song) {
        queueSongs.clear();
        queueSongs.add(song);
        queueAdapter.notifyDataSetChanged();
    }

    private void updateLoadedStatus(Song song) {
        if (song == null) txtStatus.setText("Loaded: -");
        else txtStatus.setText("Loaded: " + song.name);
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

    private void syncQueueToService() {
        if (!serviceBound) return;
        playbackService.setLibrarySongs(librarySongs);
        playbackService.setQueueSongs(queueSongs);
    }

    private void playHeadInService(boolean autoPlay) {
        if (!serviceBound) return;
        playbackService.playFromQueueHead(autoPlay);
    }

    private void refreshPlayPauseText() {
        if (!serviceBound) {
            btnPlayPause.setText("Play");
            return;
        }
        btnPlayPause.setText(playbackService.isPlaying() ? "Pause" : "Play");
    }

    private void pullQueueFromServiceAndRefreshUI() {
        if (!serviceBound) return;

        ArrayList<Song> svcQueue = playbackService.getQueueSnapshot();
        queueSongs.clear();
        if (svcQueue != null) queueSongs.addAll(svcQueue);
        queueAdapter.notifyDataSetChanged();

        Song current = playbackService.getCurrentSong();
        if (current != null) {
            updateLoadedStatus(current);
            syncCurrentIndexToSong(current);
            setControlsEnabled(true);
        } else {
            setControlsEnabled(false);
        }

        refreshPlayPauseText();
    }

    // -----------------------
    // Permissions callback
    // -----------------------

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
            return;
        }

        if (requestCode == REQ_NOTIF_PERMISSION) {
            // If denied: music still plays; lockscreen controls may not show on Android 13+
            return;
        }
    }
}
