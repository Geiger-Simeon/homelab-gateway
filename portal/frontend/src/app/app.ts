import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatToolbarModule } from '@angular/material/toolbar';

import { Me, PortalApi } from './core/portal-api';
import { ThemeService } from './core/theme.service';
import { Home } from './home/home';

@Component({
  selector: 'hl-root',
  imports: [MatToolbarModule, MatButtonModule, MatIconModule, MatProgressBarModule, Home],
  templateUrl: './app.html',
  styleUrl: './app.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  private readonly api = inject(PortalApi);
  protected readonly themes = inject(ThemeService);

  protected readonly me = signal<Me | null>(null);
  protected readonly meFailed = signal(false);
  protected readonly initial = computed(() => this.me()?.name.charAt(0).toUpperCase() ?? '');
  protected readonly isDark = computed(() => this.themes.theme() === 'dark');

  constructor() {
    this.api.me().subscribe({
      next: (me) => this.me.set(me),
      error: () => this.meFailed.set(true),
    });
  }

  protected reload(): void {
    window.location.reload();
  }
}
