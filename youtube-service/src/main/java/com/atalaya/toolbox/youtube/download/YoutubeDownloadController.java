package com.atalaya.toolbox.youtube.download;

import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadRequest;
import com.atalaya.toolbox.youtube.download.usecase.YoutubeDownloadUseCase;
import com.atalaya.toolbox.youtube.download.domain.YoutubeDownloadResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/youtube")
public class YoutubeDownloadController {
    private final YoutubeDownloadUseCase downloadUseCase;

    public YoutubeDownloadController(YoutubeDownloadUseCase downloadUseCase) {
        this.downloadUseCase = downloadUseCase;
    }

    @PostMapping("/download")
    public YoutubeDownloadResult download(@Valid @RequestBody YoutubeDownloadRequest request) {
        return downloadUseCase.download(request.url(), request.destination());
    }
}
