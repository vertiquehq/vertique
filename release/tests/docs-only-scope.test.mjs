// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Docs-only CI scope contract.
 *
 * Proves that scripts/detect-docs-only-scope.sh skips the Build and Test job
 * only for pull requests whose every changed path is on the minimal
 * documentation allowlist, and keeps the full build for everything else —
 * including the contract-gated Markdown (README.md, docs/**, canonical
 * module.md resources) that the reactor's documentation integration tests
 * assert on, mixed changes, non-pull-request events, empty diffs, and
 * malformed inputs. Runs against synthetic git repositories so the contract
 * is exercised end to end through the same `git diff` the workflow uses.
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

  it('skips the build for a pull request touching only allowlisted prose', () => {
    const head = branchWith(
      {
        'CONTRIBUTING.md': 'contributing\n',
        'SECURITY.md': 'security\n',
        'LICENSES/EUPL-1.2.txt': 'license\n',
        'NOTICE': 'notice\n',
      },
      'docs-only',
    );
    const result = detectPr(head);
    assert.equal(result.status, 0);
    assert.equal(result.stdout.trim(), 'docs_only=true');
  });

  it('keeps the full build for contract-gated Markdown the reactor tests assert on', () => {
    for (const [file, name] of [
      ['README.md', 'readme'],
      ['docs/architecture.md', 'architecture'],
      ['docs/modules.md', 'modules-index'],
      ['docs/packaging.md', 'packaging'],
      ['vertique-core/src/main/resources/META-INF/vertique/module.md', 'module-doc'],
    ]) {
      const head = branchWith({ [file]: 'changed\n' }, `gated-${name}`);
      const result = detectPr(head);
      assert.equal(result.status, 0);
      assert.equal(result.stdout.trim(), 'docs_only=false', `${file} is contract-gated and must build`);
    }
  });

  it('keeps the full build when allowlisted prose and code change together', () => {
    const head = branchWith(
      { 'CONTRIBUTING.md': 'guide\n', 'vertique-core/src/main/java/A.java': 'class A {}\n' },
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

  it('keeps the full build for allowlist-anchor lookalikes', () => {
    for (const [file, name] of [
      ['x/NOTICE', 'nested-notice'],
      ['NOTICE.java', 'notice-suffixed'],
      ['foo/LICENSES/x.txt', 'nested-licenses'],
      ['foo/CONTRIBUTING.md', 'nested-contributing'],
      ['CONTRIBUTING.mdx', 'mdx'],
    ]) {
      const head = branchWith({ [file]: 'changed\n' }, `lookalike-${name}`);
      const result = detectPr(head);
      assert.equal(result.status, 0);
      assert.equal(result.stdout.trim(), 'docs_only=false', `${file} must keep the build`);
    }
  });

  it('detects the non-docs side of a rename away from documentation', () => {
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
    const missingEvent = detect(repo, []);
    assert.notEqual(missingEvent.status, 0);
    assert.match(missingEvent.stderr, /--event-name is required/);

    const badBase = detect(repo, ['--event-name', 'pull_request', '--base-sha', 'main', '--head-sha', baseSha]);
    assert.notEqual(badBase.status, 0);
    assert.match(badBase.stderr, /--base-sha must be a lowercase full SHA/);

    const badHead = detect(repo, ['--event-name', 'pull_request', '--base-sha', baseSha, '--head-sha', 'HEAD']);
    assert.notEqual(badHead.status, 0);
    assert.match(badHead.stderr, /--head-sha must be a lowercase full SHA/);
  });
});
