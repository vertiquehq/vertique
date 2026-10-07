// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * RELEASE-001 — the SNAPSHOT payload handed from required CI to publication.
 *
 * Publication deploys bytes it did not build, so the only thing standing
 * between a stale, foreign or altered payload and the package registry is
 * release/snapshot-payload.mjs. These tests pin that it stages exactly the
 * allowlisted units and refuses every payload that is not exactly what was
 * staged for the approved commit.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { appendFileSync, mkdirSync, mkdtempSync, rmSync, unlinkSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { stagePayload, verifyPayload } from '../snapshot-payload.mjs';

const SCRIPT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'snapshot-payload.mjs');

const GROUP_ID = 'dev.vertique.fixture';
const VERSION = '0.0.1-SNAPSHOT';
const SHA = 'a'.repeat(40);
const OTHER_SHA = 'b'.repeat(40);
const ARTIFACTS = ['fixture-parent', 'fixture-unit-a', 'fixture-unit-b'];

const pom = (artifactId, modules = []) => `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>${GROUP_ID}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>${VERSION}</version>
    <packaging>pom</packaging>${
      modules.length === 0 ? '' : `\n    <modules>${modules.map((m) => `\n        <module>${m}</module>`).join('')}\n    </modules>`
    }
    <dependencyManagement/>
</project>
`;

const POLICY = {
  schemaVersion: 1,
  product: 'fixture',
  groupIdPrefix: GROUP_ID,
  publish: { fixed: ARTIFACTS, bomManaged: { pomPath: 'pom.xml', groupIdPrefix: GROUP_ID } },
  deny: [],
  skip: [],
  expectedPublishableGavCount: ARTIFACTS.length,
  expectedPublishableGavs: ARTIFACTS.map((id) => `${GROUP_ID}:${id}`),
  payloadPolicy: { pom: ['pom'] },
};

