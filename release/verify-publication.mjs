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

import { existsSync, readFileSync, readdirSync } from 'node:fs';
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
    // Discarded, not unwrapped: re-injecting CDATA contents would let the tag
    // scanner read them as live markup, so a POM whose <description> contains
    // "a < b" could silently lose its <packaging> and entire <modules> subtree.
    // POM coordinates never appear inside CDATA.
    .replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '')
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

/**
 * Returns the trimmed text of the first direct child named `name`.
 * An absent *or empty* element yields undefined, so `?? default` fallbacks
 * behave the same for `<packaging/>` as for a missing `<packaging>`.
 */
function childText(node, name) {
  const c = childrenNamed(node, name)[0];
  if (!c) return undefined;
  const text = c.text.trim();
  return text === '' ? undefined : text;
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

  const dependencies = childrenNamed(project, 'dependencies')
    .flatMap((ds) => childrenNamed(ds, 'dependency'))
    .map((d) => ({ groupId: childText(d, 'groupId'), artifactId: childText(d, 'artifactId') }))
    .filter((d) => d.groupId && d.artifactId);

  // Only project-level declarations are read, because a profile's modules and
  // managed dependencies are conditional on activation and cannot be resolved
  // build-independently. Refuse rather than silently under-report: a profile
  // that hides reactor modules would remove them from classification entirely,
  // which is precisely the fail-open the unclassified check exists to prevent.
  for (const profile of childrenNamed(project, 'profiles').flatMap((p) => childrenNamed(p, 'profile'))) {
    for (const conditional of ['modules', 'dependencyManagement']) {
      if (childrenNamed(profile, conditional).length > 0) {
        throw new Error(
          `${pomPath}: <profile> declares <${conditional}>; the publication inventory reads ` +
            `project-level declarations only. Move it to the project level or the inventory ` +
            `cannot see it.`
        );
      }
    }
  }

  return { artifactId, groupId, packaging: childText(project, 'packaging') ?? 'jar', modules, managed, dependencies };
}

/**
 * Reads every `<build><plugins><plugin>` declaration from a pom, including each plugin
 * dependency's exclusions.
 *
 * `readPom` deliberately does not carry this: its shape is publication-focused, and most
 * consumers have no interest in plugin declarations. Parsing lives here rather than in the
 * calling test for the same reason the rest of this module exists — a regex cannot tell a
 * plugin's `<dependencies>` from the project's.
 *
 * `<pluginManagement>` is excluded by construction: its `<plugins>` is a child of
 * `<pluginManagement>`, not of `<build>`, and only a real `<build><plugins>` declaration
 * causes Maven to resolve a plugin realm.
 *
 * Plugins declared inside a `<profile><build><plugins>` ARE included, in the same returned
 * shape as project-level ones. An active profile's plugin resolves its own realm exactly as a
 * project-level declaration does, so a declaration hidden in a profile is precisely the shape
 * a realm-isolation caller must see — omitting it would fail open, and refusing the pom
 * outright would decline to look at it. Profile *activation* is deliberately not evaluated:
 * this reader has no build context to evaluate it in, and a declaration anywhere in the pom is
 * in scope regardless of whether today's invocation would activate it.
 *
 * This is the gap filed as vertiquehq/vertique-dev#396.
 *
 * @param {string} pomPath absolute path to a pom.xml
 * @returns {Array<{groupId: string|undefined, artifactId: string,
 *                  dependencies: Array<{groupId: string, artifactId: string,
 *                                       version: string|undefined,
 *                                       exclusions: Array<{groupId: string, artifactId: string}>}>}>}
 */
