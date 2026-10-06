import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SeriesShow, SeriesShowDraft, WatchedEpisodeDraft } from './series-tracker.models';

@Injectable({ providedIn: 'root' })
export class SeriesTrackerApiService {
  private readonly http = inject(HttpClient);
  private readonly apiRoot = '/api/series';

  listShows(username: string): Observable<SeriesShow[]> {
    return this.http.get<SeriesShow[]>(`${this.apiRoot}/shows`, { headers: this.headers(username) });
  }

  createShow(username: string, draft: SeriesShowDraft): Observable<SeriesShow> {
    return this.http.post<SeriesShow>(`${this.apiRoot}/shows`, draft, { headers: this.headers(username) });
  }

  updateShow(username: string, id: string, draft: SeriesShowDraft): Observable<SeriesShow> {
    return this.http.put<SeriesShow>(`${this.apiRoot}/shows/${encodeURIComponent(id)}`, draft, {
      headers: this.headers(username)
    });
  }

  deleteShow(username: string, id: string): Observable<void> {
    return this.http.delete<void>(`${this.apiRoot}/shows/${encodeURIComponent(id)}`, {
      headers: this.headers(username)
    });
  }

  updateEpisode(
    username: string,
    id: string,
    episode: WatchedEpisodeDraft
  ): Observable<SeriesShow> {
    return this.http.put<SeriesShow>(`${this.apiRoot}/shows/${encodeURIComponent(id)}/episodes`, episode, {
      headers: this.headers(username)
    });
  }

  uploadCover(username: string, id: string, file: File): Observable<SeriesShow> {
    const body = new FormData();
    body.append('file', file);
    return this.http.post<SeriesShow>(`${this.apiRoot}/shows/${encodeURIComponent(id)}/cover`, body, {
      headers: this.headers(username)
    });
  }

  private headers(username: string): HttpHeaders {
    return new HttpHeaders({ 'X-Atalaya-Username': username });
  }
}
