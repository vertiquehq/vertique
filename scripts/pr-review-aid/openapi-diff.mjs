// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * OpenAPI diff between two directories of collected spec files.
 *
 * A path present on both sides is compared with `oasdiff changelog` and
 * `oasdiff breaking --fail-on ERR`. An empty changelog is an unchanged spec
 * and is omitted. A path on only one side is reported as a new or removed
 * API document and does not invoke oasdiff. The binary comes from OASDIFF_BIN
 * (default `oasdiff`) and is executed without a shell.
 */

import { execFileSync } from 'node:child_process';
import { readdirSync, statSync } from 'node:fs';
import path from 'node:path';

import { cmp } from './util.mjs';

export function runCaptured(bin, args, exec = execFileSync) {
  try {
    const stdout = exec(bin, args, {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      maxBuffer: 16 * 1024 * 1024,
    });
    return { status: 0, stdout: stdout ?? '', stderr: '' };
  } catch (err) {
    if (err?.code === 'ENOENT') throw new Error(`oasdiff executable not found: ${bin}`);
    if (typeof err?.status !== 'number') throw err;
    return {
      status: err.status,
      stdout: err.stdout?.toString?.() ?? '',
      stderr: err.stderr?.toString?.() ?? '',
    };
  }
}

/** True when oasdiff printed no API changes. A title plus "No changes detected" counts as empty. */
export function changelogIsEmpty(stdout) {
  const lines = String(stdout)
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => !line.startsWith('# API Changelog'))
    .filter((line) => line !== 'No changes detected');
  return lines.length === 0;
}

function listSpecs(dir, listDir) {
  const found = new Map();
  if (!dir) return found;
  const walk = (current) => {
    let entries;
    try {
      entries = listDir(current);
    } catch (err) {
      if (err?.code === 'ENOENT') return;
      throw err;
    }
    for (const entry of [...entries].map((item) => (typeof item === 'string' ? item : item.name)).sort(cmp)) {
      const full = path.join(current, entry);
      const stat = statSync(full);
      if (stat.isDirectory()) walk(full);
      else if (entry === 'openapi.yaml') {
        const rel = path.relative(dir, full).split(path.sep).join('/');
        found.set(rel, full);
      }
    }
  };
  walk(dir);
  return found;
}

function scrub(text, pairs) {
  let out = String(text);
  for (const [from, to] of pairs) {
    if (from) out = out.split(from).join(to);
  }
  return out;
}

/**
 * @param {{ baseDir?: string, headDir?: string, bin?: string, execFile?: Function, listDir?: Function }} options
 */
export function diffOpenApi(options = {}) {
  const bin = options.bin || process.env.OASDIFF_BIN || 'oasdiff';
  const execFile = options.execFile;
  const listDir = options.listDir ?? readdirSync;
  const baseFiles = listSpecs(options.baseDir, listDir);
  const headFiles = listSpecs(options.headDir, listDir);
  const keys = [...new Set([...baseFiles.keys(), ...headFiles.keys()])].sort(cmp);
  const sections = [];
  const runs = [];
  let breaking = false;

  for (const key of keys) {
    const baseFile = baseFiles.get(key);
    const headFile = headFiles.get(key);
    if (baseFile && headFile) {
      const changelog = runCaptured(bin, ['changelog', baseFile, headFile, '--format', 'markdown'], execFile);
      const broken = runCaptured(bin, ['breaking', baseFile, headFile, '--fail-on', 'ERR'], execFile);
      const replacements = [
        [baseFile, key],
        [headFile, key],
      ];
      const changelogText = scrub(changelog.stdout, replacements);
      const breakingText = scrub(broken.stdout, replacements);
      const empty = changelogIsEmpty(changelogText);
      if (broken.status !== 0) breaking = true;
      runs.push({
        path: key,
        kind: empty ? 'unchanged' : 'changed',
        changelogStatus: changelog.status,
        breakingStatus: broken.status,
        changelog: changelogText,
        breakingText,
      });
      if (!empty) {
        sections.push({
          path: key,
          kind: 'changed',
          markdown: changelogText.trim(),
          breaking: broken.status !== 0,
          breakingText: broken.status !== 0 ? breakingText.trim() : '',
        });
      }
      continue;
    }
    if (headFile) {
      runs.push({ path: key, kind: 'new', changelogStatus: null, breakingStatus: null, changelog: '', breakingText: '' });
      sections.push({ path: key, kind: 'new', markdown: 'new API document', breaking: false, breakingText: '' });
      continue;
    }
    runs.push({ path: key, kind: 'removed', changelogStatus: null, breakingStatus: null, changelog: '', breakingText: '' });
    sections.push({ path: key, kind: 'removed', markdown: 'removed API document', breaking: false, breakingText: '' });
  }

  return { breaking, sections, runs };
}
