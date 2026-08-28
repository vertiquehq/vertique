// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Coverage aggregate inventory.
 *
 * vertique-coverage-report produces the one merged JaCoCo XML that the CI
 * diff-coverage gate and the coverage trend consume. Its dependency list IS
 * the set of modules that coverage measures, so — exactly like the
 * publication inventory — it must be derived from the BOM, never curated ad
 * hoc: adding a module to the BOM without adding it here would silently drop
 * that module out of every coverage number, and this test exists to make that
 * a red build instead.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { loadPolicy, readPom } from '../verify-publication.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const POLICY_PATH = path.join(REPO_ROOT, 'release', 'publication-policy.json');
const AGGREGATE_POM = path.join(REPO_ROOT, 'vertique-coverage-report', 'pom.xml');

function firstPartyKey(prefix) {
  return (d) => d.groupId === prefix || d.groupId.startsWith(`${prefix}.`);
}

describe('CoverageAggregateContractTest', () => {
  it('aggregateDependsOnExactlyTheBomManagedFirstPartyArtifacts', () => {
    const policy = loadPolicy(POLICY_PATH);
    const { pomPath, groupIdPrefix } = policy.publish.bomManaged;

    const bomKeys = readPom(path.join(REPO_ROOT, pomPath))
      .managed.filter(firstPartyKey(groupIdPrefix))
      .map((d) => `${d.groupId}:${d.artifactId}`)
      .sort();
    const aggregateKeys = readPom(AGGREGATE_POM)
      .dependencies.map((d) => `${d.groupId}:${d.artifactId}`)
      .sort();

    assert.deepEqual(
      aggregateKeys,
      bomKeys,
      'vertique-coverage-report/pom.xml dependencies must equal the BOM-managed ' +
        'first-party artifact set — a BOM change moves this list in the same commit'
    );
  });

  it('aggregateDependencyCountMatchesTheDeclaredLiteral', () => {
    // Deliberately a literal, mirroring expectedPublishableGavCount: a BOM
    // edit cannot move the coverage inventory without touching this number.
    assert.equal(readPom(AGGREGATE_POM).dependencies.length, 92);
  });

  it('aggregateIsAReactorModuleAndStaysUnpublishable', () => {
    const root = readPom(path.join(REPO_ROOT, 'pom.xml'));
    assert.ok(
      root.modules.includes('vertique-coverage-report'),
      'root pom must list vertique-coverage-report so verify always produces the aggregate'
    );

    const aggregate = readPom(AGGREGATE_POM);
    assert.equal(aggregate.packaging, 'pom', 'pom packaging is what classifies it as a skipped aggregator');
    const policy = loadPolicy(POLICY_PATH);
    const bomKeys = new Set(
      readPom(path.join(REPO_ROOT, policy.publish.bomManaged.pomPath))
        .managed.map((d) => `${d.groupId}:${d.artifactId}`)
    );
    assert.ok(
      !bomKeys.has('dev.vertique:vertique-coverage-report'),
      'the coverage aggregate must never become BOM-managed (that would publish it)'
    );
  });
});
