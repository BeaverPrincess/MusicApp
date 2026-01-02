package com.example.musicapp;

import android.content.ClipData;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.VH> {

    private final List<Song> items;

    public QueueAdapter(List<Song> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Use the same row layout as the library list
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Song s = items.get(position);

        holder.txtSongName.setText(s.name);

        // Queue typically doesn't need a date -> hide it.
        // Remove this line and set text if you want date shown in queue too.
        holder.txtSongDate.setVisibility(View.GONE);

        // First item = currently playing -> cannot be dragged/removed
        if (position == 0) {
            holder.itemView.setOnLongClickListener(null);
        } else {
            holder.itemView.setOnLongClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;

                Song song = items.get(pos);
                DragData dragData = new DragData(DragData.SOURCE_QUEUE, pos, song);

                ClipData clip = ClipData.newPlainText("queue_song", song.name);
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                v.startDragAndDrop(clip, shadow, dragData, 0);
                return true;
            });
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
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
