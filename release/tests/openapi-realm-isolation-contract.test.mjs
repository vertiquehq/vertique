// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Issue #327 — the swagger plugin realm-isolation guard (ADR-0013).
 *
 * Eight example modules declare `swagger-maven-plugin-jakarta` with identical plugin
 * coordinates and an identical plugin dependency list. Maven keys its plugin realm cache
 * on exactly those, so all eight share ONE `ClassRealm` — and with it Swagger's static
 * `ModelConverters` singleton. Under `-T1C` they race on it, and a module can emit another
 * module's converter registrations (`BigDecimal` as `{"type":"number"}` rather than
 * `{"type":"string"}`).
 *
 * The fix gives each module a unique, inert `<exclusion>` in a reserved groupId, because
 * `CacheUtils.dependenciesEquals` (maven-core 3.9.9) folds exclusions into that cache key —
 * measured at 1 realm before the salts and 8 after. This test is what keeps the fix in
 * place: the salt excludes nothing real, so nothing else in the build would notice it being
 * "cleaned up" away.
 *
 * The rules are applied to synthetic fixture reactors as well as to the real tree, so a rule
 * that silently stopped rejecting anything fails here instead of comparing the tree with itself.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { readPluginDeclarations, readReactor } from '../verify-publication.mjs';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(TEST_DIR, '..', '..');

/** The plugin whose realm — and whose static `ModelConverters` — must not be shared. */
const SWAGGER_PLUGIN_ARTIFACT_ID = 'swagger-maven-plugin-jakarta';

/**
 * Reserved namespace for the realm salt. It is deliberately not a real groupId: the
 * exclusion must change the realm cache key without excluding anything.
 */
const SALT_GROUP_ID = 'dev.vertique.build.plugin-realm-salt';

/** Modules declaring the plugin. Declared here so a ninth cannot appear unnoticed. */
const SWAGGER_PLUGIN_MODULE_COUNT = 8;

/** Maven and Swagger versions the measured realm-cache behaviour was verified against. */
const PINNED_MAVEN_VERSION = '3.9.9';
const PINNED_SWAGGER_VERSION = '2.2.44';

/** What a failing module must do about it, appended to every rule failure. */
const FIX = `give that module exactly one <exclusion> of ${SALT_GROUP_ID}:<its own artifactId> on its ${SWAGGER_PLUGIN_ARTIFACT_ID} plugin dependency, as the realm-isolation comment in the sibling example poms and ADR-0013 describe`;

/** Renders rule violations into one lowercase, offender-naming failure message. */
function because(violations, fix = FIX) {
  return `${violations.join('; ')} — ${fix}`;
}

/**
 * Collects every reactor module whose `<build><plugins>` declares the swagger plugin,
 * together with the realm salts its plugin dependencies carry.
 *
 * @param {string} repoRoot reactor root directory
 * @returns {Array<{artifactId: string, relPath: string, salts: string[]}>} sorted by artifactId
 */
function readRealmSaltDeclarations(repoRoot) {
  const declarations = [];
  for (const mod of readReactor(repoRoot)) {
    const swagger = readPluginDeclarations(path.join(repoRoot, mod.relPath, 'pom.xml')).filter(
      (p) => p.artifactId === SWAGGER_PLUGIN_ARTIFACT_ID
    );
    if (swagger.length === 0) continue;
    declarations.push({
      artifactId: mod.artifactId,
      relPath: mod.relPath,
      salts: swagger
        .flatMap((p) => p.dependencies)
        .flatMap((d) => d.exclusions)
        .filter((e) => e.groupId === SALT_GROUP_ID)
        .map((e) => e.artifactId),
    });
  }
  return declarations.sort((a, b) => a.artifactId.localeCompare(b.artifactId));
}

/**
 * The realm-isolation rules themselves. The real tree and every negative fixture go through
 * this one function — duplicating the rules into the fixtures would leave the fixtures
 * proving something other than the check that guards `main`.
 *
 * Each returned bucket backs exactly one assertion below: `cardinality` →
 * everySwaggerExecutionCarriesARealmSalt, `uniqueness` → everyRealmSaltIsUnique,
 * `ownership` → everyRealmSaltNamesItsOwnModule.
 *
 * @param {Array<{artifactId: string, relPath: string, salts: string[]}>} declarations
 * @returns {{cardinality: string[], uniqueness: string[], ownership: string[]}}
 */
