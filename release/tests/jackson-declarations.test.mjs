// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const selfTest = resolve('scripts/test-verify-jackson-declarations.sh');
const checker = resolve('scripts/verify-jackson-declarations.sh');

test('the Jackson declarations checker self-test passes', () => {
  const result = spawnSync('bash', [selfTest], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
});

test('every module in the repository declares the Jackson artifacts it imports', () => {
  const result = spawnSync('bash', [checker], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
});
