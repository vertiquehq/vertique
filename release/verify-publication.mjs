#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * RELEASE-001 — derives and verifies the public Maven publication inventory
 * (FR-REL-030, FR-REL-032; ADR-0007).
 *
 * Publication eligibility is *derived* from `release/publication-policy.json`:
 * the root parent, the standalone application parent, the BOM, every
 * `dev.vertique` dependency-management entry in the BOM, and the
 * `maven-archetype` children of the archetype aggregator. Reactor membership
 * alone never makes a module publishable, and reactor `deploy` is not the
 * publication boundary (FR-REL-033) — this inventory is.
 *
 * Every reactor module must land in exactly one of two buckets: published, or
 * skipped for a declared reason. A module matching no publish rule and no skip
 * rule is reported as `unclassified`, which fails verification — that is the
 * mechanism preventing a new module from silently bypassing publication.
 *
 * Usage:
 *   node release/verify-publication.mjs [--root <dir>] [--policy <file>] [--json]
 *
 * Exits 0 when the derived inventory matches the policy, 1 otherwise.
 */

import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// ---------------------------------------------------------------------------
// Minimal POM reader
//
// POMs are read with a small purpose-built parser rather than a regex sweep:
// `<parent>` carries its own `<artifactId>`, so a naive "first artifactId"
// match silently reads the parent's coordinates instead of the module's.
// ---------------------------------------------------------------------------

/**
 * Parses a subset of XML sufficient for POM structure into a plain tree of
 * `{ name, children, text }`. Comments, CDATA, processing instructions, and
 * attributes are discarded — none of them carry POM coordinate data.
 * @param {string} xml raw document text
 * @returns {{name: string, children: object[], text: string}|null} root element
 */