function checkRealmIsolation(declarations) {
  const cardinality = [];
  const uniqueness = [];
  const ownership = [];
  const byRealmKey = new Map();

  for (const d of declarations) {
    if (d.salts.length === 0) {
      cardinality.push(
        `${d.artifactId} (${d.relPath}) declares ${SWAGGER_PLUGIN_ARTIFACT_ID} with no ${SALT_GROUP_ID} exclusion`
      );
      ownership.push(
        `${d.artifactId} (${d.relPath}) carries no realm salt naming itself, so nothing in its plugin dependency list identifies the module`
      );
    } else if (d.salts.length > 1) {
      cardinality.push(
        `${d.artifactId} (${d.relPath}) carries ${d.salts.length} realm salts (${d.salts.join(', ')}) instead of exactly one`
      );
      ownership.push(
        `${d.artifactId} (${d.relPath}) carries the realm salts ${d.salts.map((s) => `"${s}"`).join(', ')} rather than exactly its own artifactId`
      );
    } else if (d.salts[0] !== d.artifactId) {
      ownership.push(
        `${d.artifactId} (${d.relPath}) carries the realm salt "${d.salts[0]}", which names a different module — a copy-pasted salt isolates nothing`
      );
    }

    // Uniqueness is asserted on the module's whole contribution to the realm cache key,
    // NOT merely on modules that happen to have a salt. Eight modules carrying no salt
    // present one identical key and share one realm — the very defect this guards — so
    // the empty contribution has to collide with itself, or the rule would pass
    // vacuously against exactly the state it exists to reject.
    const realmKey = d.salts.length ? [...d.salts].sort().join('+') : '';
    if (!byRealmKey.has(realmKey)) byRealmKey.set(realmKey, []);
    byRealmKey.get(realmKey).push(d.artifactId);
  }

  for (const [realmKey, modules] of [...byRealmKey].sort()) {
    if (modules.length > 1) {
      uniqueness.push(
        `${modules.sort().join(', ')} present the same plugin realm key (${realmKey === '' ? `no ${SALT_GROUP_ID} exclusion at all` : `salt "${realmKey}"`}), so maven hands them one shared realm and one shared ModelConverters singleton`
      );
    }
  }

  return { cardinality, uniqueness, ownership };
}

// ---------------------------------------------------------------------------
// Synthetic fixture reactors
//
// Written to os.tmpdir() and removed in a finally, so no deliberately-broken
// pom is ever committed where a build could pick it up.
// ---------------------------------------------------------------------------

/**
 * Minimal POM writer. `salts === null` writes a module that declares no swagger plugin;
 * `salts === []` writes one that declares it with no exclusion at all.
 */
function pom(dir, { artifactId, packaging = 'jar', modules = [], salts = null }) {
  mkdirSync(dir, { recursive: true });
  const moduleXml = modules.length
    ? `<modules>${modules.map((m) => `<module>${m}</module>`).join('')}</modules>`
    : '';
  const exclusionXml = (salts ?? []).length
    ? `<exclusions>${salts
        .map((s) => `<exclusion><groupId>${SALT_GROUP_ID}</groupId><artifactId>${s}</artifactId></exclusion>`)
        .join('')}</exclusions>`
    : '';
  const buildXml =
    salts === null
      ? ''
      : `<build><plugins><plugin>
      <groupId>io.swagger.core.v3</groupId>
      <artifactId>${SWAGGER_PLUGIN_ARTIFACT_ID}</artifactId>
      <dependencies>
        <dependency>
          <groupId>dev.vertique</groupId>
          <artifactId>vertique-rest-openapi-plugin</artifactId>
          <version>\${project.version}</version>
          ${exclusionXml}
        </dependency>
      </dependencies>
    </plugin></plugins></build>`;

  writeFileSync(
    path.join(dir, 'pom.xml'),
    `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.vertique</groupId>
  <artifactId>${artifactId}</artifactId>
  <packaging>${packaging}</packaging>
  ${moduleXml}
  ${buildXml}
</project>
`
  );
}

/**
 * Builds a synthetic reactor of plugin-declaring modules.
 * @param {Array<{artifactId: string, salts: string[]}>} modules
 * @returns {string} the reactor root directory, for the caller to remove
 */
