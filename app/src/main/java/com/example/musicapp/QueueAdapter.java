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
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_queue, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Song s = items.get(position);
        holder.txt.setText(s.name);

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
        TextView txt;
        VH(@NonNull View itemView) {
            super(itemView);
            txt = itemView.findViewById(R.id.txtQueueItem);
        }
    }
}
