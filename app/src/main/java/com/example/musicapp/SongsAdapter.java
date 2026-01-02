package com.example.musicapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SongsAdapter extends RecyclerView.Adapter<SongsAdapter.VH> {

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
        holder.txt.setText(s.name);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onSongClick(holder.getAdapterPosition(), s);
        });
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView txt;
        VH(@NonNull View itemView) {
            super(itemView);
            txt = itemView.findViewById(R.id.txtSongItem);
        }
    }
}
