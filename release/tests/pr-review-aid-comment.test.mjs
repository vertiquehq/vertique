// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Sticky comment reconciliation and the run.mjs wiring around it.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const { COMMENT_MARKER } = await import(pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'report.mjs')).href);
const { reconcile } = await import(pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'comment.mjs')).href);
const { parseRunArgs, run } = await import(pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'run.mjs')).href);

const REPO = 'vertiquehq/vertique';

function client(comments, fail) {
  const calls = [];
  const denied = () => Object.assign(new Error('Resource not accessible by integration'), { status: 1 });
  return {
    calls,
    listComments() {
      calls.push('list');
      if (fail === 'list') throw denied();
      return comments.map((comment) => ({ ...comment }));
    },
    createComment(args) {
      calls.push(['create', args.body]);
      if (fail === 'create') throw denied();
    },
    updateComment(args) {
      calls.push(['update', args.commentId, args.body]);
      if (fail === 'update') throw denied();
    },
    deleteComment(args) {
      calls.push(['delete', args.commentId]);
      if (fail === 'delete') throw denied();
    },
  };
}

describe('reconcile', () => {
  const body = `${COMMENT_MARKER}\n## Pull request review\n\nhello\n`;

  it('creates a comment when none exists', () => {
    const api = client([]);
    const result = reconcile({ repo: REPO, pr: 7, body, client: api });
    assert.equal(result.action, 'create');
    assert.deepEqual(api.calls, ['list', ['create', body]]);
  });

  it('updates the first marked comment when the body differs', () => {
    const api = client([
      { id: 1, body: 'unrelated' },
      { id: 2, body: `${COMMENT_MARKER}\nold\n` },
      { id: 3, body: `${COMMENT_MARKER}\nalso\n` },
    ]);
    const result = reconcile({ repo: REPO, pr: '7', body, client: api });
    assert.equal(result.action, 'update');
    assert.equal(result.commentId, 2);
    assert.deepEqual(api.calls[1], ['update', 2, body]);
  });

  it('does not write when the body is identical', () => {
    const api = client([{ id: 4, body }]);
    const result = reconcile({ repo: REPO, pr: 7, body, client: api });
    assert.equal(result.action, 'unchanged');
    assert.deepEqual(api.calls, ['list']);
  });

  it('deletes a marked comment when the body is null', () => {
    const api = client([{ id: 9, body: `${COMMENT_MARKER}\nstale\n` }]);
    const result = reconcile({ repo: REPO, pr: 7, body: null, client: api });
    assert.equal(result.action, 'delete');
    assert.deepEqual(api.calls, ['list', ['delete', 9]]);
  });

  it('does nothing when the body is null and no comment exists', () => {
    const api = client([{ id: 1, body: 'other' }]);
    const result = reconcile({ repo: REPO, pr: 7, body: null, client: api });
    assert.equal(result.action, 'noop');
    assert.deepEqual(api.calls, ['list']);
  });

  it('skips every write for a fork', () => {
    const api = client([]);
    const notices = [];
    const result = reconcile({
      repo: REPO,
      pr: 7,
      body,
      client: api,
      log: (line) => notices.push(line),
      event: {
        pull_request: {
          head: { repo: { full_name: 'someone/fork' } },
          base: { repo: { full_name: REPO } },
        },
      },
    });
    assert.equal(result.action, 'skip-fork');
    assert.deepEqual(api.calls, []);
    assert.match(notices.join('\n'), /::notice::/);
  });

  it('continues when the token is refused', () => {
    for (const fail of ['list', 'create']) {
      const api = client(fail === 'create' ? [] : [], fail);
      const notices = [];
      const result = reconcile({
        repo: REPO,
        pr: 7,
        body,
        client: api,
        log: (line) => notices.push(line),
      });
      assert.equal(result.action, 'skip-permission');
      assert.match(notices.join('\n'), /::notice::/);
      assert.match(notices.join('\n'), /Resource not accessible by integration/);
    }
  });
});