export function readPluginDeclarations(pomPath) {
  const project = parseXml(readFileSync(pomPath, 'utf8'));
  if (!project) throw new Error(`${pomPath}: no root <project> element`);

  // Project-level <build> and every <profile><build>, read through the same path so a
  // profile-declared plugin cannot present a different shape than a project-level one.
  const builds = [
    ...childrenNamed(project, 'build'),
    ...childrenNamed(project, 'profiles')
      .flatMap((ps) => childrenNamed(ps, 'profile'))
      .flatMap((profile) => childrenNamed(profile, 'build')),
  ];

  return builds
    .flatMap((b) => childrenNamed(b, 'plugins'))
    .flatMap((ps) => childrenNamed(ps, 'plugin'))
    .map((p) => ({
      groupId: childText(p, 'groupId'),
      artifactId: childText(p, 'artifactId'),
      dependencies: childrenNamed(p, 'dependencies')
        .flatMap((ds) => childrenNamed(ds, 'dependency'))
        .map((d) => ({
          groupId: childText(d, 'groupId'),
          artifactId: childText(d, 'artifactId'),
          version: childText(d, 'version'),
          exclusions: childrenNamed(d, 'exclusions')
            .flatMap((es) => childrenNamed(es, 'exclusion'))
            .map((e) => ({ groupId: childText(e, 'groupId'), artifactId: childText(e, 'artifactId') }))
            .filter((e) => e.groupId && e.artifactId),
        }))
        .filter((d) => d.groupId && d.artifactId),
    }))
    .filter((p) => p.artifactId);
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
      groupId: pom.groupId,
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
 * True when `groupId` is `prefix` or a dot-delimited descendant of it.
 * Plain `startsWith` would make `dev.vertique.enterprise` a match for
 * `dev.vertique`, which would let enterprise GAVs into the public inventory
 * (and vice versa) in a reactor where both group families coexist.
 */
function underGroup(groupId, prefix) {
  return groupId === prefix || groupId.startsWith(`${prefix}.`);
}

/**
 * Finds the first rule matching a module. Every field a rule *declares* must
 * match (conjunction) — a rule listing both `pathPrefix` and `packaging` means
 * "that path AND that packaging", not "either".
 */
function matchRule(rules, mod) {
  return rules.find((rule) => {
    const conditions = [];
    if (rule.pathPrefix !== undefined) conditions.push(underPath(mod.relPath, rule.pathPrefix));
    if (rule.artifactIdSuffix !== undefined) conditions.push(mod.artifactId.endsWith(rule.artifactIdSuffix));
    if (rule.packaging !== undefined) conditions.push(mod.packaging === rule.packaging);
    return conditions.length > 0 && conditions.every(Boolean);
  });
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

  // Every first-party GAV the BOM manages is publishable by construction. A
  // missing BOM must be an explicit error: defaulting to an empty set would
  // report every real module as "unclassified — add it to the BOM", pointing
  // the operator at entirely the wrong cause.
  const bomPath = path.join(repoRoot, bomManaged.pomPath);
  if (!existsSync(bomPath)) {
    throw new Error(`${bomPath}: BOM POM not found (policy publish.bomManaged.pomPath)`);
  }
  const bomKeys = new Set(
    readPom(bomPath)
      .managed.filter((d) => underGroup(d.groupId, bomManaged.groupIdPrefix))
      .map((d) => `${d.groupId}:${d.artifactId}`)
  );

  const published = [];
  const skipped = [];

  for (const mod of modules) {
    const groupId = mod.groupId ?? policy.groupIdPrefix;
    const key = `${groupId}:${mod.artifactId}`;

    // Deny rules are authoritative and evaluated FIRST. Examples and
    // integration-test modules are never publishable, even if someone adds one
    // to the BOM — FR-REL-030 enumerates what publication contains, and an
    // example is not in it. Evaluating publish rules first would make a
    // BOM listing sufficient to publish an example.
    const denied = matchRule(policy.deny ?? [], mod);
    if (denied) {
      skipped.push({ ...mod, key, reason: denied.reason });
      continue;
    }

    const isFixed = fixed.includes(mod.artifactId);
    const isBomManaged = bomKeys.has(key);
    // A product with no archetypes (enterprise) simply omits the rule.
    const isArchetype =
      Boolean(archetypes) &&
      mod.packaging === archetypes.packaging &&
      underPath(mod.relPath, archetypes.aggregatorPath);

    if (isFixed || isBomManaged || isArchetype) {
      published.push({
        ...mod,
        key,
        reason: isFixed ? 'fixed' : isBomManaged ? 'bom-managed' : 'archetype',
        // Object.hasOwn keeps a POM declaring <packaging>constructor</packaging>
        // from resolving to Object.prototype.constructor.
        payloads: Object.hasOwn(policy.payloadPolicy ?? {}, mod.packaging)
          ? policy.payloadPolicy[mod.packaging]
          : null,
      });
      continue;
    }

    const rule = matchRule(policy.skip ?? [], mod);
    skipped.push({ ...mod, key, reason: rule ? rule.reason : 'unclassified' });
  }

  published.sort((a, b) => a.key.localeCompare(b.key));
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

  // Count equality alone cannot satisfy FR-REL-032: dropping one BOM GAV while
  // adding another leaves the count untouched, and renaming an archetype keeps
  // the archetype count at three. Pin the derived SET so BOM, archetype and
  // reactor drift each fail, and so a legitimate inventory change is a
  // reviewable one-line-per-GAV policy diff rather than an opaque count bump.
  if (Array.isArray(policy.expectedPublishableGavs)) {
    const derived = new Set(inventory.published.map((m) => m.key));
    const expected = new Set(policy.expectedPublishableGavs);
    const unexpected = [...derived].filter((k) => !expected.has(k)).sort();
    const missing = [...expected].filter((k) => !derived.has(k)).sort();
    for (const k of unexpected) {
      errors.push(`unexpected publishable GAV not in the declared inventory: ${k}`);
    }
    for (const k of missing) {
      errors.push(`declared publishable GAV missing from the reactor: ${k}`);
    }
  } else {
    errors.push('policy declares no expectedPublishableGavs list; set-level drift cannot be detected');
  }

  for (const id of policy.publish.fixed) {
    if (!inventory.published.some((m) => m.artifactId === id)) {
      errors.push(`declared fixed artifact "${id}" is not present in the reactor`);
    }
  }

  return { ok: errors.length === 0, errors, inventory };
}

// ---------------------------------------------------------------------------
// Deploy planning and staged-repository verification
// ---------------------------------------------------------------------------

/** Maven local-repository directory for a GAV. */
function localRepoDir(localRepository, groupId, artifactId, version) {
  return path.join(localRepository, ...groupId.split('.'), artifactId, version);
}

/** Classifier → file suffix for the payload kinds the policy names. */
const PAYLOAD_SUFFIX = { pom: '.pom', jar: '.jar', sources: '-sources.jar', javadoc: '-javadoc.jar' };

/**
 * Resolves, for every allowlisted GAV, the exact local-repository files that
 * must be deployed. The result is the immutable payload unit list: one entry
 * per POM, primary artifact and attached classifier (decision 28).
 *
 * @param {string} repoRoot reactor root
 * @param {object} policy publication policy
 * @param {{version: string, localRepository: string}} opts
 * @returns {{units: object[], errors: string[]}}
 */
export function buildDeployPlan(repoRoot, policy, { version, localRepository }) {
  const { published } = deriveInventory(repoRoot, policy);
  const units = [];
  const errors = [];

  for (const mod of published) {
    const groupId = mod.groupId ?? policy.groupIdPrefix;
    const dir = localRepoDir(localRepository, groupId, mod.artifactId, version);
    const payloads = policy.payloadPolicy[mod.packaging];
    if (!payloads) {
      errors.push(`no payload policy for packaging "${mod.packaging}" (${mod.artifactId})`);
      continue;
    }

    const files = {};
    for (const kind of payloads) {
      // A Maven plugin/archetype still produces a plain .jar primary artifact.
      const suffix = PAYLOAD_SUFFIX[kind];
      const file = path.join(dir, `${mod.artifactId}-${version}${suffix}`);
      if (!existsSync(file)) {
        const optional = kind === 'javadoc' && (policy.payloadPolicy.emptyJavadocExceptions ?? []).includes(mod.artifactId);
        if (!optional) {
          errors.push(`${mod.artifactId}: missing required ${kind} payload at ${file}`);
          continue;
        }
      }
      files[kind] = file;
    }

    units.push({
      groupId,
      artifactId: mod.artifactId,
      version,
      packaging: mod.packaging,
      relPath: mod.relPath,
      files,
    });
  }

  return { units, errors };
}

/** Recursively lists files beneath `dir`. */
function listFiles(dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) listFiles(full, acc);
    else acc.push(full);
  }
  return acc;
}

