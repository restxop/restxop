# Releasing

Releases are tag-driven. Pushing a `v*` tag runs
[`restxop-release.yml`](.github/workflows/restxop-release.yml), which
publishes the Java modules to Maven Central and `restxop-js` to npm in
parallel. Both jobs refuse to run if the tag does not match the version
declared in the tree, so the release is exactly what the tag points at.

## Prerequisites (already configured — verify, don't recreate)

- **Maven Central**: repository secrets `MAVEN_CENTRAL_USERNAME`,
  `MAVEN_CENTRAL_TOKEN` (Central Portal token), `GPG_PRIVATE_KEY`, and
  `GPG_PASSPHRASE`. Before tagging, check that the GPG key has not
  expired and the portal token is still valid — both fail only at
  publish time.
- **npm**: no token. Publishing uses npm Trusted Publishing (OIDC); the
  `restxop-js` package is bound to `restxop/restxop` +
  `restxop-release.yml` with no environment. If the workflow is renamed
  or gains an `environment:`, update the binding on npmjs.com to match.

## Cutting a release

1. **Set the versions** (both ecosystems move in lockstep):

   ```sh
   mvn -f restxop/pom.xml versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
   (cd restxop-js && npm version X.Y.Z --no-git-tag-version)
   ```

2. **Commit and tag**:

   ```sh
   git commit -am "chore: release X.Y.Z"
   git tag vX.Y.Z
   git push origin main vX.Y.Z
   ```

3. **Wait for the workflow.** The `maven` job verifies the tag against
   the reactor version, then runs `mvn -Prelease deploy`: sources and
   javadoc jars attached, artifacts GPG-signed, samples excluded
   (`skipPublishing` in `restxop-samples`). The `npm` job verifies the
   tag against `package.json`, runs the full test suite, and publishes
   with `--provenance`. A version already on the npm registry is
   skipped, not an error, so re-running the workflow is safe.

4. **Publish the Maven deployment.** The workflow uploads a validated
   deployment bundle to the [Central Portal](https://central.sonatype.com/publishing/deployments);
   it is **not** auto-published. Sign in and press **Publish**. Artifacts
   appear on `repo1.maven.org` within minutes; the search index at
   search.maven.org lags by hours and is not a health signal.

5. **Verify**:

   ```sh
   npm view restxop-js@X.Y.Z version
   mvn -q dependency:get -Dartifact=dev.restxop:restxop-core:X.Y.Z -Dtransitive=false
   ```

6. **Create the GitHub release** from the tag, with notes:

   ```sh
   gh release create vX.Y.Z --title "vX.Y.Z" --generate-notes
   ```

## After the release

Open the next development cycle on `main`:

```sh
mvn -f restxop/pom.xml versions:set -DnewVersion=X.Y+1.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "chore: open X.Y+1.0-SNAPSHOT"
git push
```

`restxop-js/package.json` stays at the released version until the next
release (npm convention — there is no snapshot equivalent).

## If something goes wrong

- **Tag/version mismatch**: the guard steps fail fast and nothing is
  published. Fix the versions on `main`, delete and re-push the tag.
- **Maven job failed after npm succeeded** (or vice versa): fix the
  cause and re-run the failed job from the Actions UI. The npm publish
  step is idempotent; a Central deployment that was never published can
  be dropped from the portal and re-uploaded by re-running the job.
- **A bad release reached the registries**: neither Maven Central nor
  npm supports overwriting a published version. Ship a fixed `X.Y.Z+1`;
  on npm, `npm deprecate` the bad version.
