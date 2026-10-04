package com.atalaya.toolbox.youtube.settings.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "toolbox.youtube")
public class YoutubeProperties {
    private volatile Path storageDirectory;
    private volatile String jsRuntime = "deno";
    private volatile String ejsRemoteComponents = "ejs:github";
    private volatile int playlistDownloadAttempts = 3;
    private volatile long playlistRetryDelayMillis = 1000;

    public YoutubeProperties() {}

    public YoutubeProperties(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
    }

    public YoutubeProperties(Path storageDirectory, String jsRuntime, String ejsRemoteComponents) {
        this.storageDirectory = storageDirectory;
        this.jsRuntime = jsRuntime;
        this.ejsRemoteComponents = ejsRemoteComponents;
    }

    public Path getStorageDirectory() {
        return storageDirectory;
    }

    public void setStorageDirectory(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
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

    public Path resolvedStorageDirectory() {
        Path configuredPath = storageDirectory == null ? Path.of(".data", "youtube") : storageDirectory;
        return (configuredPath.isAbsolute() ? configuredPath : Path.of(System.getProperty("user.dir")).resolve(configuredPath)).normalize();
    }

    public Path resolvedMusicDirectory(String destination) {
        Path musicDirectory = resolvedStorageDirectory().resolve("music").normalize();
        if (destination == null || destination.isBlank()) {
            return musicDirectory;
        }
        validateDestination(destination);
        Path relativeDestination;
        try {
            relativeDestination = Path.of(destination);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("La carpeta destino no es válida.", e);
        }
        Path resolvedDestination = musicDirectory.resolve(relativeDestination).normalize();
        if (!resolvedDestination.startsWith(musicDirectory)) {
            throw new IllegalArgumentException("La carpeta destino debe permanecer dentro de music.");
        }
        return resolvedDestination;
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