/** Version-like strings that indicate an unresolved or non-final build input. */
const SNAPSHOT_RE = /<(?:version|.*?\.version)>[^<]*-SNAPSHOT<\//i;

/**
 * Verifies a staged `file://` repository against the derived allowlist.
 *
 * Checks that exactly the allowlisted GAVs are present, that each carries its
 * required payload set with no unexpected classifier, that no payload belongs
 * to a non-allowlisted GAV, and — in final mode — that no staged POM carries a
 * SNAPSHOT in any version position (FR-REL-042).
 *
 * @returns {{ok: boolean, errors: string[], stagedGavs: string[]}}
 */
export function verifyStagedRepository(stagedDir, { repoRoot, policy, version, mode }) {
  const errors = [];
  const { published } = deriveInventory(repoRoot, policy);
  const expected = new Map(
    published.map((m) => [`${m.groupId ?? policy.groupIdPrefix}:${m.artifactId}`, m])
  );

  if (!existsSync(stagedDir)) {
    return { ok: false, errors: [`staged repository ${stagedDir} does not exist`], stagedGavs: [] };
  }

  const files = listFiles(stagedDir).filter((f) => !/maven-metadata|\.sha1$|\.md5$|\.sha256$|\.sha512$/.test(f));
  const seen = new Map();

  for (const file of files) {
    const rel = path.relative(stagedDir, file).split(path.sep);
    // <group/parts...>/<artifactId>/<version>/<file>
    if (rel.length < 4) {
      errors.push(`unexpected file outside the Maven layout: ${rel.join('/')}`);
      continue;
    }
    const fileName = rel[rel.length - 1];
    const ver = rel[rel.length - 2];
    const artifactId = rel[rel.length - 3];
    const groupId = rel.slice(0, rel.length - 3).join('.');
    const key = `${groupId}:${artifactId}`;

    if (!expected.has(key)) {
      errors.push(`non-allowlisted payload staged: ${key}:${ver} (${fileName})`);
      continue;
    }
    if (ver !== version) {
      errors.push(`${key}: staged version ${ver} does not match requested ${version}`);
      continue;
    }

    const kind = fileName.endsWith('.pom')
      ? 'pom'
      : fileName.endsWith('-sources.jar')
        ? 'sources'
        : fileName.endsWith('-javadoc.jar')
          ? 'javadoc'
          : fileName.endsWith('.jar')
            ? 'jar'
            : 'unexpected';
    if (kind === 'unexpected') {
      errors.push(`${key}: unexpected classifier or file type staged: ${fileName}`);
      continue;
    }
    if (!seen.has(key)) seen.set(key, new Set());
    seen.get(key).add(kind);

    if (kind === 'pom') {
      const pomText = readFileSync(file, 'utf8');
      if (pomText.includes('${revision}')) {
        errors.push(`${key}: staged POM still contains the unresolved \${revision} placeholder`);
      }
      if (mode === 'final' && SNAPSHOT_RE.test(pomText)) {
        errors.push(`${key}: staged POM contains a SNAPSHOT version in final mode`);
      }
    }
  }

  if (mode === 'final' && /-SNAPSHOT$/.test(version)) {
    errors.push(`final mode requested with a SNAPSHOT version "${version}"`);
  }

  for (const [key, mod] of expected) {
    const got = seen.get(key);
    if (!got) {
      errors.push(`allowlisted GAV missing from the staged repository: ${key}`);
      continue;
    }
    for (const kind of policy.payloadPolicy[mod.packaging] ?? []) {
      const exempt = kind === 'javadoc' && (policy.payloadPolicy.emptyJavadocExceptions ?? []).includes(mod.artifactId);
      if (!got.has(kind) && !exempt) errors.push(`${key}: missing required ${kind} payload`);
    }
  }

  if (seen.size !== policy.expectedPublishableGavCount) {
    errors.push(`staged GAV count ${seen.size} does not match expected ${policy.expectedPublishableGavCount}`);
  }

  return { ok: errors.length === 0, errors, stagedGavs: [...seen.keys()].sort() };
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = {
    root: undefined,
    policy: undefined,
    json: false,
    deployPlan: undefined,
    verifyStaged: undefined,
    version: undefined,
    mode: 'snapshot',
  };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--root') args.root = argv[++i];
    else if (argv[i] === '--policy') args.policy = argv[++i];
    else if (argv[i] === '--json') args.json = true;
    else if (argv[i] === '--deploy-plan') args.deployPlan = argv[++i];
    else if (argv[i] === '--verify-staged') args.verifyStaged = argv[++i];
    else if (argv[i] === '--version') args.version = argv[++i];
    else if (argv[i] === '--mode') args.mode = argv[++i];
    else throw new Error(`unrecognised argument "${argv[i]}"`);
  }
  if (args.mode !== 'snapshot' && args.mode !== 'final') {
    throw new Error(`--mode must be "snapshot" or "final", got "${args.mode}"`);
  }
  if ((args.deployPlan || args.verifyStaged) && !args.version) {
    throw new Error('--version is required with --deploy-plan and --verify-staged');
  }
  return args;
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    const args = parseArgs(process.argv.slice(2));
    const repoRoot = path.resolve(args.root ?? path.join(path.dirname(fileURLToPath(import.meta.url)), '..'));
    const policyPath = path.resolve(args.policy ?? path.join(repoRoot, 'release', 'publication-policy.json'));
    const policy = loadPolicy(policyPath);

    // Emit the immutable payload unit list for the staging step (NDJSON, one unit per line).
    if (args.deployPlan) {
      if (args.mode === 'final' && /-SNAPSHOT$/.test(args.version)) {
        throw new Error(`final mode refuses a SNAPSHOT version "${args.version}"`);
      }
      const plan = buildDeployPlan(repoRoot, policy, {
        version: args.version,
        localRepository: path.resolve(args.deployPlan),
      });
      for (const e of plan.errors) console.error(`error: ${e}`);
      if (plan.errors.length) process.exit(1);
      for (const unit of plan.units) console.log(JSON.stringify(unit));
      process.exit(0);
    }

    // Verify an already-staged file:// repository against the derived allowlist.
    if (args.verifyStaged) {
      const staged = verifyStagedRepository(path.resolve(args.verifyStaged), {
        repoRoot,
        policy,
        version: args.version,
        mode: args.mode,
      });
      for (const e of staged.errors) console.error(`error: ${e}`);
      console.log(
        staged.ok
          ? `staged repository OK (${staged.stagedGavs.length} GAVs, mode=${args.mode})`
          : 'staged repository FAILED'
      );
      process.exit(staged.ok ? 0 : 1);
    }

    const result = verifyInventory(repoRoot, policy);

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
