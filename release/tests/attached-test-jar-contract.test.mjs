// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Attached test JARs are not a published surface.
 *
 * Several modules attach a test JAR carrying their own test fixtures for sibling
 * modules inside this reactor. Those JARs sit next to the main JAR in the
 * installed repository, so the staged-repository check must refuse them by name
 * rather than let a `-tests.jar` pass as a second main JAR or a `-test-sources.jar`
 * pass as the module's sources payload.
 *
 * Runs against a tiny synthetic reactor and a synthetic staged repository; it
 * needs no Maven and no network.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildDeployPlan, loadPolicy, verifyStagedRepository } from '../verify-publication.mjs';

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

/** Policy publishing the parent POM and one jar module; `exceptions` allow-lists test artifacts. */
const policyWith = (exceptions) => ({
  schemaVersion: 1,
  product: 'fixture',
  groupIdPrefix: GROUP_ID,
  publish: { fixed: [ROOT_ID, UNIT_ID], bomManaged: { pomPath: 'pom.xml', groupIdPrefix: GROUP_ID } },
  deny: [],
  skip: [],
  expectedPublishableGavCount: 2,
  expectedPublishableGavs: [`${GROUP_ID}:${ROOT_ID}`, `${GROUP_ID}:${UNIT_ID}`],
  payloadPolicy: {
    pom: ['pom'],
    jar: ['pom', 'jar', 'sources'],
    ...(exceptions ? { attachedTestArtifactExceptions: exceptions } : {}),
  },
});

