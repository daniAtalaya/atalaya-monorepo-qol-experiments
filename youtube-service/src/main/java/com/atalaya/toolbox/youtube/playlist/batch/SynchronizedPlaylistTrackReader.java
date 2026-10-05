package com.atalaya.toolbox.youtube.playlist.batch;

import org.springframework.batch.infrastructure.item.ItemReader;

import java.util.Iterator;

public class SynchronizedPlaylistTrackReader implements ItemReader<PreparedPlaylistTrack> {
    private final Iterator<PreparedPlaylistTrack> tracks;

    public SynchronizedPlaylistTrackReader(Iterator<PreparedPlaylistTrack> tracks) {
        this.tracks = tracks;
    }

    @Override
    public synchronized PreparedPlaylistTrack read() {
        return tracks.hasNext() ? tracks.next() : null;
    }
}