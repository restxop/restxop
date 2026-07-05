#!/usr/bin/env bash
#
# Copyright 2026 the restxop contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SC-008 open-source hygiene check. Runs as an ALLOWLIST so the published
# repository never has to contain any non-neutral identifier, even inside
# this check: every package/import root and every Maven groupId appearing
# under restxop/ must come from the known-neutral set below. A maintainer-
# private denylist (kept OUTSIDE this repository) complements this scan.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0

ALLOWED_JAVA_ROOTS='dev\.restxop|demo|java|javax\.annotation|jakarta|org\.springframework|com\.fasterxml\.jackson|tools\.jackson|org\.slf4j|feign|org\.junit|org\.opentest4j|org\.apache\.(commons|hc|http)|com\.sun\.net\.httpserver|org\.xml|org\.w3c'

echo "== hygiene: package/import roots"
HITS=$(grep -RhoE '^(package|import( static)?) +[a-zA-Z_][a-zA-Z0-9_.]*' \
        --include='*.java' --exclude-dir=target "$ROOT" \
      | sed -E 's/^(package|import( static)?) +//' \
      | grep -vE "^(${ALLOWED_JAVA_ROOTS})(\.|$)" || true)
if [ -n "$HITS" ]; then
  echo "non-allowlisted java roots found:"
  echo "$HITS" | sort -u
  FAIL=1
fi

echo "== hygiene: Maven coordinates"
ALLOWED_GROUPS='dev\.restxop|org\.junit\.jupiter|org\.springframework|org\.springframework\.boot|org\.springframework\.cloud|com\.fasterxml\.jackson.*|tools\.jackson.*|org\.slf4j|org\.junit|jakarta\.activation|org\.jacoco|com\.github\.spotbugs|com\.mycila|org\.apache\.maven\.plugins'
GHITS=$(grep -RhoE '<groupId>[^<]+</groupId>' --include='pom.xml' --exclude-dir=target "$ROOT" \
      | sed -E 's#</?groupId>##g' \
      | grep -vE "^(${ALLOWED_GROUPS})$" || true)
if [ -n "$GHITS" ]; then
  echo "non-allowlisted maven groupIds found:"
  echo "$GHITS" | sort -u
  FAIL=1
fi

echo "== hygiene: no absolute non-neutral hosts in sources/docs"
HHITS=$(grep -RhoE 'https?://[a-zA-Z0-9.-]+' \
        --include='*.java' --include='*.xml' --include='*.md' --include='*.properties' \
        --exclude-dir=target "$ROOT" \
      | sed -E 's#https?://##; s#/.*##' \
      | grep -vE '^(www\.apache\.org|maven\.apache\.org|cwiki\.apache\.org|restxop\.dev|localhost|127\.0\.0\.1|host|javadoc\.io|repo\.maven\.apache\.org|github\.com|search\.maven\.org|www\.w3\.org)$' || true)
if [ -n "$HHITS" ]; then
  echo "non-allowlisted hosts found:"
  echo "$HHITS" | sort -u
  FAIL=1
fi

echo "== hygiene: Apache-2.0 headers on all Java sources"
MISSING=$(grep -RL "Licensed under the Apache License, Version 2.0" \
        --include='*.java' "$ROOT/"*/src 2>/dev/null || true)
if [ -n "$MISSING" ]; then
  echo "java sources missing the license header:"
  echo "$MISSING"
  FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then
  echo "HYGIENE CHECK FAILED (SC-008)"
  exit 1
fi
echo "hygiene check passed (SC-008)"
