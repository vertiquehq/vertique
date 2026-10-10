// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * The publication verifier is a release gate, so it must never exit 0 without
 * having run.
 *
 * `import.meta.url` is symlink-resolved while `process.argv[1]` is the path as
 * invoked. A script reached through a symlink (a linked checkout, or a macOS
 * temp directory under /var -> /private/var) used to fail that comparison, so
 * the CLI body never ran: no output, exit 0. These tests invoke the real script
 * directly and through a symlink and require identical behavior, including a
 * non-zero exit for a staged repository holding an attached test JAR.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'verify-publication.mjs');

const GROUP_ID = 'dev.vertique.fixture';
const VERSION = '1.0.0';
const ROOT_ID = 'fixture-parent';
const UNIT_ID = 'fixture-unit';

const pom = (artifactId, packaging, modules = []) => `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>${GROUP_ID}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>${VERSION}</version>
    <packaging>${packaging}</packaging>${
      modules.length === 0 ? '' : `\n    <modules>${modules.map((m) => `<module>${m}</module>`).join('')}</modules>`
    }
</project>
`;

const POLICY = {
  schemaVersion: 1,
  product: 'fixture',
  groupIdPrefix: GROUP_ID,
  publish: { fixed: [ROOT_ID, UNIT_ID], bomManaged: { pomPath: 'pom.xml', groupIdPrefix: GROUP_ID } },
  deny: [],
  skip: [],
  expectedPublishableGavCount: 2,
  expectedPublishableGavs: [`${GROUP_ID}:${ROOT_ID}`, `${GROUP_ID}:${UNIT_ID}`],
  payloadPolicy: { pom: ['pom'], jar: ['pom', 'jar'] },
};

/** A reactor, its policy file, a staged repository, and a symlink to the script. */
function fixture(t, { withTestJar }) {
  const root = realpathSync(mkdtempSync(path.join(tmpdir(), 'vertique-entry-')));
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const reactor = path.join(root, 'reactor');
  mkdirSync(path.join(reactor, UNIT_ID), { recursive: true });
  writeFileSync(path.join(reactor, 'pom.xml'), pom(ROOT_ID, 'pom', [UNIT_ID]));
  writeFileSync(path.join(reactor, UNIT_ID, 'pom.xml'), pom(UNIT_ID, 'jar'));
  const policy = path.join(root, 'policy.json');
  writeFileSync(policy, JSON.stringify(POLICY));

  const staged = path.join(root, 'staged');
  const put = (artifactId, fileName) => {
    const dir = path.join(staged, ...GROUP_ID.split('.'), artifactId, VERSION);
    mkdirSync(dir, { recursive: true });
    writeFileSync(path.join(dir, fileName), 'x');
  };
  put(ROOT_ID, `${ROOT_ID}-${VERSION}.pom`);
  put(UNIT_ID, `${UNIT_ID}-${VERSION}.pom`);
  put(UNIT_ID, `${UNIT_ID}-${VERSION}.jar`);
  if (withTestJar) put(UNIT_ID, `${UNIT_ID}-${VERSION}-tests.jar`);

  const linkDir = path.join(root, 'linked');
  mkdirSync(linkDir);
  const link = path.join(linkDir, 'verify-publication.mjs');
  symlinkSync(SCRIPT, link);

  const args = ['--root', reactor, '--policy', policy, '--verify-staged', staged, '--version', VERSION, '--mode', 'final'];
  const run = (script) => spawnSync(process.execPath, [script, ...args], { encoding: 'utf8' });
  return { direct: () => run(SCRIPT), linked: () => run(link) };
}

describe('VerifyPublicationEntryPointTest', () => {
  it('runsAndPassesACleanStagedRepositoryWhenInvokedDirectly', (t) => {
    const run = fixture(t, { withTestJar: false }).direct();
    assert.equal(run.status, 0, `${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /staged repository OK/);
  });

  it('runsTheSameWhenInvokedThroughASymlinkToTheScript', (t) => {
    const f = fixture(t, { withTestJar: false });
    const direct = f.direct();
    const linked = f.linked();
    assert.equal(linked.status, direct.status, `${linked.stdout}\n${linked.stderr}`);
    assert.equal(linked.stdout, direct.stdout);
    // The check must have produced its verdict: silence is not a pass.
    assert.match(linked.stdout, /staged repository OK/);
  });

  it('failsADirtyStagedRepositoryWhenInvokedThroughASymlinkToTheScript', (t) => {
    const f = fixture(t, { withTestJar: true });
    const direct = f.direct();
    const linked = f.linked();
    assert.notEqual(direct.status, 0, 'a staged test JAR passed verification when invoked directly');
    assert.notEqual(linked.status, 0, `a staged test JAR passed verification through a symlink:\n${linked.stdout}`);
    assert.equal(linked.status, direct.status);
    assert.match(linked.stderr, /attached test artifact staged: fixture-unit-1\.0\.0-tests\.jar/);
    assert.equal(linked.stderr, direct.stderr);
  });
});
