// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * The SNAPSHOT gates must never exit without having run.
 *
 * `import.meta.url` is symlink-resolved while `process.argv[1]` is the path as
 * invoked. A gate script reached through a symlink (a linked checkout, or a
 * macOS temp directory under /var -> /private/var) used to fail that
 * comparison, so its CLI body never ran: no verdict, no error, exit 0 for the
 * payload verifier. Each script is invoked directly and through a symlink here
 * and must behave identically, using a path that needs no Maven or network.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, realpathSync, rmSync, symlinkSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const RELEASE_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** A real script and a symlink to it, plus a scratch directory to run in. */
function scripts(t, name) {
  const root = realpathSync(mkdtempSync(path.join(tmpdir(), 'vertique-snapshot-entry-')));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const linkDir = path.join(root, 'linked');
  mkdirSync(linkDir);
  const link = path.join(linkDir, name);
  symlinkSync(path.join(RELEASE_DIR, name), link);
  const cwd = path.join(root, 'cwd');
  mkdirSync(cwd);
  return { direct: path.join(RELEASE_DIR, name), link, cwd, root };
}

const invoke = (script, args, { cwd, env }) =>
  spawnSync(process.execPath, [script, ...args], { cwd, env, encoding: 'utf8' });

describe('SnapshotGuardEntryPointTest', () => {
  // No event payload, so the guard denies; the point is that it speaks at all.
  const env = { PATH: process.env.PATH ?? '', VERTIQUE_VERSION: '0.0.0-SNAPSHOT' };

  it('printsItsVerdictWhenInvokedThroughASymlink', (t) => {
    const s = scripts(t, 'snapshot-guard.mjs');
    const direct = invoke(s.direct, [], { cwd: s.cwd, env });
    const linked = invoke(s.link, [], { cwd: s.cwd, env });
    assert.match(direct.stdout, /^snapshot-guard: DENY/m);
    assert.equal(linked.status, direct.status);
    assert.equal(linked.stdout, direct.stdout);
    assert.match(linked.stdout, /^snapshot-guard: DENY/m, 'the guard produced no verdict through a symlink');
  });

  it('writesTheSameWorkflowOutputWhenInvokedThroughASymlink', (t) => {
    const s = scripts(t, 'snapshot-guard.mjs');
    const direct = invoke(s.direct, ['--github-output'], { cwd: s.cwd, env });
    const linked = invoke(s.link, ['--github-output'], { cwd: s.cwd, env });
    assert.match(direct.stdout, /^allowed=false$/m);
    assert.equal(linked.status, direct.status);
    assert.equal(linked.stdout, direct.stdout);
    assert.equal(linked.stderr, direct.stderr);
  });
});

describe('SnapshotPayloadEntryPointTest', () => {
  const env = { PATH: process.env.PATH ?? '' };
  const SHA = 'a'.repeat(40);

  it('refusesAMissingPayloadWhenInvokedThroughASymlink', (t) => {
    const s = scripts(t, 'snapshot-payload.mjs');
    const args = ['verify', '--dir', path.join(s.root, 'no-such-payload'), '--sha', SHA, '--version', '0.0.1-SNAPSHOT'];
    const direct = invoke(s.direct, args, { cwd: s.cwd, env });
    const linked = invoke(s.link, args, { cwd: s.cwd, env });
    assert.equal(direct.status, 1, `${direct.stdout}\n${direct.stderr}`);
    assert.match(direct.stderr, /payload manifest not found/);
    // A silent exit 0 here would let an absent payload through to deployment.
    assert.equal(linked.status, 1, `the payload verifier did not run through a symlink:\n${linked.stdout}`);
    assert.equal(linked.stderr, direct.stderr);
  });

  it('printsUsageWhenInvokedThroughASymlinkWithNoCommand', (t) => {
    const s = scripts(t, 'snapshot-payload.mjs');
    const direct = invoke(s.direct, [], { cwd: s.cwd, env });
    const linked = invoke(s.link, [], { cwd: s.cwd, env });
    assert.equal(direct.status, 1);
    assert.match(direct.stderr, /usage: snapshot-payload\.mjs/);
    assert.equal(linked.status, direct.status);
    assert.equal(linked.stderr, direct.stderr);
  });
});
