// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * RELEASE-001 S1 — public publication contract (FR-REL-030, FR-REL-032).
 *
 * Proves that publication eligibility is *derived* from the declared policy
 * (root parent, standalone application parent, BOM, every `dev.vertique`
 * dependency-management entry in the BOM, and the `maven-archetype` children
 * of the archetype aggregator) rather than selected ad hoc or inferred from
 * reactor membership, and that every reactor module carries exactly one
 * publish-or-skip classification.
 *
 * Drift proofs run against synthetic fixture reactors rather than the real
 * tree so a genuine inventory change is a deliberate policy edit, not a
 * silently-absorbed test adjustment.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { loadPolicy, deriveInventory, verifyInventory, readPom } from '../verify-publication.mjs';

/** The development line both independently invokable public parents must declare (FR-REL-001). */
const DEVELOPMENT_VERSION = '0.1.0-SNAPSHOT';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(TEST_DIR, '..', '..');
const POLICY_PATH = path.join(REPO_ROOT, 'release', 'publication-policy.json');

/** Minimal POM writer for synthetic fixture reactors. */
function pom(dir, { artifactId, packaging = 'jar', modules = [], managed = [] }) {
  mkdirSync(dir, { recursive: true });
  const moduleXml = modules.length
    ? `<modules>${modules.map((m) => `<module>${m}</module>`).join('')}</modules>`
    : '';
  const managedXml = managed.length
    ? `<dependencyManagement><dependencies>${managed
        .map(
          (a) =>
            `<dependency><groupId>dev.vertique</groupId><artifactId>${a}</artifactId><version>\${revision}</version></dependency>`
        )
        .join('')}</dependencies></dependencyManagement>`
    : '';
  writeFileSync(
    path.join(dir, 'pom.xml'),
    `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.vertique</groupId>
  <artifactId>${artifactId}</artifactId>
  <packaging>${packaging}</packaging>
  ${moduleXml}
  ${managedXml}
</project>
`
  );
}

/**
 * Builds a synthetic reactor shaped like the real public one but tiny:
 * parent + app-parent + bom (3 managed GAVs) + 3 archetypes + 1 example.
 * Returns its root directory; callers mutate it to simulate drift.
 */
function fixtureReactor(overrides = {}) {
  const root = mkdtempSync(path.join(tmpdir(), 'vertique-pub-'));
  const managed = overrides.managed ?? ['vertique-core', 'vertique-rest-core', 'vertique-json'];
  const archetypes = overrides.archetypes ?? [
    'vertique-archetype-rest',
    'vertique-archetype-services',
    'vertique-archetype-rest-postgresql',
  ];
  const extraJars = overrides.extraJars ?? [];

  pom(root, {
    artifactId: 'vertique-parent',
    packaging: 'pom',
    modules: [
      'vertique-app-parent',
      'vertique-bom',
      'vertique-archetype',
      'examples',
      ...managed,
      ...extraJars,
    ],
  });
  pom(path.join(root, 'vertique-app-parent'), { artifactId: 'vertique-app-parent', packaging: 'pom' });
  pom(path.join(root, 'vertique-bom'), { artifactId: 'vertique-bom', packaging: 'pom', managed });
  pom(path.join(root, 'vertique-archetype'), {
    artifactId: 'vertique-archetype',
    packaging: 'pom',
    modules: archetypes,
  });
  for (const a of archetypes) {
    pom(path.join(root, 'vertique-archetype', a), { artifactId: a, packaging: 'maven-archetype' });
  }
  pom(path.join(root, 'examples'), {
    artifactId: 'vertique-example-parent',
    packaging: 'pom',
    modules: ['vertique-example-hello'],
  });
  pom(path.join(root, 'examples', 'vertique-example-hello'), { artifactId: 'vertique-example-hello' });
  for (const a of managed) pom(path.join(root, a), { artifactId: a });
  for (const a of extraJars) pom(path.join(root, a), { artifactId: a });
  return root;
}

/** Policy matching {@link fixtureReactor}'s expected count (3 managed + 3 fixed + 3 archetypes). */
function fixturePolicy() {
  return { ...loadPolicy(POLICY_PATH), expectedPublishableGavCount: 9 };
}

