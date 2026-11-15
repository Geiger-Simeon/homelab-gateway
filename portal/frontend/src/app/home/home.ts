import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatChipListboxChange, MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';

import { Me, PortalApi, PortalService, SystemStatus } from '../core/portal-api';
import { ServiceCard } from './service-card';
import { SystemPanel } from './system-panel';

type Filter = 'all' | 'online';

@Component({
  selector: 'hl-home',
  imports: [MatChipsModule, MatButtonModule, MatIconModule, ServiceCard, SystemPanel],
  templateUrl: './home.html',
  styleUrl: './home.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Home implements OnInit {
  private readonly api = inject(PortalApi);

  readonly me = input.required<Me>();

  protected readonly services = signal<PortalService[] | null>(null);
  protected readonly servicesFailed = signal(false);
  protected readonly system = signal<SystemStatus | null>(null);
  protected readonly systemFailed = signal(false);
  protected readonly filter = signal<Filter>('all');

  protected readonly firstName = computed(() => this.me().name.split(' ')[0]);
  protected readonly onlineCount = computed(
    () => this.services()?.filter((s) => s.status === 'ONLINE').length ?? 0,
  );
  protected readonly shown = computed(() => {
    const all = this.services() ?? [];
    return this.filter() === 'online' ? all.filter((s) => s.status === 'ONLINE') : all;
  });

  ngOnInit(): void {
    this.loadServices();
    // the backend refuses this for non-admins anyway, this only avoids a pointless 403
    if (this.me().admin) {
      this.api.system().subscribe({
        next: (s) => this.system.set(s),
        error: () => this.systemFailed.set(true),
      });
    }
  }

  protected loadServices(): void {
    this.servicesFailed.set(false);
    this.api.services().subscribe({
      next: (s) => this.services.set(s),
      error: () => this.servicesFailed.set(true),
    });
  }

  protected onFilter(event: MatChipListboxChange): void {
    // clicking the selected chip again deselects it, keep one filter active
    if (event.value === 'all' || event.value === 'online') {
      this.filter.set(event.value);
    } else {
      event.source.value = this.filter();
    }
  }
}
