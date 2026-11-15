import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatIconRegistry } from '@angular/material/icon';
import { DomSanitizer } from '@angular/platform-browser';

import { registerIcons } from '../core/icons';
import { Me, PortalService, SystemStatus } from '../core/portal-api';
import { Home } from './home';

const SERVICES: PortalService[] = [
  {
    id: 'nextcloud', name: 'Nextcloud', description: 'Dateien', host: 'cloud.homelab.test',
    url: 'https://cloud.homelab.test', icon: 'cloud', color: 'blue', status: 'ONLINE',
  },
  {
    id: 'jellyfin', name: 'Jellyfin', description: 'Filme', host: 'media.homelab.test',
    url: 'https://media.homelab.test', icon: 'play', color: 'purple', status: 'PLANNED',
  },
  {
    id: 'rezepte', name: 'Rezeptbuch', description: 'Rezepte', host: 'rezepte.homelab.test',
    url: 'https://rezepte.homelab.test', icon: 'book', color: 'orange', status: 'UNKNOWN',
  },
];

const SYSTEM: SystemStatus = {
  generatedAt: '2026-11-15T12:00:00Z',
  stale: false,
  checks: [{ id: 'tunnel', label: 'WireGuard-Tunnel', value: 'Verbunden', state: 'OK' }],
};

const USER: Me = { name: 'Max Mustermann', email: 's@x', admin: false };
const ADMIN: Me = { name: 'Max', email: 's@x', admin: true };

describe('Home', () => {
  let fixture: ComponentFixture<Home>;
  let http: HttpTestingController;

  async function render(me: Me): Promise<HTMLElement> {
    fixture = TestBed.createComponent(Home);
    fixture.componentRef.setInput('me', me);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function cardNames(el: HTMLElement): (string | undefined)[] {
    return [...el.querySelectorAll('h3')].map((h) => h.textContent?.trim());
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Home],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    registerIcons(TestBed.inject(MatIconRegistry), TestBed.inject(DomSanitizer));
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('given a user, when the page loads, then it greets by first name and lists the services', async () => {
    // given
    const me = USER;

    // when
    const el = await render(me);
    http.expectOne('/api/services').flush(SERVICES);
    await settle();

    // then
    expect(el.querySelector('h1')?.textContent).toContain('Hallo Max');
    expect(cardNames(el)).toEqual(['Nextcloud', 'Jellyfin', 'Rezeptbuch']);
  });

  it('given a normal user, when the page loads, then no system status is requested', async () => {
    // given
    const me = { ...USER, name: 'Gast' };

    // when
    const el = await render(me);
    http.expectOne('/api/services').flush(SERVICES);
    await settle();

    // then
    http.expectNone('/api/system');
    expect(el.querySelector('hl-system-panel')).toBeNull();
  });

  it('given an admin, when the page loads, then the system panel is shown', async () => {
    // given
    const status = SYSTEM;

    // when
    const el = await render(ADMIN);
    http.expectOne('/api/services').flush(SERVICES);
    http.expectOne('/api/system').flush(status);
    await settle();

    // then
    const panel = el.querySelector('hl-system-panel')?.textContent;
    expect(panel).toContain('Verbunden');
    expect(panel).toContain('Stand');
    expect(panel).not.toContain('Veraltet');
  });

  it('given a stale system status, when the page loads, then it is marked as outdated', async () => {
    // given
    const status = { ...SYSTEM, stale: true };

    // when
    const el = await render(ADMIN);
    http.expectOne('/api/services').flush(SERVICES);
    http.expectOne('/api/system').flush(status);
    await settle();

    // then
    expect(el.querySelector('hl-system-panel')?.textContent).toContain('Veraltet');
  });

  it('given no status file, when the page loads, then the admin sees a hint', async () => {
    // given
    const notFound = { status: 404, statusText: 'Not Found' };

    // when
    const el = await render(ADMIN);
    http.expectOne('/api/services').flush(SERVICES);
    http.expectOne('/api/system').flush(null, notFound);
    await settle();

    // then
    expect(el.querySelector('hl-system-panel')).toBeNull();
    expect(el.textContent).toContain('Systemstatus nicht verfügbar');
  });

  it('given a service with unknown status, when the page loads, then it is labelled', async () => {
    // given
    const services = SERVICES;

    // when
    const el = await render(USER);
    http.expectOne('/api/services').flush(services);
    await settle();

    // then
    const cards = [...el.querySelectorAll('hl-service-card')];
    expect(cards[2].textContent).toContain('Status unbekannt');
    expect(cards[0].textContent).not.toContain('Status unbekannt');
  });

  it('given loaded services, when filtering by online, then only online services remain', async () => {
    // given
    const el = await render(USER);
    http.expectOne('/api/services').flush(SERVICES);
    await settle();
    const online = [...el.querySelectorAll('mat-chip-option')].find((c) =>
      c.textContent?.includes('Online'),
    ) as HTMLElement;

    // when
    (online.querySelector('button, [role="option"], .mdc-evolution-chip__action') as HTMLElement ?? online).click();
    await settle();

    // then
    expect(online.textContent).toContain('Online (1)');
    expect(el.textContent).toContain('Alle (3)');
    expect(cardNames(el)).toEqual(['Nextcloud']);
  });

  it('given no assigned services, when the page loads, then a hint is shown', async () => {
    // given
    const me = { ...USER, name: 'Neu' };

    // when
    const el = await render(me);
    http.expectOne('/api/services').flush([]);
    await settle();

    // then
    expect(el.textContent).toContain('Noch keine Dienste freigegeben');
    expect(el.querySelector('mat-chip-listbox')).toBeNull();
  });
});
