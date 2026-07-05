# Security Policy

## Supported Versions

We provide security updates for the following versions:

| Version | Supported          |
| ------- | ------------------ |
| 0.1.x   | :white_check_mark: |

This covers both deliverables: the `dev.restxop` Maven artifacts and the
`restxop-js` npm package (versioned together).

## Reporting a Vulnerability

We take the security of this software seriously. If you believe you have
found a security vulnerability, please do NOT open a public GitHub issue.
This would make the vulnerability public and potentially put users at risk.

Instead, please report the vulnerability privately by emailing
**admin@restxop.dev**, or via GitHub's private
[security advisory reporting](https://github.com/restxop/restxop/security/advisories/new)
for this repository.

When reporting a vulnerability, please include:

- A description of the issue.
- The affected component (`restxop-*` Maven artifact or `restxop-js`) and version.
- Steps to reproduce the issue — a wire-level reproduction (a captured or
  constructed `multipart/related` message) is ideal.
- Any potential impact.

We will acknowledge receipt of your report within 48 hours and work to
provide a fix as soon as possible.

## Scope notes

restxop parses attacker-controllable wire input by design. Parsing is
bounded by configurable limits (part count, header block size, root part
size, timeouts) with documented defaults; reports of resource-exhaustion
paths that escape those bounds are very much in scope.