function fixtureReactor(modules) {
  const root = mkdtempSync(path.join(tmpdir(), 'vertique-realm-'));
  pom(root, {
    artifactId: 'vertique-parent',
    packaging: 'pom',
    modules: modules.map((m) => m.artifactId),
  });
  for (const m of modules) {
    pom(path.join(root, m.artifactId), { artifactId: m.artifactId, salts: m.salts });
  }
  return root;
}

/** The eight real example modules, each correctly salted — the shape the fix produces. */
const CORRECTLY_SALTED = [
  'vertique-example-custom-response',
  'vertique-example-db',
  'vertique-example-hello',
  'vertique-example-localization',
  'vertique-example-services',
  'vertique-example-services-codegen',
  'vertique-example-sse',
  'vertique-example-websocket',
].map((artifactId) => ({ artifactId, salts: [artifactId] }));

describe('SwaggerPluginRealmIsolationTest', () => {
  it('everySwaggerExecutionCarriesARealmSalt', () => {
    const { cardinality } = checkRealmIsolation(readRealmSaltDeclarations(REPO_ROOT));
    assert.deepEqual(cardinality, [], because(cardinality));
  });

  it('everyRealmSaltIsUnique', () => {
    // The invariant that actually prevents realm sharing: two identical dependency lists
    // are one cache key, however well-commented each pom is.
    const { uniqueness } = checkRealmIsolation(readRealmSaltDeclarations(REPO_ROOT));
    assert.deepEqual(uniqueness, [], because(uniqueness));
  });

  it('everyRealmSaltNamesItsOwnModule', () => {
    // Uniqueness alone would accept a shuffled set of salts; naming catches the far more
    // likely failure — a pom copied wholesale into a new example module.
    const { ownership } = checkRealmIsolation(readRealmSaltDeclarations(REPO_ROOT));
    assert.deepEqual(ownership, [], because(ownership));
  });

  it('saltNamespaceMatchesNoReactorArtifact', () => {
    // The salt is a cache-key nudge, not dependency hygiene: it must exclude nothing. The
    // artifactId side deliberately names a real module, so inertness rests on the groupId.
    const colliding = readReactor(REPO_ROOT)
      .filter((m) => m.groupId === SALT_GROUP_ID || (m.groupId ?? '').startsWith(`${SALT_GROUP_ID}.`))
      .map((m) => `${m.artifactId} (${m.relPath}) publishes under ${m.groupId}`);
    assert.deepEqual(
      colliding,
      [],
      because(
        colliding,
        `the reserved salt namespace ${SALT_GROUP_ID} must match no reactor artifact, or the exclusion would drop a real dependency; move that module to another groupId or reserve a different salt namespace`
      )
    );
  });

  it('swaggerPluginModuleCountMatchesTheDeclaredLiteral', () => {
    const declarations = readRealmSaltDeclarations(REPO_ROOT);
    const found = declarations.map((d) => d.artifactId).join(', ');
    // Asserted against the constant AND against a literal, deliberately: a constant bumped
    // to match a new module would move the contract silently.
    assert.equal(
      declarations.length,
      SWAGGER_PLUGIN_MODULE_COUNT,
      `${declarations.length} modules declare ${SWAGGER_PLUGIN_ARTIFACT_ID} (${found}), not the declared ${SWAGGER_PLUGIN_MODULE_COUNT} — salt the new module and bump SWAGGER_PLUGIN_MODULE_COUNT deliberately`
    );
    assert.equal(
      declarations.length,
      8,
      `${declarations.length} modules declare ${SWAGGER_PLUGIN_ARTIFACT_ID} (${found}), not 8 — salt the new module and bump both the constant and this literal deliberately`
    );
  });

  it('pinnedMavenAndSwaggerVersionsAreUnchanged', () => {
    // Scalar property lookups, not structure: unlike a plugin's <dependencies>, neither of
    // these tag names is ambiguous, so there is nothing for a parser to disambiguate.
    const wrapper = readFileSync(path.join(REPO_ROOT, '.mvn', 'wrapper', 'maven-wrapper.properties'), 'utf8');
    const maven = /apache-maven-(\d+\.\d+\.\d+)-bin\.zip/.exec(wrapper);
    assert.ok(maven, 'the maven wrapper declares no apache-maven-<version>-bin.zip distributionUrl');

    const rootPom = readFileSync(path.join(REPO_ROOT, 'pom.xml'), 'utf8');
    const swagger = /<swagger-core\.version>([^<]+)<\/swagger-core\.version>/.exec(rootPom);
    assert.ok(swagger, 'the root pom declares no <swagger-core.version> property');

    const upgraded =
      'the realm salt works only because this maven version folds exclusions into its plugin realm cache key ' +
      '(CacheUtils.dependenciesEquals), and only matters because this swagger version keeps ModelConverters ' +
      'static — an upgrade must re-run the runtime realm-count measurement (1 shared realm before the salts, ' +
      '8 after) before this pin is moved, and update ADR-0013 with the result';
    assert.equal(
      maven[1],
      PINNED_MAVEN_VERSION,
      `maven moved from ${PINNED_MAVEN_VERSION} to ${maven[1]}: ${upgraded}`
    );
    assert.equal(
      swagger[1],
      PINNED_SWAGGER_VERSION,
      `swagger-core moved from ${PINNED_SWAGGER_VERSION} to ${swagger[1]}: ${upgraded}`
    );
  });

  it('rejectsRealmSaltDrift', () => {
    const roots = [];
    try {
      // Baseline: the shape the fix produces passes every rule, so the three rules above
      // are red for a reason and not merely unsatisfiable.
      const base = fixtureReactor(CORRECTLY_SALTED);
      roots.push(base);
      const baseDeclarations = readRealmSaltDeclarations(base);
      assert.equal(baseDeclarations.length, SWAGGER_PLUGIN_MODULE_COUNT, 'baseline fixture must declare eight modules');
      assert.deepEqual(
        checkRealmIsolation(baseDeclarations),
        { cardinality: [], uniqueness: [], ownership: [] },
        'a correctly salted reactor must pass every realm-isolation rule'
      );

      // Two modules sharing one salt: identical dependency lists, so identical realm keys.
      const shared = fixtureReactor([
        ...CORRECTLY_SALTED.slice(0, 7),
        { artifactId: 'vertique-example-websocket', salts: ['vertique-example-sse'] },
      ]);
      roots.push(shared);
      const sharedResult = checkRealmIsolation(readRealmSaltDeclarations(shared));
      assert.notDeepEqual(sharedResult.uniqueness, [], 'a duplicated realm salt must be rejected');
      assert.match(sharedResult.uniqueness.join('\n'), /vertique-example-sse/);
      assert.match(sharedResult.uniqueness.join('\n'), /vertique-example-websocket/);

      // No salt at all: the pre-fix state, which is exactly what must never come back.
      const unsalted = fixtureReactor([
        ...CORRECTLY_SALTED.slice(0, 7),
        { artifactId: 'vertique-example-websocket', salts: [] },
      ]);
      roots.push(unsalted);
      const unsaltedResult = checkRealmIsolation(readRealmSaltDeclarations(unsalted));
      assert.notDeepEqual(unsaltedResult.cardinality, [], 'a missing realm salt must be rejected');
      assert.match(unsaltedResult.cardinality.join('\n'), /vertique-example-websocket/);

      // A salt naming another module — unique, so only the ownership rule can see it.
      const misnamed = fixtureReactor([
        ...CORRECTLY_SALTED.slice(0, 7),
        { artifactId: 'vertique-example-websocket', salts: ['vertique-example-nowhere'] },
      ]);
      roots.push(misnamed);
      const misnamedResult = checkRealmIsolation(readRealmSaltDeclarations(misnamed));
      assert.notDeepEqual(misnamedResult.ownership, [], 'a salt naming another module must be rejected');
      assert.match(misnamedResult.ownership.join('\n'), /vertique-example-websocket/);
      assert.deepEqual(misnamedResult.uniqueness, [], 'a misnamed but unique salt must fail ownership, not uniqueness');

      // A ninth declaring module, added unsalted — the drift the literal count exists for.
      const ninth = fixtureReactor([...CORRECTLY_SALTED, { artifactId: 'vertique-example-ninth', salts: [] }]);
      roots.push(ninth);
      const ninthDeclarations = readRealmSaltDeclarations(ninth);
      assert.notEqual(
        ninthDeclarations.length,
        SWAGGER_PLUGIN_MODULE_COUNT,
        'a ninth plugin-declaring module must not match the declared module count'
      );
      const ninthResult = checkRealmIsolation(ninthDeclarations);
      assert.notDeepEqual(ninthResult.cardinality, [], 'a ninth unsalted module must be rejected');
      assert.match(ninthResult.cardinality.join('\n'), /vertique-example-ninth/);
    } finally {
      for (const r of roots) rmSync(r, { recursive: true, force: true });
    }
  });
});
