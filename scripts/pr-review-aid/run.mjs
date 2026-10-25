// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Pull request review aid entry point.
 *
 * Compares two checkouts and their collected OpenAPI documents, writes the
 * report and comment body, reconciles the sticky comment, and records whether
 * any spec has an error-level breaking change. This process exits 0 even
 * when breaking changes exist; the workflow's last step reads `breaking`.
 *
 *   node scripts/pr-review-aid/run.mjs \
 *     --base <dir> --head <dir> --base-specs <dir> --head-specs <dir> \
 *     --out <dir> --pr <n> --repo <owner/name> [--dry-run]
 *
 * The same values can be supplied as BASE_DIR, HEAD_DIR, BASE_SPECS,
 * HEAD_SPECS, OUT_DIR, PR_NUMBER and REPO. RELEVANT=false skips the diff and
 * removes a stale comment. BASE_BUILD_OUTCOME=failure records that the API
 * diff was skipped. --dry-run prints the comment action instead of calling gh.
 */

import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { dryRunClient, readEvent, reconcile } from './comment.mjs';
import { extractReactor } from './maven-graph.mjs';
import { diffModules } from './module-diff.mjs';
import { diffOpenApi } from './openapi-diff.mjs';
import { buildComment, renderOpenApiMarkdown, renderReport, summaryText, BASE_BUILD_FAILED } from './report.mjs';
import { stableStringify } from './util.mjs';

const FLAGS = new Set(['dry-run', 'base-build-failed']);
const VALUED = new Set(['base', 'head', 'base-specs', 'head-specs', 'out', 'pr', 'repo', 'node-cap', 'relevant']);

export function parseRunArgs(argv, env = process.env) {
  const map = {};
  const flags = new Set();
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) throw new Error(`unexpected argument ${arg}`);
    const name = arg.slice(2);
    if (FLAGS.has(name)) {
      flags.add(name);
      continue;
    }
    if (!VALUED.has(name)) throw new Error(`unexpected argument ${arg}`);
    const value = argv[i + 1];
    if (value === undefined || value.startsWith('--')) throw new Error(`missing value for --${name}`);
    map[name] = value;
    i += 1;
  }
  return {
    base: map.base ?? env.BASE_DIR,
    head: map.head ?? env.HEAD_DIR,
    baseSpecs: map['base-specs'] ?? env.BASE_SPECS,
    headSpecs: map['head-specs'] ?? env.HEAD_SPECS,
    out: map.out ?? env.OUT_DIR,
    pr: map.pr ?? env.PR_NUMBER,
    repo: map.repo ?? env.REPO,
    nodeCap: map['node-cap'] ?? env.PR_REVIEW_AID_NODE_CAP,
    relevant: map.relevant ?? env.RELEVANT,
    dryRun: flags.has('dry-run') || env.DRY_RUN === '1',
    baseBuildFailed: flags.has('base-build-failed') || env.BASE_BUILD_OUTCOME === 'failure',
  };
}

function relevantRequested(value) {
  if (value == null || value === '') return true;
  return value !== false && value !== 'false';
}

function safeSpecName(specPath) {
  return specPath.replace(/[^A-Za-z0-9._-]+/g, '__');
}

export function tryWriteSvg(dotPath, svgPath, exec = execFileSync) {
  try {
    exec('dot', ['-Tsvg', '-o', svgPath, dotPath], { stdio: ['ignore', 'pipe', 'pipe'] });
    return true;
  } catch (err) {
    if (err?.code === 'ENOENT') return false;
    throw err;
  }
}

function writeText(file, text) {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, text);
}

/**
 * @param {object} options parsed arguments plus test overrides:
 *   client, event, execFile, summaryFile, outputFile, listDir
 */
