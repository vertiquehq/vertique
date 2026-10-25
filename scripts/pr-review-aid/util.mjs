// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import path from 'node:path';

/** Locale-independent string compare. Do not use localeCompare. */
export function cmp(a, b) {
  const left = String(a);
  const right = String(b);
  if (left < right) return -1;
  if (left > right) return 1;
  return 0;
}

export function sortBy(items, ...keys) {
  return [...items].sort((a, b) => {
    for (const key of keys) {
      const av = typeof key === 'function' ? key(a) : a[key];
      const bv = typeof key === 'function' ? key(b) : b[key];
      const order = cmp(av, bv);
      if (order !== 0) return order;
    }
    return 0;
  });
}

function sortValue(value) {
  if (Array.isArray(value)) return value.map(sortValue);
  if (value && typeof value === 'object') {
    const sorted = {};
    for (const key of Object.keys(value).sort(cmp)) sorted[key] = sortValue(value[key]);
    return sorted;
  }
  return value;
}

/** Stable JSON with sorted object keys and a trailing newline. Arrays keep their order. */
export function stableStringify(value) {
  return `${JSON.stringify(sortValue(value), null, 2)}\n`;
}

export function decodeXml(value) {
  return String(value)
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'");
}

/** Untrusted text placed inside a Markdown code span. */
export function inlineCode(value) {
  const text = String(value).replace(/\|/g, '\\|').replace(/`/g, '\\`').replace(/\r?\n/g, ' ');
  return `\`${text}\``;
}

export function escapeTable(value) {
  return String(value).replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
}

/** Mermaid text inside a quoted label. */
export function escapeMermaid(value) {
  return String(value)
    .replace(/#/g, '#35;')
    .replace(/&/g, '&amp;')
    .replace(/"/g, '#quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\[/g, '#91;')
    .replace(/\]/g, '#93;')
    .replace(/\{/g, '#123;')
    .replace(/\}/g, '#125;')
    .replace(/\|/g, '#124;')
    .replace(/\r?\n/g, ' ');
}

export function escapeDot(value) {
  return String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r?\n/g, ' ');
}

export function relativePosix(root, dir) {
  const rel = path.relative(root, dir);
  if (!rel || rel === '.') return '.';
  return rel.split(path.sep).join('/');
}
