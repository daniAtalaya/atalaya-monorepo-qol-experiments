package com.atalaya.toolbox.youtube.history;

import com.atalaya.toolbox.youtube.history.usecase.GetInaccessibleVideoMetadataUseCase;
import com.atalaya.toolbox.youtube.history.usecase.ReconcileMissingHistoryUseCase;
import com.atalaya.toolbox.youtube.history.usecase.YoutubeHistoryUseCase;
import com.atalaya.toolbox.youtube.history.domain.DownloadHistoryEntry;
import com.atalaya.toolbox.youtube.history.domain.InaccessibleVideoMetadata;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/youtube")
public class YoutubeHistoryController {
    private final YoutubeHistoryUseCase historyUseCase;
    private final ReconcileMissingHistoryUseCase reconcileMissingHistoryUseCase;
    private final GetInaccessibleVideoMetadataUseCase inaccessibleVideoMetadataUseCase;

    public YoutubeHistoryController(
        YoutubeHistoryUseCase historyUseCase,
        ReconcileMissingHistoryUseCase reconcileMissingHistoryUseCase,
        GetInaccessibleVideoMetadataUseCase inaccessibleVideoMetadataUseCase
    ) {
        this.historyUseCase = historyUseCase;
        this.reconcileMissingHistoryUseCase = reconcileMissingHistoryUseCase;
        this.inaccessibleVideoMetadataUseCase = inaccessibleVideoMetadataUseCase;
    }

    @GetMapping("/history/list")
    public List<DownloadHistoryEntry> history(
        @RequestParam(required = false) String dateOrder,
        @RequestParam(required = false) String downloadTimeOrder
    ) {
        return historyUseCase.history(dateOrder, downloadTimeOrder);
    }

    @PostMapping("/history/reconcile-missing")
    public ReconcileMissingHistoryUseCase.MissingHistoryReconciliationResult reconcileMissingHistory() {
        return reconcileMissingHistoryUseCase.reconcile();
    }

    @GetMapping("/history/metadata-inaccessible")
    public List<InaccessibleVideoMetadata> inaccessibleMetadata() {
        return inaccessibleVideoMetadataUseCase.getAll();
    }

    @DeleteMapping("/history/metadata-inaccessible/{videoId}")
    public ResponseEntity<Void> deleteInaccessibleMetadata(@PathVariable String videoId) {
        return inaccessibleVideoMetadataUseCase.delete(videoId)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/history/metadata-inaccessible")
    public ResponseEntity<Void> clearInaccessibleMetadata() {
        inaccessibleVideoMetadataUseCase.clear();
        return ResponseEntity.noContent().build();
    }
}
