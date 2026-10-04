package com.atalaya.toolbox.youtube.library.domain;

import java.util.List;

public record MusicLibraryNode(String name, String path, boolean directory, long sizeBytes, List<MusicLibraryNode> children) {
    public MusicLibraryNode {
        children = List.copyOf(children);
    }
}
