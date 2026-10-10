#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * RELEASE-001 — the SNAPSHOT payload handed from required CI to publication.
 *
 * Required CI builds the allowlisted payload units once, on the commit it
 * verifies, and stages exactly those files with a manifest. The publication
 * workflow downloads that payload instead of rebuilding the reactor, and checks
 * it for consistency with the commit the publication guard approved before any
 * byte is deployed: a payload for any other commit, a changed, missing or extra
 * file, or anything that is not a regular file, is refused.
 *
 * This is a consistency check, not authentication. The manifest and the files
 * travel together, so it catches mix-ups and corruption; who may produce the
 * payload is decided by the run the publication workflow downloads it from.
 *
 * Usage:
 *   node release/snapshot-payload.mjs stage \
 *     --local-repository <installed Maven repository> --sha <commit> \
 *     --out <payload directory> [--version <version>]
 *   node release/snapshot-payload.mjs verify \
 *     --dir <payload directory> --sha <commit> --version <version>
 *
 * `stage` writes <out>/manifest.json and <out>/repository/ (Maven layout);
 * `verify` checks both and prints nothing on success.
 */

import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, realpathSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { declaredVersion } from './snapshot-guard.mjs';
import { buildDeployPlan, loadPolicy } from './verify-publication.mjs';

const SHA1_RE = /^[0-9a-f]{40}$/;
const MANIFEST = 'manifest.json';
const REPOSITORY = 'repository';

const sha256 = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');

/**
 * Recursively lists regular files beneath `dir` as POSIX paths relative to
 * `base`. A symlink, device or any other entry is reported in `irregular`
 * rather than followed: a link would otherwise let a payload name a file
 * outside it.
 */
function listRelative(dir, base = dir, acc = { files: [], irregular: [] }) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    const relative = path.relative(base, full).split(path.sep).join('/');
    if (entry.isDirectory()) listRelative(full, base, acc);
    else if (entry.isFile()) acc.files.push(relative);
    else acc.irregular.push(relative);
  }
  return acc;
}

/**
 * Copies the allowlisted payload files out of an installed repository and
 * records their digests against the commit they were built from.
 *
 * @returns {{sha: string, version: string, files: Record<string, string>}} the manifest
 */
export function stagePayload({ repoRoot, policy, localRepository, version, sha, out }) {
  if (!SHA1_RE.test(sha ?? '')) throw new Error(`--sha must be a 40-character commit, got "${sha}"`);
  if (!version) throw new Error('no project version: pass --version or declare <revision> in pom.xml');

  const plan = buildDeployPlan(repoRoot, policy, { version, localRepository });
  if (plan.errors.length > 0) throw new Error(`deploy plan is incomplete:\n  ${plan.errors.join('\n  ')}`);
  if (plan.units.length === 0) throw new Error('deploy plan is empty');

  const files = {};
  for (const unit of plan.units) {
    for (const source of Object.values(unit.files)) {
      // An optional, deliberately empty Javadoc JAR is listed but never built.
      if (!existsSync(source)) continue;
      const relative = path.relative(localRepository, source).split(path.sep).join('/');
      const target = path.join(out, REPOSITORY, ...relative.split('/'));
      mkdirSync(path.dirname(target), { recursive: true });
      copyFileSync(source, target);
      files[relative] = sha256(target);
    }
  }

  const manifest = { schemaVersion: 1, sha, version, files: Object.fromEntries(Object.entries(files).sort()) };
  writeFileSync(path.join(out, MANIFEST), `${JSON.stringify(manifest, null, 2)}\n`);
  return manifest;
}

/**
 * Checks a downloaded payload against the commit and version publication was
 * approved for.
 *
 * @returns {string[]} problems; empty when the payload is exactly what was staged
 */
