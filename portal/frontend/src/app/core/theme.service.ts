import { DOCUMENT, Injectable, effect, inject, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'hl-theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  readonly theme = signal<Theme>(readStored());

  constructor() {
    effect(() => {
      const theme = this.theme();
      this.doc.documentElement.style.colorScheme = theme;
      try {
        localStorage.setItem(STORAGE_KEY, theme);
      } catch {
        // storage blocked, theme just won't survive a reload
      }
    });
  }

  toggle(): void {
    this.theme.update((t) => (t === 'dark' ? 'light' : 'dark'));
  }
}

function readStored(): Theme {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}
