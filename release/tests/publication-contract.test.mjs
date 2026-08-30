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
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
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
const DEFAULT_MANAGED = ['vertique-core', 'vertique-rest-core', 'vertique-json'];
const DEFAULT_ARCHETYPES = [
  'vertique-archetype-rest',
  'vertique-archetype-services',
  'vertique-archetype-rest-postgresql',
];

function fixtureReactor(overrides = {}) {
  const root = mkdtempSync(path.join(tmpdir(), 'vertique-pub-'));
  const managed = overrides.managed ?? DEFAULT_MANAGED;
  const archetypes = overrides.archetypes ?? DEFAULT_ARCHETYPES;
  const extraJars = overrides.extraJars ?? [];
  // BOM entries for modules that already exist elsewhere in the reactor, so a
  // case can add an existing module to the BOM without also creating a
  // second module of the same artifactId at the reactor root.
  const bomOnly = overrides.bomOnly ?? [];

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
  pom(path.join(root, 'vertique-bom'), {
    artifactId: 'vertique-bom',
    packaging: 'pom',
    managed: [...managed, ...bomOnly],
  });
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

/**
 * Policy pinned to {@link fixtureReactor}'s BASELINE inventory: 3 fixed +
 * 3 BOM-managed + 3 archetypes. Drift cases mutate the reactor and verify
 * against this unchanged policy, which is exactly the real failure mode —
 * someone changing the reactor without deliberately updating the policy.
 */
function fixturePolicy() {
  const ids = ['vertique-parent', 'vertique-app-parent', 'vertique-bom', ...DEFAULT_MANAGED, ...DEFAULT_ARCHETYPES];
  return {
    ...loadPolicy(POLICY_PATH),
    expectedPublishableGavCount: ids.length,
    expectedPublishableGavs: ids.map((a) => `dev.vertique:${a}`).sort(),
  };
}

describe('PublicPublicationInventoryTest', () => {
  it('everyReactorModuleHasExactlyOnePublicationClassification', () => {
    const policy = loadPolicy(POLICY_PATH);
    const inventory = deriveInventory(REPO_ROOT, policy);

    // Every reactor module is classified exactly once, with no overlap and no gap.
    const publishedIds = inventory.published.map((u) => u.artifactId);

    // Partition on relPath — the only stable per-module identity — and assert
    // the counts sum. Comparing a Set of artifactIds against modules.length
    // would only fail when two modules share an artifactId, which is not the
    // property this test is named for.
    const publishedPaths = inventory.published.map((u) => u.relPath);
    const skippedPaths = inventory.skipped.map((u) => u.relPath);
    assert.equal(
      inventory.published.length + inventory.skipped.length,
      inventory.modules.length,
      'published + skipped must sum to the reactor module count'
    );
    assert.deepEqual(
      publishedPaths.filter((p) => skippedPaths.includes(p)),
      [],
      'no module may be both published and skipped'
    );
    assert.equal(
      new Set([...publishedPaths, ...skippedPaths]).size,
      inventory.modules.length,
      'every reactor module must be classified exactly once'
    );

    // No module may fall through to the catch-all: an unrecognised module is drift.
    assert.deepEqual(
      inventory.skipped.filter((u) => u.reason === 'unclassified'),
      [],
      'every skipped module must match a declared skip rule'
    );

    // The derived allowlist is exactly the declared inventory.
    assert.equal(inventory.published.length, policy.expectedPublishableGavCount);
    assert.equal(inventory.published.length, 111);
    for (const fixed of ['vertique-parent', 'vertique-app-parent', 'vertique-bom']) {
      assert.ok(publishedIds.includes(fixed), `${fixed} must be published`);
    }
    // Assert archetype IDENTITY, not merely a count of three — renaming an
    // archetype must fail, and a count assertion would not notice.
    assert.deepEqual(
      inventory.published
        .filter((u) => u.packaging === 'maven-archetype')
        .map((u) => u.artifactId)
        .sort(),
      ['vertique-archetype-rest', 'vertique-archetype-rest-postgresql', 'vertique-archetype-services']
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

      // COUNT-NEUTRAL swap: dropping one BOM GAV while adding another leaves
      // the total at 9, so a cardinality check alone cannot see it. FR-REL-032
      // requires BOM drift to fail regardless of count.
      const swapped = fixtureReactor({
        managed: ['vertique-core', 'vertique-rest-core', 'vertique-totally-new'],
      });
      roots.push(swapped);
      const swap = verifyInventory(swapped, fixturePolicy());
      assert.equal(swap.ok, false, 'a count-neutral BOM swap must fail verification');
      assert.match(swap.errors.join('\n'), /vertique-totally-new/i, 'the unexpected GAV must be named');
      assert.match(swap.errors.join('\n'), /vertique-json/i, 'the missing GAV must be named');

      // A renamed archetype is also count-neutral.
      const renamedArchetype = fixtureReactor({
        archetypes: ['vertique-archetype-rest', 'vertique-archetype-services', 'vertique-archetype-renamed'],
      });
      roots.push(renamedArchetype);
      assert.equal(
        verifyInventory(renamedArchetype, fixturePolicy()).ok,
        false,
        'a renamed archetype must fail verification'
      );

      // A BOM-listed EXAMPLE must never become publishable: deny rules are
      // authoritative and evaluated before the publish rules, so a BOM listing
      // is not sufficient to publish something FR-REL-030 does not enumerate.
      const bomListedExample = fixtureReactor({ bomOnly: ['vertique-example-hello'] });
      roots.push(bomListedExample);
      const exampleInv = deriveInventory(bomListedExample, fixturePolicy());
      assert.ok(
        !exampleInv.published.some((u) => u.artifactId === 'vertique-example-hello'),
        'a BOM-listed example must not be classified as publishable'
      );
      assert.equal(
        exampleInv.skipped.find((u) => u.artifactId === 'vertique-example-hello')?.reason,
        'example',
        'a BOM-listed example must still be skipped as an example'
      );
    } finally {
      for (const r of roots) rmSync(r, { recursive: true, force: true });
    }
  });
});

describe('PomReaderTest', () => {
  /** Writes `xml` to a scratch pom and returns the parsed result. */
  function parse(xml) {
    const dir = mkdtempSync(path.join(tmpdir(), 'vertique-pom-'));
    try {
      writeFileSync(path.join(dir, 'pom.xml'), xml);
      return readPom(path.join(dir, 'pom.xml'));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }

  it('readsTheModuleOwnCoordinatesNotItsParents', () => {
    // The reason this is a parser and not a regex: <parent> carries its own
    // <artifactId>, and a first-match scan would read the parent's.
    const pom = parse(`<project xmlns="http://maven.apache.org/POM/4.0.0">
        <parent><groupId>dev.vertique</groupId><artifactId>vertique-parent</artifactId><version>1</version></parent>
        <artifactId>vertique-core</artifactId>
        <packaging>jar</packaging>
      </project>`);
    assert.equal(pom.artifactId, 'vertique-core');
    assert.equal(pom.packaging, 'jar');
    // groupId falls back to the parent's when the module declares none.
    assert.equal(pom.groupId, 'dev.vertique');
  });

  it('ignoresCommentedOutAndCdataContent', () => {
    const pom = parse(`<project xmlns="http://maven.apache.org/POM/4.0.0">
        <artifactId>vertique-thing</artifactId>
        <packaging>pom</packaging>
        <description><![CDATA[ranges where a < b, and </description><artifactId>forged</artifactId>]]></description>
        <modules><module>real</module><!--<module>commented</module>--></modules>
      </project>`);
    // CDATA is discarded rather than re-injected: unwrapping it would let its
    // contents be scanned as markup, dropping <packaging> and the <modules>
    // subtree — modules that vanish are never classified at all.
    assert.equal(pom.artifactId, 'vertique-thing');
    assert.equal(pom.packaging, 'pom');
    assert.deepEqual(pom.modules, ['real']);
  });

  it('readsOnlyProjectLevelManagedDependencies', () => {
    // A plugin's own <dependencies> are not dependency management.
    const pom = parse(`<project xmlns="http://maven.apache.org/POM/4.0.0">
        <artifactId>vertique-bom</artifactId><packaging>pom</packaging>
        <dependencyManagement><dependencies>
          <dependency><groupId>dev.vertique</groupId><artifactId>vertique-core</artifactId></dependency>
        </dependencies></dependencyManagement>
        <build><plugins><plugin><artifactId>some-plugin</artifactId><dependencies>
          <dependency><groupId>dev.vertique</groupId><artifactId>not-managed</artifactId></dependency>
        </dependencies></plugin></plugins></build>
      </project>`);
    assert.deepEqual(pom.managed.map((d) => d.artifactId), ['vertique-core']);
  });

  it('refusesProfileScopedModulesAndDependencyManagement', () => {
    // Profile-conditional declarations cannot be resolved build-independently.
    // Refusing beats silently returning a smaller reactor, because hidden
    // modules never reach the unclassified fail-closed check at all.
    assert.throws(
      () =>
        parse(`<project xmlns="http://maven.apache.org/POM/4.0.0">
          <artifactId>vertique-parent</artifactId><packaging>pom</packaging>
          <profiles><profile><id>extra</id><modules><module>hidden</module></modules></profile></profiles>
        </project>`),
      /<profile> declares <modules>/
    );
  });

  it('treatsAnEmptyPackagingElementAsTheMavenDefault', () => {
    const pom = parse(`<project xmlns="http://maven.apache.org/POM/4.0.0">
        <groupId>dev.vertique</groupId><artifactId>vertique-core</artifactId><packaging></packaging>
      </project>`);
    assert.equal(pom.packaging, 'jar');
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

  it('everyEmptyJavadocExceptionIsAModuleWithNoJavaApi', () => {
    // FR-REL-034 permits an absent Javadoc payload only where a module has no
    // Java API. Without this proof the exception list would be a way to
    // silence a genuinely missing Javadoc for a module that does have one.
    const policy = loadPolicy(POLICY_PATH);
    const inventory = deriveInventory(REPO_ROOT, policy);
    const exceptions = policy.payloadPolicy.emptyJavadocExceptions ?? [];

    for (const artifactId of exceptions) {
      const module = inventory.published.find((u) => u.artifactId === artifactId);
      assert.ok(module, `empty-Javadoc exception "${artifactId}" is not a published module`);

      const sourceDir = path.join(REPO_ROOT, module.relPath, 'src', 'main', 'java');
      const javaFiles = existsSync(sourceDir)
        ? execFileSync('find', [sourceDir, '-name', '*.java'], { encoding: 'utf8' }).split('\n').filter(Boolean)
        : [];
      assert.deepEqual(
        javaFiles,
        [],
        `"${artifactId}" declares an empty-Javadoc exception but has ${javaFiles.length} Java source(s); it must publish a real Javadoc JAR`
      );
    }
  });
});

/** Returns a POM's direct parent artifact ID, if it declares one. */
function declaredParentArtifactId(pomRelPath) {
  const xml = readFileSync(path.join(REPO_ROOT, pomRelPath), 'utf8');
  const parent = /<parent>\s*([\s\S]*?)<\/parent>/.exec(xml);
  return parent ? /<artifactId>\s*([^<]+?)\s*<\/artifactId>/.exec(parent[1])?.[1] : undefined;
}

/** Returns every reactor module keyed by its artifact ID. */
function reactorModulesByArtifactId() {
  const policy = loadPolicy(POLICY_PATH);
  return new Map(deriveInventory(REPO_ROOT, policy).modules.map((module) => [module.artifactId, module]));
}

describe('CentralPomMetadataContractTest', () => {
  const CENTRAL_METADATA = {
    projectUrl: 'https://vertique.dev',
    licenseName: 'European Union Public Licence v. 1.2',
    licenseUrl: 'https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12',
    organizationName: 'Koivisto Capital Oy',
    developerEmail: 'releases@vertique.dev',
    scmUrl: 'https://github.com/vertiquehq/vertique',
  };

  function assertCentralMetadata(pomRelPath) {
    const xml = readFileSync(path.join(REPO_ROOT, pomRelPath), 'utf8');
    assert.match(xml, new RegExp(`<url>${CENTRAL_METADATA.projectUrl}</url>`), `${pomRelPath} declares the project URL`);
    assert.match(
      xml,
      new RegExp(`<name>${CENTRAL_METADATA.licenseName}</name>`),
      `${pomRelPath} declares the EUPL-1.2 license name`
    );
    assert.match(
      xml,
      new RegExp(`<url>${CENTRAL_METADATA.licenseUrl}</url>`),
      `${pomRelPath} declares the EUPL-1.2 license URL`
    );
    assert.match(
      xml,
      new RegExp(`<organization>\\s*<name>${CENTRAL_METADATA.organizationName}</name>\\s*<url>${CENTRAL_METADATA.projectUrl}</url>`),
      `${pomRelPath} declares the organization identity`
    );
    assert.match(
      xml,
      new RegExp(`<developer>\\s*<id>vertique-release</id>\\s*<name>Vertique Release</name>\\s*<email>${CENTRAL_METADATA.developerEmail}</email>\\s*<organization>${CENTRAL_METADATA.organizationName}</organization>`),
      `${pomRelPath} declares the release developer identity`
    );
    assert.match(
      xml,
      new RegExp(`<email>${CENTRAL_METADATA.developerEmail}</email>`),
      `${pomRelPath} declares the release contact`
    );
    assert.match(xml, new RegExp(`<url>${CENTRAL_METADATA.scmUrl}</url>`), `${pomRelPath} declares the SCM URL`);
  }

  it('everyPublishedUnitInheritsCompleteCentralMetadata', () => {
    // These are the two independently invokable public parent POMs. All other
    // published units inherit the root parent through their Maven parent chain.
    assertCentralMetadata('pom.xml');
    assertCentralMetadata('vertique-app-parent/pom.xml');

    const policy = loadPolicy(POLICY_PATH);
    const inventory = deriveInventory(REPO_ROOT, policy);
    const modulesByArtifactId = reactorModulesByArtifactId();

    for (const unit of inventory.published) {
      if (unit.artifactId === 'vertique-parent' || unit.artifactId === 'vertique-app-parent') continue;

      let current = unit;
      const seen = new Set();
      while (current.artifactId !== 'vertique-parent') {
        assert.ok(!seen.has(current.artifactId), `${unit.artifactId} has a cyclic parent chain`);
        seen.add(current.artifactId);
        const parentArtifactId = declaredParentArtifactId(path.join(current.relPath, 'pom.xml'));
        assert.ok(parentArtifactId, `${unit.artifactId} declares no parent POM`);
        current = modulesByArtifactId.get(parentArtifactId);
        assert.ok(current, `${unit.artifactId} parent ${parentArtifactId} is not in the reactor`);
      }
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

  it('retainsSurefireAndFailsafeReportsAfterFailureOrCancellation', () => {
    // Maven redirects test output to these directories, so the artifact is the
    // durable diagnostic record for CI-only flakes and cancelled runs.
    const yaml = ci();
    const buildStep = yaml.indexOf('      - name: Build and test\n');
    const reportStep = yaml.indexOf('      - name: Upload test reports after build interruption\n');
    assert.ok(buildStep >= 0, 'ci.yml must run Maven verification');
    assert.ok(reportStep > buildStep, 'test-report retention must run after Maven verification');
    assert.match(
      yaml,
      /^      - name: Upload test reports after build interruption\n        if: \$\{\{ always\(\) && \(failure\(\) \|\| cancelled\(\)\) \}\}\n        uses: actions\/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7\.0\.1\n        with:\n          name: test-reports\n          path: \|\n            \*\*\/target\/surefire-reports\/\*\*\n            \*\*\/target\/failsafe-reports\/\*\*\n          if-no-files-found: ignore$/m,
      'ci.yml must retain Surefire and Failsafe diagnostics after failure or cancellation'
    );
  });
});
