import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

import { iconOrFallback } from '../core/icons';
import { SystemStatus } from '../core/portal-api';

const TIME_FORMAT = new Intl.DateTimeFormat('de-DE', { dateStyle: 'short', timeStyle: 'short' });

@Component({
  selector: 'hl-system-panel',
  imports: [MatIconModule],
  template: `
    <section aria-labelledby="system-heading">
      <div class="head">
        <h2 id="system-heading">System</h2>
        <span class="updated" [class.stale]="status().stale">
          @if (status().stale) {
            Veraltet, Stand {{ updatedAt() }}
          } @else {
            Stand {{ updatedAt() }}
          }
        </span>
      </div>
      <ul class="checks" [class.dimmed]="status().stale">
        @for (check of status().checks; track check.id) {
          <li class="check">
            <mat-icon class="check-icon" [svgIcon]="icon(check.id)" aria-hidden="true" />
            <div class="check-text">
              <span class="label">{{ check.label }}</span>
              <span class="value" [class]="'state-' + check.state.toLowerCase()">
                @if (check.state === 'OK' || check.state === 'FAIL') {
                  <span class="dot"></span>
                }
                {{ check.value }}
              </span>
            </div>
          </li>
        }
      </ul>
    </section>
  `,
  styleUrl: './system-panel.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SystemPanel {
  readonly status = input.required<SystemStatus>();

  protected readonly updatedAt = computed(() =>
    TIME_FORMAT.format(new Date(this.status().generatedAt)),
  );

  protected icon(id: string): string {
    return iconOrFallback(id, 'server');
  }
}
