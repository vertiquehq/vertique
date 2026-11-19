// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';

const script = resolve('scripts/check-formatting.sh');

/** A repository whose origin/main holds a root POM, with a fake mvnw that echoes its arguments. */
function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'check-formatting-'));
  const git = (...args) => execFileSync('git', args, { cwd: dir, encoding: 'utf8' });
  git('init', '-q', '-b', 'main');
  git('config', 'user.email', 'fixture@example.invalid');
  git('config', 'user.name', 'Fixture');
  writeFileSync(join(dir, 'pom.xml'), '<project/>\n');
  writeFileSync(join(dir, 'mvnw'), '#!/usr/bin/env bash\nprintf "%s\\n" "$@"\n', { mode: 0o755 });
  git('add', '.');
  git('commit', '-q', '-m', 'base');
  git('update-ref', 'refs/remotes/origin/main', 'HEAD');
  return { dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

function run(dir, base) {
  const env = { ...process.env };
  delete env.FORMAT_BASE_REF;
  if (base !== undefined) env.FORMAT_BASE_REF = base;
  return spawnSync('bash', [script], { cwd: dir, encoding: 'utf8', env });
}

test('a push checks every file', () => {
  const { dir, cleanup } = fixture();
  try {
    const result = run(dir);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /^spotless:check$/m);
    assert.doesNotMatch(result.stdout, /ratchetFrom/);
  } finally {
    cleanup();
  }
});

test('a pull request checks only the files it changes against its base', () => {
  const { dir, cleanup } = fixture();
  try {
    const result = run(dir, 'main');
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /^spotless:check$/m);
    assert.match(result.stdout, /^-DratchetFrom=origin\/main$/m);
  } finally {
    cleanup();
  }
});

test('a pull request that changes the root POM checks every file', () => {
  const { dir, cleanup } = fixture();
  try {
    writeFileSync(join(dir, 'pom.xml'), '<project><!-- formatter bump --></project>\n');
    const result = run(dir, 'main');
    assert.equal(result.status, 0, result.stderr);
    assert.doesNotMatch(result.stdout, /ratchetFrom/);
    assert.match(result.stderr, /root POM changed/);
  } finally {
    cleanup();
  }
});

test('a pull request whose base branch was not fetched fails instead of checking nothing', () => {
  const { dir, cleanup } = fixture();
  try {
    const result = run(dir, 'feature/missing');
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /origin\/feature\/missing is not available/);
    assert.doesNotMatch(result.stdout, /spotless/);
  } finally {
    cleanup();
  }
});
