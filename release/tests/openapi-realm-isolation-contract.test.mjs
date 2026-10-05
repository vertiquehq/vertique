// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Guards the swagger-maven-plugin-jakarta realm isolation (vertique-dev#400, #327, #177).
 *
 * Maven keys its plugin ClassRealm cache on the plugin coordinates and its dependency list,
 * exclusions included. Example modules whose plugin dependency lists are identical therefore share
 * one realm, and with it Swagger's static `ModelConverters` singleton, which races under `-T1C`.
 * Each example that declares the plugin carries one inert `<exclusion>` salt named after its own
 * artifactId so every module gets its own realm. A salt excludes nothing, so it looks like cruft;
 * this test fails if one is removed, duplicated or misnamed.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const EXAMPLES_DIR = path.join(REPO_ROOT, 'examples');
const SALT_GROUP = 'dev.vertique.build.plugin-realm-salt';

/** Returns the plugin `<dependencies>` block of the swagger plugin, or null if not declared. */
export function swaggerPluginDependencies(pomXml) {
  const plugin = pomXml.match(
    /<plugin>\s*<groupId>io\.swagger\.core\.v3<\/groupId>\s*<artifactId>swagger-maven-plugin-jakarta<\/artifactId>[\s\S]*?<\/plugin>/
  );
  if (!plugin) return null;
  const deps = plugin[0].match(/<dependencies>([\s\S]*?)<\/dependencies>/);
  return deps ? deps[1].replace(/<!--[\s\S]*?-->/g, '') : '';
}

/** Own artifactId: the first top-level artifactId after the `<parent>` block. */
export function ownArtifactId(pomXml) {
  const withoutParent = pomXml.replace(/<parent>[\s\S]*?<\/parent>/, '');
  return withoutParent.match(/<artifactId>([^<]+)<\/artifactId>/)[1];
}

export function saltsIn(depsXml) {
  return [
    ...depsXml.matchAll(
      new RegExp(
        `<exclusion>\\s*<groupId>${SALT_GROUP.replace(/\./g, '\\.')}</groupId>\\s*<artifactId>([^<]+)</artifactId>\\s*</exclusion>`,
        'g'
      )
    ),
  ].map((m) => m[1]);
}

/** Whitespace-normalised dependency list: a proxy for Maven's realm cache key. */
export function realmKey(depsXml) {
  return depsXml.replace(/\s+/g, '');
}

function swaggerExamples() {
  return readdirSync(EXAMPLES_DIR)
    .map((name) => path.join(EXAMPLES_DIR, name, 'pom.xml'))
    .filter((p) => existsSync(p))
    .map((p) => ({ pom: p, xml: readFileSync(p, 'utf8') }))
    .map((m) => ({ ...m, deps: swaggerPluginDependencies(m.xml) }))
    .filter((m) => m.deps !== null);
}

describe('swagger plugin realm isolation', () => {
  const modules = swaggerExamples();

  it('finds the example modules that declare the swagger plugin', () => {
    assert.ok(modules.length >= 8, `expected at least 8 modules, found ${modules.length}`);
  });

  it('gives every module exactly one salt named after its own artifactId', () => {
    for (const m of modules) {
      assert.deepEqual(
        saltsIn(m.deps),
        [ownArtifactId(m.xml)],
        `${path.relative(REPO_ROOT, m.pom)} must carry exactly one realm salt exclusion for itself`
      );
    }
  });

  it('gives every module a distinct plugin dependency list', () => {
    const keys = modules.map((m) => realmKey(m.deps));
    assert.equal(new Set(keys).size, keys.length, 'two modules share a plugin realm cache key');
  });

  it('rejects a module without a salt (synthetic)', () => {
    const deps = '<dependency><groupId>dev.vertique</groupId><artifactId>x</artifactId></dependency>';
    assert.deepEqual(saltsIn(deps), []);
  });
});
