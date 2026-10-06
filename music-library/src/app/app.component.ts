import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import {
  AfterViewInit,
  Component,
  ElementRef,
  HostListener,
  OnDestroy,
  OnInit,
  ViewChild,
  inject,
  signal
} from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { MusicLibraryApiService } from './music-library-api.service';
import { SeriesTrackerComponent } from './series-tracker.component';
import { RepeatControlComponent } from './repeat-control.component';
import { PanicBackupComponent } from './panic-backup.component';
import {
  MostListenedTrack,
  MusicPlayerUser,
  MusicLibraryNode,
  PlayerThemeCatalog,
  PlayerThemeDraft,
  PlayerThemeOption,
  RepeatMode,
  ShuffleMode,
  YoutubeSettings
} from './music-library.models';

interface InaccessibleMetadataEntry {
  videoId: string;
  firstSeenAt: string;
  lastSeenAt: string;
  observations: number;
  sourceUrls: string[];
  latestReason: string;
}

@Component({
  selector: 'atalaya-root',
  standalone: true,
  imports: [CommonModule, SeriesTrackerComponent, RepeatControlComponent, PanicBackupComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css'
})
export class AppComponent implements OnInit, AfterViewInit, OnDestroy {
  readonly library = signal<MusicLibraryNode[]>([]);
  readonly folderContents = signal<Record<string, MusicLibraryNode[]>>({});
  readonly loadingFolders = signal<ReadonlySet<string>>(new Set());
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  readonly nowPlaying = signal<MusicLibraryNode | null>(null);
  readonly shuffleMode = signal<ShuffleMode>('off');
  readonly repeatMode = signal<RepeatMode>('off');
  readonly shuffleDepth = signal(1);
  readonly shuffleMessage = signal('');
  readonly emptyMessage = signal('');
  readonly activeTab = signal<'library' | 'most-played' | 'service'>('library');
  readonly activeWorkspace = signal<'music' | 'series'>('music');
  readonly activeUsername = signal('');
  readonly knownUsers = signal<MusicPlayerUser[]>([]);
  readonly userMode = signal<'existing' | 'new'>('existing');
  readonly selectedLoginUsername = signal('');
  readonly loginUsername = signal('');
  readonly userBusy = signal(false);
  readonly userError = signal('');
  readonly userLoading = signal(true);
  readonly selectedThemeId = signal('midnight');
  readonly activeTheme = signal<PlayerThemeOption | null>(null);
  readonly availableThemes = signal<PlayerThemeOption[]>([]);
  readonly themeChooserOpen = signal(false);
  readonly themeBusy = signal(false);
  readonly themeStatus = signal('');
  readonly themeEditor = signal<PlayerThemeDraft | null>(null);
  readonly editingThemeId = signal<string | null>(null);
  readonly listenRanking = signal<MostListenedTrack[]>([]);
  readonly rankingLoading = signal(false);
  readonly rankingError = signal('');
  readonly youtubeSettings = signal<YoutubeSettings | null>(null);
  readonly settingsBusy = signal(false);
  readonly settingsStatus = signal('Loading settings…');
  readonly apiBusy = signal(false);
  readonly apiResponseStatus = signal('No request yet');
  readonly apiResponse = signal('Choose a service action to see its response here.');
  readonly inaccessibleMetadata = signal<InaccessibleMetadataEntry[] | null>(null);
  readonly metadataMutationBusy = signal(false);
  readonly isPlaying = signal(false);
  readonly visualizerOpen = signal(false);
  readonly shuffleAvailable = signal(false);
  readonly playbackPosition = signal(0);
  readonly playbackDuration = signal(0);
  readonly playerVolume = signal(1);

  @ViewChild('player') private playerRef?: ElementRef<HTMLAudioElement>;
  @ViewChild('visualizerCanvas') private visualizerCanvasRef?: ElementRef<HTMLCanvasElement>;
  @ViewChild('visualizerTitle') private visualizerTitleRef?: ElementRef<HTMLElement>;
  @ViewChild('visualizerControls') private visualizerControlsRef?: ElementRef<HTMLElement>;

  private readonly api = inject(MusicLibraryApiService);
  private readonly http = inject(HttpClient);
  private shuffleSource: MusicLibraryNode[] = [];
  private shuffleQueue: MusicLibraryNode[] = [];
  private shuffleScope: ShuffleMode = 'off';
  private playbackHistory: MusicLibraryNode[] = [];
  private historyIndex = -1;
  private playbackQueue: MusicLibraryNode[] = [];
  private playbackQueueIndex = -1;
  private playbackQueueRequest = 0;
  private lastTrackPath = '';
  private countedPlaybackKey = '';
  private playbackTransitionRequest = 0;
  private audioContext?: AudioContext;
  private analyser?: AnalyserNode;
  private frequencyData?: Uint8Array<ArrayBuffer>;
  private animationFrame = 0;
  private visualizerResizeObserver?: ResizeObserver;
  private readonly usernameStorageKey = 'atalaya.music.username';
  private volumeSaveTimer?: number;
  private savedVolume?: number;

  private get audioPlayer(): HTMLAudioElement | undefined {
    return this.playerRef?.nativeElement;
  }

  ngOnInit(): void {
    this.restoreWorkspaceFromPath();
    this.restoreMusicPlayerUser();
  }

  ngAfterViewInit(): void {
    const player = this.audioPlayer;
    if (!player) return;
    player.volume = this.playerVolume();
    this.playerVolume.set(player.volume);
  }

  ngOnDestroy(): void {
    this.stopVisualizer();
    this.analyser?.disconnect();
    void this.audioContext?.close();
  }

  loadLibrary(): void {
    this.loading.set(true);
    this.errorMessage.set('');
    this.folderContents.set({});
    this.api.getTree('', 0).subscribe({
      next: (nodes) => {
        this.library.set(this.sortNodes(nodes));
        this.emptyMessage.set(nodes.length === 0 ? 'Your library is empty.' : '');
        this.loading.set(false);
      },
      error: (error: unknown) => {
        this.errorMessage.set(this.describeError(error));
        this.loading.set(false);
      }
    });
  }

  onFolderToggle(node: MusicLibraryNode, event: Event): void {
    if (!(event.target instanceof HTMLDetailsElement) || !event.target.open) return;
    if (Object.hasOwn(this.folderContents(), node.path) || this.loadingFolders().has(node.path)) return;

    this.setFolderLoading(node.path, true);
    this.api.getTree(node.path, 0).subscribe({
      next: (nodes) => {
        this.folderContents.update((contents) => ({
          ...contents,
          [node.path]: this.sortNodes(nodes)
        }));
        this.setFolderLoading(node.path, false);
      },
      error: (error: unknown) => {
        this.setFolderLoading(node.path, false);
        this.errorMessage.set(this.describeError(error));
      }
    });
  }

  playTrack(track: MusicLibraryNode): void {
    this.shuffleMode.set('off');
    this.shuffleScope = 'off';
    this.shuffleAvailable.set(false);
    this.shuffleMessage.set('');
    this.shuffleSource = [];
    this.shuffleQueue = [];
    this.playbackHistory = [track];
    this.historyIndex = 0;
    this.startPlayback(track);

    const parentPath = track.path.includes('/') ? track.path.slice(0, track.path.lastIndexOf('/')) : '';
    const requestId = ++this.playbackQueueRequest;
    this.api.getTree(parentPath, 0).subscribe({
      next: (nodes) => {
        if (requestId !== this.playbackQueueRequest || this.nowPlaying()?.path !== track.path) return;
        this.playbackQueue = this.collectTracks(nodes)
          .filter((candidate) => this.parentFolder(candidate.path) === parentPath)
          .sort((left, right) => left.name.localeCompare(right.name));
        this.playbackQueueIndex = this.playbackQueue.findIndex((candidate) => candidate.path === track.path);
      },
      error: (error: unknown) => this.errorMessage.set(this.describeError(error))
    });
  }

  toggleTrack(track: MusicLibraryNode): void {
    if (this.nowPlaying()?.path === track.path) {
      this.togglePlayback();
      return;
    }
    this.playTrack(track);
  }

  toggleCurrentTrack(): void {
    this.togglePlayback();
  }

  togglePlayback(): void {
    const player = this.audioPlayer;
    if (!player || !this.nowPlaying()) return;
    if (player.paused) {
      if (player.ended) this.countedPlaybackKey = '';
      void player.play().catch((error: unknown) => {
        this.errorMessage.set(`Playback could not resume: ${this.describeError(error)}`);
      });
    } else {
      player.pause();
    }
  }

  playPreviousTrack(): void {
    this.playbackTransitionRequest++;
    const player = this.audioPlayer;
    if (!player || !this.nowPlaying()) return;
    if (this.historyIndex > 0) {
      this.historyIndex--;
      const previous = this.playbackHistory[this.historyIndex];
      this.playbackQueueIndex = this.playbackQueue.findIndex((track) => track.path === previous.path);
      this.startPlayback(previous);
      return;
    }
    const currentTrack = this.nowPlaying();
    if (!currentTrack || this.shuffleMode() !== 'off') {
      player.currentTime = 0;
      return;
    }
    const parentPath = this.parentFolder(currentTrack.path);
    if (this.playbackQueue.length > 0) {
      const currentIndex = this.playbackQueue.findIndex((track) => track.path === currentTrack.path);
      if (currentIndex > 0) {
        this.playbackQueueIndex = currentIndex - 1;
        const previous = this.playbackQueue[this.playbackQueueIndex];
        this.playbackHistory = [previous, currentTrack];
        this.historyIndex = 0;
        this.startPlayback(previous);
        return;
      }
      player.currentTime = 0;
      return;
    }
    const requestId = ++this.playbackQueueRequest;
    void firstValueFrom(this.api.getTree(parentPath, 0)).then((nodes) => {
      if (requestId !== this.playbackQueueRequest || this.nowPlaying()?.path !== currentTrack.path) return;
      this.playbackQueue = this.collectTracks(nodes)
        .filter((track) => this.parentFolder(track.path) === parentPath)
        .sort((left, right) => left.name.localeCompare(right.name));
      const currentIndex = this.playbackQueue.findIndex((track) => track.path === currentTrack.path);
      if (currentIndex <= 0) {
        player.currentTime = 0;
        return;
      }
      this.playbackQueueIndex = currentIndex - 1;
      const previous = this.playbackQueue[this.playbackQueueIndex];
      this.playbackHistory = [previous, currentTrack];
      this.historyIndex = 0;
      this.startPlayback(previous);
    }).catch((error: unknown) => this.errorMessage.set(this.describeError(error)));
  }

  playNextTrack(): void {
    this.playbackTransitionRequest++;
    if (!this.nowPlaying()) return;
    if (this.historyIndex + 1 < this.playbackHistory.length) {
      this.historyIndex++;
      const next = this.playbackHistory[this.historyIndex];
      this.playbackQueueIndex = this.playbackQueue.findIndex((track) => track.path === next.path);
      this.startPlayback(next);
      return;
    }

    if (this.shuffleMode() !== 'off') {
      if (this.shuffleQueue.length === 0) this.shuffleQueue = this.shuffled(this.shuffleSource);
      const next = this.shuffleQueue.shift();
      if (next) this.playFromQueue(next);
      return;
    }

    const nextIndex = this.playbackQueueIndex + 1;
    if (this.playbackQueueIndex < 0 || nextIndex >= this.playbackQueue.length) return;
    this.playbackQueueIndex = nextIndex;
    const next = this.playbackQueue[nextIndex];
    this.playbackHistory = this.playbackHistory.slice(0, this.historyIndex + 1);
    this.playbackHistory.push(next);
    this.historyIndex++;
    this.startPlayback(next);
  }

  async startShuffle(path?: string): Promise<void> {
    this.errorMessage.set('');
    const isLibraryShuffle = path === undefined;
    const depth = isLibraryShuffle ? undefined : this.shuffleDepth();
    const requestId = ++this.playbackQueueRequest;
    try {
      const nodes = await firstValueFrom(this.api.getTree(path ?? '', depth));
      if (requestId !== this.playbackQueueRequest) return;
      const tracks = this.collectTracks(nodes);
      if (tracks.length === 0) {
        this.shuffleMode.set('off');
        this.shuffleMessage.set('No tracks are available in this shuffle scope.');
        return;
      }
      this.stopPlayer();
      this.shuffleSource = tracks;
      this.shuffleAvailable.set(true);
      this.shuffleQueue = this.shuffled(tracks);
      this.shuffleMode.set(isLibraryShuffle ? 'library' : 'folder');
      this.shuffleScope = this.shuffleMode();
      this.playbackQueue = [];
      this.playbackQueueIndex = -1;
      this.playbackHistory = [];
      this.historyIndex = -1;
      this.shuffleMessage.set(
        isLibraryShuffle
          ? `Shuffling ${tracks.length} tracks from your library.`
          : `Shuffling ${tracks.length} tracks in this folder, up to ${depth} nested level(s).`
      );
      const firstTrack = this.shuffleQueue.shift();
      if (firstTrack) this.playFromQueue(firstTrack);
    } catch (error: unknown) {
      this.errorMessage.set(this.describeError(error));
    }
  }

  onPlaybackEnded(): void {
    this.isPlaying.set(false);
    const request = ++this.playbackTransitionRequest;
    const track = this.nowPlaying();
    const mode = this.repeatMode();
    if (track && mode !== 'off') {
      this.api.playbackEnded(mode).subscribe({
        next: (transition) => {
          if (request !== this.playbackTransitionRequest || this.nowPlaying()?.path !== track.path
            || !this.audioPlayer?.ended) return;
          this.repeatMode.set(transition.repeatMode);
          if (transition.replay) {
            // Keep the queue and shuffle position, but start a fresh listen.
            this.countedPlaybackKey = '';
            this.audioPlayer.currentTime = 0;
            void this.audioPlayer.play().catch((error: unknown) => {
              this.errorMessage.set(`Replay could not start: ${this.describeError(error)}`);
            });
          } else {
            this.advanceAfterPlayback();
          }
        },
        error: (error: unknown) => {
          if (request === this.playbackTransitionRequest) {
            this.errorMessage.set(`Repeat could not start: ${this.describeApiError(error)}. Press play to retry.`);
          }
        }
      });
      return;
    }
    this.advanceAfterPlayback();
  }

  cycleRepeatMode(): void {
    const modes: RepeatMode[] = ['off', 'once', 'infinite'];
    this.repeatMode.set(modes[(modes.indexOf(this.repeatMode()) + 1) % modes.length]);
    if (this.audioPlayer?.ended) this.onPlaybackEnded();
  }

  private advanceAfterPlayback(): void {
    if (this.shuffleMode() === 'off') {
      this.playNextTrack();
      return;
    }
    if (this.shuffleQueue.length === 0) {
      this.shuffleQueue = this.shuffled(this.shuffleSource);
    }
    this.playNextShuffleTrack();
  }

  folderChildren(path: string): MusicLibraryNode[] | undefined {
    return this.folderContents()[path];
  }

  folderIsLoading(path: string): boolean {
    return this.loadingFolders().has(path);
  }

  trackByPath(_index: number, node: MusicLibraryNode): string {
    return node.path;
  }

  onShuffleDepthChange(event: Event): void {
    const target = event.target;
    if (target instanceof HTMLSelectElement) this.shuffleDepth.set(Number(target.value));
  }

  selectTab(tab: 'library' | 'most-played' | 'service'): void {
    this.activeTab.set(tab);
    if (tab === 'most-played') this.loadListenRanking();
  }

  selectWorkspace(workspace: 'music' | 'series'): void {
    this.activeWorkspace.set(workspace);
    window.location.hash = workspace === 'series' ? '#/series' : '#/music';
  }

  restoreWorkspaceFromPath(): void {
    this.activeWorkspace.set(window.location.hash === '#/series' ? 'series' : 'music');
  }

  @HostListener('window:popstate')
  onWorkspaceHistoryNavigation(): void {
    this.restoreWorkspaceFromPath();
  }

  @HostListener('window:hashchange')
  onWorkspaceHashNavigation(): void {
    this.restoreWorkspaceFromPath();
  }

  restoreMusicPlayerUser(): void {
    this.userLoading.set(true);
    this.http.get<MusicPlayerUser[]>('/api/preferences/users').subscribe({
      next: (users) => {
        this.knownUsers.set(users);
        let savedUsername = '';
        try {
          savedUsername = localStorage.getItem(this.usernameStorageKey) ?? '';
        } catch (error: unknown) {
          this.userError.set(`Could not read the saved username: ${this.describeApiError(error)}`);
        }
        this.selectedLoginUsername.set(users[0]?.username ?? '');
        this.userMode.set(users.length ? 'existing' : 'new');
        if (savedUsername) {
          this.activateMusicPlayerUser(savedUsername);
        } else {
          this.userLoading.set(false);
        }
      },
      error: (error: unknown) => {
        this.userError.set(`Could not load users: ${this.describeApiError(error)}`);
        this.userLoading.set(false);
      }
    });
  }

  setUserMode(mode: 'existing' | 'new'): void {
    this.userMode.set(mode);
    this.userError.set('');
  }

  onLoginUsernameChange(event: Event): void {
    const target = event.target;
    if (target instanceof HTMLSelectElement) this.selectedLoginUsername.set(target.value);
  }

  onNewUsernameChange(event: Event): void {
    const target = event.target;
    if (target instanceof HTMLInputElement) this.loginUsername.set(target.value);
  }

  submitMusicPlayerUser(event: Event): void {
    event.preventDefault();
    if (this.userBusy()) return;
    const username = this.knownUsers().length > 0 && this.userMode() === 'existing'
      ? this.selectedLoginUsername()
      : this.loginUsername().trim();
    if (!username) {
      this.userError.set('Choose or enter a username to continue.');
      return;
    }
    this.activateMusicPlayerUser(username);
  }

  activateMusicPlayerUser(username: string): void {
    this.userBusy.set(true);
    this.userError.set('');
    this.http.post<MusicPlayerUser>('/api/preferences/users', { username }).subscribe({
      next: (user) => {
        try {
          localStorage.setItem(this.usernameStorageKey, user.username);
        } catch (error: unknown) {
          this.userBusy.set(false);
          this.userLoading.set(false);
          this.userError.set(`Could not save the username on this device: ${this.describeApiError(error)}`);
          return;
        }
        this.activeUsername.set(user.username);
        this.loginUsername.set('');
        this.selectedLoginUsername.set(user.username);
        this.userBusy.set(false);
        this.userLoading.set(false);
        this.loadLibrary();
        this.loadYoutubeSettings();
        this.loadPlayerTheme();
        this.loadPlayerSettings();
      },
      error: (error: unknown) => {
        this.userBusy.set(false);
        this.userLoading.set(false);
        this.userError.set(this.describeApiError(error));
      }
    });
  }

  logoutMusicPlayerUser(): void {
    this.playbackTransitionRequest++;
    this.repeatMode.set('off');
    this.stopPlayer();
    this.nowPlaying.set(null);
    try {
      localStorage.removeItem(this.usernameStorageKey);
    } catch (error: unknown) {
      this.userError.set(`Could not clear the saved username: ${this.describeApiError(error)}`);
      return;
    }
    this.activeUsername.set('');
    this.availableThemes.set([]);
    this.activeTheme.set(null);
    this.listenRanking.set([]);
    if (this.volumeSaveTimer !== undefined) {
      window.clearTimeout(this.volumeSaveTimer);
      this.volumeSaveTimer = undefined;
    }
    this.savedVolume = undefined;
    this.activeTab.set('library');
    this.userError.set('');
    this.userLoading.set(true);
    this.http.get<MusicPlayerUser[]>('/api/preferences/users').subscribe({
      next: (users) => {
        this.knownUsers.set(users);
        this.selectedLoginUsername.set(users[0]?.username ?? '');
        this.userMode.set(users.length ? 'existing' : 'new');
        this.userLoading.set(false);
      },
      error: (error: unknown) => {
        this.userError.set(`Could not load users: ${this.describeApiError(error)}`);
        this.userLoading.set(false);
      }
    });
  }

  prepareForRestore(): void {
    this.playbackTransitionRequest++;
    this.playbackQueueRequest++;
    this.repeatMode.set('off');
    this.stopPlayer();
    this.nowPlaying.set(null);
    if (this.volumeSaveTimer !== undefined) window.clearTimeout(this.volumeSaveTimer);
    this.volumeSaveTimer = undefined;
  }

  private userHeaders(): HttpHeaders {
    return new HttpHeaders({ 'X-Atalaya-Username': this.activeUsername() });
  }

  loadPlayerTheme(): void {
    this.http.get<PlayerThemeCatalog>('/api/preferences/player-theme', { headers: this.userHeaders() }).subscribe({
      next: (catalog) => this.applyThemeCatalog(catalog),
      error: (error: unknown) => this.themeStatus.set(`Could not load themes: ${this.describeApiError(error)}`)
    });
  }

  loadPlayerSettings(): void {
    const username = this.activeUsername();
    if (!username) return;
    this.http.get<{ volume: number | null }>('/api/preferences/player-settings', { headers: this.userHeaders() }).subscribe({
      next: (settings) => {
        if (this.activeUsername() !== username) return;
        if (settings.volume === null || !Number.isFinite(settings.volume) || settings.volume < 0 || settings.volume > 1) return;
        this.savedVolume = settings.volume;
        this.playerVolume.set(settings.volume);
        const player = this.audioPlayer;
        if (player) player.volume = settings.volume;
      },
      error: (error: unknown) => {
        if (this.activeUsername() === username) {
          this.errorMessage.set(`Could not load player preferences: ${this.describeApiError(error)}`);
        }
      }
    });
  }

  chooseTheme(themeId: string): void {
    if (this.themeBusy() || themeId === this.selectedThemeId()) return;
    this.themeBusy.set(true);
    this.themeStatus.set('Applying theme…');
    this.http.put<PlayerThemeCatalog>('/api/preferences/player-theme/selection', { themeId }, { headers: this.userHeaders() }).subscribe({
      next: (catalog) => {
        this.applyThemeCatalog(catalog);
        this.themeStatus.set('Theme saved');
        this.themeBusy.set(false);
      },
      error: (error: unknown) => {
        this.themeStatus.set(`Could not save theme: ${this.describeApiError(error)}`);
        this.themeBusy.set(false);
      }
    });
  }

  toggleThemeChooser(): void {
    this.themeChooserOpen.update((open) => !open);
  }

  selectedThemeName(): string {
    return this.availableThemes().find((theme) => theme.id === this.selectedThemeId())?.name ?? 'Theme';
  }

  themeSwatches(theme: PlayerThemeOption): string[] {
    return [theme.background, theme.line, theme.accent];
  }

  selectedThemeOption(): PlayerThemeOption | null {
    return this.availableThemes().find((theme) => theme.id === this.selectedThemeId()) ?? null;
  }

  themeCssProperties(): Record<string, string> {
    const theme = this.activeTheme();
    if (!theme) return {};
    const colorMix = (color: string, opacity: number): string =>
      `color-mix(in srgb, ${color} ${opacity}%, transparent)`;
    return {
      '--background': theme.background,
      '--surface': theme.surface,
      '--line': theme.line,
      '--text': theme.text,
      '--muted': theme.muted,
      '--faint': colorMix(theme.muted, 70),
      '--accent': theme.accent,
      '--accent-ink': theme.mode === 'light' ? '#fff9ef' : '#0c1b2b',
      '--panel-background': `linear-gradient(145deg, ${colorMix(theme.surface, 96)}, ${colorMix(theme.background, 98)} 72%)`,
      '--panel-border': theme.line,
      '--card-background': colorMix(theme.surface, 82),
      '--card-border': theme.line,
      '--input-background': colorMix(theme.background, 92),
      '--input-border': theme.line,
      '--button-background': colorMix(theme.surface, 92),
      '--button-border': theme.line,
      '--button-text': theme.text,
      '--button-hover': colorMix(theme.accent, 23),
      '--nav-background': theme.surface,
      '--dock-background': colorMix(theme.background, 96),
      '--dock-border': theme.line,
      '--disc-background': theme.surface,
      '--disc-border': theme.line,
      '--row-hover': colorMix(theme.accent, 9),
      '--theme-scheme': theme.mode
    };
  }

  beginCreateTheme(): void {
    const current = this.selectedThemeOption();
    this.editingThemeId.set(null);
    this.themeEditor.set(current ? {
      ...current,
      name: 'My new theme',
      description: 'A personal look for your listening space.'
    } : {
      name: 'My new theme',
      description: 'A personal look for your listening space.',
      mode: 'dark',
      background: '#101624',
      surface: '#1a2435',
      text: '#f0f3f8',
      muted: '#a2afc1',
      accent: '#c5a7ff',
      line: '#36435a',
      backdrop: 'aurora',
      visualizerPalette: ['#63d8ff', '#a87bff', '#ff83c5'],
      visualizerStyle: 'bars',
      visualizerBarCount: 48,
      visualizerSensitivity: 1
    });
  }

  beginEditTheme(theme: PlayerThemeOption): void {
    this.editingThemeId.set(theme.id);
    this.themeEditor.set({
      name: theme.name,
      description: theme.description,
      mode: theme.mode,
      background: theme.background,
      surface: theme.surface,
      text: theme.text,
      muted: theme.muted,
      accent: theme.accent,
      line: theme.line,
      backdrop: theme.backdrop,
      visualizerPalette: [...theme.visualizerPalette],
      visualizerStyle: theme.visualizerStyle,
      visualizerBarCount: theme.visualizerBarCount,
      visualizerSensitivity: theme.visualizerSensitivity
    });
  }

  cancelThemeEdit(): void {
    this.themeEditor.set(null);
    this.editingThemeId.set(null);
  }

  saveTheme(event: Event): void {
    event.preventDefault();
    const form = event.currentTarget;
    if (!(form instanceof HTMLFormElement) || this.themeBusy()) return;
    const values = new FormData(form);
    const draft: PlayerThemeDraft = {
      name: String(values.get('name') ?? '').trim(),
      description: String(values.get('description') ?? '').trim(),
      mode: String(values.get('mode')) as PlayerThemeDraft['mode'],
      background: String(values.get('background')),
      surface: String(values.get('surface')),
      text: String(values.get('text')),
      muted: String(values.get('muted')),
      accent: String(values.get('accent')),
      line: String(values.get('line')),
      backdrop: String(values.get('backdrop')) as PlayerThemeDraft['backdrop'],
      visualizerPalette: [
        String(values.get('visualizerColor1')),
        String(values.get('visualizerColor2')),
        String(values.get('visualizerColor3'))
      ],
      visualizerStyle: String(values.get('visualizerStyle')) as PlayerThemeDraft['visualizerStyle'],
      visualizerBarCount: Number(values.get('visualizerBarCount')),
      visualizerSensitivity: Number(values.get('visualizerSensitivity'))
    };
    const themeId = this.editingThemeId();
    this.themeBusy.set(true);
    this.themeStatus.set(themeId ? 'Updating theme…' : 'Creating theme…');
    const request = themeId
      ? this.http.put<PlayerThemeCatalog>(`/api/preferences/player-theme/${encodeURIComponent(themeId)}`, draft, { headers: this.userHeaders() })
      : this.http.post<PlayerThemeCatalog>('/api/preferences/player-theme', draft, { headers: this.userHeaders() });
    request.subscribe({
      next: (catalog) => {
        this.applyThemeCatalog(catalog);
        this.cancelThemeEdit();
        this.themeStatus.set(themeId ? 'Theme updated' : 'Theme created');
        this.themeBusy.set(false);
      },
      error: (error: unknown) => {
        this.themeStatus.set(`Could not save theme: ${this.describeApiError(error)}`);
        this.themeBusy.set(false);
      }
    });
  }

  deleteTheme(theme: PlayerThemeOption): void {
    if (this.themeBusy() || !window.confirm(`Delete the "${theme.name}" theme?`)) return;
    this.themeBusy.set(true);
    this.themeStatus.set('Deleting theme…');
    this.http.delete<PlayerThemeCatalog>(`/api/preferences/player-theme/${encodeURIComponent(theme.id)}`, { headers: this.userHeaders() }).subscribe({
      next: (catalog) => {
        this.applyThemeCatalog(catalog);
        if (this.editingThemeId() === theme.id) this.cancelThemeEdit();
        this.themeStatus.set('Theme deleted');
        this.themeBusy.set(false);
      },
      error: (error: unknown) => {
        this.themeStatus.set(`Could not delete theme: ${this.describeApiError(error)}`);
        this.themeBusy.set(false);
      }
    });
  }

  loadListenRanking(): void {
    this.rankingLoading.set(true);
    this.rankingError.set('');
    this.http.get<MostListenedTrack[]>('/api/preferences/music/most-listened', { headers: this.userHeaders() }).subscribe({
      next: (tracks) => {
        this.listenRanking.set(tracks);
        this.rankingLoading.set(false);
      },
      error: (error: unknown) => {
        this.rankingError.set(this.describeApiError(error));
        this.rankingLoading.set(false);
      }
    });
  }

  playRankedTrack(track: MostListenedTrack): void {
    this.playTrack({
      name: track.name,
      path: track.path,
      directory: false,
      sizeBytes: 0,
      children: []
    });
  }

  private applyThemeCatalog(catalog: PlayerThemeCatalog): void {
    this.selectedThemeId.set(catalog.selectedThemeId);
    this.availableThemes.set(catalog.themes);
    this.activeTheme.set(catalog.themes.find((theme) => theme.id === catalog.selectedThemeId) ?? null);
  }

  loadYoutubeSettings(): void {
    this.settingsStatus.set('Loading settings…');
    this.http.get<YoutubeSettings>('/api/youtube/settings').subscribe({
      next: (settings) => {
        this.youtubeSettings.set(settings);
        this.settingsStatus.set('Settings loaded');
      },
      error: (error: unknown) => this.settingsStatus.set(`Could not load settings: ${this.describeApiError(error)}`)
    });
  }

  saveYoutubeSettings(event: Event): void {
    event.preventDefault();
    const form = event.currentTarget;
    if (!(form instanceof HTMLFormElement) || this.settingsBusy()) return;
    const values = new FormData(form);
    const settings: YoutubeSettings = {
      storageDirectory: String(values.get('storageDirectory') ?? '').trim(),
      jsRuntime: String(values.get('jsRuntime') ?? '').trim(),
      ejsRemoteComponents: String(values.get('ejsRemoteComponents') ?? '').trim(),
      playlistDownloadAttempts: Number(values.get('playlistDownloadAttempts')),
      playlistRetryDelayMillis: Number(values.get('playlistRetryDelayMillis'))
    };

    this.settingsBusy.set(true);
    this.settingsStatus.set('Saving settings…');
    this.http.put<YoutubeSettings>('/api/youtube/settings', settings).subscribe({
      next: (updatedSettings) => {
        this.youtubeSettings.set(updatedSettings);
        this.settingsStatus.set('Settings saved');
        this.settingsBusy.set(false);
      },
      error: (error: unknown) => {
        this.settingsStatus.set(`Could not save settings: ${this.describeApiError(error)}`);
        this.settingsBusy.set(false);
      }
    });
  }

  onPlaybackStateChange(): void {
    const player = this.audioPlayer;
    this.isPlaying.set(player ? !player.paused : false);
    const track = this.nowPlaying();
    const username = this.activeUsername();
    const countedPlaybackKey = username && track ? JSON.stringify([username, track.path]) : '';
    if (player && !player.paused && track && username && this.countedPlaybackKey !== countedPlaybackKey) {
      this.countedPlaybackKey = countedPlaybackKey;
      this.http.post<MostListenedTrack>('/api/preferences/music/listens', { path: track.path }, { headers: this.userHeaders() }).subscribe({
        next: () => {
          if (this.activeTab() === 'most-played') this.loadListenRanking();
        },
        error: (error: unknown) => this.errorMessage.set(`Could not record this listen: ${this.describeApiError(error)}`)
      });
    }
  }

  onPlaybackTimeUpdate(): void {
    const player = this.audioPlayer;
    if (!player) return;
    this.playbackPosition.set(player.currentTime);
    this.playbackDuration.set(Number.isFinite(player.duration) ? player.duration : 0);
  }

  seekPlayback(event: Event): void {
    const target = event.target;
    const player = this.audioPlayer;
    if (!(target instanceof HTMLInputElement) || !player) return;
    player.currentTime = Number(target.value);
    this.playbackPosition.set(player.currentTime);
  }

  setPlayerVolume(event: Event): void {
    const target = event.target;
    const player = this.audioPlayer;
    if (!(target instanceof HTMLInputElement) || !player) return;
    player.volume = Number(target.value);
    this.playerVolume.set(player.volume);
  }

  seekBy(seconds: number): void {
    const player = this.audioPlayer;
    if (!player || !Number.isFinite(player.duration)) return;
    player.currentTime = Math.max(0, Math.min(player.duration, player.currentTime + seconds));
    this.playbackPosition.set(player.currentTime);
  }

  adjustVolume(delta: number): void {
    const player = this.audioPlayer;
    if (!player) return;
    player.volume = Math.max(0, Math.min(1, player.volume + delta));
    this.playerVolume.set(player.volume);
  }

  formatTime(seconds: number): string {
    if (!Number.isFinite(seconds) || seconds < 0) return '0:00';
    const wholeSeconds = Math.floor(seconds);
    return `${Math.floor(wholeSeconds / 60)}:${String(wholeSeconds % 60).padStart(2, '0')}`;
  }

  saveVolume(): void {
    const player = this.audioPlayer;
    const username = this.activeUsername();
    if (!player || !username) return;
    this.playerVolume.set(player.volume);
    if (this.savedVolume === player.volume) return;
    if (this.volumeSaveTimer !== undefined) window.clearTimeout(this.volumeSaveTimer);
    const volume = player.volume;
    this.volumeSaveTimer = window.setTimeout(() => {
      this.volumeSaveTimer = undefined;
      const headers = new HttpHeaders({ 'X-Atalaya-Username': username });
      this.http.put('/api/preferences/player-settings', { volume }, { headers }).subscribe({
        next: () => {
          if (this.activeUsername() === username) this.savedVolume = volume;
        },
        error: (error: unknown) => {
          if (this.activeUsername() === username) {
            this.errorMessage.set(`Could not save player preferences: ${this.describeApiError(error)}`);
          }
        }
      });
    }, 300);
  }

  toggleShuffle(): void {
    if (this.shuffleMode() === 'off') {
      if (this.shuffleSource.length === 0) return;
      this.shuffleMode.set(this.shuffleScope === 'folder' ? 'folder' : 'library');
      this.shuffleQueue = this.shuffled(
        this.shuffleSource.filter((track) => track.path !== this.nowPlaying()?.path)
      );
      this.shuffleMessage.set(
        this.shuffleMode() === 'folder' ? 'Folder shuffle restored.' : 'Library shuffle restored.'
      );
      const currentTrack = this.nowPlaying();
      this.playbackHistory = currentTrack ? [currentTrack] : [];
      this.historyIndex = this.playbackHistory.length ? 0 : -1;
      return;
    }
    const currentTrack = this.nowPlaying();
    this.shuffleScope = this.shuffleMode();
    this.shuffleMode.set('off');
    this.shuffleMessage.set('Shuffle paused. Folder order restored; toggle to resume shuffle.');
    this.playbackQueue = [...this.shuffleSource].sort((left, right) => left.name.localeCompare(right.name));
    this.shuffleQueue = [];
    if (currentTrack && !this.playbackQueue.some((track) => track.path === currentTrack.path)) {
      this.playbackQueue.push(currentTrack);
      this.playbackQueue.sort((left, right) => left.path.localeCompare(right.path));
    }
    this.playbackQueueIndex = this.playbackQueue.findIndex((track) => track.path === currentTrack?.path);
    this.playbackHistory = currentTrack ? [currentTrack] : [];
    this.historyIndex = currentTrack ? 0 : -1;
  }

  toggleVisualizer(): void {
    if (this.visualizerOpen()) {
      this.visualizerOpen.set(false);
      this.stopVisualizer();
      return;
    }
    this.visualizerOpen.set(true);
    requestAnimationFrame(() => this.startVisualizer());
  }

  closeVisualizer(): void {
    this.visualizerOpen.set(false);
    this.stopVisualizer();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.visualizerOpen()) this.closeVisualizer();
  }

  invokeApi(
    event: Event,
    method: 'GET' | 'POST',
    endpoint: string,
    bodyType: 'json' | 'none' = 'none',
    queryFields: string[] = []
  ): void {
    event.preventDefault();
    const form = event.currentTarget;
    if (!(form instanceof HTMLFormElement) || this.apiBusy()) return;

    const formData = new FormData(form);
    let url = `/api/youtube/${endpoint}`;
    const params = new URLSearchParams();
    for (const field of queryFields) {
      const value = String(formData.get(field) ?? '').trim();
      if (value) params.set(field, value);
    }
    if (params.size) url += `?${params.toString()}`;

    let body: Record<string, FormDataEntryValue> | undefined;
    if (bodyType === 'json') {
      const jsonBody: Record<string, FormDataEntryValue> = {};
      formData.forEach((value, key) => jsonBody[key] = value);
      body = jsonBody;
      for (const [key, value] of Object.entries(body)) {
        if (typeof value === 'string' && !value.trim()) delete body[key];
      }
    }

    this.apiBusy.set(true);
    this.apiResponseStatus.set(`${method} /api/youtube/${endpoint} …`);
    this.apiResponse.set('Request in progress…');
    if (endpoint !== 'history/metadata-inaccessible') this.inaccessibleMetadata.set(null);
    this.http.request<unknown>(method, url, {
      body,
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      observe: 'response',
      responseType: 'json'
    }).subscribe({
      next: (response) => {
        this.apiResponse.set(response.body === null ? 'No response body.' : JSON.stringify(response.body, null, 2));
        if (endpoint === 'history/metadata-inaccessible') {
          this.inaccessibleMetadata.set(this.isInaccessibleMetadataList(response.body) ? response.body : []);
        }
        this.apiResponseStatus.set(`HTTP ${response.status} ${response.statusText}`);
        this.apiBusy.set(false);
        if (endpoint === 'download' || endpoint === 'playlist/process') this.loadLibrary();
      },
      error: (error: unknown) => {
        this.apiResponse.set(this.describeApiError(error));
        this.apiResponseStatus.set('Request failed');
        this.apiBusy.set(false);
      }
    });
  }

  deleteInaccessibleMetadata(videoId: string): void {
    if (this.metadataMutationBusy()) return;
    this.metadataMutationBusy.set(true);
    this.http.delete<void>(`/api/youtube/history/metadata-inaccessible/${encodeURIComponent(videoId)}`)
      .subscribe({
        next: () => {
          const remaining = (this.inaccessibleMetadata() ?? []).filter((entry) => entry.videoId !== videoId);
          this.inaccessibleMetadata.set(remaining);
          this.apiResponse.set(JSON.stringify(remaining, null, 2));
          this.apiResponseStatus.set('Entry removed');
          this.metadataMutationBusy.set(false);
        },
        error: (error: unknown) => {
          this.apiResponse.set(this.describeApiError(error));
          this.apiResponseStatus.set('Could not remove entry');
          this.metadataMutationBusy.set(false);
        }
      });
  }

  clearInaccessibleMetadata(): void {
    if (this.metadataMutationBusy()) return;
    this.metadataMutationBusy.set(true);
    this.http.delete<void>('/api/youtube/history/metadata-inaccessible').subscribe({
      next: () => {
        this.inaccessibleMetadata.set([]);
        this.apiResponse.set('[]');
        this.apiResponseStatus.set('Metadata list cleared');
        this.metadataMutationBusy.set(false);
      },
      error: (error: unknown) => {
        this.apiResponse.set(this.describeApiError(error));
        this.apiResponseStatus.set('Could not clear list');
        this.metadataMutationBusy.set(false);
      }
    });
  }

  formatSize(sizeBytes: number): string {
    if (sizeBytes < 1024 * 1024) return `${Math.max(1, Math.round(sizeBytes / 1024))} KB`;
    return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  private setFolderLoading(path: string, loading: boolean): void {
    this.loadingFolders.update((current) => {
      const updated = new Set(current);
      if (loading) updated.add(path);
      else updated.delete(path);
      return updated;
    });
  }

  private playNextShuffleTrack(): void {
    const track = this.shuffleQueue.shift();
    if (track) this.playFromQueue(track);
  }

  private playFromQueue(track: MusicLibraryNode): void {
    this.playbackHistory = this.playbackHistory.slice(0, this.historyIndex + 1);
    this.playbackHistory.push(track);
    this.historyIndex++;
    this.startPlayback(track, false);
  }

  private startPlayback(track: MusicLibraryNode, resetQueueRequest = true): void {
    this.playbackTransitionRequest++;
    if (this.nowPlaying()?.path !== track.path && this.repeatMode() === 'once') this.repeatMode.set('off');
    if (resetQueueRequest) this.playbackQueueRequest++;
    this.stopPlayer();
    this.nowPlaying.set(track);
    this.countedPlaybackKey = '';
    this.lastTrackPath = track.path;
    const player = this.audioPlayer;
    if (!player) return;
    player.src = this.api.trackUrl(track.path);
    player.load();
    void player.play().catch((error: unknown) => {
      this.errorMessage.set(`Playback could not start: ${this.describeError(error)}`);
    });
  }

  private startVisualizer(): void {
    const canvas = this.visualizerCanvasRef?.nativeElement;
    const player = this.audioPlayer;
    if (!canvas || !player || !this.visualizerOpen()) return;
    this.stopVisualizer();

    if (!this.analyser) {
      if (typeof AudioContext === 'undefined') {
        this.errorMessage.set('This browser does not support the audio visualizer.');
        this.closeVisualizer();
        return;
      }
      try {
        this.audioContext = new AudioContext();
        this.analyser = this.audioContext.createAnalyser();
        this.analyser.fftSize = 512;
        this.analyser.smoothingTimeConstant = 0.82;
        const source = this.audioContext.createMediaElementSource(player);
        source.connect(this.analyser);
        this.analyser.connect(this.audioContext.destination);
        this.frequencyData = new Uint8Array(new ArrayBuffer(this.analyser.frequencyBinCount));
      } catch (error: unknown) {
        this.errorMessage.set(`Audio visualizer could not start: ${this.describeError(error)}`);
        this.closeVisualizer();
        return;
      }
    }
    if (this.audioContext?.state === 'suspended') {
      void this.audioContext.resume().catch((error: unknown) => {
        this.errorMessage.set(`Audio visualizer could not start: ${this.describeError(error)}`);
      });
    }

    const context = canvas.getContext('2d');
    if (!context || !this.analyser || !this.frequencyData) return;
    const analyser = this.analyser;
    const data = this.frequencyData;
    const theme = this.activeTheme();
    const barCount = theme?.visualizerBarCount ?? 48;
    const sensitivity = theme?.visualizerSensitivity ?? 1;
    const style = theme?.visualizerStyle ?? 'bars';
    const palette = theme?.visualizerPalette ?? ['#4edfff', '#a274ff', '#ff67ce'];
    let width = 0;
    let height = 0;
    let lastFrameAt = 0;
    const resizeCanvas = (): void => {
      const bounds = canvas.getBoundingClientRect();
      width = bounds.width;
      height = bounds.height;
      const pixelRatio = Math.min(window.devicePixelRatio || 1, 1);
      canvas.width = Math.round(width * pixelRatio);
      canvas.height = Math.round(height * pixelRatio);
      context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0);
    };
    resizeCanvas();
    this.visualizerResizeObserver = new ResizeObserver(() => {
      resizeCanvas();
    });
    this.visualizerResizeObserver.observe(canvas);

    const draw = (timestamp: number): void => {
      if (!this.visualizerOpen()) return;
      if (timestamp - lastFrameAt < 1000 / 30) {
        this.animationFrame = requestAnimationFrame(draw);
        return;
      }
      lastFrameAt = timestamp;
      analyser.getByteFrequencyData(data);
      context.clearRect(0, 0, width, height);
      const canvasTop = canvas.getBoundingClientRect().top;
      const titleBottom = this.visualizerTitleRef?.nativeElement.getBoundingClientRect().bottom ?? height * 0.3;
      const controlsTop = this.visualizerControlsRef?.nativeElement.getBoundingClientRect().top ?? height * 0.82;
      const plotTop = Math.max(height * 0.34, titleBottom - canvasTop + 24);
      const plotBottom = Math.min(height * 0.84, controlsTop - canvasTop - 22);
      if (plotBottom - plotTop < 28) {
        this.animationFrame = requestAnimationFrame(draw);
        return;
      }
      const plotHeight = plotBottom - plotTop;
      const bandWidth = width / barCount;
      const colorGradient = context.createLinearGradient(0, 0, width, 0);
      palette.forEach((color, index) => colorGradient.addColorStop(index / (palette.length - 1), color));
      const gain = Math.min(1, Math.max(0.1, sensitivity));
      const baseline = style === 'mirror' ? plotTop + plotHeight / 2 : plotBottom;
      context.fillStyle = colorGradient;
      context.strokeStyle = colorGradient;
      context.lineWidth = style === 'wave' ? 3 : 1.4;
      context.globalAlpha = 0.9;
      context.beginPath();
      for (let bar = 0; bar < barCount; bar++) {
        const frequencyIndex = Math.floor(Math.pow(bar / barCount, 1.7) * data.length);
        const energy = Math.min(1, (data[frequencyIndex] / 255) * gain);
        const x = bar * bandWidth + bandWidth / 2;
        const barHeight = Math.max(2, energy * plotHeight * (style === 'mirror' ? 0.42 : 0.76));
        if (style === 'wave') {
          const y = baseline - energy * plotHeight * 0.85;
          if (bar === 0) context.moveTo(x, y);
          else context.lineTo(x, y);
        } else if (style === 'mirror') {
          context.fillRect(x - bandWidth * 0.34, baseline - barHeight, bandWidth * 0.68, barHeight * 2);
        } else {
          context.fillRect(x - bandWidth * 0.34, baseline - barHeight, bandWidth * 0.68, barHeight);
        }
      }
      if (style === 'wave') {
        context.stroke();
      } else {
        context.globalAlpha = 0.25;
        context.fillRect(0, baseline, width, 1);
      }
      context.globalAlpha = 1;
      this.animationFrame = requestAnimationFrame(draw);
    };
    this.animationFrame = requestAnimationFrame(draw);
  }

  private stopVisualizer(): void {
    if (this.animationFrame) cancelAnimationFrame(this.animationFrame);
    this.animationFrame = 0;
    this.visualizerResizeObserver?.disconnect();
    this.visualizerResizeObserver = undefined;
  }

  private stopPlayer(): void {
    const player = this.audioPlayer;
    if (!player) return;
    player.pause();
    player.removeAttribute('src');
    player.load();
  }

  private collectTracks(nodes: MusicLibraryNode[]): MusicLibraryNode[] {
    return nodes.flatMap((node) => node.directory ? this.collectTracks(node.children) : [node]);
  }

  private parentFolder(path: string): string {
    const separatorIndex = path.lastIndexOf('/');
    return separatorIndex < 0 ? '' : path.slice(0, separatorIndex);
  }

  private isInaccessibleMetadataList(value: unknown): value is InaccessibleMetadataEntry[] {
    return Array.isArray(value) && value.every((entry) =>
      typeof entry === 'object'
      && entry !== null
      && 'videoId' in entry
      && typeof entry.videoId === 'string'
      && 'firstSeenAt' in entry
      && typeof entry.firstSeenAt === 'string'
      && 'lastSeenAt' in entry
      && typeof entry.lastSeenAt === 'string'
      && 'observations' in entry
      && typeof entry.observations === 'number'
      && 'sourceUrls' in entry
      && Array.isArray(entry.sourceUrls)
      && entry.sourceUrls.every((url: unknown) => typeof url === 'string')
      && 'latestReason' in entry
      && typeof entry.latestReason === 'string'
    );
  }

  private shuffled(tracks: MusicLibraryNode[]): MusicLibraryNode[] {
    const result = [...tracks];
    for (let index = result.length - 1; index > 0; index--) {
      const randomIndex = Math.floor(Math.random() * (index + 1));
      [result[index], result[randomIndex]] = [result[randomIndex], result[index]];
    }
    if (result.length > 1 && result[0].path === this.lastTrackPath) {
      [result[0], result[1]] = [result[1], result[0]];
    }
    return result;
  }

  private sortNodes(nodes: MusicLibraryNode[]): MusicLibraryNode[] {
    return [...nodes].sort((left, right) => {
      if (left.directory !== right.directory) return left.directory ? -1 : 1;
      return left.name.localeCompare(right.name);
    });
  }

  private describeError(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      return error.status === 0
        ? 'Could not reach YouTube Service. Start it on port 8081 and try again.'
        : `YouTube Service returned HTTP ${error.status}.`;
    }
    return error instanceof Error ? error.message : 'An unexpected error occurred.';
  }

  private describeApiError(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      const details = typeof error.error === 'string'
        ? error.error
        : JSON.stringify(error.error, null, 2);
      return `HTTP ${error.status} ${error.statusText}\n${details || this.describeError(error)}`;
    }
    return this.describeError(error);
  }
}
