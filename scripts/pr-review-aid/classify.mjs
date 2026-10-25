// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Decide whether a pull request needs the review-aid builds.
 *
 * A workflow path filter would skip the job after a later push removes the
 * last relevant file, and a stale comment would stay. This prints
 * `relevant=true` or `relevant=false` for the changed paths between the
 * merge base and the head. The merge base is `git merge-base` of the base
 * branch tip and the head, so commits that landed on the base branch after
 * the branch point are not treated as changes in the pull request. Relevant
 * means a pom.xml, anything under src/main, or this directory. The caller
 * still reconciles the comment when the result is false.
 *
 *   node scripts/pr-review-aid/classify.mjs --base <sha> --head <sha>
 *
 * BASE_SHA and HEAD_SHA are accepted as well. The line is suitable for
 * $GITHUB_OUTPUT.
 */

import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export function isRelevantPath(file) {
  const norm = String(file).replace(/\\/g, '/').replace(/^\.\//, '');
  if (norm === 'pom.xml' || norm.endsWith('/pom.xml')) return true;
  if (norm === 'src/main' || norm.startsWith('src/main/') || norm.includes('/src/main/')) return true;
  if (norm === 'scripts/pr-review-aid' || norm.startsWith('scripts/pr-review-aid/')) return true;
  return false;
}

export function classifyPaths(paths) {
  return paths.some(isRelevantPath);
}

function option(argv, name, envName, env) {
  const index = argv.indexOf(`--${name}`);
  if (index !== -1) {
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) throw new Error(`missing value for --${name}`);
    return value;
  }
  if (env[envName]) return env[envName];
  throw new Error(`missing --${name}`);
}

export function mergeBase(base, head, cwd, exec = execFileSync) {
  const out = exec('git', ['merge-base', base, head], {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return String(out).trim();
}

export function changedPaths(base, head, cwd, exec = execFileSync) {
  const baseCommit = mergeBase(base, head, cwd, exec);
  const out = exec('git', ['diff', '--name-only', '--no-renames', `${baseCommit}...${head}`], {
    cwd,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return String(out).split('\n').map((line) => line.trim()).filter(Boolean);
}

function main(argv, env = process.env) {
  const base = option(argv, 'base', 'BASE_SHA', env);
  const head = option(argv, 'head', 'HEAD_SHA', env);
  const relevant = classifyPaths(changedPaths(base, head, env.CLASSIFY_CWD || process.cwd()));
  process.stdout.write(`relevant=${relevant ? 'true' : 'false'}\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (err) {
    console.error(err?.message ?? err);
    process.exit(1);
  }
}
