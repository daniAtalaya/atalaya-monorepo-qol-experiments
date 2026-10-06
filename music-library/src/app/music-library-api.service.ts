import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { MusicLibraryNode, PlaybackTransition, RepeatMode } from './music-library.models';

@Injectable({ providedIn: 'root' })
export class MusicLibraryApiService {
  private readonly http = inject(HttpClient);
  private readonly apiRoot = '/api/youtube/music';

  getTree(path = '', nestedFolderDepth?: number): Observable<MusicLibraryNode[]> {
    let params = new HttpParams();
    if (path) params = params.set('path', path);
    if (nestedFolderDepth !== undefined) {
      params = params.set('nestedFolderDepth', nestedFolderDepth);
    }
    return this.http.get<MusicLibraryNode[]>(`${this.apiRoot}/tree`, { params });
  }

  trackUrl(path: string): string {
    return `${this.apiRoot}/track?path=${encodeURIComponent(path)}`;
  }

  playbackEnded(repeatMode: RepeatMode): Observable<PlaybackTransition> {
    return this.http.post<PlaybackTransition>(`${this.apiRoot}/playback/ended`, { repeatMode });
  }
}
