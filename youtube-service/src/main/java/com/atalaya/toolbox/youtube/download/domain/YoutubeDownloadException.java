package com.atalaya.toolbox.youtube.download.domain;

public class YoutubeDownloadException extends RuntimeException {

    public YoutubeDownloadException(String message, Throwable cause) {
        super(message, cause);
    }

    public YoutubeDownloadException(String message) {
        super(message);
    }
}
