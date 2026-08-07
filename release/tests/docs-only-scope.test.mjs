// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Docs-only CI scope contract.
 *
 * Proves that scripts/detect-docs-only-scope.sh skips the Build and Test job
 * only for pull requests whose every changed path is on the documentation
 * allowlist, and keeps the full build for everything else — mixed changes,
 * non-pull-request events, empty diffs, and malformed inputs. Runs against
 * synthetic git repositories so the contract is exercised end to end through
 * the same `git diff` the workflow uses.
 */

import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const SCRIPT = path.resolve(TEST_DIR, '..', '..', 'scripts', 'detect-docs-only-scope.sh');

/** Runs the script in `cwd`, returning { status, stdout, stderr }. */
function detect(cwd, args) {
  try {
    const stdout = execFileSync('bash', [SCRIPT, ...args], { cwd, encoding: 'utf8' });
    return { status: 0, stdout, stderr: '' };
  } catch (error) {
    return { status: error.status, stdout: error.stdout ?? '', stderr: error.stderr ?? '' };
  }
}

function git(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
}

/** Creates a git repo with a base commit and returns its path + base SHA. */
function makeRepo() {
  const repo = mkdtempSync(path.join(tmpdir(), 'docs-only-scope-'));
  git(repo, 'init', '--quiet', '--initial-branch=main');
  git(repo, 'config', 'user.email', 'test@invalid');
  git(repo, 'config', 'user.name', 'test');
  git(repo, 'config', 'commit.gpgsign', 'false');
  writeFileSync(path.join(repo, 'README.md'), 'base\n');
  git(repo, 'add', '.');
  git(repo, 'commit', '--quiet', '-m', 'base');
  return { repo, baseSha: git(repo, 'rev-parse', 'HEAD') };
}

/** Commits the given files (path → content) and returns the new HEAD SHA. */
function commit(repo, files, message = 'change') {
  for (const [file, content] of Object.entries(files)) {
    const target = path.join(repo, file);
    mkdirSync(path.dirname(target), { recursive: true });
    writeFileSync(target, content);
  }
  git(repo, 'add', '.');
  git(repo, 'commit', '--quiet', '-m', message);
  return git(repo, 'rev-parse', 'HEAD');
}

describe('detect-docs-only-scope', () => {
  let repo;
  let baseSha;

  before(() => {
    ({ repo, baseSha } = makeRepo());
  });

  after(() => {
    rmSync(repo, { recursive: true, force: true });
  });

  function detectPr(headSha) {
    return detect(repo, ['--event-name', 'pull_request', '--base-sha', baseSha, '--head-sha', headSha]);
  }

  /** Returns HEAD of a throwaway branch containing exactly `files` on top of base. */
  function branchWith(files, name) {
    git(repo, 'checkout', '--quiet', '-b', name, baseSha);
    return commit(repo, files, name);
  }

  it('skips the build for a pull request touching only allowlisted documentation', () => {
    const head = branchWith(
      {
        'docs/adr/0001-example.md': 'adr\n',
        'README.md': 'updated\n',
        'LICENSES/EUPL-1.2.txt': 'license\n',
        'NOTICE': 'notice\n',
        'vertique-core/src/main/resources/META-INF/vertique/module.md': 'module doc\n',
      },
      'docs-only',
    );
    const result = detectPr(head);
    assert.equal(result.status, 0);
    assert.equal(result.stdout.trim(), 'docs_only=true');
  });

  it('keeps the full build when documentation and code change together', () => {
    const head = branchWith(
      { 'docs/guide.md': 'guide\n', 'vertique-core/src/main/java/A.java': 'class A {}\n' },
      'mixed',
    );
    const result = detectPr(head);
    assert.equal(result.status, 0);
    assert.equal(result.stdout.trim(), 'docs_only=false');
  });

  it('keeps the full build for workflow, script, and pom changes', () => {
    for (const [file, name] of [
      ['.github/workflows/ci.yml', 'workflow'],
      ['scripts/detect-docs-only-scope.sh', 'script'],
      ['pom.xml', 'pom'],
      ['.mvn/maven.config', 'maven-config'],
    ]) {
      const head = branchWith({ [file]: 'changed\n' }, `non-docs-${name}`);
      const result = detectPr(head);
      assert.equal(result.status, 0);
      assert.equal(result.stdout.trim(), 'docs_only=false', `${file} must keep the build`);
    }
  });

  it('detects the docs-only side of a rename away from documentation', () => {
    git(repo, 'checkout', '--quiet', '-b', 'rename', baseSha);
    git(repo, 'mv', 'README.md', 'run.sh');
    const head = commit(repo, {}, 'rename');
    const result = detectPr(head);
    assert.equal(result.status, 0);
    assert.equal(result.stdout.trim(), 'docs_only=false');
  });

  it('never skips the build for non-pull-request events', () => {
    const result = detect(repo, ['--event-name', 'push']);
    assert.equal(result.status, 0);
    assert.equal(result.stdout.trim(), 'docs_only=false');
  });

  it('never skips the build on an empty diff', () => {
    const result = detectPr(baseSha);
    assert.equal(result.status, 0);
    assert.equal(result.stdout.trim(), 'docs_only=false');
  });

  it('rejects malformed inputs instead of guessing', () => {
    assert.notEqual(detect(repo, []).status, 0, 'missing event name must fail');
    assert.notEqual(
      detect(repo, ['--event-name', 'pull_request', '--base-sha', 'main', '--head-sha', baseSha]).status,
      0,
      'non-SHA base must fail',
    );
    assert.notEqual(
      detect(repo, ['--event-name', 'pull_request', '--base-sha', baseSha, '--head-sha', 'HEAD']).status,
      0,
      'non-SHA head must fail',
    );
  });
});
