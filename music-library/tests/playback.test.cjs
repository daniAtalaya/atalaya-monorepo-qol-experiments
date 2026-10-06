const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { of, Subject } = require('rxjs');

// Exercise the actual component methods with an audio-element double. No browser/audio device required.
function playerFixture() {
  const listens = [];
  const transitions = [];
  const audio = { paused: true, ended: false, currentTime: 0, volume: 1,
    play() { this.paused = false; this.ended = false; return Promise.resolve(); },
    pause() { this.paused = true; }, load() {}, removeAttribute() {} };
  const api = {
    trackUrl: (value) => value,
    playbackEnded: (mode) => { const result = new Subject(); transitions.push({ mode, result }); return result; }
  };
  const http = { post: (url, body, options) => { listens.push({ url, body, options }); return of({}); } };
  class HttpClient {}
  class MusicLibraryApiService {}
  const signal = (initial) => { let value = initial; const read = () => value;
    read.set = (next) => { value = next; }; read.update = (fn) => { value = fn(value); }; return read; };
  const decorator = () => () => {};
  const source = fs.readFileSync(path.join(__dirname, '../src/app/app.component.ts'), 'utf8');
  const compiled = ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, experimentalDecorators: true
  }}).outputText;
  const modules = {
    '@angular/core': { Component: decorator, ViewChild: decorator, HostListener: decorator, signal,
      inject: (token) => token === HttpClient ? http : api },
    '@angular/common': { CommonModule: class {} },
    '@angular/common/http': { HttpClient, HttpHeaders: class { constructor(value) { this.value = value; } }, HttpErrorResponse: class {} },
    'rxjs': require('rxjs'),
    './music-library-api.service': { MusicLibraryApiService },
    './series-tracker.component': { SeriesTrackerComponent: class {} },
    './repeat-control.component': { RepeatControlComponent: class {} },
    './panic-backup.component': { PanicBackupComponent: class {} }
  };
  const context = { exports: {}, require: (name) => {
    assert.ok(modules[name], `Unexpected import: ${name}`); return modules[name];
  }, window: { clearTimeout() {} }, console, HTMLInputElement: class {} };
  vm.runInNewContext(compiled, context);
  const component = new context.exports.AppComponent();
  component.playerRef = { nativeElement: audio };
  component.activeUsername.set('alice');
  const first = { path: 'songs/first.mp3', name: 'first.mp3', directory: false, children: [], sizeBytes: 100 };
  const second = { ...first, path: 'songs/second.mp3', name: 'second.mp3' };
  component.playbackQueue = [first, second];
  component.playbackQueueIndex = 0;
  component.playbackHistory = [first];
  component.historyIndex = 0;
  component.startPlayback(first, false);
  component.onPlaybackStateChange();
  function end() { audio.paused = true; audio.ended = true; component.onPlaybackEnded(); }
  function resolveRepeat(replay, mode) { transitions.at(-1).result.next({ replay, repeatMode: mode }); component.onPlaybackStateChange(); }
  return { component, audio, listens, transitions, first, second, end, resolveRepeat };
}

test('repeat once records its replay and then advances, without consuming the queue early', () => {
  const f = playerFixture();
  f.component.repeatMode.set('once');
  f.end();
  assert.equal(f.transitions[0].mode, 'once');
  f.resolveRepeat(true, 'off');
  assert.equal(f.component.nowPlaying().path, f.first.path);
  assert.equal(f.component.playbackQueueIndex, 0);
  assert.equal(f.listens.length, 2);
  f.end();
  f.component.onPlaybackStateChange();
  assert.equal(f.component.nowPlaying().path, f.second.path);
  assert.equal(f.listens.length, 3);
  assert.ok(f.listens.every((listen) => listen.url === '/api/preferences/music/listens'));
});

test('every infinite repeat adds a listen; pause/resume and duplicate playing events do not', () => {
  const f = playerFixture();
  f.component.repeatMode.set('infinite');
  for (let index = 0; index < 4; index++) {
    f.end(); f.resolveRepeat(true, 'infinite');
    f.component.onPlaybackStateChange();
    f.audio.pause(); f.component.onPlaybackStateChange();
    f.audio.play(); f.component.onPlaybackStateChange();
  }
  assert.equal(f.listens.length, 5);
  assert.equal(f.component.repeatMode(), 'infinite');
  assert.equal(f.component.playbackQueueIndex, 0);
});

test('manual next bypasses repeat and ignores a delayed repeat response', () => {
  const f = playerFixture();
  f.component.repeatMode.set('once');
  f.end();
  f.component.playNextTrack();
  f.component.onPlaybackStateChange();
  f.resolveRepeat(true, 'off');
  assert.equal(f.component.nowPlaying().path, f.second.path);
  assert.equal(f.component.repeatMode(), 'off');
  assert.equal(f.listens.length, 2);
});

test('repeat does not move the shuffle queue and repeating a completed song manually counts again', () => {
  const f = playerFixture();
  f.component.shuffleMode.set('library');
  f.component.shuffleQueue = [f.second];
  f.component.repeatMode.set('infinite');
  f.end(); f.resolveRepeat(true, 'infinite');
  assert.equal(f.component.shuffleQueue.length, 1);
  f.audio.ended = true; f.audio.paused = true;
  f.component.togglePlayback(); f.component.onPlaybackStateChange();
  assert.equal(f.listens.length, 3);
});

test('restoring stops playback and invalidates pending repeat responses', () => {
  const f = playerFixture();
  f.component.repeatMode.set('infinite');
  f.end();
  f.component.prepareForRestore();
  f.resolveRepeat(true, 'infinite');
  assert.equal(f.component.nowPlaying(), null);
  assert.equal(f.component.repeatMode(), 'off');
  assert.equal(f.audio.paused, true);
  assert.equal(f.listens.length, 1);
});
