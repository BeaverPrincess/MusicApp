// app/src/main/java/com/example/musicapp/Song.java
package com.example.musicapp;

import android.net.Uri;

public class Song {
    public final long id;
    public final String name;
    public final Uri uri;
    public final long dateAddedMillis;

    public Song(long id, String name, Uri uri, long dateAddedMillis) {
        this.id = id;
        this.name = name;
        this.uri = uri;
        this.dateAddedMillis = dateAddedMillis;
    }
}
