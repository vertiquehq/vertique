// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Coverage PR comment publisher.
 *
 * Runs from the non-required Coverage Comment workflow (the snapshot.yml
 * credential model: required CI is read-only and merely uploads the
 * coverage-pr artifact; this workflow_run workflow holds the pull-request
 * write and executes only code from the default branch). It downloads the
 * artifact the verified CI run produced, renders one sticky comment — diff
 * coverage on the changed lines, per-file gaps, project totals, and the delta
 * against main's latest published datapoint — and upserts it on the PR.
 *
 *   node scripts/coverage-pr-comment.mjs --repo <owner/name> --run-id <id> --head-sha <sha>
 *
 * Security posture: the PR is resolved server-side from the run's head SHA —
 * never from artifact content, which a pull request author controls. Artifact
 * content is treated as display data only; file paths are escaped before
 * being placed in the comment table.
 */

import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const ARTIFACT_NAME = 'coverage-pr';
const DATA_BRANCH = 'coverage-data';
const MARKER = '<!-- vertique-coverage-report -->';
const THRESHOLD = 80;
const MAX_FILE_ROWS = 15;

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  if (i === -1 || i + 1 >= process.argv.length) throw new Error(`missing --${name}`);
  return process.argv[i + 1];
}

function gh(args, input) {
  return execFileSync('gh', args, {
    encoding: 'utf8',
    stdio: [input === undefined ? 'ignore' : 'pipe', 'pipe', 'inherit'],
    input,
  }).trim();
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

/** 1,2,3,7,9,10 -> "1-3, 7, 9-10" */
function ranges(lines) {
  const sorted = [...lines].sort((a, b) => a - b);
  const out = [];
  for (let i = 0; i < sorted.length; ) {
    let j = i;
    while (j + 1 < sorted.length && sorted[j + 1] === sorted[j] + 1) j++;
    out.push(i === j ? `${sorted[i]}` : `${sorted[i]}-${sorted[j]}`);
    i = j + 1;
  }
  return out.join(', ');
}

/** Display-escape untrusted text placed in a Markdown table cell. */
function cell(text) {
  return String(text).replace(/[|`]/g, '\\$&');
}

const repo = arg('repo');
const runId = arg('run-id');
const headSha = arg('head-sha');

// 1. The PR is resolved from the verified run's head SHA, server-side. A SHA
// that no longer resolves (force-pushed away between run and comment) is a
// normal outcome, not a fault.
let prs = [];
try {
  prs = JSON.parse(gh(['api', `repos/${repo}/commits/${headSha}/pulls`]));
} catch {
  console.log(`coverage-pr-comment: ${headSha} no longer resolves; nothing to do`);
  process.exit(0);
}
const pr = prs.find((p) => p.state === 'open' && p.head?.sha === headSha);
if (!pr) {
  console.log(`coverage-pr-comment: no open PR with head ${headSha}; nothing to do`);
  process.exit(0);
}

// 2. The artifact from the exact run required CI verified. A build that
// failed before producing coverage uploads nothing — skip quietly.
const dir = mkdtempSync(path.join(tmpdir(), 'coverage-pr-'));
try {
  gh(['run', 'download', runId, '--repo', repo, '--name', ARTIFACT_NAME, '--dir', dir]);
} catch {
  console.log('coverage-pr-comment: no coverage-pr artifact on this run; nothing to do');
  process.exit(0);
}

const diffReportPath = path.join(dir, 'diff-cover.json');
const xmlPath = path.join(dir, 'jacoco.xml');
const diff = existsSync(diffReportPath) ? JSON.parse(readFileSync(diffReportPath, 'utf8')) : null;
const xml = existsSync(xmlPath) ? readFileSync(xmlPath, 'utf8') : null;

// 3. Main's latest published datapoint, for the total-coverage delta.
let mainPoint = null;
try {
  const content = gh(['api', `repos/${repo}/contents/coverage.jsonl?ref=${DATA_BRANCH}`, '--jq', '.content']);
  const lines = Buffer.from(content, 'base64').toString('utf8').trim().split('\n');
  mainPoint = JSON.parse(lines[lines.length - 1]);
} catch {
  // No data branch yet — omit the delta.
}

// 4. Render.
const out = [MARKER, '## Coverage report', ''];

if (diff && diff.total_num_lines > 0) {
  const covered = diff.total_num_lines - diff.total_num_violations;
  const passed = diff.total_percent_covered >= THRESHOLD;
  out.push(
    `**Changed-line coverage: ${diff.total_percent_covered}%** ` +
      `(${covered}/${diff.total_num_lines} lines) — threshold ${THRESHOLD}% ${passed ? '✅' : '❌'}`,
    ''
  );

  const gaps = Object.entries(diff.src_stats ?? {})
    .filter(([, s]) => (s.violation_lines ?? []).length > 0)
    .sort((a, b) => b[1].violation_lines.length - a[1].violation_lines.length);
  if (gaps.length > 0) {
    out.push('| File | Coverage | Missing lines |', '|---|---|---|');
    for (const [file, s] of gaps.slice(0, MAX_FILE_ROWS)) {
      out.push(`| ${cell(file)} | ${Math.round(s.percent_covered * 10) / 10}% | ${ranges(s.violation_lines)} |`);
    }
    if (gaps.length > MAX_FILE_ROWS) out.push('', `…and ${gaps.length - MAX_FILE_ROWS} more file(s) with missing lines.`);
    out.push('');
  }
} else {
  out.push('No coverage-measurable lines in this diff (docs, config, or excluded paths only). ✅', '');
}

if (xml) {
  const line = lastCounter(xml, 'LINE');
  const branch = lastCounter(xml, 'BRANCH');
  if (line) {
    const linePct = pct(line);
    let delta = '';
    if (mainPoint?.linePct !== undefined) {
      const d = Math.round((linePct - mainPoint.linePct) * 10) / 10;
      delta = ` (main ${mainPoint.linePct}%, ${d >= 0 ? '+' : ''}${d} pp)`;
    }
    out.push(`**Project totals:** line ${linePct}%${delta}${branch ? ` · branch ${pct(branch)}%` : ''}`, '');
  }
}

out.push(`<sub>Commit ${headSha.slice(0, 12)} · [CI run](https://github.com/${repo}/actions/runs/${runId})</sub>`);
const body = out.join('\n');

// 5. Sticky upsert: one comment per PR, updated in place.
const comments = JSON.parse(gh(['api', `repos/${repo}/issues/${pr.number}/comments`, '--paginate']));
const existing = comments.find((c) => typeof c.body === 'string' && c.body.startsWith(MARKER));
if (existing) {
  gh(['api', '--method', 'PATCH', `repos/${repo}/issues/comments/${existing.id}`, '--input', '-'], JSON.stringify({ body }));
  console.log(`coverage-pr-comment: updated comment ${existing.id} on PR #${pr.number}`);
} else {
  gh(['api', '--method', 'POST', `repos/${repo}/issues/${pr.number}/comments`, '--input', '-'], JSON.stringify({ body }));
  console.log(`coverage-pr-comment: created comment on PR #${pr.number}`);
}