/** Writes a reactor and a staged repository holding the main payload plus `extraFiles`. */
function fixture(t, extraFiles = []) {
  const root = mkdtempSync(path.join(tmpdir(), 'vertique-test-jar-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));

  const reactor = path.join(root, 'reactor');
  mkdirSync(path.join(reactor, UNIT_ID), { recursive: true });
  writeFileSync(path.join(reactor, 'pom.xml'), pom(ROOT_ID, 'pom', [UNIT_ID]));
  writeFileSync(path.join(reactor, UNIT_ID, 'pom.xml'), pom(UNIT_ID, 'jar'));

  const staged = path.join(root, 'staged');
  const put = (artifactId, fileName, content = 'x') => {
    const dir = path.join(staged, ...GROUP_ID.split('.'), artifactId, VERSION);
    mkdirSync(dir, { recursive: true });
    writeFileSync(path.join(dir, fileName), content);
  };
  put(ROOT_ID, `${ROOT_ID}-${VERSION}.pom`, pom(ROOT_ID, 'pom'));
  put(UNIT_ID, `${UNIT_ID}-${VERSION}.pom`, pom(UNIT_ID, 'jar'));
  put(UNIT_ID, `${UNIT_ID}-${VERSION}.jar`);
  put(UNIT_ID, `${UNIT_ID}-${VERSION}-sources.jar`);
  for (const [artifactId, fileName] of extraFiles) put(artifactId, fileName);

  return { root, reactor, staged };
}

const verify = ({ reactor, staged }, policy) =>
  verifyStagedRepository(staged, { repoRoot: reactor, policy, version: VERSION, mode: 'final' });

describe('AttachedTestJarPublicationTest', () => {
  it('acceptsAModuleWhoseStagedPayloadIsOnlyItsMainArtifacts', (t) => {
    const result = verify(fixture(t), policyWith());
    assert.deepEqual(result.errors, []);
    assert.equal(result.ok, true);
  });

  it('rejectsAnAttachedTestsJarAndNamesTheArtifact', (t) => {
    const f = fixture(t, [[UNIT_ID, `${UNIT_ID}-${VERSION}-tests.jar`]]);
    const result = verify(f, policyWith());
    assert.equal(result.ok, false);
    const message = result.errors.find((e) => e.includes('attached test artifact'));
    assert.ok(message, `expected an attached-test-artifact error, got: ${result.errors.join(' | ')}`);
    assert.ok(message.includes(`${GROUP_ID}:${UNIT_ID}`), message);
    assert.ok(message.includes(`${UNIT_ID}-${VERSION}-tests.jar`), message);
  });

  it('rejectsAnAttachedTestSourcesJarAndNamesTheArtifact', (t) => {
    const f = fixture(t, [[UNIT_ID, `${UNIT_ID}-${VERSION}-test-sources.jar`]]);
    const result = verify(f, policyWith());
    assert.equal(result.ok, false);
    assert.ok(
      result.errors.some((e) => e.includes('attached test artifact') && e.includes('-test-sources.jar')),
      result.errors.join(' | ')
    );
  });

  it('doesNotLetATestSourcesJarSatisfyTheRequiredSourcesPayload', (t) => {
    // The main sources JAR is absent; only the test sources JAR is staged. With
    // the module allow-listed the test JAR is tolerated, yet the module must
    // still be reported as missing its own sources.
    const f = fixture(t);
    const sources = path.join(f.staged, ...GROUP_ID.split('.'), UNIT_ID, VERSION, `${UNIT_ID}-${VERSION}-sources.jar`);
    rmSync(sources);
    writeFileSync(sources.replace('-sources.jar', '-test-sources.jar'), 'x');
    const result = verify(f, policyWith([{ artifactId: UNIT_ID, reason: 'fixture exception' }]));
    assert.equal(result.ok, false);
    assert.ok(
      result.errors.some((e) => e.includes(`${GROUP_ID}:${UNIT_ID}`) && e.includes('missing required sources payload')),
      result.errors.join(' | ')
    );
  });

  it('rejectsEveryTestClassifierForAnUnlistedModule', (t) => {
    for (const classifier of ['tests', 'test-sources', 'test-javadoc']) {
      const f = fixture(t, [[UNIT_ID, `${UNIT_ID}-${VERSION}-${classifier}.jar`]]);
      const result = verify(f, policyWith());
      assert.ok(
        result.errors.some((e) => e.includes('attached test artifact') && e.includes(`classifier "${classifier}"`)),
        `${classifier}: ${result.errors.join(' | ')}`
      );
    }
  });

  it('acceptsAnAttachedTestJarForAModuleAllowListedWithAReason', (t) => {
    const f = fixture(t, [[UNIT_ID, `${UNIT_ID}-${VERSION}-tests.jar`]]);
    const result = verify(f, policyWith([{ artifactId: UNIT_ID, reason: 'ships fixtures consumed outside the reactor' }]));
    assert.deepEqual(result.errors, []);
    assert.equal(result.ok, true);
  });

  it('keepsRejectingAnAttachedTestJarForAModuleThatIsNotAllowListed', (t) => {
    const f = fixture(t, [[UNIT_ID, `${UNIT_ID}-${VERSION}-tests.jar`]]);
    const result = verify(f, policyWith([{ artifactId: 'some-other-module', reason: 'unrelated' }]));
    assert.equal(result.ok, false);
  });

  it('neverSelectsATestJarForTheDeployPlan', (t) => {
    // The local repository holds the test JAR next to the main payload, as an
    // install leaves it. The plan names its files by payload kind, so a test JAR
    // can never become a deployed unit file.
    const f = fixture(t, [[UNIT_ID, `${UNIT_ID}-${VERSION}-tests.jar`]]);
    const plan = buildDeployPlan(f.reactor, policyWith(), { version: VERSION, localRepository: f.staged });
    assert.deepEqual(plan.errors, []);
    const files = plan.units.flatMap((u) => Object.values(u.files));
    assert.ok(files.length > 0);
    assert.deepEqual(files.filter((file) => /-(tests|test-sources|test-javadoc)\.jar$/.test(file)), []);
  });
});

describe('AttachedTestArtifactExceptionPolicyTest', () => {
  const writePolicy = (t, exceptions) => {
    const dir = mkdtempSync(path.join(tmpdir(), 'vertique-test-jar-policy-'));
    t.after(() => rmSync(dir, { recursive: true, force: true }));
    const file = path.join(dir, 'policy.json');
    writeFileSync(file, JSON.stringify(policyWith(exceptions)));
    return file;
  };

  it('requiresADocumentedReasonForEveryException', (t) => {
    for (const entry of [{ artifactId: UNIT_ID }, { artifactId: UNIT_ID, reason: '  ' }, { reason: 'why' }]) {
      assert.throws(() => loadPolicy(writePolicy(t, [entry])), /documented "reason"/);
    }
  });

  it('acceptsAnExceptionThatNamesTheModuleAndTheReason', (t) => {
    const policy = loadPolicy(writePolicy(t, [{ artifactId: UNIT_ID, reason: 'documented' }]));
    assert.equal(policy.payloadPolicy.attachedTestArtifactExceptions.length, 1);
  });

  it('shipsTheRealPolicyWithoutAnyException', () => {
    const real = loadPolicy(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'publication-policy.json'));
    assert.deepEqual(real.payloadPolicy.attachedTestArtifactExceptions ?? [], []);
  });
});
