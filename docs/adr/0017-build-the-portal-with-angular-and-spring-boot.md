# 17. Build the portal with Angular and Spring Boot

Date: 2026-11-15

## Status

Accepted

## Context

A start page (portal) on the Pi lists the services a user may open, based on the Entra app roles, and shows a short system overview for me as admin. The first idea was a static HTML/JS page that asks oauth2-proxy for the user info and hides tiles.

What the page needs:

- tiles per role, filter (all / online), dark and light theme
- service status from health checks, which the browser can't do itself (other hostnames, internal endpoints)
- system values (updates, backup, tunnel) that only the admin may see; hiding them in the browser isn't enough, the server has to refuse them
- the same stack as the own apps that are planned later (recipes, calories), and a piece I can show in interviews

Options:

- static page plus `/oauth2/userinfo`: no build step, but no health checks, no server-side check for the admin data
- server-rendered pages (Spring Boot with Thymeleaf): one deployable, but a different UI stack than the later apps
- Angular SPA with a small Spring Boot API

## Decision

- frontend: Angular with Angular Material (Material 3, dark theme by default, light theme selectable), built on the PC to static files that nginx on the Pi serves
- backend: Spring Boot API under `/api`, only listening on `127.0.0.1:8081`, behind the same oauth2-proxy check as the page
- the API takes user and roles from headers nginx sets from the oauth2-proxy response, no own login code
- services are configured in `application.yml` (name, host, role, health URL); a user only gets the ones he has the role for
- system values come from a JSON file a root timer writes; the API only reads it and only returns it with the role `portal-admin`

## Consequences

- access to the services themselves is still enforced per hostname in nginx/oauth2-proxy, the portal only mirrors it
- the API trusts the headers blindly: it must stay on the loopback interface and nginx has to overwrite the headers on every request, otherwise a client could claim any role
- the portal needs no privileges, everything that needs root stays in the timer script
- the Pi runs a JVM (more RAM than a static page) and one more systemd service
- Node and the Angular build only run on the PC, not on the Pi
- the full monitoring with history and alerts stays a separate tool, the portal only shows a few values
- health checks over HTTPS need a certificate the JVM trusts; in the VM that's the test CA, after go-live Let's Encrypt
