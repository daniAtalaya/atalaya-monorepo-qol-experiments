package com.atalaya.toolbox.youtube.download.provider;

import com.atalaya.toolbox.youtube.download.domain.DownloadedTrack;

import java.util.List;

public interface YoutubeMediaDownloader {
    List<DownloadedTrack> download(String url);
    List<DownloadedTrack> download(String url, String destination);
}