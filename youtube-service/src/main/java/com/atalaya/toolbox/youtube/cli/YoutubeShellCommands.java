package com.atalaya.toolbox.youtube.cli;

import com.atalaya.toolbox.youtube.download.usecase.YoutubeDownloadUseCase;
import com.atalaya.toolbox.youtube.history.usecase.YoutubeHistoryUseCase;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadResult;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class YoutubeShellCommands {
    private final YoutubeDownloadUseCase downloadUseCase;
    private final YoutubeHistoryUseCase historyUseCase;

    public YoutubeShellCommands(YoutubeDownloadUseCase downloadUseCase, YoutubeHistoryUseCase historyUseCase) {
        this.downloadUseCase = downloadUseCase;
        this.historyUseCase = historyUseCase;
    }

    @Command(name = "youtube download", description = "Descarga una canción o playlist de YouTube en MP3.")
    public String download(@Option(longName = "url", required = true, description = "URL de YouTube") String url) {
        YoutubeDownloadResult result = downloadUseCase.download(url);
        return "Descargadas " + result.downloadedCount() + " canciones:\n" + result.tracks().stream().map(track -> track.name() + " (" + track.url() + ")").collect(Collectors.joining("\n"));
    }

    @Command(name = "youtube history", description = "Muestra las canciones descargadas.")
    public String history() {
        String history = historyUseCase.history().stream().map(track -> track.name() + " (" + track.url() + ")").collect(Collectors.joining("\n"));
        return history.isBlank() ? "El historial está vacío." : history;
    }
}