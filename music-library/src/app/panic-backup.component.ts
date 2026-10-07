import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { Component, DestroyRef, ElementRef, OnDestroy, ViewChild, inject, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';

interface BackupDownload { id: string; fileName: string; sizeBytes: number; fileCount: number; createdAt: string; }
interface RestorePreview { id: string; createdAt: string; sizeBytes: number; fileCount: number; folders: string[]; }
interface RestoreResult { fileCount: number; recoveryDirectory: string | null; restartServices: boolean; }

@Component({
  selector: 'atalaya-panic-backup',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './panic-backup.component.html',
  styleUrl: './panic-backup.component.css'
})
export class PanicBackupComponent implements OnDestroy {
  readonly restoring = output<void>();
  readonly tab = signal<'backup' | 'restore'>('backup');
  readonly busy = signal(false);
  readonly message = signal('');
  readonly error = signal('');
  readonly download = signal<BackupDownload | null>(null);
  readonly preview = signal<RestorePreview | null>(null);
  readonly result = signal<RestoreResult | null>(null);
  readonly uploadProgress = signal<number | null>(null);
  readonly servicesStopped = signal(false);
  readonly dragging = signal(false);
  @ViewChild('dialog') private dialog?: ElementRef<HTMLDialogElement>;
  @ViewChild('fileInput') private fileInput?: ElementRef<HTMLInputElement>;
  private readonly http = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);
  private readonly root = '/api/preferences/backups';

  open(tab: 'backup' | 'restore'): void {
    if (this.busy() || this.result()) return;
    this.tab.set(tab);
    this.error.set('');
    this.message.set('');
    this.dialog?.nativeElement.showModal();
    if (tab === 'backup' && !this.download()) this.createBackup();
  }

  close(): void {
    if (this.busy() || this.result()) return;
    this.discardPreview();
    this.dialog?.nativeElement.close();
  }

  ngOnDestroy(): void {
    this.discardPreview();
  }

  onCancel(event: Event): void {
    event.preventDefault();
    this.close();
  }

  selectTab(tab: 'backup' | 'restore'): void {
    if (this.busy() || this.result()) return;
    if (tab === 'backup') this.discardPreview();
    this.tab.set(tab);
    this.error.set('');
    this.message.set('');
  }

  createBackup(): void {
    if (this.busy() || this.result()) return;
    this.busy.set(true);
    this.error.set('');
    this.download.set(null);
    this.message.set('Preparing your entire .data folder. Large libraries can take a few minutes.');
    console.info('[panic-backup] Preparing archive');
    this.http.post<BackupDownload>(this.root, {}).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.busy.set(false))
    ).subscribe({
      next: (backup) => {
        this.download.set(backup);
        console.info('[panic-backup] Archive ready', { id: backup.id, files: backup.fileCount, bytes: backup.sizeBytes });
        this.downloadAgain();
      },
      error: (error: unknown) => this.fail('prepare archive', error)
    });
  }

  downloadAgain(): void {
    const backup = this.download();
    if (!backup) return;
    const anchor = document.createElement('a');
    anchor.href = `${this.root}/${encodeURIComponent(backup.id)}`;
    anchor.download = backup.fileName;
    // Native downloads stream to disk; JavaScript cannot observe their completion.
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    console.info('[panic-backup] Browser download requested', { id: backup.id });
    this.message.set('Download requested. Check browser Downloads for progress or failures. You can retry this ZIP for one hour; after expiry, create another backup.');
  }

  selectFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (file) this.inspect(file);
    input.value = '';
  }

  drop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    if (this.busy()) return;
    const file = event.dataTransfer?.files[0];
    if (file) this.inspect(file);
  }

  dragOver(event: DragEvent): void {
    event.preventDefault();
    if (!this.busy()) this.dragging.set(true);
  }

  browse(): void { this.fileInput?.nativeElement.click(); }

  private inspect(file: File): void {
    if (this.busy() || this.result()) return;
    if (!file.size || !file.name.toLowerCase().endsWith('.zip')) {
      this.error.set('Choose an Atalaya panic backup .zip file.');
      return;
    }
    this.discardPreview();
    this.busy.set(true);
    this.error.set('');
    this.uploadProgress.set(0);
    this.servicesStopped.set(false);
    this.message.set(`Uploading ${file.name}…`);
    console.info('[panic-backup] Upload started', { bytes: file.size });
    const body = new FormData();
    body.append('file', file);
    this.http.post<RestorePreview>(`${this.root}/restore/preview`, body,
      { observe: 'events', reportProgress: true }).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => {
        this.busy.set(false);
        this.uploadProgress.set(null);
      })
    ).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          const percent = Math.round(100 * event.loaded / event.total);
          this.uploadProgress.set(percent === 100 ? null : percent);
          if (percent === 100) this.message.set('Verifying every file and checksum. Your current data is still untouched.');
        }
        if (event.type === HttpEventType.Response && event.body) {
          this.preview.set(event.body);
          this.message.set('Backup verified. Review the contents before restoring.');
          console.info('[panic-backup] Upload verified', {
            id: event.body.id, files: event.body.fileCount, bytes: event.body.sizeBytes
          });
        }
      },
      error: (error: unknown) => this.fail('upload and verify', error)
    });
  }

  restore(): void {
    const preview = this.preview();
    if (!preview || !this.servicesStopped() || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.message.set('Restoring your data and reloading profiles…');
    console.info('[panic-backup] Restore requested', { id: preview.id });
    this.restoring.emit();
    this.http.post<RestoreResult>(`${this.root}/restore/${encodeURIComponent(preview.id)}`, {}).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.busy.set(false))
    ).subscribe({
      next: (result) => {
        this.result.set(result);
        this.preview.set(null);
        this.message.set('Restore complete. Restart the other services, then reopen Atalaya.');
        console.info('[panic-backup] Restore complete', result);
      },
      error: (error: unknown) => this.fail('restore', error)
    });
  }

  reopen(): void { window.location.reload(); }

  private discardPreview(): void {
    const preview = this.preview();
    this.preview.set(null);
    this.servicesStopped.set(false);
    if (preview) this.http.delete(`${this.root}/restore/${encodeURIComponent(preview.id)}`).subscribe({
      error: (error: unknown) => console.warn(
        '[panic-backup] Could not discard preview; server expiry will clean it up', { id: preview.id, error }
      )
    });
  }

  private fail(operation: string, error: unknown): void {
    console.error(`[panic-backup] ${operation} failed`, error);
    this.message.set('');
    let message = 'Backup operation could not finish. Please try again.';
    if (error instanceof HttpErrorResponse) {
      const body: unknown = error.error;
      const detail = body && typeof body === 'object'
        ? ('error' in body ? body.error : 'detail' in body ? body.detail : null) : null;
      message = typeof detail === 'string' ? detail : error.status === 0
        ? operation === 'restore'
          ? 'The connection was lost during restore. Its outcome is unknown: check preferences-service logs before retrying or reopening the app.'
          : 'The backup service could not be reached. Check preferences-service and try again.'
        : `Backup operation failed (${error.status}). Please try again.`;
    }
    if (message.includes('Backup session expired')) {
      this.preview.set(null);
      this.servicesStopped.set(false);
    }
    this.error.set(message);
  }
}