/** A reactor of three POM-packaged units plus an installed repository holding them. */
function fixture(t) {
  const root = mkdtempSync(path.join(tmpdir(), 'vertique-snapshot-payload-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const repoRoot = path.join(root, 'repo');
  mkdirSync(repoRoot);
  writeFileSync(path.join(repoRoot, 'pom.xml'), pom('fixture-parent', ['fixture-unit-a', 'fixture-unit-b']));
  for (const id of ['fixture-unit-a', 'fixture-unit-b']) {
    mkdirSync(path.join(repoRoot, id));
    writeFileSync(path.join(repoRoot, id, 'pom.xml'), pom(id));
  }

  const localRepository = path.join(root, 'm2');
  for (const id of ARTIFACTS) {
    const dir = path.join(localRepository, ...GROUP_ID.split('.'), id, VERSION);
    mkdirSync(dir, { recursive: true });
    writeFileSync(path.join(dir, `${id}-${VERSION}.pom`), pom(id));
  }

  return { root, repoRoot, localRepository, out: path.join(root, 'payload') };
}

const stage = (f, overrides = {}) =>
  stagePayload({
    repoRoot: f.repoRoot,
    policy: POLICY,
    localRepository: f.localRepository,
    version: VERSION,
    sha: SHA,
    out: f.out,
    ...overrides,
  });

const payloadFile = (f, id) =>
  path.join(f.out, 'repository', ...GROUP_ID.split('.'), id, VERSION, `${id}-${VERSION}.pom`);

describe('SnapshotPayloadTest', () => {
  it('stagesExactlyTheAllowlistedUnitsAndVerifiesCleanly', (t) => {
    const f = fixture(t);
    // Not allowlisted: installed by the reactor build but never published.
    const stray = path.join(f.localRepository, ...GROUP_ID.split('.'), 'fixture-stray', VERSION);
    mkdirSync(stray, { recursive: true });
    writeFileSync(path.join(stray, `fixture-stray-${VERSION}.pom`), pom('fixture-stray'));

    const manifest = stage(f);

    assert.equal(manifest.sha, SHA);
    assert.equal(manifest.version, VERSION);
    assert.deepEqual(
      Object.keys(manifest.files),
      ARTIFACTS.map((id) => `${GROUP_ID.replaceAll('.', '/')}/${id}/${VERSION}/${id}-${VERSION}.pom`).sort()
    );
    assert.deepEqual(verifyPayload({ dir: f.out, sha: SHA, version: VERSION }), []);
  });

  it('refusesToStageWhenAnAllowlistedUnitWasNotBuilt', (t) => {
    const f = fixture(t);
    unlinkSync(path.join(f.localRepository, ...GROUP_ID.split('.'), 'fixture-unit-b', VERSION, `fixture-unit-b-${VERSION}.pom`));

    assert.throws(() => stage(f), /deploy plan is incomplete[\s\S]*fixture-unit-b/);
  });

  it('refusesToStageForAnythingButACommit', (t) => {
    const f = fixture(t);
    assert.throws(() => stage(f, { sha: 'main' }), /40-character commit/);
  });

  it('refusesAPayloadBuiltFromAnotherCommitOrVersion', (t) => {
    const f = fixture(t);
    stage(f);

    assert.match(verifyPayload({ dir: f.out, sha: OTHER_SHA, version: VERSION }).join('\n'), /not the approved commit/);
    assert.match(verifyPayload({ dir: f.out, sha: SHA, version: '0.0.2-SNAPSHOT' }).join('\n'), /not the approved/);
  });

  it('refusesAChangedMissingOrExtraFile', (t) => {
    const f = fixture(t);
    stage(f);

    appendFileSync(payloadFile(f, 'fixture-unit-a'), '<!-- tampered -->');
    unlinkSync(payloadFile(f, 'fixture-unit-b'));
    writeFileSync(path.join(f.out, 'repository', 'unlisted.jar'), 'x');

    const problems = verifyPayload({ dir: f.out, sha: SHA, version: VERSION }).join('\n');
    assert.match(problems, /fixture-unit-a-.*\.pom: content does not match/);
    assert.match(problems, /fixture-unit-b-.*\.pom: listed in the manifest but missing/);
    assert.match(problems, /unlisted\.jar: present but not listed/);
  });

  it('refusesAnAbsentOrUnreadableManifest', (t) => {
    const f = fixture(t);
    mkdirSync(f.out);
    assert.match(verifyPayload({ dir: f.out, sha: SHA, version: VERSION }).join('\n'), /manifest not found/);

    writeFileSync(path.join(f.out, 'manifest.json'), '{ not json');
    assert.match(verifyPayload({ dir: f.out, sha: SHA, version: VERSION }).join('\n'), /not valid JSON/);

    writeFileSync(path.join(f.out, 'manifest.json'), JSON.stringify({ schemaVersion: 1, sha: SHA, version: VERSION, files: {} }));
    assert.match(verifyPayload({ dir: f.out, sha: SHA, version: VERSION }).join('\n'), /lists no files/);
  });

  it('exitsNonZeroFromTheCommandLineWhenVerificationFails', (t) => {
    const f = fixture(t);
    stage(f);

    const ok = spawnSync('node', [SCRIPT, 'verify', '--dir', f.out, '--sha', SHA, '--version', VERSION], { encoding: 'utf8' });
    assert.equal(ok.status, 0, ok.stderr);
    assert.equal(ok.stdout, '');

    const wrong = spawnSync('node', [SCRIPT, 'verify', '--dir', f.out, '--sha', OTHER_SHA, '--version', VERSION], { encoding: 'utf8' });
    assert.notEqual(wrong.status, 0);
    assert.match(wrong.stderr, /not the approved commit/);

    const missing = spawnSync('node', [SCRIPT, 'verify', '--dir', f.out, '--version', VERSION], { encoding: 'utf8' });
    assert.notEqual(missing.status, 0);
    assert.match(missing.stderr, /--sha is required/);
  });
});
