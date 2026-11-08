# 6. Use Microsoft Entra ID via Azure for Students

Date: 2026-11-08

## Status

Accepted

Supersedes [5. Use Amazon Cognito as identity provider](0005-use-amazon-cognito-as-identity-provider.md)

## Context

The identity provider has to be free, work without a payment card, and support "only assigned users" plus MFA. Cognito failed on the payment method (see [5](0005-use-amazon-cognito-as-identity-provider.md)).

Azure for Students is verified with the university email and creates an own tenant without a card.

## Decision

Own Entra ID tenant via Azure for Students:

- app registration `nextcloud`, single tenant
- assignment required, only `simeon@` assigned
- separate non-admin user for the login, the Global Admin only manages the tenant
- security defaults enabled

Fallback if this stops working: Auth0 (no card needed).

## Consequences

- Entra ID Free: no group-based assignment, no Conditional Access
- Global Admins bypass "assignment required" by design, has to be blocked in Nextcloud (phase 5)
- after graduation the subscription ends; that the tenant and app registration stay is only backed by a Microsoft Q&A answer, not the official docs
- the client secret expires and has to be renewed
