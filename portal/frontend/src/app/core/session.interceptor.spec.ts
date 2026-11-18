import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { PAGE_RELOAD, sessionInterceptor } from './session.interceptor';

describe('sessionInterceptor', () => {
  let reload: ReturnType<typeof vi.fn>;
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    reload = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([sessionInterceptor])),
        provideHttpClientTesting(),
        { provide: PAGE_RELOAD, useValue: reload },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  function failWith(status: number): void {
    http.get('/api/me').subscribe({ error: () => undefined });
    backend.expectOne('/api/me').flush(null, { status, statusText: 'x' });
  }

  it('given no earlier reload, when a request fails with 401, then the page reloads once', () => {
    // given: no reload happened in this session yet

    // when
    failWith(401);

    // then
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('given a reload just happened, when another 401 follows, then it does not reload again', () => {
    // given
    failWith(401);

    // when
    failWith(401);

    // then
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('given an expired reload guard, when a request fails with 401, then the page reloads', () => {
    // given
    sessionStorage.setItem('hl-401-reload', String(Date.now() - 60_000));

    // when
    failWith(401);

    // then
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it.each([500, 403])('given a fresh session, when a request fails with %i, then nothing reloads', (status) => {
    // given: fresh session

    // when
    failWith(status);

    // then
    expect(reload).not.toHaveBeenCalled();
  });
});