export function verifyPayload({ dir, sha, version }) {
  const manifestPath = path.join(dir, MANIFEST);
  if (!existsSync(manifestPath)) return [`${manifestPath}: payload manifest not found`];

  let manifest;
  try {
    manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch (err) {
    return [`${manifestPath}: not valid JSON (${err.message})`];
  }

  const problems = [];
  if (manifest.schemaVersion !== 1) problems.push(`unsupported manifest schemaVersion ${manifest.schemaVersion}`);
  if (manifest.sha !== sha) problems.push(`payload was built from ${manifest.sha}, not the approved commit ${sha}`);
  if (manifest.version !== version) problems.push(`payload version "${manifest.version}" is not the approved "${version}"`);
  // A Map, so a file named like an Object.prototype member is still "unlisted".
  const expected = new Map(
    manifest.files && typeof manifest.files === 'object' ? Object.entries(manifest.files) : []
  );
  if (expected.size === 0) problems.push('manifest lists no files');

  const repository = path.join(dir, REPOSITORY);
  const listing = existsSync(repository) ? listRelative(repository) : { files: [], irregular: [] };
  for (const relative of listing.irregular) problems.push(`${relative}: not a regular file`);
  const present = new Set(listing.files);
  for (const [relative, digest] of expected) {
    if (!present.has(relative)) problems.push(`${relative}: listed in the manifest but missing`);
    else if (sha256(path.join(repository, ...relative.split('/'))) !== digest) {
      problems.push(`${relative}: content does not match the manifest digest`);
    }
  }
  for (const relative of present) {
    if (!expected.has(relative)) problems.push(`${relative}: present but not listed in the manifest`);
  }
  return problems;
}

function parseArgs(argv) {
  const [command, ...rest] = argv;
  const args = { command };
  for (let i = 0; i < rest.length; i++) {
    const flag = rest[i];
    if (!['--local-repository', '--sha', '--out', '--version', '--dir'].includes(flag)) {
      throw new Error(`unrecognised argument "${flag}"`);
    }
    args[flag.slice(2).replace(/-(\w)/g, (_, c) => c.toUpperCase())] = rest[++i];
  }
  return args;
}

/**
 * True when this module is the process entry point.
 *
 * `import.meta.url` is already symlink-resolved while `process.argv[1]` is the
 * path as invoked, so comparing them unresolved makes the CLI silently not run
 * when the script is reached through a symlinked path (a macOS temp directory,
 * or a linked checkout). Both sides are resolved to their real paths first.
 */
function isEntryPoint() {
  if (!process.argv[1]) return false;
  const self = fileURLToPath(import.meta.url);
  try {
    return realpathSync(process.argv[1]) === realpathSync(self);
  } catch {
    return path.resolve(process.argv[1]) === path.resolve(self);
  }
}

if (isEntryPoint()) {
  try {
    const args = parseArgs(process.argv.slice(2));
    const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

    if (args.command === 'stage') {
      for (const required of ['localRepository', 'sha', 'out']) {
        if (!args[required]) throw new Error(`--${required.replace(/[A-Z]/g, (c) => `-${c.toLowerCase()}`)} is required`);
      }
      const manifest = stagePayload({
        repoRoot,
        policy: loadPolicy(path.join(repoRoot, 'release', 'publication-policy.json')),
        localRepository: path.resolve(args.localRepository),
        version: args.version ?? declaredVersion(),
        sha: args.sha,
        out: path.resolve(args.out),
      });
      console.log(`snapshot-payload: staged ${Object.keys(manifest.files).length} files for ${manifest.version} at ${manifest.sha}`);
    } else if (args.command === 'verify') {
      for (const required of ['dir', 'sha', 'version']) {
        if (!args[required]) throw new Error(`--${required} is required`);
      }
      const problems = verifyPayload({ dir: path.resolve(args.dir), sha: args.sha, version: args.version });
      if (problems.length > 0) {
        for (const problem of problems) console.error(`error: ${problem}`);
        process.exit(1);
      }
    } else {
      throw new Error('usage: snapshot-payload.mjs stage|verify [options]');
    }
  } catch (err) {
    console.error(`snapshot-payload: ${err.message}`);
    process.exit(1);
  }
}
