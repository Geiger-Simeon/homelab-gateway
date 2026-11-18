import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';

import { iconOrFallback } from '../core/icons';
import { PortalService } from '../core/portal-api';

@Component({
  selector: 'hl-service-card',
  imports: [MatCardModule, MatButtonModule, MatIconModule],
  template: `
    @let s = service();
    <mat-card appearance="outlined" class="card" [class.live]="isLive()">
      <div class="top">
        <div class="icon" [class]="'svc-' + s.color">
          <mat-icon [svgIcon]="icon()" aria-hidden="true" />
        </div>
        @switch (s.status) {
          @case ('ONLINE') {
            <span class="chip ok"><span class="dot"></span>Online</span>
          }
          @case ('OFFLINE') {
            <span class="chip down"><span class="dot"></span>Offline</span>
          }
          @case ('PLANNED') {
            <span class="chip planned">In Planung</span>
          }
          @case ('UNKNOWN') {
            <span class="chip unknown">Status unbekannt</span>
          }
        }
      </div>

      <div class="text">
        <h3>{{ s.name }}</h3>
        <span class="host hl-mono">{{ s.host }}</span>
        <p class="desc">{{ s.description }}</p>
      </div>

      <div class="foot">
        @if (s.status === 'PLANNED') {
          <button mat-flat-button type="button" disabled>Noch nicht verfügbar</button>
        } @else {
          <!-- the ::after of this link covers the whole card -->
          <a mat-flat-button class="open" [href]="s.url">
            Öffnen<span class="visually-hidden">: {{ s.name }}</span>
            <mat-icon iconPositionEnd svgIcon="open" />
          </a>
        }
      </div>
    </mat-card>
  `,
  styleUrl: './service-card.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ServiceCard {
  readonly service = input.required<PortalService>();

  protected readonly icon = computed(() => iconOrFallback(this.service().icon));
  protected readonly isLive = computed(() => this.service().status !== 'PLANNED');
}
