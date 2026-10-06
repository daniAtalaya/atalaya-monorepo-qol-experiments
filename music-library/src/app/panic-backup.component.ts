import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { Component, ElementRef, ViewChild, inject, output, signal } from '@angular/core';

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
export class PanicBackupComponent {
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
  private readonly root = '/api/preferences/backups';

  open(tab: 'backup' | 'restore'): void {
    this.tab.set(tab);
    this.error.set('');
    this.dialog?.nativeElement.showModal();
    if (tab === 'backup') this.createBackup();
  }

  close(): void {
    if (this.busy()) return;
    this.discardPreview();
    this.dialog?.nativeElement.close();
  }

  onCancel(event: Event): void {
    event.preventDefault();
    this.close();
  }

  selectTab(tab: 'backup' | 'restore'): void {
    if (this.busy()) return;
    this.tab.set(tab);
    this.error.set('');
    this.message.set('');
  }

  createBackup(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.download.set(null);
    this.message.set('Preparing your entire .data folder. Large libraries can take a few minutes.');
    this.http.post<BackupDownload>(this.root, {}).subscribe({
      next: (backup) => {
        this.download.set(backup);
        const anchor = document.createElement('a');
        anchor.href = `${this.root}/${encodeURIComponent(backup.id)}`;
        anchor.download = backup.fileName;
        // Let the browser stream to Downloads instead of holding the ZIP in JavaScript memory.
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        this.message.set('Your ZIP is ready and the download has started. Check your browser Downloads for completion.');
        this.busy.set(false);
      },
      error: (error: unknown) => this.fail(error)
    });
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
    if (this.busy()) return;
    if (!file.name.toLowerCase().endsWith('.zip')) {
      this.error.set('Choose an Atalaya panic backup .zip file.');
      return;
    }
    this.discardPreview();
    this.result.set(null);
    this.busy.set(true);
    this.error.set('');
    this.uploadProgress.set(0);
    this.servicesStopped.set(false);
    this.message.set(`Uploading ${file.name}…`);
    const body = new FormData();
    body.append('file', file);
    this.http.post<RestorePreview>(`${this.root}/restore/preview`, body,
      { observe: 'events', reportProgress: true }).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          const percent = Math.round(100 * event.loaded / event.total);
          this.uploadProgress.set(percent);
          if (percent === 100) this.message.set('Verifying every file and checksum. Your current data is still untouched.');
        }
        if (event.type === HttpEventType.Response && event.body) {
          this.preview.set(event.body);
          this.message.set('Backup verified. Review the contents before restoring.');
          this.uploadProgress.set(null);
          this.busy.set(false);
        }
      },
      error: (error: unknown) => this.fail(error)
    });
  }

  restore(): void {
    const preview = this.preview();
    if (!preview || !this.servicesStopped() || this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.message.set('Restoring your data and reloading profiles…');
    this.restoring.emit();
    this.http.post<RestoreResult>(`${this.root}/restore/${encodeURIComponent(preview.id)}`, {}).subscribe({
      next: (result) => {
        this.result.set(result);
        this.preview.set(null);
        this.message.set('Restore complete. Restart the other services, then reopen Atalaya.');
        this.busy.set(false);
      },
      error: (error: unknown) => this.fail(error)
    });
  }

  reopen(): void { window.location.reload(); }

  private discardPreview(): void {
    const preview = this.preview();
    this.preview.set(null);
    this.servicesStopped.set(false);
    if (preview) this.http.delete(`${this.root}/restore/${encodeURIComponent(preview.id)}`).subscribe({ error: () => {} });
  }

  private fail(error: unknown): void {
    this.busy.set(false);
    this.uploadProgress.set(null);
    this.message.set('');
    this.error.set(error instanceof HttpErrorResponse
      ? error.error?.error || error.error?.detail || (error.status === 0
        ? 'The backup service could not be reached. Check preferences-service and try again.'
        : `Backup operation failed (${error.status}). Please try again.`)
      : 'Backup operation could not finish. Please try again.');
  }
}
