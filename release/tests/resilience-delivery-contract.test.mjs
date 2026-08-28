// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * RESILIENCE-001 T010 — public delivery contract.
 *
 * The implementation tasks own runtime behavior. This contract locks the
 * final public documentation, artifact references, and explicit boundaries so
 * a release cannot silently ship the runtime without its usage guidance.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const SOURCE_ROOT = path.resolve(TEST_DIR, '..', '..');
const DEVELOPMENT_ROOT = path.resolve(SOURCE_ROOT, '..', '..');

function read(relativePath) {
  return readFileSync(path.join(DEVELOPMENT_ROOT, relativePath), 'utf8');
}

function readSource(relativePath) {
  return readFileSync(path.join(SOURCE_ROOT, relativePath), 'utf8');
}

describe('ResilienceDeliveryContractTest', () => {
  it('publishes the canonical runtime and optional metrics adapter references', () => {
    const readme = readSource('README.md');
    const artifacts = read('developer-docs/content/artifacts.md');
    const modules = readSource('docs/modules.md');

    assert.match(readme, /vertique-resilience\/src\/main\/resources\/META-INF\/vertique\/module\.md/);
    assert.match(readme, /dev\.vertique\.resilience\.annotation/);
    assert.match(readme, /vertique-micrometer-resilience\/src\/main\/resources\/META-INF\/vertique\/module\.md/);
    assert.match(artifacts, /\| `vertique-resilience` \|/);
    assert.match(artifacts, /\| `vertique-micrometer-resilience` \|/);
    assert.match(modules, /\| `vertique-resilience` \|/);
    assert.match(modules, /\| `vertique-micrometer-resilience` \|/);
    assert.ok(existsSync(path.join(SOURCE_ROOT, 'vertique-resilience/src/main/resources/META-INF/vertique/module.md')));
    assert.ok(existsSync(path.join(SOURCE_ROOT, 'vertique-micrometer/vertique-micrometer-resilience/src/main/resources/META-INF/vertique/module.md')));
  });

  it('documents consumer boundaries and retry safety', () => {
    const configuration = read('developer-docs/content/configuration.md');
    const services = read('developer-docs/content/services.md');
    const jobs = read('developer-docs/content/jobs.md');

    for (const document of [configuration, services]) {
      assert.match(document, /dev\.vertique\.resilience\.annotation/);
      assert.match(document, /maxRetries/);
      assert.match(document, /0.*100/);
      assert.match(document, /bulkhead/);
    }
    assert.match(configuration, /replay.*idempotency/i);
    assert.match(services, /per supplier attempt/);
    assert.match(jobs, /durable delivery policy/i);
    assert.match(jobs, /RedisDeadline/);
    assert.match(jobs, /do not install common resilience behavior implicitly/i);
  });

  it('documents observability and the accepted fatal breaker consequence', () => {
    const readme = readSource('README.md');
    const artifacts = read('developer-docs/content/artifacts.md');
    const configuration = read('developer-docs/content/configuration.md');
    const observability = read('developer-docs/content/observability.md');

    assert.match(observability, /vertique-micrometer-resilience/);
    assert.match(observability, /metrics\.enabled/);
    for (const document of [readme, artifacts, configuration]) {
      assert.match(document, /fatal/i);
      assert.match(document, /reset[\s\S]*closed-state[\s\S]*breaker failures/i);
      assert.match(document, /half-open breaker/i);
    }
  });

  it('keeps obsolete package and module names out of public delivery guidance', () => {
    const publicDocuments = [
      readSource('README.md'),
      read('developer-docs/content/artifacts.md'),
      read('developer-docs/content/configuration.md'),
      read('developer-docs/content/jobs.md'),
      read('developer-docs/content/observability.md'),
      read('developer-docs/content/services.md'),
    ].join('\n');

    assert.doesNotMatch(publicDocuments, /dev\.vertique\.core\.resilience/);
    assert.doesNotMatch(publicDocuments, /vertique-resilience-core/);
  });
});
