export type SeriesStatus = 'AIRING' | 'HIATUS' | 'COMPLETED' | 'PLANNED' | 'DROPPED';
export type SeriesDay = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY' | 'FLEXIBLE';

export interface WatchedEpisode {
  season: number;
  episode: number;
  title: string | null;
  watchedAt: string;
  notes: string | null;
}

export interface WatchedEpisodeDraft {
  season: number;
  episode: number;
  title: string | null;
  watched: boolean;
  notes: string | null;
  previousSeason?: number;
  previousEpisode?: number;
}

export interface SeriesShow {
  id: string;
  title: string;
  description: string | null;
  status: SeriesStatus;
  platform: string | null;
  genres: string[];
  scheduleDay: SeriesDay;
  scheduleTime: string | null;
  timezone: string | null;
  seasons: number;
  episodesPerSeason: number;
  watchedEpisodes: WatchedEpisode[];
  nextEpisodeDate: string | null;
  rating: number | null;
  startDate: string | null;
  finishDate: string | null;
  coverUrl: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SeriesShowDraft {
  title: string;
  description: string | null;
  status: SeriesStatus;
  platform: string | null;
  genres: string[];
  scheduleDay: SeriesDay;
  scheduleTime: string | null;
  timezone: string | null;
  seasons: number;
  episodesPerSeason: number;
  nextEpisodeDate: string | null;
  rating: number | null;
  startDate: string | null;
  finishDate: string | null;
  notes: string | null;
}