describe('run', () => {
  const header = `<?xml version="1.0" encoding="UTF-8"?>
<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.example</groupId>
  <artifactId>root</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
</project>
`;

  function checkout(dir) {
    mkdirSync(dir, { recursive: true });
    writeFileSync(path.join(dir, 'pom.xml'), header);
  }

  it('stays silent and deletes a stale comment when nothing changed', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-run-'));
    const base = path.join(dir, 'base');
    const head = path.join(dir, 'head');
    checkout(base);
    checkout(head);
    const summary = path.join(dir, 'summary.md');
    const output = path.join(dir, 'output.txt');
    const api = client([{ id: 3, body: `${COMMENT_MARKER}\nold\n` }]);
    const result = run({
      base,
      head,
      out: path.join(dir, 'out'),
      pr: 4,
      repo: REPO,
      client: api,
      summaryFile: summary,
      outputFile: output,
      baseSpecs: path.join(dir, 'missing-base'),
      headSpecs: path.join(dir, 'missing-head'),
      execFile() {
        throw new Error('oasdiff should not run');
      },
    });
    assert.equal(result.comment, null);
    assert.equal(result.breaking, false);
    assert.equal(readFileSync(summary, 'utf8'), 'No module graph or API changes\n');
    assert.equal(readFileSync(output, 'utf8'), 'breaking=false\n');
    assert.equal(readFileSync(path.join(dir, 'out', 'breaking'), 'utf8'), 'false\n');
    assert.deepEqual(api.calls, ['list', ['delete', 3]]);
    rmSync(dir, { recursive: true, force: true });
  });

  it('records a breaking change and still exits through the caller', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-break-'));
    const base = path.join(dir, 'base');
    const head = path.join(dir, 'head');
    checkout(base);
    checkout(head);
    mkdirSync(path.join(dir, 'b', 'sample'), { recursive: true });
    mkdirSync(path.join(dir, 'h', 'sample'), { recursive: true });
    writeFileSync(path.join(dir, 'b', 'sample', 'openapi.yaml'), 'base\n');
    writeFileSync(path.join(dir, 'h', 'sample', 'openapi.yaml'), 'head\n');
    const api = client([]);
    const result = run({
      base,
      head,
      baseSpecs: path.join(dir, 'b'),
      headSpecs: path.join(dir, 'h'),
      out: path.join(dir, 'out'),
      pr: 4,
      repo: REPO,
      client: api,
      execFile(_bin, args) {
        if (args[0] === 'breaking') {
          const error = new Error('err');
          error.status = 1;
          error.stdout = '1 error\n';
          error.stderr = '';
          throw error;
        }
        return '# API Changelog\n\n## API Changes\n\n- removed the path\n';
      },
    });
    assert.equal(result.breaking, true);
    assert.equal(readFileSync(path.join(dir, 'out', 'breaking'), 'utf8'), 'true\n');
    assert.equal(api.calls[0], 'list');
    assert.equal(api.calls[1][0], 'create');
    assert.match(readFileSync(path.join(dir, 'out', 'comment.md'), 'utf8'), /removed the path/);
    rmSync(dir, { recursive: true, force: true });
  });

  it('skips the API diff when the base build failed', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-base-fail-'));
    const base = path.join(dir, 'base');
    const head = path.join(dir, 'head');
    checkout(base);
    checkout(head);
    let called = false;
    const result = run({
      base,
      head,
      out: path.join(dir, 'out'),
      baseBuildFailed: true,
      execFile() {
        called = true;
        throw new Error('should not be called');
      },
    });
    assert.equal(called, false);
    assert.match(result.report, /base build failed, API diff skipped/);
    assert.equal(result.breaking, false);
    rmSync(dir, { recursive: true, force: true });
  });

  it('removes a stale comment without reading the trees when nothing relevant changed', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-skip-'));
    const api = client([{ id: 8, body: `${COMMENT_MARKER}\nstale\n` }]);
    const result = run({
      relevant: 'false',
      out: path.join(dir, 'out'),
      pr: 1,
      repo: REPO,
      client: api,
    });
    assert.equal(result.comment, null);
    assert.deepEqual(api.calls, ['list', ['delete', 8]]);
    rmSync(dir, { recursive: true, force: true });
  });

  it('does not call the client for a fork', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-fork-'));
    const base = path.join(dir, 'base');
    const head = path.join(dir, 'head');
    checkout(base);
    checkout(head);
    const api = client([]);
    run({
      base,
      head,
      out: path.join(dir, 'out'),
      pr: 1,
      repo: REPO,
      client: api,
      log() {},
      event: {
        pull_request: {
          head: { repo: { full_name: 'someone/fork' } },
          base: { repo: { full_name: REPO } },
        },
      },
    });
    assert.deepEqual(api.calls, []);
    rmSync(dir, { recursive: true, force: true });
  });

  it('parses arguments and environment values', () => {
    const parsed = parseRunArgs(['--dry-run', '--base-build-failed', '--pr', '12'], {
      BASE_DIR: '/base',
      HEAD_DIR: '/head',
      OUT_DIR: '/out',
      REPO: REPO,
    });
    assert.equal(parsed.dryRun, true);
    assert.equal(parsed.baseBuildFailed, true);
    assert.equal(parsed.pr, '12');
    assert.equal(parsed.base, '/base');
    assert.throws(() => parseRunArgs(['--nope']), /unexpected argument/);
  });
});
