package com.example.musicapp;

public class DragData {
    public static final String SOURCE_LIBRARY = "LIB";
    public static final String SOURCE_QUEUE = "QUEUE";

    public final String source;
    public final int position;
    public final Song song;

    public DragData(String source, int position, Song song) {
        this.source = source;
        this.position = position;
        this.song = song;
    }
}
