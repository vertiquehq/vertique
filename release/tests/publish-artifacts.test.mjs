// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * RELEASE-001 — staging never depends on the reactor model
 * (FR-REL-033; ADR-0007).
 *
 * release/publish-artifacts.sh deploys each payload unit with deploy-file,
 * which needs no project. Run from the repository root, Maven still builds the
 * reactor model first, and that resolves every import-scoped BOM through the
 * deploy invocation's scratch local repository and Central alone. A SNAPSHOT
 * BOM from any other repository then fails model building, and nothing is
 * deployed.
 *
 * The fixture reproduces exactly that: a reactor root whose BOM import resolves
 * nowhere, and one allowlisted POM unit in an isolated local repository. It
 * runs the real script and the real Maven Deploy Plugin against a file://
 * target, so it needs Java and Central, like every other Maven step here.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

// A stalled Central connection must fail this test, not the whole job.
const MAVEN_TIMEOUT_MS = 300_000;

const GROUP_ID = 'dev.vertique.fixture';
const ARTIFACT_ID = 'fixture-parent';
const VERSION = '0.0.1-SNAPSHOT';

// Outside the policy's group on purpose, so the BOM-managed rule stays empty
// and the unresolvable import is the only thing this POM contributes.
const REACTOR_POM = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>${GROUP_ID}</groupId>
    <artifactId>${ARTIFACT_ID}</artifactId>
    <version>${VERSION}</version>
    <packaging>pom</packaging>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>dev.vertique.absent</groupId>
                <artifactId>absent-bom</artifactId>
                <version>0.0.0-SNAPSHOT</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
`;

const POLICY = {
  schemaVersion: 1,
  product: 'fixture',
  groupIdPrefix: GROUP_ID,
  publish: {
    fixed: [ARTIFACT_ID],
    bomManaged: { pomPath: 'pom.xml', groupIdPrefix: GROUP_ID },
  },
  deny: [],
  skip: [],
  expectedPublishableGavCount: 1,
  expectedPublishableGavs: [`${GROUP_ID}:${ARTIFACT_ID}`],
  payloadPolicy: { pom: ['pom'] },
};

/** A throwaway repository holding the real staging tooling and the fixture reactor. */
function fixtureRepository(root) {
  const repo = path.join(root, 'repo');
  for (const file of [
    'mvnw',
    '.mvn/wrapper/maven-wrapper.properties',
    'release/publish-artifacts.sh',
    'release/verify-publication.mjs',
  ]) {
    mkdirSync(path.dirname(path.join(repo, file)), { recursive: true });
    copyFileSync(path.join(REPO_ROOT, file), path.join(repo, file));
  }
  writeFileSync(path.join(repo, 'pom.xml'), REACTOR_POM);
  writeFileSync(path.join(repo, 'release', 'publication-policy.json'), JSON.stringify(POLICY, null, 2));
  // Passed as a relative --settings, which must still mean relative to the
  // root; being empty, it also keeps a developer's own settings out of the run.
  writeFileSync(path.join(repo, 'settings.xml'), '<settings/>\n');

  // The installed unit carries the same unresolvable import: deploy-file must
  // read it as a payload, never build it as a model.
  const unitDir = path.join(root, 'm2', ...GROUP_ID.split('.'), ARTIFACT_ID, VERSION);
  mkdirSync(unitDir, { recursive: true });
  writeFileSync(path.join(unitDir, `${ARTIFACT_ID}-${VERSION}.pom`), REACTOR_POM);

  return { repo, localRepository: path.join(root, 'm2') };
}

describe('PublishArtifactsTest', () => {
  it('stagesAllowlistFromReactorWhoseBomImportCannotResolve', (t) => {
    const root = mkdtempSync(path.join(tmpdir(), 'vertique-publish-artifacts-'));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    const { repo, localRepository } = fixtureRepository(root);
    const stage = path.join(root, 'staged');
    mkdirSync(stage);

    // Precondition: the fixture reactor really cannot be built, so a pass below
    // proves staging never needed it rather than that the import resolved.
    const model = spawnSync(
      path.join(repo, 'mvnw'),
      ['-B', '-ntp', '-o', `-Dmaven.repo.local=${path.join(root, 'empty-m2')}`, 'validate'],
      { cwd: repo, encoding: 'utf8', timeout: MAVEN_TIMEOUT_MS }
    );
    assert.notEqual(model.status, 0, 'fixture reactor model unexpectedly resolved');
    assert.match(model.stdout, /Non-resolvable import POM/);

    const run = spawnSync(
      'bash',
      [
        path.join(repo, 'release', 'publish-artifacts.sh'),
        '--local-repository', localRepository,
        '--repository-id', 'fixture-stage',
        '--target-url', `file://${stage}`,
        '--mode', 'snapshot',
        '--version', VERSION,
        '--settings', 'settings.xml',
      ],
      { encoding: 'utf8', timeout: MAVEN_TIMEOUT_MS }
    );
    assert.equal(run.status, 0, `publish-artifacts failed:\n${run.stdout}\n${run.stderr}`);
    assert.match(run.stdout, /deployed 1\/1 GAVs/);

    const stagedDir = path.join(stage, ...GROUP_ID.split('.'), ARTIFACT_ID, VERSION);
    assert.ok(existsSync(path.join(stagedDir, 'maven-metadata.xml')), `no SNAPSHOT metadata in ${stagedDir}`);
    const poms = readdirSync(stagedDir).filter((name) => name.endsWith('.pom'));
    assert.equal(poms.length, 1, `expected one timestamped POM, found ${JSON.stringify(poms)}`);
  });

  it('refusesRelativeFileTargetUrl', (t) => {
    const root = mkdtempSync(path.join(tmpdir(), 'vertique-publish-artifacts-'));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    const { repo, localRepository } = fixtureRepository(root);

    // Maven runs from a scratch directory, so a relative file: target would
    // stage there and be deleted with it while the script reported success.
    const run = spawnSync(
      'bash',
      [
        path.join(repo, 'release', 'publish-artifacts.sh'),
        '--local-repository', localRepository,
        '--repository-id', 'fixture-stage',
        '--target-url', 'file:staged',
        '--mode', 'snapshot',
        '--version', VERSION,
      ],
      { encoding: 'utf8', timeout: MAVEN_TIMEOUT_MS }
    );
    assert.notEqual(run.status, 0, `relative file: target was accepted:\n${run.stdout}`);
    assert.match(run.stderr, /must be an absolute file:\/\/\/ URL/);
    assert.doesNotMatch(run.stdout, /deploying/);
  });
});
