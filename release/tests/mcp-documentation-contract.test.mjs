// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(TEST_DIR, '..', '..');
const MODULE_INDEX = 'docs/modules.md';
const MODULE_REFERENCES = new Map([
  ['vertique-codegen-mcp', 'vertique-codegen/vertique-codegen-mcp/src/main/resources/META-INF/vertique/module.md'],
  ['vertique-mcp-core', 'vertique-mcp/vertique-mcp-core/src/main/resources/META-INF/vertique/module.md'],
  ['vertique-mcp-server', 'vertique-mcp/vertique-mcp-server/src/main/resources/META-INF/vertique/module.md'],
  ['vertique-micrometer-mcp', 'vertique-micrometer/vertique-micrometer-mcp/src/main/resources/META-INF/vertique/module.md'],
  ['vertique-opentelemetry-mcp', 'vertique-opentelemetry/vertique-opentelemetry-mcp/src/main/resources/META-INF/vertique/module.md'],
  ['vertique-rest-security', 'vertique-rest/vertique-rest-security/src/main/resources/META-INF/vertique/module.md'],
]);
const DOCUMENTATION = [
  'README.md',
  'docs/architecture.md',
  MODULE_INDEX,
  ...MODULE_REFERENCES.values(),
];
const DEFERRED_CLAIM = /\b(?:supports?|implements?|provides?|offers?)\s+(?:an?\s+)?(?:stdio|sessions?|oauth discovery|rate limit(?:ing)?|rich content|progress notifications?|resources?|prompts?)\b/i;

function read(relativePath) {
  return readFileSync(path.join(REPO_ROOT, relativePath), 'utf8');
}

function relativeLinks(relativePath, markdown) {
  const links = markdown.matchAll(/\[[^\]]+\]\(([^)]+)\)/g);
  return [...links]
    .map(([, destination]) => destination.replace(/^<|>$/g, '').split('#')[0])
    .filter((destination) => destination && !/^[a-z]+:/i.test(destination))
    .map((destination) => path.resolve(REPO_ROOT, path.dirname(relativePath), decodeURIComponent(destination)));
}

test('mcp documentation rejects deferred capability claims and broken links', () => {
  const moduleIndex = read(MODULE_INDEX);
  for (const [artifact, reference] of MODULE_REFERENCES) {
    const rowPrefix = '| `' + artifact + '` |';
    const matchingRows = moduleIndex.split('\n').filter((line) => line.startsWith(rowPrefix));
    assert.equal(matchingRows.length, 1, `${artifact} must have exactly one canonical module-index row`);
    assert.ok(matchingRows[0].includes(`../${reference}`), `${artifact} row must link to its module reference`);
  }

  for (const relativePath of DOCUMENTATION) {
    const markdown = read(relativePath);
    assert.equal(DEFERRED_CLAIM.test(markdown), false, `${relativePath} claims a deferred MCP capability`);
    for (const destination of relativeLinks(relativePath, markdown)) {
      assert.ok(existsSync(destination), `${relativePath} has a broken link to ${destination}`);
    }
  }

  const releaseClaims = `${read('README.md')}\n${read('docs/architecture.md')}`;
  assert.match(releaseClaims, /supported-scope conformance/i);
  assert.doesNotMatch(releaseClaims, /full(?:-revision)? conformance/i);
});
