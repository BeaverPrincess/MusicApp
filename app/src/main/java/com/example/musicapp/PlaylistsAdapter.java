package com.example.musicapp;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class PlaylistsAdapter extends RecyclerView.Adapter<PlaylistsAdapter.VH> {

    public interface OnPlaylistClickListener {
        void onPlaylistClick(int position, Playlist playlist);
    }

    private final List<Playlist> playlists;
    private final OnPlaylistClickListener listener;

    public PlaylistsAdapter(List<Playlist> playlists, OnPlaylistClickListener listener) {
        this.playlists = playlists;
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
        Playlist p = playlists.get(position);

        holder.txtSongName.setText(p.name);

        // Reuse the date field to show count (for now)
        holder.txtSongDate.setVisibility(View.VISIBLE);
        holder.txtSongDate.setText(p.songCount + " songs");

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                listener.onPlaylistClick(pos, playlists.get(pos));
            }
        });

        // No drag/drop for playlists yet
        holder.itemView.setOnLongClickListener(null);
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView txtSongName;
        TextView txtSongDate;

        VH(@NonNull View itemView) {
            super(itemView);
            txtSongName = itemView.findViewById(R.id.txtSongName);
            txtSongDate = itemView.findViewById(R.id.txtSongDate);
        }
    }
}
