// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(TEST_DIR, '..', '..');
const MASTER_PATH = path.join(REPO_ROOT, 'release', 'mcp-public-inventory.json');
const POLICY_PATH = path.join(REPO_ROOT, 'release', 'publication-policy.json');

const PRODUCT_INVENTORIES = [
  'vertique-mcp/vertique-mcp-core/src/test/resources/mcp/architecture/mcp-core-public-inventory.json',
  'vertique-mcp/vertique-mcp-server/src/test/resources/mcp/architecture/mcp-server-public-inventory.json',
  'vertique-rest/vertique-rest-security/src/test/resources/mcp/architecture/rest-security-public-inventory.json',
  'vertique-codegen/vertique-codegen-mcp/src/test/resources/mcp/architecture/codegen-mcp-public-inventory.json',
  'vertique-micrometer/vertique-micrometer-mcp/src/test/resources/mcp/architecture/micrometer-mcp-public-inventory.json',
  'vertique-opentelemetry/vertique-opentelemetry-mcp/src/test/resources/mcp/architecture/opentelemetry-mcp-public-inventory.json',
];

function readJson(relativePath) {
  return JSON.parse(readFileSync(path.join(REPO_ROOT, relativePath), 'utf8'));
}

test('mcp public inventory union equals the master release record', () => {
  const master = readJson('release/mcp-public-inventory.json');
  const productUnion = PRODUCT_INVENTORIES.map((source) => ({ source, ...readJson(source) }));
  const modules = productUnion.map(({ module }) => module);
  const policy = readJson('release/publication-policy.json');
  const publishedMcpArtifacts = policy.expectedPublishableGavs
    .map((gav) => gav.split(':')[1])
    .filter((artifact) => artifact.includes('-mcp') || artifact.startsWith('vertique-mcp-'))
    .sort();

  assert.equal(master.schemaVersion, 1);
  assert.equal(master.comparisonBasis, 'full-generic-signature');
  assert.deepEqual(master.modules, productUnion, 'local inventory union must equal the master entry-for-entry');
  assert.equal(new Set(modules).size, modules.length, 'each guarded product module must occur exactly once');
  assert.ok(productUnion.every(({ direction }) => direction === 'exact'), 'every product guard must be exact');
  assert.deepEqual(
    modules.filter((module) => publishedMcpArtifacts.includes(module)).toSorted(),
    publishedMcpArtifacts,
    'every published product MCP artifact must have exactly one guarded module entry'
  );
  assert.ok(modules.includes('vertique-rest-security'), 'the shared authorization seam must remain guarded');
  assert.deepEqual(master.excludedModules, [
    {
      module: 'vertique-rest-core',
      reason:
        'Its only MCP-001 surface change was completed by T001 and is part of the T004 replacement-task baseline; no replacement task alters it.',
    },
  ]);
  assert.deepEqual(master.delegatedModules, [
    {
      module: 'vertique-audit-mcp',
      delegated: 'enterprise-build',
      guard: 'dev.vertique.audit.mcp.McpAuditArchitectureTest',
    },
  ]);
});
