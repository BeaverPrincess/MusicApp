package com.example.musicapp;

import android.content.ClipData;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SongsAdapter extends RecyclerView.Adapter<SongsAdapter.VH> {
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    public interface OnSongClickListener {
        void onSongClick(int position, Song song);
    }

    private final List<Song> songs;
    private final OnSongClickListener listener;

    public SongsAdapter(List<Song> songs, OnSongClickListener listener) {
        this.songs = songs;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Song s = songs.get(position);

        holder.txtSongName.setText(s.name);
        holder.txtSongDate.setText(dateFmt.format(new Date(s.dateAddedMillis)));

        // Click -> play
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onSongClick(pos, songs.get(pos));
            }
        });

        // Long press -> drag to queue
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;

            Song song = songs.get(pos);
            DragData dragData = new DragData(DragData.SOURCE_LIBRARY, pos, song);

            ClipData clip = ClipData.newPlainText("song", song.name);
            View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
            v.startDragAndDrop(clip, shadow, dragData, 0);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView txtSongName, txtSongDate;

        public VH(View itemView) {
            super(itemView);
            txtSongName = itemView.findViewById(R.id.txtSongName);
            txtSongDate = itemView.findViewById(R.id.txtSongDate);
        }
    }
}
