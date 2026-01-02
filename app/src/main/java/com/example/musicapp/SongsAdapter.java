package com.example.musicapp;

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

    public interface OnSongLongPressListener {
        void onSongLongPress(Song song);
    }

    private final List<Song> songs;
    private final OnSongClickListener clickListener;
    private final OnSongLongPressListener longPressListener;

    public SongsAdapter(List<Song> songs,
                        OnSongClickListener clickListener,
                        OnSongLongPressListener longPressListener) {
        this.songs = songs;
        this.clickListener = clickListener;
        this.longPressListener = longPressListener;
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
        holder.txtSongDate.setVisibility(View.VISIBLE);
        holder.txtSongDate.setText(dateFmt.format(new Date(s.dateAddedMillis)));

        // Click -> play
        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && clickListener != null) {
                clickListener.onSongClick(pos, songs.get(pos));
            }
        });

        // Long press -> show options (Add to queue / Add to playlist)
        holder.itemView.setOnLongClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;

            if (longPressListener != null) {
                longPressListener.onSongLongPress(songs.get(pos));
                return true; // consume
            }
            return false;
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