export function run(options) {
  const out = options.out;
  if (!out) throw new Error('missing --out');
  mkdirSync(out, { recursive: true });
  const relevant = relevantRequested(options.relevant);
  let report = '';
  let breaking = false;
  let moduleDiff = null;
  let openApi = null;

  if (relevant) {
    if (!options.base || !options.head) throw new Error('missing --base or --head');
    const baseModel = extractReactor(options.base, { listDir: options.listDir });
    const headModel = extractReactor(options.head, { listDir: options.listDir });
    writeText(path.join(out, 'module-graph-base.json'), stableStringify(baseModel));
    writeText(path.join(out, 'module-graph-head.json'), stableStringify(headModel));
    moduleDiff = diffModules(baseModel, headModel, { nodeCap: options.nodeCap });
    if (options.baseBuildFailed) {
      openApi = { breaking: false, sections: [], runs: [] };
    } else {
      openApi = diffOpenApi({
        baseDir: options.baseSpecs,
        headDir: options.headSpecs,
        bin: options.bin,
        execFile: options.execFile,
        listDir: options.listDir,
      });
      breaking = openApi.breaking;
      const oasDir = path.join(out, 'oasdiff');
      for (const entry of openApi.runs) {
        if (entry.kind !== 'changed' && entry.kind !== 'unchanged') continue;
        const name = safeSpecName(entry.path);
        const changelog = entry.changelog.endsWith('\n') || entry.changelog === '' ? entry.changelog : `${entry.changelog}\n`;
        writeText(path.join(oasDir, `${name}.changelog.md`), changelog === '' ? '\n' : changelog);
        writeText(path.join(oasDir, `${name}.breaking.txt`), `exit: ${entry.breakingStatus}\n${entry.breakingText}`);
      }
    }
    writeText(
      path.join(out, 'openapi-diff.json'),
      stableStringify({
        breaking: openApi.breaking,
        entries: openApi.runs.map(({ path: specPath, kind, changelogStatus, breakingStatus }) => ({
          breakingStatus,
          changelogStatus,
          kind,
          path: specPath,
        })),
        ...(options.baseBuildFailed ? { note: BASE_BUILD_FAILED } : {}),
      }),
    );
    report = renderReport({
      openApiMarkdown: renderOpenApiMarkdown(openApi, { baseBuildFailed: options.baseBuildFailed }),
      moduleMarkdown: moduleDiff.markdown,
    });
    if (moduleDiff.dot) writeText(path.join(out, 'module-graph.dot'), moduleDiff.dot);
    else rmSync(path.join(out, 'module-graph.dot'), { force: true });
    if (!moduleDiff.dot) rmSync(path.join(out, 'module-graph.svg'), { force: true });
  }

  const comment = buildComment(report);
  writeText(path.join(out, 'report.md'), report.trim() ? report : 'No module graph or API changes\n');
  const commentPath = path.join(out, 'comment.md');
  if (comment) writeText(commentPath, comment);
  else rmSync(commentPath, { force: true });
  writeText(path.join(out, 'breaking'), breaking ? 'true\n' : 'false\n');

  let svgError = null;
  if (moduleDiff?.dot) {
    try {
      tryWriteSvg(path.join(out, 'module-graph.dot'), path.join(out, 'module-graph.svg'));
    } catch (err) {
      svgError = err;
    }
  } else {
    rmSync(path.join(out, 'module-graph.dot'), { force: true });
    rmSync(path.join(out, 'module-graph.svg'), { force: true });
  }

  const summaryFile = options.summaryFile ?? process.env.GITHUB_STEP_SUMMARY;
  if (summaryFile) appendFileSync(summaryFile, summaryText(report));
  const outputFile = options.outputFile ?? process.env.GITHUB_OUTPUT;
  if (outputFile) appendFileSync(outputFile, `breaking=${breaking ? 'true' : 'false'}\n`);

  if (options.pr && options.repo) {
    const event = options.event !== undefined ? options.event : readEvent();
    const client = options.client ?? (options.dryRun ? dryRunClient(options.log ?? console.log) : undefined);
    reconcile({
      repo: options.repo,
      pr: options.pr,
      body: comment,
      client,
      event,
      log: options.log ?? console.log,
    });
  }

  if (svgError) throw svgError;
  return { report, comment, breaking, moduleDiff, openApi };
}

function main(argv) {
  try {
    run(parseRunArgs(argv));
  } catch (err) {
    console.error(err?.message ?? err);
    process.exit(1);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
