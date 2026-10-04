package com.atalaya.toolbox.youtube.history.usecase;

public interface ReconcileMissingHistoryUseCase {
    String RECONCILIATION_URL = "history-reconciliation";

    MissingHistoryReconciliationResult reconcile();
    record MissingHistoryReconciliationResult(int historyVideoCount, int presentTrackCount, int missingTrackCount, int queuedTrackCount, String playlistId) {}
}