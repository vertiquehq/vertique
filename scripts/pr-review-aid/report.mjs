// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Pull request review report and the sticky comment derived from it.
 *
 * The comment starts with the marker, then a short header, then the report.
 * It stays within 60,000 characters by dropping whole sections or spec
 * blocks and pointing at the job summary and artifact. The summary receives
 * the full report, truncated only past about 900 KiB.
 *
 * An oasdiff changelog drops its top-level title and shifts the remaining
 * headings down so they sit under the spec heading. The changelog is wrapped
 * in a details block. Error-level breaking changes stay outside that block.
 */

import { inlineCode } from './util.mjs';

export const COMMENT_MARKER = '<!-- vertique-pr-review-aid -->';
export const COMMENT_CHAR_LIMIT = 60000;
export const SUMMARY_BYTE_LIMIT = 900 * 1024;
export const BASE_BUILD_FAILED = 'base build failed, API diff skipped';
export const SILENT_NOTE = 'No module graph or API changes\n';
export const TRUNCATION_POINTER = 'Truncated. The full report is in the job summary and the `pr-review-aid` artifact.';

const SUMMARY_POINTER = 'Summary truncated. The full report is in the `pr-review-aid` artifact.';

/** Drop the leading level-1 title and shift every remaining heading down by two levels. */
export function demoteChangelog(markdown) {
  const lines = String(markdown).replace(/\r\n/g, '\n').split('\n');
  let droppedTitle = false;
  const kept = [];
  for (const line of lines) {
    if (!droppedTitle && /^# /.test(line)) {
      droppedTitle = true;
      continue;
    }
    const match = /^(#{1,6})( .*)$/.exec(line);
    if (!match) {
      kept.push(line);
      continue;
    }
    const level = Math.min(6, match[1].length + 2);
    kept.push(`${'#'.repeat(level)}${match[2]}`);
  }
  return kept.join('\n').trim();
}

/** Count operation headings in an oasdiff changelog, before they are demoted. */
export function changedOperationCount(markdown) {
  return String(markdown).split('\n').filter((line) => /^### /.test(line)).length;
}

function breakingBlock(text) {
  const lines = String(text)
    .split('\n')
    .map((line) => line.replace(/\t/g, ' ').replace(/ +/g, ' ').trim())
    .filter(Boolean);
  if (lines.length === 0) return 'Breaking changes (ERR)\n';
  return `Breaking changes (ERR)\n\n${lines.map((line) => `- ${inlineCode(line)}`).join('\n')}\n`;
}

function changelogDetails(sectionPath, body, count, open) {
  const noun = count === 1 ? 'operation' : 'operations';
  const openAttr = open ? ' open' : '';
  return `<details${openAttr}>\n<summary>${inlineCode(sectionPath)} (${count} ${noun})</summary>\n\n${body}\n\n</details>\n`;
}

function renderSpec(section, openDetails) {
  const heading = `### ${inlineCode(section.path)}\n`;
  if (section.kind !== 'changed') return `${heading}\n${String(section.markdown).trim()}\n`;
  const parts = [heading];
  if (section.breaking) parts.push(`\n${breakingBlock(section.breakingText)}\n`);
  const body = demoteChangelog(section.markdown);
  const count = changedOperationCount(section.markdown);
  parts.push(`\n${changelogDetails(section.path, body, count, openDetails)}`);
  return parts.join('');
}

export function renderOpenApiMarkdown(diff, { baseBuildFailed = false } = {}) {
  if (baseBuildFailed) return `${BASE_BUILD_FAILED}\n`;
  if (!diff || diff.sections.length === 0) return '';
  const openDetails = diff.sections.length === 1 && diff.sections[0].kind === 'changed';
  return diff.sections.map((section) => renderSpec(section, openDetails)).join('\n');
}

export function renderReport({ openApiMarkdown = '', moduleMarkdown = '' } = {}) {
  const sections = [];
  if (openApiMarkdown.trim()) sections.push(`## OpenAPI changes\n\n${openApiMarkdown.trim()}\n`);
  if (moduleMarkdown.trim()) sections.push(`## Module graph changes\n\n${moduleMarkdown.trim()}\n`);
  return sections.join('\n');
}

export function splitSections(text) {
  const normalized = text.endsWith('\n') ? text : `${text}\n`;
  const lines = normalized.slice(0, -1).split('\n');
  const chunks = [];
  let buf = [];
  const flush = () => {
    if (buf.length === 0) return;
    chunks.push(`${buf.join('\n')}\n`);
    buf = [];
  };
  for (const line of lines) {
    if (/^#{2,3} /.test(line) && buf.length > 0) flush();
    buf.push(line);
  }
  flush();
  return chunks;
}

function takeWholeSections(text, budget) {
  if (budget <= 0) return '';
  let kept = '';
  for (const chunk of splitSections(text)) {
    if (kept.length + chunk.length > budget) break;
    kept += chunk;
  }
  return kept;
}

export function buildComment(report, limit = COMMENT_CHAR_LIMIT) {
  if (!report || !report.trim()) return null;
  const header = '## Pull request review';
  const prefix = `${COMMENT_MARKER}\n${header}\n\n`;
  const body = report.endsWith('\n') ? report : `${report}\n`;
  const full = prefix + body;
  if (full.length <= limit) return full;
  const note = `\n\n_${TRUNCATION_POINTER}_\n`;
  const budget = limit - prefix.length - note.length;
  const kept = takeWholeSections(body, budget).trimEnd();
  const comment = kept ? `${prefix}${kept}${note}` : `${prefix.trimEnd()}\n${note}`;
  if (comment.length <= limit) return comment;
  return comment.slice(0, limit);
}

export function summaryText(report, limit = SUMMARY_BYTE_LIMIT) {
  if (!report || !report.trim()) return SILENT_NOTE;
  const text = report.endsWith('\n') ? report : `${report}\n`;
  if (Buffer.byteLength(text) <= limit) return text;
  const note = `\n_${SUMMARY_POINTER}_\n`;
  const budget = limit - Buffer.byteLength(note);
  let kept = '';
  for (const chunk of splitSections(text)) {
    if (Buffer.byteLength(kept) + Buffer.byteLength(chunk) > budget) break;
    kept += chunk;
  }
  if (!kept) {
    const buf = Buffer.from(text);
    let end = Math.max(0, budget);
    while (end > 0 && buf[end - 1] !== 0x0a) end -= 1;
    kept = buf.subarray(0, end).toString('utf8');
  }
  return `${kept}${note}`;
}
