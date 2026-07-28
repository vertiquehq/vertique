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

/**
 * Splits a workflow's `run:` step bodies out of the raw YAML.
 *
 * The workflow contract is asserted textually rather than through a YAML
 * object model: the properties that matter here (which permissions are
 * granted anywhere in the file, whether any secret is referenced, whether a
 * step embeds logic instead of calling an entry point) are all properties of
 * the literal document a reviewer reads, and a parser would let a
 * semantically-equivalent-but-unreviewable form slip through.
 */
function runStepBodies(yaml) {
  const bodies = [];
  const lines = yaml.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const m = /^(\s*)-?\s*run:\s*(\|[-+]?|>[-+]?)?\s*(.*)$/.exec(lines[i]);
    if (!m) continue;
    const [, indent, block, inline] = m;
    if (!block) {
      bodies.push(inline.trim());
      continue;
    }
    const body = [];
    for (let j = i + 1; j < lines.length; j++) {
      if (lines[j].trim() === '') { body.push(''); continue; }
      const lead = lines[j].match(/^\s*/)[0].length;
      if (lead <= indent.length) break;
      body.push(lines[j].trim());
    }
    bodies.push(body.join('\n').trim());
  }
  return bodies;
}

describe('PublicCiContractTest', () => {
  const CI_PATH = path.join(REPO_ROOT, '.github', 'workflows', 'ci.yml');
  const ci = () => readFileSync(CI_PATH, 'utf8');

  it('pullRequestBuildHasReadOnlyPermissionsAndNoSecrets', () => {
    const yaml = ci();

    // Required CI must hold no package, release, tag, or repository-content
    // write credential (FR-REL-014, NFR-REL-001).
    const granted = [...yaml.matchAll(/^\s*(contents|packages|pull-requests|id-token|actions|checks|deployments|issues|statuses|security-events|attestations|pages):\s*(\S+)\s*$/gm)];
    assert.ok(granted.length > 0, 'ci.yml declares no explicit permissions block');
    for (const [, scope, level] of granted) {
      assert.equal(level, 'read', `permission ${scope} must be read, got "${level}"`);
    }
    assert.match(yaml, /^permissions:\s*$/m, 'ci.yml must declare a top-level permissions block');

    // No secret may be referenced at all: nothing in required CI needs one.
    const secretRefs = [...yaml.matchAll(/\$\{\{\s*secrets\.([A-Za-z0-9_]+)/g)].map((m) => m[1]);
    assert.deepEqual(secretRefs, [], `required CI must reference no secrets, found ${secretRefs}`);

    // It must run on both PR and main push, and must actually verify.
    assert.match(yaml, /pull_request:/, 'ci.yml must run on pull_request');
    assert.match(yaml, /push:/, 'ci.yml must run on push');
    const bodies = runStepBodies(yaml).join('\n');
    assert.match(bodies, /mvnw[^\n]*\bverify\b/, 'ci.yml must run a clean Maven verification');
    assert.match(bodies, /spotless:check/, 'ci.yml must run the formatting check');
    assert.match(
      bodies,
      /verify-publication\.mjs|publication-contract\.test\.mjs/,
      'ci.yml must run the release-contract tests'
    );
  });

  it('delegatesSubstantiveLogicToRepositoryOwnedScripts', () => {
    // NFR-REL-006: workflow YAML binds events and permissions; substantive
    // logic lives in versioned, locally runnable repository-owned entry points.
    const bodies = runStepBodies(ci());
    assert.ok(bodies.length > 0, 'ci.yml declares no run steps');

    const ENTRY_POINT = /^(\.\/mvnw|bash\s+\S+\.sh|node\s+(--test\s+)?\S+\.mjs|npm\s+\S+)\b/;
    for (const body of bodies) {
      const commands = body.split('\n').map((l) => l.trim()).filter(Boolean);
      for (const command of commands) {
        assert.match(
          command,
          ENTRY_POINT,
          `ci.yml step embeds logic instead of calling a repository-owned entry point: "${command}"`
        );
      }
      // Shell control flow in a workflow step is logic that cannot be run or
      // tested locally, which is exactly what NFR-REL-006 forbids.
      assert.doesNotMatch(body, /\b(if|for|while|case)\b\s|&&|\|\||;\s*\w/, `ci.yml step contains inline control flow:\n${body}`);
    }
  });
});
