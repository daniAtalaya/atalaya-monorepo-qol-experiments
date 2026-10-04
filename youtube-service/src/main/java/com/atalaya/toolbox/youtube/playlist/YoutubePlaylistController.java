package com.atalaya.toolbox.youtube.playlist;

import com.atalaya.toolbox.youtube.playlist.domain.PlaylistPreparationStatusResponse;
import com.atalaya.toolbox.youtube.playlist.domain.PlaylistProcessingAcceptedResponse;
import com.atalaya.toolbox.youtube.playlist.domain.PrepareYoutubePlaylistRequest;
import com.atalaya.toolbox.youtube.playlist.domain.PreparedPlaylistResponse;
import com.atalaya.toolbox.youtube.playlist.usecase.GetPlaylistPreparationStatusUseCase;
import com.atalaya.toolbox.youtube.playlist.usecase.PrepareYoutubePlaylistUseCase;
import com.atalaya.toolbox.youtube.playlist.usecase.SchedulePreparedPlaylistProcessingUseCase;
import com.atalaya.toolbox.youtube.playlist.usecase.YoutubePlaylistStatusUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/youtube/playlist")
public class YoutubePlaylistController {
    private final PrepareYoutubePlaylistUseCase preparePlaylistUseCase;
    private final SchedulePreparedPlaylistProcessingUseCase schedulePreparedPlaylistProcessingUseCase;
    private final YoutubePlaylistStatusUseCase playlistStatusUseCase;
    private final GetPlaylistPreparationStatusUseCase playlistPreparationStatusUseCase;

    public YoutubePlaylistController(
        PrepareYoutubePlaylistUseCase preparePlaylistUseCase,
        SchedulePreparedPlaylistProcessingUseCase schedulePreparedPlaylistProcessingUseCase,
        YoutubePlaylistStatusUseCase playlistStatusUseCase,
        GetPlaylistPreparationStatusUseCase playlistPreparationStatusUseCase
    ) {
        this.preparePlaylistUseCase = preparePlaylistUseCase;
        this.schedulePreparedPlaylistProcessingUseCase = schedulePreparedPlaylistProcessingUseCase;
        this.playlistStatusUseCase = playlistStatusUseCase;
        this.playlistPreparationStatusUseCase = playlistPreparationStatusUseCase;
    }

    @GetMapping("/status")
    public YoutubePlaylistStatusUseCase.PlaylistStatusReport playlistStatus() {
        return playlistStatusUseCase.status();
    }

    @PostMapping("/prepare")
    public ResponseEntity<PreparedPlaylistResponse> preparePlaylist(@Valid @RequestBody PrepareYoutubePlaylistRequest request) {
        var result = preparePlaylistUseCase.prepare(request.url(), request.destination());
        return ResponseEntity.accepted().body(new PreparedPlaylistResponse(result.playlistId(), result.status(), result.metadataFile()));
    }

    @GetMapping("/prepare/status")
    public PlaylistPreparationStatusResponse playlistPreparationStatus(@RequestParam String playlistId) {
        var status = playlistPreparationStatusUseCase.get(playlistId);
        return new PlaylistPreparationStatusResponse(status.playlistId(), status.status(), status.metadataFile(), status.error());
    }

    @PostMapping("/process")
    public PlaylistProcessingAcceptedResponse processPrepared(@RequestParam(required = false) String playlistId) {
        return new PlaylistProcessingAcceptedResponse(true, schedulePreparedPlaylistProcessingUseCase.schedule(playlistId));
    }
}
