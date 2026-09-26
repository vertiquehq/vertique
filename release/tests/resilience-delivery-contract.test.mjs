// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * RESILIENCE-001 T010 — public delivery contract.
 *
 * The implementation tasks own runtime behavior. This contract locks the
 * final public documentation, artifact references, and explicit boundaries so
 * a release cannot silently ship the runtime without its usage guidance.
 *
 * It reads only files this repository ships, so it runs from a standalone
 * checkout. The developer-guide pages are maintained outside this repository;
 * their resilience assertions live beside that corpus.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const SOURCE_ROOT = path.resolve(TEST_DIR, '..', '..');

function readSource(relativePath) {
  return readFileSync(path.join(SOURCE_ROOT, relativePath), 'utf8');
}

describe('ResilienceDeliveryContractTest', () => {
  it('publishes the canonical runtime and optional metrics adapter references', () => {
    const readme = readSource('README.md');
    const modules = readSource('docs/modules.md');

    assert.match(readme, /vertique-resilience\/src\/main\/resources\/META-INF\/vertique\/module\.md/);
    assert.match(readme, /dev\.vertique\.resilience\.annotation/);
    assert.match(readme, /vertique-micrometer-resilience\/src\/main\/resources\/META-INF\/vertique\/module\.md/);
    assert.match(modules, /\| `vertique-resilience` \|/);
    assert.match(modules, /\| `vertique-micrometer-resilience` \|/);
    assert.ok(existsSync(path.join(SOURCE_ROOT, 'vertique-resilience/src/main/resources/META-INF/vertique/module.md')));
    assert.ok(existsSync(path.join(SOURCE_ROOT, 'vertique-micrometer/vertique-micrometer-resilience/src/main/resources/META-INF/vertique/module.md')));
  });

  it('documents the accepted fatal breaker consequence', () => {
    const readme = readSource('README.md');

    assert.match(readme, /fatal/i);
    assert.match(readme, /reset[\s\S]*closed-state[\s\S]*breaker failures/i);
    assert.match(readme, /half-open breaker/i);
  });

  it('keeps obsolete package and module names out of public delivery guidance', () => {
    const readme = readSource('README.md');

    assert.doesNotMatch(readme, /dev\.vertique\.core\.resilience/);
    assert.doesNotMatch(readme, /vertique-resilience-core/);
  });
});
