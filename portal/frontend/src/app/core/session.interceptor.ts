import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { InjectionToken, inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';

const RELOAD_KEY = 'hl-401-reload';
const RELOAD_GUARD_MS = 30_000;

// own token so tests don't have to touch window.location
export const PAGE_RELOAD = new InjectionToken<() => void>('PAGE_RELOAD', {
  factory: () => () => window.location.reload(),
});

// oauth2-proxy answers API calls with 401 once the session cookie is gone.
// A full reload goes through the proxy again and ends up at the Microsoft login.
export const sessionInterceptor: HttpInterceptorFn = (req, next) => {
  const reload = inject(PAGE_RELOAD);
  return next(req).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && mayReload()) {
        reload();
      }
      return throwError(() => err);
    }),
  );
};

// A second 401 right after a reload means the API didn't get a valid token (nginx/proxy config
// or an expired token from oauth2-proxy), not an expired session. Reloading again would loop
// forever, so the error page shows instead.
function mayReload(): boolean {
  try {
    const last = Number(sessionStorage.getItem(RELOAD_KEY));
    if (Date.now() - last < RELOAD_GUARD_MS) {
      return false;
    }
    sessionStorage.setItem(RELOAD_KEY, String(Date.now()));
    return true;
  } catch {
    // no storage, rather no reload than a loop
    return false;
  }
}
