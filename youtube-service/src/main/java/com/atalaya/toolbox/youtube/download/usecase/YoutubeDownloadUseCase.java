package com.atalaya.toolbox.youtube.download.usecase;

import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadResult;

public interface YoutubeDownloadUseCase {
    YoutubeDownloadResult download(String url);
    YoutubeDownloadResult download(String url, String destination);
}