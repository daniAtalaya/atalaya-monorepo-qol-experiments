import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { SeriesTrackerApiService } from './series-tracker-api.service';
import {
  SeriesDay, SeriesShow, SeriesShowDraft, SeriesStatus, WatchedEpisode, WatchedEpisodeDraft
} from './series-tracker.models';

const WEEK_DAYS: readonly { key: SeriesDay; label: string }[] = [
  { key: 'MONDAY', label: 'Monday' },
  { key: 'TUESDAY', label: 'Tuesday' },
  { key: 'WEDNESDAY', label: 'Wednesday' },
  { key: 'THURSDAY', label: 'Thursday' },
  { key: 'FRIDAY', label: 'Friday' },
  { key: 'SATURDAY', label: 'Saturday' },
  { key: 'SUNDAY', label: 'Sunday' }
];

@Component({
  selector: 'atalaya-series-tracker',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './series-tracker.component.html',
  styleUrl: './series-tracker.component.css'
})
export class SeriesTrackerComponent implements OnInit {
  readonly username = input.required<string>();
  readonly shows = signal<SeriesShow[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly episodeBusy = signal(false);
  readonly editingEpisodeKey = signal<string | null>(null);
  readonly formOpen = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly selectedId = signal<string | null>(null);
  readonly activeView = signal<'week' | 'library' | 'timeline'>('week');
  readonly searchQuery = signal('');
  readonly error = signal('');
  readonly notice = signal('');
  readonly weekDays = WEEK_DAYS;
  readonly todayKey = WEEK_DAYS[(new Date().getDay() + 6) % 7]?.key;

  private readonly api = inject(SeriesTrackerApiService);

  ngOnInit(): void {
    void this.load();
  }

  async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.shows.set(await firstValueFrom(this.api.listShows(this.username())));
    } catch (error: unknown) {
      this.error.set(this.describeError(error));
    } finally {
      this.loading.set(false);
    }
  }

  selectView(view: 'week' | 'library' | 'timeline'): void {
    this.activeView.set(view);
    this.selectedId.set(null);
  }

  filteredShows(): SeriesShow[] {
    const query = this.searchQuery().trim().toLocaleLowerCase();
    return this.shows().filter((show) => !query || [
      show.title, show.description, show.platform, ...show.genres
    ].some((value) => value?.toLocaleLowerCase().includes(query)));
  }

  showsForDay(day: SeriesDay): SeriesShow[] {
    return this.filteredShows().filter((show) => show.scheduleDay === day);
  }

  timelineShows(): SeriesShow[] {
    const dayIndex = new Map(WEEK_DAYS.map((day, index) => [day.key, index]));
    return this.filteredShows().slice().sort((left, right) =>
      (left.nextEpisodeDate || '9999-12-31').localeCompare(right.nextEpisodeDate || '9999-12-31') ||
      (dayIndex.get(left.scheduleDay) ?? 7) - (dayIndex.get(right.scheduleDay) ?? 7) ||
      left.title.localeCompare(right.title)
    );
  }

  selectedShow(): SeriesShow | undefined {
    return this.shows().find((show) => show.id === this.selectedId());
  }

  editingShow(): SeriesShow | undefined {
    return this.shows().find((show) => show.id === this.editingId());
  }

  watchedCount(show: SeriesShow): number {
    return show.watchedEpisodes.length;
  }

  episodeCapacity(show: SeriesShow): number {
    return show.seasons * show.episodesPerSeason;
  }

  progressPercent(show: SeriesShow): number {
    const capacity = this.episodeCapacity(show);
    return capacity ? Math.min(100, Math.round(this.watchedCount(show) * 100 / capacity)) : 0;
  }

  totalWatched(): number {
    return this.shows().reduce((total, show) => total + this.watchedCount(show), 0);
  }

  statusCount(status: SeriesStatus): number {
    return this.shows().filter((show) => show.status === status).length;
  }

  formatEpisode(episode: Pick<WatchedEpisode, 'season' | 'episode'>): string {
    return `S${episode.season.toString().padStart(2, '0')} · E${episode.episode.toString().padStart(2, '0')}`;
  }

  nextEpisode(show: SeriesShow): { season: number; episode: number } {
    const latest = show.watchedEpisodes
      .filter((item) => item.season > 0)
      .reduce<{ season: number; episode: number }>((current, item) =>
        item.season > current.season || (item.season === current.season && item.episode > current.episode)
          ? item
          : current,
      { season: 1, episode: 0 });
    const episodeLimit = show.episodesPerSeason;
    if (episodeLimit > 0 && latest.episode >= episodeLimit) {
      return { season: latest.season + 1, episode: 1 };
    }
    return { season: latest.season, episode: latest.episode + 1 };
  }

  episodeKey(show: SeriesShow, episode: WatchedEpisode): string {
    return `${show.id}:${episode.season}:${episode.episode}`;
  }

  formatDay(day: SeriesDay): string {
    return WEEK_DAYS.find((item) => item.key === day)?.label ?? 'Whenever';
  }

  showDetails(show: SeriesShow): void {
    this.selectedId.set(this.selectedId() === show.id ? null : show.id);
    this.formOpen.set(false);
  }

  closeDetails(): void {
    this.selectedId.set(null);
  }

  beginCreate(): void {
    this.editingId.set(null);
    this.selectedId.set(null);
    this.formOpen.set(true);
  }

  beginEdit(show: SeriesShow): void {
    this.editingId.set(show.id);
    this.formOpen.set(true);
  }

  cancelEdit(): void {
    this.formOpen.set(false);
    this.editingId.set(null);
  }

  async saveShow(event: Event): Promise<void> {
    event.preventDefault();
    const form = event.currentTarget;
    if (!(form instanceof HTMLFormElement) || this.busy()) return;
    const fields = new FormData(form);
    const get = (name: string): string => String(fields.get(name) ?? '').trim();
    const ratingValue = get('rating');
    const draft: SeriesShowDraft = {
      title: get('title'),
      description: get('description') || null,
      status: get('status') as SeriesStatus,
      platform: get('platform') || null,
      genres: get('genres').split(',').map((genre) => genre.trim()).filter(Boolean),
      scheduleDay: get('scheduleDay') as SeriesDay,
      scheduleTime: get('scheduleTime') || null,
      timezone: get('timezone') || 'UTC',
      seasons: Number(get('seasons') || 0),
      episodesPerSeason: Number(get('episodesPerSeason') || 0),
      nextEpisodeDate: get('nextEpisodeDate') || null,
      rating: ratingValue ? Number(ratingValue) : null,
      startDate: get('startDate') || null,
      finishDate: get('finishDate') || null,
      notes: get('notes') || null
    };
    const fileInput = form.elements.namedItem('cover');
    const file = fileInput instanceof HTMLInputElement ? fileInput.files?.[0] : undefined;
    if (file && file.size > 5 * 1024 * 1024) {
      this.error.set('Cover image must be 5 MB or smaller.');
      return;
    }

    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      const id = this.editingId();
      const saved = id
        ? await firstValueFrom(this.api.updateShow(this.username(), id, draft))
        : await firstValueFrom(this.api.createShow(this.username(), draft));
      this.replaceShow(saved);
      this.selectedId.set(saved.id);
      this.formOpen.set(false);
      this.editingId.set(null);
      if (file) {
        try {
          this.replaceShow(await firstValueFrom(this.api.uploadCover(this.username(), saved.id, file)));
          this.notice.set('Series and cover saved.');
        } catch (error: unknown) {
          this.error.set(`Series saved, but the cover could not be uploaded: ${this.describeError(error)}`);
        }
      } else {
        this.notice.set('Series profile saved.');
      }
    } catch (error: unknown) {
      this.error.set(this.describeError(error));
    } finally {
      this.busy.set(false);
    }
  }

  async toggleEpisode(show: SeriesShow, episode: WatchedEpisode): Promise<void> {
    await this.saveEpisode(show.id, {
      season: episode.season,
      episode: episode.episode,
      title: episode.title,
      watched: false,
      notes: episode.notes
    });
  }

  beginEpisodeEdit(show: SeriesShow, episode: WatchedEpisode): void {
    this.editingEpisodeKey.set(this.episodeKey(show, episode));
    this.error.set('');
    this.notice.set('');
  }

  cancelEpisodeEdit(): void {
    this.editingEpisodeKey.set(null);
  }

  async saveEpisodeEdit(event: Event, show: SeriesShow, episode: WatchedEpisode): Promise<void> {
    event.preventDefault();
    const form = event.currentTarget;
    if (!(form instanceof HTMLFormElement) || this.episodeBusy()) return;
    const fields = new FormData(form);
    const draft: WatchedEpisodeDraft = {
      season: Number(fields.get('season')),
      episode: Number(fields.get('episode')),
      title: String(fields.get('title') ?? '').trim() || null,
      watched: true,
      notes: String(fields.get('episodeNotes') ?? '').trim() || null,
      previousSeason: episode.season,
      previousEpisode: episode.episode
    };
    if (await this.saveEpisode(show.id, draft)) this.editingEpisodeKey.set(null);
  }

  async markEpisode(event: Event, show: SeriesShow): Promise<void> {
    event.preventDefault();
    const form = event.currentTarget;
    if (!(form instanceof HTMLFormElement) || this.episodeBusy()) return;
    const fields = new FormData(form);
    const season = Number(fields.get('season'));
    const episode = Number(fields.get('episode'));
    const watched = show.watchedEpisodes.some((item) => item.season === season && item.episode === episode);
    if (watched) {
      this.error.set(`Season ${season}, episode ${episode} is already in your log.`);
      return;
    }
    const title = String(fields.get('title') ?? '').trim();
    const notes = String(fields.get('episodeNotes') ?? '').trim();
    const saved = await this.saveEpisode(show.id, { season, episode, title: title || null, watched: true, notes: notes || null });
    if (saved) {
      form.reset();
      const next = this.nextEpisode(this.selectedShow() ?? show);
      const seasonInput = form.elements.namedItem('season');
      const episodeInput = form.elements.namedItem('episode');
      if (seasonInput instanceof HTMLInputElement) seasonInput.value = String(next.season);
      if (episodeInput instanceof HTMLInputElement) episodeInput.value = String(next.episode);
    }
  }

  async deleteShow(show: SeriesShow): Promise<void> {
    if (!window.confirm(`Remove "${show.title}" and its episode log?`)) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await firstValueFrom(this.api.deleteShow(this.username(), show.id));
      this.shows.update((shows) => shows.filter((item) => item.id !== show.id));
      this.selectedId.set(null);
      this.notice.set(`${show.title} removed.`);
    } catch (error: unknown) {
      this.error.set(this.describeError(error));
    } finally {
      this.busy.set(false);
    }
  }

  onSearchChange(event: Event): void {
    if (event.target instanceof HTMLInputElement) this.searchQuery.set(event.target.value);
  }

  private async saveEpisode(
    id: string,
    episode: WatchedEpisodeDraft
  ): Promise<boolean> {
    this.episodeBusy.set(true);
    this.error.set('');
    this.notice.set('');
    try {
      this.replaceShow(await firstValueFrom(this.api.updateEpisode(this.username(), id, episode)));
      this.notice.set(episode.watched ? 'Episode added to your watch log.' : 'Episode removed from your watch log.');
      return true;
    } catch (error: unknown) {
      this.error.set(this.describeError(error));
      return false;
    } finally {
      this.episodeBusy.set(false);
    }
  }

  private replaceShow(updated: SeriesShow): void {
    this.shows.update((shows) => {
      const index = shows.findIndex((show) => show.id === updated.id);
      if (index < 0) return [...shows, updated];
      return shows.map((show) => show.id === updated.id ? updated : show);
    });
  }

  private describeError(error: unknown): string {
    if (error instanceof HttpErrorResponse) {
      const body: unknown = error.error;
      if (typeof body === 'object' && body !== null && 'error' in body && typeof body.error === 'string') {
        return body.error;
      }
      return error.message || `Request failed (${error.status}).`;
    }
    return error instanceof Error ? error.message : 'Something went wrong while saving your series.';
  }
}
