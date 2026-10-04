export interface MusicLibraryNode {
  name: string;
  path: string;
  directory: boolean;
  sizeBytes: number;
  children: MusicLibraryNode[];
}

export type ShuffleMode = 'off' | 'library' | 'folder';

export interface YoutubeSettings {
  storageDirectory: string;
  jsRuntime: string;
  ejsRemoteComponents: string;
  playlistDownloadAttempts: number;
  playlistRetryDelayMillis: number;
}

export interface PlayerThemeOption {
  id: string;
  name: string;
  description: string;
  mode: 'dark' | 'light';
  background: string;
  surface: string;
  text: string;
  muted: string;
  accent: string;
  line: string;
  backdrop: 'aurora' | 'halo' | 'plain';
  visualizerPalette: string[];
  visualizerStyle: 'bars' | 'mirror' | 'wave';
  visualizerBarCount: number;
  visualizerSensitivity: number;
}

export interface PlayerThemeCatalog {
  selectedThemeId: string;
  themes: PlayerThemeOption[];
}

export type PlayerThemeDraft = Omit<PlayerThemeOption, 'id'>;

export interface MostListenedTrack {
  path: string;
  name: string;
  listenCount: number;
  lastListenedAt: string;
}

export interface MusicPlayerUser {
  username: string;
}
