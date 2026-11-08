# 11. Forward only known hostnames on the VPS

Date: 2026-11-08

## Status

Accepted

## Context

Unauthorized traffic should be stopped on the VPS where possible, because the Pi sits in the shared home LAN. With TLS passthrough the VPS can't check logins (ADR 0003), but it can read the SNI from the ClientHello without decrypting anything.

A large part of the traffic on a public IP comes from scanners that connect to the IP directly, without a hostname, or with arbitrary names.

## Decision

nginx on the VPS uses `ssl_preread` and a `map` on `$ssl_preread_server_name`. Only configured hostnames are forwarded to `10.99.0.2:443`. Everything else, including connections without SNI, goes to `127.0.0.1:1`, where nothing listens, so nginx closes the connection.

## Consequences

- IP scanners and wrong hostnames never reach the Pi
- the SNI is not secret, anyone who knows the domain gets through; this reduces noise, it's no access control
- certificate transparency logs make the domain public as soon as a certificate is issued
- every rejected connection writes a `connect() failed (111)` line to the nginx error log on the VPS
- a new hostname needs an entry in the map on the VPS
