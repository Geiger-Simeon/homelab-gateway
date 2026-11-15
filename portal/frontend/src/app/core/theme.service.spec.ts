import { TestBed } from '@angular/core/testing';

import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => localStorage.clear());

  it('given no saved theme, when the service starts, then it is dark', () => {
    // given: no saved theme, storage is cleared in beforeEach

    // when
    const themes = TestBed.inject(ThemeService);
    TestBed.tick();

    // then
    expect(themes.theme()).toBe('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');
  });

  it('given the dark theme, when toggling, then it is light and saved', () => {
    // given
    const themes = TestBed.inject(ThemeService);
    TestBed.tick();

    // when
    themes.toggle();
    TestBed.tick();

    // then
    expect(document.documentElement.style.colorScheme).toBe('light');
    expect(localStorage.getItem('hl-theme')).toBe('light');
  });

  it('given a saved light theme, when the service starts, then it is light', () => {
    // given
    localStorage.setItem('hl-theme', 'light');

    // when
    const themes = TestBed.inject(ThemeService);

    // then
    expect(themes.theme()).toBe('light');
  });
});
