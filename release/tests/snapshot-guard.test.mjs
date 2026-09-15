// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * RELEASE-001 S9 — mutable SNAPSHOT publication guard
 * (FR-REL-020, FR-REL-021, FR-REL-022, FR-REL-023; ADR 0006).
 *
 * SNAPSHOT publication is the one product-owned workflow holding a write
 * credential, so the conditions under which it may run are the security
 * boundary. The guard is a pure decision function precisely so those conditions
 * are testable without a workflow run.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { evaluateSnapshotPublication } from '../snapshot-guard.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const WORKFLOW_PATH = path.join(REPO_ROOT, '.github', 'workflows', 'snapshot.yml');

const OWNING_REPOSITORY = 'vertiquehq/vertique';
const MAIN_SHA = 'a'.repeat(40);

/** The one input shape that must be allowed, shallow-merged with `overrides`. */
const allowedInputs = (overrides = {}) => ({
  event: 'workflow_run',
  conclusion: 'success',
  workflowName: 'CI',
  requiredWorkflowName: 'CI',
  owningRepository: OWNING_REPOSITORY,
  headRepository: OWNING_REPOSITORY,
  headBranch: 'main',
  headSha: MAIN_SHA,
  currentMainSha: MAIN_SHA,
  version: '0.1.0-SNAPSHOT',
  existingFinalTags: [],
  ...overrides,
});

describe('SnapshotGuardTest', () => {
  it('acceptsSuccessfulMainSnapshotOnly', () => {
    const decision = evaluateSnapshotPublication(allowedInputs());
    assert.equal(decision.allowed, true, decision.reason);
    // The checkout must be the exact SHA that was verified, not a moving ref.
    assert.equal(decision.checkoutSha, MAIN_SHA);
  });

  it('rejectsPullRequestForkTagDetachedReleaseAndReleasedLine', () => {
    const denied = {
      'pull request': { event: 'pull_request' },
      'failed required CI': { conclusion: 'failure' },
      'cancelled required CI': { conclusion: 'cancelled' },
      'a different workflow': { workflowName: 'Some Other Workflow' },
      fork: { headRepository: 'someone-else/vertique' },
      'non-main branch': { headBranch: 'feat/thing' },
      tag: { headBranch: 'refs/tags/v0.1.0' },
      // The run succeeded on a commit that is no longer main: publishing it
      // would overwrite the snapshot with older bytes.
      'superseded head': { currentMainSha: 'b'.repeat(40) },
      'final version': { version: '0.1.0' },
      // A released line must not keep receiving mutable snapshots.
      'already-released line': { version: '0.1.0-SNAPSHOT', existingFinalTags: ['v0.1.0'] },
    };

    for (const [label, override] of Object.entries(denied)) {
      const decision = evaluateSnapshotPublication(allowedInputs(override));
      assert.equal(decision.allowed, false, `${label} must be denied`);
      assert.ok(decision.reason && decision.reason.length > 0, `${label} must state a reason`);
    }
  });

  it('deniesRatherThanThrowsOnMissingInput', () => {
    // A guard that throws on an unexpected shape could be read as "no decision"
    // by a caller with a loose error path. It must always deny explicitly.
    for (const inputs of [{}, null, undefined, { event: 'workflow_run' }]) {
      const decision = evaluateSnapshotPublication(inputs);
      assert.equal(decision.allowed, false);
      assert.ok(decision.reason);
    }
  });
});

describe('PublicSnapshotWorkflowContractTest', () => {
  const workflow = () => readFileSync(WORKFLOW_PATH, 'utf8');

  it('bindsSuccessfulCurrentMainCiToExactSha', () => {
    const yaml = workflow();
    // Bound to the named required-CI workflow's completion, not to push.
    assert.match(yaml, /workflow_run:/, 'snapshot publication must be bound to workflow_run');
    assert.match(yaml, /workflows:\s*\[?\s*["']?CI["']?/, 'it must name the required CI workflow');
    assert.match(yaml, /types:\s*\[\s*completed\s*\]/, 'it must trigger on completion');
    assert.match(yaml, /branches:\s*\[?\s*main\s*\]?/, 'it must be limited to main');
    // The checkout must pin the exact triggering SHA, and fetch every ref: the
    // guard reads current main from the fetched refs/remotes/origin/main, the
    // only credentialed view of the remote the job gets once the checkout has
    // discarded its token.
    assert.match(
      yaml,
      /ref:\s*\$\{\{\s*github\.event\.workflow_run\.head_sha/,
      'checkout must use the exact triggering head_sha, not a moving ref'
    );
    assert.match(yaml, /fetch-depth:\s*0/, 'the checkout must fetch all refs so origin/main is available to the guard');
    // The decision itself is delegated to the versioned guard.
    assert.match(yaml, /snapshot-guard\.mjs/, 'the workflow must delegate the decision to the guard');
  });

  it('usesLocalMinimalTokenAndRemainsNonRequired', () => {
    const yaml = workflow();

    // Only the repository-local token, with exactly the two permissions needed.
    const granted = Object.fromEntries(
      [...yaml.matchAll(/^\s*(contents|packages|pull-requests|id-token|actions|checks|deployments|issues|statuses|security-events|attestations|pages):\s*(\S+)\s*$/gm)]
        .map((m) => [m[1], m[2]])
    );
    assert.equal(granted.contents, 'read', 'contents must be read');
    assert.equal(granted.packages, 'write', 'packages must be write');
    for (const [scope, level] of Object.entries(granted)) {
      if (scope !== 'packages') {
        assert.equal(level, 'read', `permission ${scope} must be read, got "${level}"`);
      }
    }
    // It must not be able to write a CI status, which would let a failed
    // publication erase the established required-CI result (FR-REL-023).
    assert.ok(!('statuses' in granted), 'snapshot publication must not write CI statuses');
    assert.ok(!('checks' in granted), 'snapshot publication must not write checks');

    // No cross-repository App credential: this is a repository-local job.
    const secretRefs = [...yaml.matchAll(/\$\{\{\s*secrets\.([A-Za-z0-9_]+)/g)].map((m) => m[1]);
    assert.deepEqual(
      secretRefs.filter((s) => s !== 'GITHUB_TOKEN'),
      [],
      `snapshot publication must use only the local GITHUB_TOKEN, found ${secretRefs}`
    );

    // An in-flight deployment must never be cancelled (FR-REL-022).
    assert.match(yaml, /concurrency:/, 'snapshot publication must be serialized');
    assert.match(yaml, /cancel-in-progress:\s*false/, 'an in-flight deployment must not be cancelled');

    // Substantive logic stays in versioned entry points.
    const bodies = [...yaml.matchAll(/^\s*-?\s*run:\s*(.*)$/gm)].map((m) => m[1].trim()).filter(Boolean);
    for (const command of bodies) {
      assert.match(
        command,
        /^(\.\/mvnw|bash\s+\S+\.sh|node\s+(--test\s+)?\S+\.mjs)\b/,
        `snapshot workflow step embeds logic: "${command}"`
      );
    }
  });
});
