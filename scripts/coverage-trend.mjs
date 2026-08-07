// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Coverage trend publisher.
 *
 * Runs from the non-required, main-only Coverage Trend workflow (the
 * snapshot.yml pattern: required CI holds no write credential; this workflow
 * holds the repository-content write and can only consume bytes required CI
 * already verified). It downloads the jacoco-aggregate artifact the verified
 * CI run uploaded, extracts total and per-module coverage, and appends one
 * datapoint to coverage.jsonl on the data branch — an append-only, jq-friendly
 * time series the repository owns forever — plus a shields.io endpoint badge.
 *
 *   node scripts/coverage-trend.mjs --run-id <id> --head-sha <sha>
 *
 * A failure here must never erase a CI result: the workflow is deliberately
 * not a required check.
 */

import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, appendFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const ARTIFACT_NAME = 'jacoco-aggregate';
const DATA_BRANCH = 'coverage-data';
const DATA_FILE = 'coverage.jsonl';
const BADGE_FILE = 'badge.json';

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  if (i === -1 || i + 1 >= process.argv.length) throw new Error(`missing --${name}`);
  return process.argv[i + 1];
}

function run(cmd, args, opts = {}) {
  return execFileSync(cmd, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'], ...opts }).trim();
}

/** Last counter of a type inside an XML fragment (JaCoCo puts totals last). */
function lastCounter(xml, type) {
  const all = [...xml.matchAll(new RegExp(`<counter type="${type}" missed="(\\d+)" covered="(\\d+)"`, 'g'))];
  if (all.length === 0) return null;
  const [, missed, covered] = all[all.length - 1];
  return { missed: Number(missed), covered: Number(covered) };
}

function pct(counter) {
  const total = counter.missed + counter.covered;
  return total === 0 ? 0 : Math.round((counter.covered / total) * 1000) / 10;
}

const runId = arg('run-id');
const headSha = arg('head-sha');

// 1. The artifact from the exact run required CI verified.
const downloadDir = mkdtempSync(path.join(tmpdir(), 'coverage-trend-'));
run('gh', ['run', 'download', runId, '--name', ARTIFACT_NAME, '--dir', downloadDir]);
const xml = readFileSync(path.join(downloadDir, 'jacoco.xml'), 'utf8');

// 2. Extract totals and per-module line coverage. report-aggregate emits one
// flat (never nested) <group> per module; report-level totals are the
// document's last counters, after the final group.
const modules = {};
for (const m of xml.matchAll(/<group name="([^"]+)">([\s\S]*?)<\/group>/g)) {
  const line = lastCounter(m[2], 'LINE');
  if (line) modules[m[1]] = line;
}
const totals = {
  instruction: lastCounter(xml, 'INSTRUCTION'),
  line: lastCounter(xml, 'LINE'),
  branch: lastCounter(xml, 'BRANCH'),
};
if (!totals.line) throw new Error('no LINE counter found in aggregate XML');
const linePct = pct(totals.line);

const datapoint = {
  date: new Date().toISOString(),
  sha: headSha,
  runId: Number(runId),
  totals,
  linePct,
  branchPct: totals.branch ? pct(totals.branch) : null,
  modules,
};

// 3. Append on the data branch via a worktree so the verified checkout that
// this script runs from is never disturbed.
const worktree = mkdtempSync(path.join(tmpdir(), 'coverage-data-'));
rmSync(worktree, { recursive: true, force: true });
let branchExists = true;
try {
  run('git', ['fetch', '--quiet', '--no-tags', 'origin', DATA_BRANCH]);
} catch {
  branchExists = false;
}
if (branchExists) {
  run('git', ['worktree', 'add', '--quiet', '-B', DATA_BRANCH, worktree, `origin/${DATA_BRANCH}`]);
} else {
  run('git', ['worktree', 'add', '--quiet', '--detach', worktree]);
  run('git', ['checkout', '--quiet', '--orphan', DATA_BRANCH], { cwd: worktree });
  run('git', ['rm', '-rfq', '--ignore-unmatch', '.'], { cwd: worktree });
}

try {
  const dataPath = path.join(worktree, DATA_FILE);
  const line = `${JSON.stringify(datapoint)}\n`;
  if (existsSync(dataPath)) appendFileSync(dataPath, line);
  else writeFileSync(dataPath, line);

  const color = linePct >= 90 ? 'brightgreen' : linePct >= 80 ? 'green' : linePct >= 70 ? 'yellow' : 'red';
  writeFileSync(
    path.join(worktree, BADGE_FILE),
    `${JSON.stringify({ schemaVersion: 1, label: 'coverage', message: `${linePct}%`, color })}\n`
  );

  const git = (args) => run('git', args, { cwd: worktree });
  git(['add', DATA_FILE, BADGE_FILE]);
  git([
    '-c', 'user.name=github-actions[bot]',
    '-c', 'user.email=41898282+github-actions[bot]@users.noreply.github.com',
    'commit', '--quiet', '-m', `coverage: ${linePct}% line at ${headSha.slice(0, 12)}`,
  ]);
  git(['push', '--quiet', 'origin', `${DATA_BRANCH}:${DATA_BRANCH}`]);
} finally {
  run('git', ['worktree', 'remove', '--force', worktree]);
}

console.log(`coverage-trend: appended ${linePct}% line coverage for ${headSha} to ${DATA_BRANCH}/${DATA_FILE}`);
