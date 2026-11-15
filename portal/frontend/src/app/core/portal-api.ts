import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Me {
  name: string;
  email: string;
  admin: boolean;
}

export type ServiceStatus = 'ONLINE' | 'OFFLINE' | 'UNKNOWN' | 'PLANNED';
export type ServiceColor = 'blue' | 'purple' | 'orange' | 'teal';

export interface PortalService {
  id: string;
  name: string;
  description: string;
  host: string;
  url: string;
  icon: string;
  color: ServiceColor;
  status: ServiceStatus;
}

export type CheckState = 'OK' | 'WARN' | 'FAIL' | 'INFO';

export interface SystemCheck {
  id: string;
  label: string;
  value: string;
  state: CheckState;
}

export interface SystemStatus {
  generatedAt: string;
  // set by the backend when the timer hasn't written the file for a while
  stale: boolean;
  checks: SystemCheck[];
}

@Injectable({ providedIn: 'root' })
export class PortalApi {
  private readonly http = inject(HttpClient);

  me(): Observable<Me> {
    return this.http.get<Me>('/api/me');
  }

  services(): Observable<PortalService[]> {
    return this.http.get<PortalService[]>('/api/services');
  }

  system(): Observable<SystemStatus> {
    return this.http.get<SystemStatus>('/api/system');
  }
}
