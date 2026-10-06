package com.atalaya.toolbox.youtube.settings.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "toolbox.youtube")
public class YoutubeProperties {
    private volatile String storageDirectory = ".data";
    private volatile String musicDirectory = "music";
    private volatile String jsRuntime = "deno";
    private volatile String ejsRemoteComponents = "ejs:github";
    private volatile int playlistDownloadAttempts = 3;
    private volatile long playlistRetryDelayMillis = 1000;

    public YoutubeProperties() {}

    public YoutubeProperties(String storageDirectory) {
        this.storageDirectory = storageDirectory;
    }

    public YoutubeProperties(String storageDirectory, String jsRuntime, String ejsRemoteComponents) {
        this.storageDirectory = storageDirectory;
        this.jsRuntime = jsRuntime;
        this.ejsRemoteComponents = ejsRemoteComponents;
    }

    public Path resolvedStorageDirectory() {
        Path configuredPath =
                storageDirectory == null || storageDirectory.isBlank()
                        ? Path.of(".data")
                        : Path.of(storageDirectory);

        return (configuredPath.isAbsolute()
                ? configuredPath
                : Path.of(System.getProperty("user.dir")).resolve(configuredPath))
                .normalize();
    }
    public Path resolvedMusicDirectory(String destination) {

        if (musicDirectory == null || musicDirectory.isBlank()) {
            throw new IllegalArgumentException(
                    "Music directory must not be blank."
            );
        }

        Path storage = resolvedStorageDirectory();

        Path music = storage
                .resolve(this.musicDirectory)
                .normalize();

        if (!music.startsWith(storage)) {
            throw new IllegalArgumentException(
                    "Music directory must remain inside the YouTube storage directory."
            );
        }

        if (destination == null || destination.isBlank()) {
            return music;
        }

        validateDestination(destination);

        Path relativeDestination;

        try {
            relativeDestination = Path.of(destination);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "La carpeta destino no es válida.",
                    e
            );
        }

        Path resolvedDestination =
                music.resolve(relativeDestination).normalize();

        if (!resolvedDestination.startsWith(music)) {
            throw new IllegalArgumentException(
                    "La carpeta destino debe permanecer dentro de music."
            );
        }

        return resolvedDestination;
    }

    public String getStorageDirectory() {
        return storageDirectory;
    }

    public void setStorageDirectory(String storageDirectory) {
        this.storageDirectory = storageDirectory;
    }

    public String getMusicDirectory() {
        return musicDirectory;
    }

    public void setMusicDirectory(String musicDirectory) {
        this.musicDirectory = musicDirectory;
    }

    public String getJsRuntime() {
        return jsRuntime;
    }

    public void setJsRuntime(String jsRuntime) {
        this.jsRuntime = jsRuntime;
    }

    public String getEjsRemoteComponents() {
        return ejsRemoteComponents;
    }

    public void setEjsRemoteComponents(String ejsRemoteComponents) {
        this.ejsRemoteComponents = ejsRemoteComponents;
    }

    public int getPlaylistDownloadAttempts() {
        return playlistDownloadAttempts;
    }

    public void setPlaylistDownloadAttempts(int playlistDownloadAttempts) {
        this.playlistDownloadAttempts = playlistDownloadAttempts;
    }

    public long getPlaylistRetryDelayMillis() {
        return playlistRetryDelayMillis;
    }

    public void setPlaylistRetryDelayMillis(long playlistRetryDelayMillis) {
        this.playlistRetryDelayMillis = playlistRetryDelayMillis;
    }

    public static void validateDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return;
        }
        Path relativeDestination;
        try {
            relativeDestination = Path.of(destination);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("La carpeta destino no es válida.", e);
        }
        if (relativeDestination.isAbsolute() || relativeDestination.normalize().startsWith("..") || destination.contains(":")) {
            throw new IllegalArgumentException("La carpeta destino debe ser relativa a music.");
        }
    }

    public String resolvedJsRuntime() {
        return jsRuntime == null || jsRuntime.isBlank() ? "deno" : jsRuntime;
    }

    public String resolvedEjsRemoteComponents() {
        return ejsRemoteComponents == null || ejsRemoteComponents.isBlank() ? "ejs:github" : ejsRemoteComponents;
    }
}