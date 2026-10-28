// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const script = resolve('scripts/ci-build.sh');
function run(event, token) {
  const dir = mkdtempSync(join(tmpdir(), 'ci-build-'));
  try {
    writeFileSync(join(dir, 'mvnw'), '#!/usr/bin/env bash\nprintf "%s\\n" "$@"\n', { mode: 0o755 });
    return spawnSync('bash', [script], {
      cwd: dir, encoding: 'utf8', env: { ...process.env, EVENT_NAME: event, SONAR_TOKEN: token },
    });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test('pull requests run verification without analysis', () => {
  const result = run('pull_request', '');
  assert.equal(result.status, 0);
  assert.match(result.stdout, /clean\nverify\n/);
  assert.doesNotMatch(result.stdout, /sonar/);
});

test('main pushes analyze in the same invocation after verification', () => {
  const result = run('push', 'test-token');
  assert.equal(result.status, 0);
  assert.match(result.stdout, /clean\nverify\norg\.sonarsource\.scanner\.maven:sonar-maven-plugin:5\.5\.0\.6356:sonar\n/);
  assert.match(result.stdout, /-Dsonar\.projectKey=vertiquehq_vertique/);
  assert.match(result.stdout, /-Dsonar\.organization=vertiquehq/);
  assert.match(result.stdout, /-Dsonar\.coverage\.jacoco\.xmlReportPaths=.*\/vertique-coverage-report\/target\/site\/jacoco-aggregate\/jacoco\.xml/);
  assert.doesNotMatch(result.stdout, /test-token/);
});

test('main pushes fail when the token is missing', () => {
  const result = run('push', '');
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /SONAR_TOKEN is required/);
});
