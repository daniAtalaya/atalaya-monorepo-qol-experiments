import { Component, input, output } from '@angular/core';
import { RepeatMode } from './music-library.models';

@Component({
  selector: 'atalaya-repeat-control',
  standalone: true,
  template: `
    <button type="button" [disabled]="disabled()" [class.active]="mode() !== 'off'"
      [attr.aria-pressed]="mode() !== 'off'" [attr.aria-label]="description()" [title]="description()"
      (click)="change.emit()">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M17 2l4 4-4 4M3 11V8a2 2 0 0 1 2-2h16M7 22l-4-4 4-4m14-1v3a2 2 0 0 1-2 2H3"/></svg>
      <span aria-live="polite">{{ mode() === 'once' ? 'Once' : mode() === 'infinite' ? '∞' : 'Off' }}</span>
    </button>`,
  styles: [`
    :host { display: inline-flex; }
    button { display: flex; align-items: center; gap: 6px; min-height: 38px; padding: 0 10px;
      border: 1px solid var(--line); border-radius: 999px; background: var(--surface); color: var(--muted);
      font: inherit; font-size: 11px; cursor: pointer; white-space: nowrap; }
    button.active { color: var(--accent); border-color: var(--accent); background: color-mix(in srgb, var(--accent) 12%, var(--surface)); }
    button:hover:not(:disabled) { border-color: var(--accent); color: var(--text); }
    button:disabled { opacity: .4; cursor: default; }
    button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
    svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.7; stroke-linecap: round; stroke-linejoin: round; }
  `]
})
export class RepeatControlComponent {
  readonly mode = input<RepeatMode>('off');
  readonly disabled = input(false);
  readonly change = output<void>();
  description(): string {
    return this.mode() === 'off' ? 'Repeat off. Click to repeat this song once.'
      : this.mode() === 'once' ? 'Repeat this song once, then continue. Click to repeat indefinitely.'
      : 'Repeat this song indefinitely. Click to turn repeat off.';
  }
}
