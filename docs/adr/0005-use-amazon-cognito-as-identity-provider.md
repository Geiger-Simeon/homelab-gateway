# 5. Use Amazon Cognito as identity provider

Date: 2026-11-08

## Status

Superseded by [6. Use Microsoft Entra ID via Azure for Students](0006-use-microsoft-entra-id-via-azure-for-students.md)

## Context

Signing in to the Azure portal kept failing with AADSTS50058 in Brave and Chrome, so no Entra tenant could be set up.

## Decision

Use an Amazon Cognito user pool as identity provider, self-registration disabled, MFA via TOTP.

## Consequences

Didn't work out before the setup was finished:

- new AWS accounts have to upgrade to the paid plan with a payment method, otherwise they get closed after the free period
- verifying a bank account (TrueLayer) and entering SEPA manually both failed
- I don't want to add a credit or debit card
