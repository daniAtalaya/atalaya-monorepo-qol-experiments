package com.atalaya.toolbox.series;

import com.atalaya.toolbox.series.domain.EpisodeWatchRequest;
import com.atalaya.toolbox.series.domain.SeriesShow;
import com.atalaya.toolbox.series.domain.SeriesShowRequest;
import com.atalaya.toolbox.series.service.SeriesTrackerService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/series")
public class SeriesTrackerController {
    private final SeriesTrackerService trackerService;

    public SeriesTrackerController(SeriesTrackerService trackerService) {
        this.trackerService = trackerService;
    }

    @GetMapping("/shows")
    public List<SeriesShow> listShows(@RequestHeader("X-Atalaya-Username") String username) {
        return trackerService.listShows(username);
    }

    @PostMapping("/shows")
    public SeriesShow createShow(
        @RequestHeader("X-Atalaya-Username") String username,
        @Valid @RequestBody SeriesShowRequest request
    ) {
        return trackerService.createShow(username, request);
    }

    @PutMapping("/shows/{id}")
    public SeriesShow updateShow(
        @RequestHeader("X-Atalaya-Username") String username,
        @PathVariable String id,
        @Valid @RequestBody SeriesShowRequest request
    ) {
        return trackerService.updateShow(username, id, request);
    }

    @DeleteMapping("/shows/{id}")
    public void deleteShow(@RequestHeader("X-Atalaya-Username") String username, @PathVariable String id) {
        trackerService.deleteShow(username, id);
    }

    @PutMapping("/shows/{id}/episodes")
    public SeriesShow updateEpisode(
        @RequestHeader("X-Atalaya-Username") String username,
        @PathVariable String id,
        @Valid @RequestBody EpisodeWatchRequest request
    ) {
        return trackerService.updateEpisode(username, id, request);
    }

    @PostMapping(value = "/shows/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SeriesShow uploadCover(
        @RequestHeader("X-Atalaya-Username") String username,
        @PathVariable String id,
        @RequestPart("file") MultipartFile file
    ) {
        return trackerService.uploadCover(username, id, file);
    }

    @GetMapping("/covers/{fileName:.+}")
    public ResponseEntity<Resource> getCover(@PathVariable String fileName) {
        Path path = trackerService.coverPath(fileName);
        MediaType mediaType = switch (path.getFileName().toString().substring(path.getFileName().toString().lastIndexOf('.') + 1)) {
            case "jpg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        return ResponseEntity.ok()
            .contentType(mediaType)
            .cacheControl(CacheControl.noCache())
            .body(new FileSystemResource(path));
    }
}
