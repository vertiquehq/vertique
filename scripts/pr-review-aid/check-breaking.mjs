// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Exit 1 when the review aid recorded an error-level breaking API change.
 *
 * scripts/pr-review-aid/run.mjs always exits 0 so the comment, job summary
 * and artifact are produced first. This is the workflow's last step. The
 * check is advisory: it is not a required status check.
 *
 *   node scripts/pr-review-aid/check-breaking.mjs --out <dir>
 *
 * OUT_DIR is accepted as well. A missing file exits 0.
 */

import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

function outDir(argv, env) {
  const index = argv.indexOf('--out');
  if (index !== -1) {
    const value = argv[index + 1];
    if (value === undefined || value.startsWith('--')) throw new Error('missing value for --out');
    return value;
  }
  return env.OUT_DIR || 'pr-review-aid-out';
}

export function breakingFrom(dir) {
  const file = path.join(dir, 'breaking');
  if (!existsSync(file)) return false;
  return readFileSync(file, 'utf8').trim() === 'true';
}

function main(argv, env = process.env) {
  if (breakingFrom(outDir(argv, env))) {
    console.error('Breaking API changes found. This advisory check fails the job.');
    process.exit(1);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (err) {
    console.error(err?.message ?? err);
    process.exit(1);
  }
}
