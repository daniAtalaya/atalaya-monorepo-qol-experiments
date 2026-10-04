package com.atalaya.toolbox.youtube.library;

import com.atalaya.toolbox.youtube.library.usecase.GetMusicLibraryUseCase;
import com.atalaya.toolbox.youtube.library.domain.MusicLibraryNode;
import com.atalaya.toolbox.youtube.library.domain.MostListenedTrack;
import com.atalaya.toolbox.youtube.library.domain.TrackListenRequest;
import com.atalaya.toolbox.youtube.library.service.TrackListenService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/youtube/music")
public class MusicLibraryController {
    private final GetMusicLibraryUseCase musicLibraryUseCase;
    private final TrackListenService trackListenService;

    public MusicLibraryController(GetMusicLibraryUseCase musicLibraryUseCase, TrackListenService trackListenService) {
        this.musicLibraryUseCase = musicLibraryUseCase;
        this.trackListenService = trackListenService;
    }

    @GetMapping("/most-listened")
    public List<MostListenedTrack> mostListened(@RequestHeader("X-Atalaya-Username") String username) {
        return trackListenService.mostListened(username);
    }

    @PostMapping("/listens")
    public MostListenedTrack recordListen(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody TrackListenRequest request
    ) {
        return trackListenService.recordListen(username, request.path());
    }

    @GetMapping("/tree")
    public List<MusicLibraryNode> musicTree(
        @RequestParam(required = false, defaultValue = "") String path,
        @RequestParam(required = false) Integer nestedFolderDepth
    ) {
        return musicLibraryUseCase.getTree(path, nestedFolderDepth);
    }

    @GetMapping("/track")
    public ResponseEntity<Resource> musicTrack(@RequestParam String path) {
        return musicLibraryUseCase.getTrack(path)
            .map(file -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .contentLength(file.toFile().length())
                .header("Content-Disposition", ContentDisposition.inline()
                    .filename(file.getFileName().toString(), StandardCharsets.UTF_8)
                    .build()
                    .toString())
                .body((Resource) new FileSystemResource(file)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
