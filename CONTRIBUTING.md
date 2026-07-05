# Contributing to restxop

First off, thank you for considering contributing to restxop!

## Code of Conduct

This project and everyone participating in it is governed by the
[restxop Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are
expected to uphold this code.

## How the project is organized

| Directory | What |
|---|---|
| `restxop/` | Java library (Maven reactor) |
| `restxop-js/` | JavaScript/TypeScript client (npm) |
| `specs/` | Feature specifications, contracts, and decision records |
| `.specify/memory/constitution.md` | The project constitution — the governing engineering principles |

restxop is developed **spec-first**. The wire format
([specs/001-rest-attachment-streaming/contracts/wire-format.md](specs/001-rest-attachment-streaming/contracts/wire-format.md))
is normative: changes to wire behavior require a spec amendment before any
implementation change, and both implementations must stay byte-exact
against the shared fixture corpus in `restxop/restxop-testkit`.

## How can I contribute?

### Reporting bugs

Bugs are tracked as [GitHub issues](https://github.com/restxop/restxop/issues).
Please include:

- A clear and descriptive title.
- Exact reproduction steps — for parsing issues, a wire-level reproduction
  (the raw `multipart/related` bytes, or a testkit-style `.http` fixture)
  is worth a thousand words.
- Expected vs. actual behavior, and the version affected.

Security vulnerabilities go through [SECURITY.md](SECURITY.md), not public
issues.

### Suggesting enhancements

Open a GitHub issue. For anything touching wire behavior, expect the
discussion to happen at the spec level first.

### Pull requests

- Add tests — test-first is a constitutional requirement (principle VI);
  we won't accept changes without coverage, and wire-behavior changes need
  fixture-driven conformance tests.
- Ensure the build passes locally before submitting (see below).
- Keep the sanitized-repo hygiene: `restxop/build-config/check-hygiene.sh`
  must pass (it runs in CI and gates merges).
- CI must be green: JDK 17 + 25 verify, restxop-js Node + Chromium, and
  the SonarCloud quality gate on new code.

## Technical setup

### Prerequisites

- JDK 17 and Maven 3.9+ (the Java library targets 17; CI also verifies on 25)
- Node.js ≥ 20 and npm (for `restxop-js`)

### Build and test

```bash
# Java reactor
cd restxop
mvn verify

# JS client
cd restxop-js
npm ci
npm test              # build + Node suite + bundle-size gate
npm run test:browser  # the same suite in Chromium (needs: npx playwright install chromium)
```

### Git commit messages

- Conventional-commit style (`feat:`, `fix:`, `test:`, `chore:`, …)
- Use the imperative mood; keep the first line ≤ 72 characters

## License

By contributing, you agree that your contributions will be licensed under
the [Apache License 2.0](LICENSE).