describe('PublicPublicationInventoryTest', () => {
  it('everyReactorModuleHasExactlyOnePublicationClassification', () => {
    const policy = loadPolicy(POLICY_PATH);
    const inventory = deriveInventory(REPO_ROOT, policy);

    // Every reactor module is classified exactly once, with no overlap and no gap.
    const publishedIds = inventory.published.map((u) => u.artifactId);
    const skippedIds = inventory.skipped.map((u) => u.artifactId);
    assert.equal(
      new Set([...publishedIds, ...skippedIds]).size,
      inventory.modules.length,
      'published ∪ skipped must cover every reactor module exactly once'
    );
    assert.deepEqual(
      publishedIds.filter((id) => skippedIds.includes(id)),
      [],
      'no module may be both published and skipped'
    );

    // No module may fall through to the catch-all: an unrecognised module is drift.
    assert.deepEqual(
      inventory.skipped.filter((u) => u.reason === 'unclassified'),
      [],
      'every skipped module must match a declared skip rule'
    );

    // The derived allowlist is exactly the declared inventory.
    assert.equal(inventory.published.length, policy.expectedPublishableGavCount);
    assert.equal(inventory.published.length, 93);
    for (const fixed of ['vertique-parent', 'vertique-app-parent', 'vertique-bom']) {
      assert.ok(publishedIds.includes(fixed), `${fixed} must be published`);
    }
    assert.equal(
      inventory.published.filter((u) => u.packaging === 'maven-archetype').length,
      3,
      'exactly the three public archetypes are published'
    );

    // Examples and integration-test modules are never publishable.
    for (const id of publishedIds) {
      assert.ok(!id.startsWith('vertique-example'), `${id} is an example and must not publish`);
      assert.ok(!id.endsWith('-integration-tests'), `${id} is an IT module and must not publish`);
    }

    assert.equal(verifyInventory(REPO_ROOT, policy).ok, true);
  });

  it('rejectsInventoryDrift', () => {
    const roots = [];
    try {
      // Baseline: the untouched fixture verifies clean against its policy.
      const base = fixtureReactor();
      roots.push(base);
      assert.equal(verifyInventory(base, fixturePolicy()).ok, true, 'baseline fixture must verify');

      // A BOM GAV added without a policy/count update is drift.
      const addedBomGav = fixtureReactor({
        managed: ['vertique-core', 'vertique-rest-core', 'vertique-json', 'vertique-newly-added'],
      });
      roots.push(addedBomGav);
      const bomDrift = verifyInventory(addedBomGav, fixturePolicy());
      assert.equal(bomDrift.ok, false, 'an added BOM GAV must fail verification');
      assert.match(bomDrift.errors.join('\n'), /count/i);

      // A fourth archetype added without a policy/count update is drift.
      const addedArchetype = fixtureReactor({
        archetypes: [
          'vertique-archetype-rest',
          'vertique-archetype-services',
          'vertique-archetype-rest-postgresql',
          'vertique-archetype-batch',
        ],
      });
      roots.push(addedArchetype);
      assert.equal(
        verifyInventory(addedArchetype, fixturePolicy()).ok,
        false,
        'an added archetype must fail verification'
      );

      // A new reactor jar matching no publish rule and no skip rule is drift,
      // so a module can never bypass publication by simply being unrecognised.
      const unknownModule = fixtureReactor({ extraJars: ['vertique-brand-new-thing'] });
      roots.push(unknownModule);
      const unknown = verifyInventory(unknownModule, fixturePolicy());
      assert.equal(unknown.ok, false, 'an unclassifiable reactor module must fail verification');
      assert.match(unknown.errors.join('\n'), /unclassified|vertique-brand-new-thing/i);
    } finally {
      for (const r of roots) rmSync(r, { recursive: true, force: true });
    }
  });
});

/** Reads the literal `<revision>` property declared in a POM's own `<properties>`. */
function declaredRevision(pomRelPath) {
  const xml = readFileSync(path.join(REPO_ROOT, pomRelPath), 'utf8');
  const match = /<revision>([^<]+)<\/revision>/.exec(xml);
  assert.ok(match, `${pomRelPath} declares no <revision> property`);
  return match[1].trim();
}

describe('PublicVersionContractTest', () => {
  it('rootAndStandaloneApplicationParentDefaultToZeroOneSnapshot', () => {
    // Both public parents are independently invokable, so each carries its own
    // <revision>. main must identify the actual next intended release
    // (FR-REL-001), not the 0.0.0-SNAPSHOT cutover placeholder.
    assert.equal(declaredRevision('pom.xml'), DEVELOPMENT_VERSION);
    assert.equal(declaredRevision('vertique-app-parent/pom.xml'), DEVELOPMENT_VERSION);

    // Both parents are themselves publishable, so the version they declare is
    // the version consumers resolve.
    const policy = loadPolicy(POLICY_PATH);
    const published = deriveInventory(REPO_ROOT, policy).published.map((u) => u.artifactId);
    assert.ok(published.includes('vertique-parent'));
    assert.ok(published.includes('vertique-app-parent'));
  });

  it('declaresReleaseShapedPayloadPolicyForEveryPublishedPackaging', () => {
    // FR-REL-034: every published packaging must have a declared payload set,
    // so staging can never silently omit sources or Javadocs for a GAV.
    const policy = loadPolicy(POLICY_PATH);
    const inventory = deriveInventory(REPO_ROOT, policy);
    const packagings = new Set(inventory.published.map((u) => u.packaging));

    for (const packaging of packagings) {
      const payloads = policy.payloadPolicy[packaging];
      assert.ok(payloads, `payloadPolicy declares no payload set for packaging "${packaging}"`);
      assert.ok(payloads.includes('pom'), `${packaging} must publish a flattened POM`);
      if (packaging !== 'pom') {
        for (const required of ['jar', 'sources', 'javadoc']) {
          assert.ok(payloads.includes(required), `${packaging} must publish a ${required} payload`);
        }
      }
    }

    // Every published unit carries its resolved payload set, so the staging
    // step never has to re-derive it.
    for (const unit of inventory.published) {
      assert.ok(Array.isArray(unit.payloads), `${unit.artifactId} has no resolved payload set`);
    }
  });
});
