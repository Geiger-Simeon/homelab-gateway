# 22. Run checks in GitHub Actions without deploy access

Date: 2026-11-26

## Status

Accepted

## Context

The portal (Spring Boot, Angular) and the setup scripts change often, and their dependencies get security fixes that need a rebuild (e.g. the Spring advisories in November 2026). Builds and tests only ran on the PC before a deploy; nothing checked a branch or noticed a new vulnerability.

- GitHub Actions on GitHub-hosted runners: the repo is public on GitHub, minutes and the security features are free
- a self-hosted runner on the Pi: code from pull requests of a public repo would run inside the home network
- another CI (GitLab CI, Jenkins): one more system to run and secure
- deploying from CI would put SSH keys to the VPS and the Pi into GitHub secrets
- CI tools are targets themselves: in March 2026 most tags of `aquasecurity/trivy-action` were moved to malicious commits

## Decision

- GitHub Actions on GitHub-hosted runners, on every pull request and on `main`: backend `mvn verify`, frontend tests and build, shellcheck, PSScriptAnalyzer, gitleaks over the whole history, dependency review
- CodeQL for Java, TypeScript and the workflow files, also weekly
- Dependabot for Maven, npm and Actions, weekly with a 7 day cooldown, minor and patch grouped; Angular majors by hand with `ng update`; security updates right away
- a weekly job compares the pinned oauth2-proxy with the latest release
- actions pinned to commit SHAs, workflow token read-only, no credentials left in the checkout, gitleaks as a checksummed binary instead of an action
- no deployment from CI: GitHub gets no access to the hosts, deploying stays `deploy.ps1` from the PC

## Consequences

- every pull request shows whether build, tests and checks pass; vulnerable dependencies show up as Dependabot alerts and pull requests
- Dependabot pull requests are merged and deployed by hand for now
- the CI doesn't test nginx, nftables or the running hosts; that still happens on the test VMs
- what the tests don't cover can still break with an update