function parseXml(xml) {
  const stripped = xml
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<\?[\s\S]*?\?>/g, '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/<!DOCTYPE[^>]*>/gi, '');

  const root = { name: '#root', children: [], text: '' };
  const stack = [root];
  const tagRe = /<\s*(\/?)\s*([A-Za-z_][\w.:-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)\s*>/g;
  let lastIndex = 0;
  let match;

  while ((match = tagRe.exec(stripped)) !== null) {
    const [full, closing, name, , selfClosing] = match;
    const text = stripped.slice(lastIndex, match.index);
    if (text.trim()) stack[stack.length - 1].text += text;
    lastIndex = match.index + full.length;

    if (closing) {
      // Tolerate a stray close tag rather than corrupting the stack.
      for (let i = stack.length - 1; i > 0; i--) {
        if (stack[i].name === name) {
          stack.length = i;
          break;
        }
      }
    } else if (!selfClosing) {
      const node = { name, children: [], text: '' };
      stack[stack.length - 1].children.push(node);
      stack.push(node);
    } else {
      stack[stack.length - 1].children.push({ name, children: [], text: '' });
    }
  }
  return root.children[0] ?? null;
}

/** Returns the direct children of `node` named `name`. */
function childrenNamed(node, name) {
  return node ? node.children.filter((c) => c.name === name) : [];
}

/** Returns the trimmed text of the first direct child named `name`, or undefined. */
function childText(node, name) {
  const c = childrenNamed(node, name)[0];
  return c ? c.text.trim() : undefined;
}

/**
 * Reads one POM's coordinates and structure.
 * @param {string} pomPath absolute path to a `pom.xml`
 * @returns {{artifactId: string, groupId: string|undefined, packaging: string, modules: string[], managed: {groupId: string, artifactId: string}[]}}
 */
export function readPom(pomPath) {
  const project = parseXml(readFileSync(pomPath, 'utf8'));
  if (!project) throw new Error(`${pomPath}: no root <project> element`);

  const artifactId = childText(project, 'artifactId');
  if (!artifactId) throw new Error(`${pomPath}: missing project <artifactId>`);

  const parent = childrenNamed(project, 'parent')[0];
  const groupId = childText(project, 'groupId') ?? (parent ? childText(parent, 'groupId') : undefined);

  const modules = childrenNamed(project, 'modules').flatMap((m) =>
    childrenNamed(m, 'module').map((x) => x.text.trim()).filter(Boolean)
  );

  const managed = childrenNamed(project, 'dependencyManagement')
    .flatMap((dm) => childrenNamed(dm, 'dependencies'))
    .flatMap((ds) => childrenNamed(ds, 'dependency'))
    .map((d) => ({ groupId: childText(d, 'groupId'), artifactId: childText(d, 'artifactId') }))
    .filter((d) => d.groupId && d.artifactId);

  return { artifactId, groupId, packaging: childText(project, 'packaging') ?? 'jar', modules, managed };
}

/**
 * Walks the reactor from `repoRoot/pom.xml`, following `<modules>`.
 * @param {string} repoRoot reactor root directory
 * @returns {{relPath: string, artifactId: string, packaging: string}[]} every reactor module
 */
export function readReactor(repoRoot) {
  const found = [];
  const seen = new Set();

  const walk = (relDir) => {
    const pomPath = path.join(repoRoot, relDir, 'pom.xml');
    if (seen.has(pomPath) || !existsSync(pomPath)) return;
    seen.add(pomPath);
    const pom = readPom(pomPath);
    found.push({
      relPath: relDir === '' ? '.' : relDir.split(path.sep).join('/'),
      artifactId: pom.artifactId,
      packaging: pom.packaging,
    });
    for (const m of pom.modules) walk(relDir === '' ? m : path.join(relDir, m));
  };

  walk('');
  return found;
}

/** Reads and minimally validates the publication policy document. */
export function loadPolicy(policyPath) {
  const policy = JSON.parse(readFileSync(policyPath, 'utf8'));
  for (const key of ['schemaVersion', 'product', 'publish', 'skip', 'expectedPublishableGavCount']) {
    if (policy[key] === undefined) throw new Error(`${policyPath}: missing "${key}"`);
  }
  if (policy.schemaVersion !== 1) {
    throw new Error(`${policyPath}: unsupported schemaVersion ${policy.schemaVersion}`);
  }
  return policy;
}

/** True when `relPath` is `prefix` itself or lies beneath it. */
function underPath(relPath, prefix) {
  return relPath === prefix || relPath.startsWith(`${prefix}/`);
}

/**
 * Derives the publication inventory for a reactor.
 *
 * @param {string} repoRoot reactor root directory
 * @param {object} policy parsed publication policy
 * @returns {{modules: object[], published: object[], skipped: object[]}}
 *   `published` and `skipped` partition `modules` exactly.
 */
export function deriveInventory(repoRoot, policy) {
  const modules = readReactor(repoRoot);
  const { fixed, bomManaged, archetypes } = policy.publish;

  // Every dev.vertique GAV the BOM manages is publishable by construction.
  const bomPath = path.join(repoRoot, bomManaged.pomPath);
  const bomIds = existsSync(bomPath)
    ? new Set(
        readPom(bomPath)
          .managed.filter((d) => d.groupId.startsWith(bomManaged.groupIdPrefix))
          .map((d) => d.artifactId)
      )
    : new Set();

  const published = [];
  const skipped = [];

  for (const mod of modules) {
    const isFixed = fixed.includes(mod.artifactId);
    const isBomManaged = bomIds.has(mod.artifactId);
    const isArchetype =
      mod.packaging === archetypes.packaging && underPath(mod.relPath, archetypes.aggregatorPath);

    if (isFixed || isBomManaged || isArchetype) {
      published.push({
        ...mod,
        reason: isFixed ? 'fixed' : isBomManaged ? 'bom-managed' : 'archetype',
        payloads: policy.payloadPolicy?.[mod.packaging] ?? null,
      });
      continue;
    }

    const rule = policy.skip.find(
      (r) =>
        (r.pathPrefix !== undefined && underPath(mod.relPath, r.pathPrefix)) ||
        (r.artifactIdSuffix !== undefined && mod.artifactId.endsWith(r.artifactIdSuffix)) ||
        (r.packaging !== undefined && mod.packaging === r.packaging)
    );
    skipped.push({ ...mod, reason: rule ? rule.reason : 'unclassified' });
  }

  published.sort((a, b) => a.artifactId.localeCompare(b.artifactId));
  return { modules, published, skipped };
}

/**
 * Derives the inventory and checks it against the policy's declared expectations.
 * @returns {{ok: boolean, errors: string[], inventory: object}}
 */
export function verifyInventory(repoRoot, policy) {
  const errors = [];
  const inventory = deriveInventory(repoRoot, policy);

  const unclassified = inventory.skipped.filter((m) => m.reason === 'unclassified');
  for (const m of unclassified) {
    errors.push(
      `unclassified reactor module "${m.artifactId}" (${m.relPath}): it matches no publish rule ` +
        `and no skip rule. Add it to the BOM to publish it, or declare a skip rule for it.`
    );
  }

  if (inventory.published.length !== policy.expectedPublishableGavCount) {
    errors.push(
      `publishable GAV count drift: derived ${inventory.published.length}, ` +
        `policy expects ${policy.expectedPublishableGavCount}. ` +
        `Update expectedPublishableGavCount deliberately if the inventory really changed.`
    );
  }

  for (const id of policy.publish.fixed) {
    if (!inventory.published.some((m) => m.artifactId === id)) {
      errors.push(`declared fixed artifact "${id}" is not present in the reactor`);
    }
  }

  return { ok: errors.length === 0, errors, inventory };
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = { root: undefined, policy: undefined, json: false };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--root') args.root = argv[++i];
    else if (argv[i] === '--policy') args.policy = argv[++i];
    else if (argv[i] === '--json') args.json = true;
    else throw new Error(`unrecognised argument "${argv[i]}"`);
  }
  return args;
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    const args = parseArgs(process.argv.slice(2));
    const repoRoot = path.resolve(args.root ?? path.join(path.dirname(fileURLToPath(import.meta.url)), '..'));
    const policyPath = path.resolve(args.policy ?? path.join(repoRoot, 'release', 'publication-policy.json'));
    const result = verifyInventory(repoRoot, loadPolicy(policyPath));

    if (args.json) {
      console.log(JSON.stringify({ ok: result.ok, errors: result.errors, published: result.inventory.published }, null, 2));
    } else {
      const { published, skipped, modules } = result.inventory;
      console.log(`reactor modules : ${modules.length}`);
      console.log(`publishable     : ${published.length}`);
      const counts = skipped.reduce((acc, m) => ({ ...acc, [m.reason]: (acc[m.reason] ?? 0) + 1 }), {});
      console.log(`skipped         : ${skipped.length} ${JSON.stringify(counts)}`);
      for (const e of result.errors) console.error(`error: ${e}`);
      console.log(result.ok ? 'publication inventory OK' : 'publication inventory FAILED');
    }
    process.exit(result.ok ? 0 : 1);
  } catch (err) {
    console.error(`verify-publication: ${err.message}`);
    process.exit(1);
  }
}
