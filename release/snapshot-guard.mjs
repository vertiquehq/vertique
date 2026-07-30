#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * RELEASE-001 — mutable SNAPSHOT publication guard
 * (FR-REL-020, FR-REL-021, FR-REL-022, FR-REL-023; ADR 0006).
 *
 * SNAPSHOT publication is the only product-owned workflow that holds a write
 * credential, so the conditions under which it may run ARE the security
 * boundary. Those conditions live here, as a pure function, so they can be
 * tested exhaustively without a workflow run — and so the workflow YAML does
 * not become the place where security logic is written.
 *
 * The guard is deny-by-default and never throws: a caller with a loose error
 * path could otherwise read a thrown exception as "no decision" and continue.
 * Every rejection carries a reason.
 *
 * Usage (from the workflow, reading the event payload):
 *   node release/snapshot-guard.mjs --github-output >> "$GITHUB_OUTPUT"
 */

import { existsSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SHA1_RE = /^[0-9a-f]{40}$/;
const SNAPSHOT_VERSION_RE = /-SNAPSHOT$/;

/** Deny with a reason. */
const deny = (reason) => ({ allowed: false, reason, checkoutSha: null });

/**
 * Decides whether a SNAPSHOT publication may proceed.
 *
 * @param {object} inputs
 * @param {string} inputs.event               triggering event name
 * @param {string} inputs.conclusion          the required-CI run's conclusion
 * @param {string} inputs.workflowName        the workflow that completed
 * @param {string} inputs.requiredWorkflowName the named required-CI workflow
 * @param {string} inputs.owningRepository    this product's own repository
 * @param {string} inputs.headRepository      the repository the run belongs to
 * @param {string} inputs.headBranch          the branch the run was for
 * @param {string} inputs.headSha             the commit the run verified
 * @param {string} inputs.currentMainSha      remote main's current commit
 * @param {string} inputs.version             the effective project version
 * @param {string[]} inputs.existingFinalTags final tags already published
 * @returns {{allowed: boolean, reason: string, checkoutSha: string|null}}
 */
export function evaluateSnapshotPublication(inputs) {
  if (!inputs || typeof inputs !== 'object') return deny('no publication context supplied');

  const {
    event,
    conclusion,
    workflowName,
    requiredWorkflowName,
    owningRepository,
    headRepository,
    headBranch,
    headSha,
    currentMainSha,
    version,
    existingFinalTags = [],
  } = inputs;

  // Only the completion of the named required-CI workflow may trigger this.
  // Binding to push instead would publish unverified bytes.
  if (event !== 'workflow_run') return deny(`event "${event}" is not workflow_run`);
  if (!requiredWorkflowName || workflowName !== requiredWorkflowName) {
    return deny(`triggering workflow "${workflowName}" is not the required CI workflow "${requiredWorkflowName}"`);
  }
  if (conclusion !== 'success') return deny(`required CI concluded "${conclusion}", not success`);

  // A fork's run must never publish to this repository's packages.
  if (!owningRepository) return deny('owning repository not supplied');
  if (headRepository !== owningRepository) {
    return deny(`run belongs to "${headRepository}", not the owning repository "${owningRepository}"`);
  }

  // main only: no tag, no feature branch, no detached run.
  if (headBranch !== 'main') return deny(`head branch "${headBranch}" is not main`);

  // The verified commit must still be main. A run that succeeded on a commit
  // since superseded would overwrite the mutable snapshot with older bytes.
  if (!SHA1_RE.test(headSha ?? '')) return deny(`head SHA "${headSha}" is not a 40-character commit`);
  if (!SHA1_RE.test(currentMainSha ?? '')) return deny(`current main SHA "${currentMainSha}" is not a 40-character commit`);
  if (headSha !== currentMainSha) {
    return deny(`head ${headSha} is no longer current main ${currentMainSha}; a newer commit supersedes it`);
  }

  // GitHub Packages is SNAPSHOT-only. A final version never publishes here.
  if (!version) return deny('no project version supplied');
  if (!SNAPSHOT_VERSION_RE.test(version)) return deny(`version "${version}" is not a SNAPSHOT`);

  // Once a line is released, it stops receiving mutable snapshots.
  const releasedLine = version.replace(SNAPSHOT_VERSION_RE, '');
  if (existingFinalTags.includes(`v${releasedLine}`)) {
    return deny(`version "${version}" has already been released as v${releasedLine}`);
  }

  return { allowed: true, reason: 'successful required CI on current main at a SNAPSHOT version', checkoutSha: headSha };
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  const readEvent = () => {
    const eventPath = process.env.GITHUB_EVENT_PATH;
    if (!eventPath || !existsSync(eventPath)) return {};
    try {
      return JSON.parse(readFileSync(eventPath, 'utf8'));
    } catch {
      return {};
    }
  };

  // Context is collected here rather than in a separate shell step so the
  // workflow YAML stays a pure event/permission binding and every input the
  // decision depends on comes from one versioned, locally runnable place.
  const gitOutput = (args, fallback = '') => {
    const result = spawnSync('git', args, { encoding: 'utf8' });
    return result.status === 0 ? result.stdout.trim() : fallback;
  };

  /** Reads the declared `<revision>` from the reactor root POM. */
  const declaredVersion = () => {
    const pomPath = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'pom.xml');
    if (!existsSync(pomPath)) return undefined;
    return /<revision>([^<]+)<\/revision>/.exec(readFileSync(pomPath, 'utf8'))?.[1]?.trim();
  };

  const payload = readEvent();
  const run = payload.workflow_run ?? {};
  const decision = evaluateSnapshotPublication({
    event: process.env.GITHUB_EVENT_NAME,
    conclusion: run.conclusion,
    workflowName: run.name,
    requiredWorkflowName: process.env.VERTIQUE_REQUIRED_WORKFLOW ?? 'CI',
    owningRepository: process.env.GITHUB_REPOSITORY,
    headRepository: run.head_repository?.full_name,
    headBranch: run.head_branch,
    headSha: run.head_sha,
    // Resolved from the remote, not from the local checkout: the checkout is
    // pinned to the triggering SHA, so asking it what main is would always
    // agree with itself and prove nothing.
    currentMainSha:
      process.env.VERTIQUE_CURRENT_MAIN_SHA ||
      gitOutput(['ls-remote', 'origin', 'refs/heads/main']).split(/\s+/)[0],
    version: process.env.VERTIQUE_VERSION || declaredVersion(),
    existingFinalTags: (process.env.VERTIQUE_FINAL_TAGS || gitOutput(['tag', '--list', 'v*']))
      .split(/\s+/)
      .filter(Boolean),
  });

  if (process.argv.includes('--github-output')) {
    console.log(`allowed=${decision.allowed}`);
    console.log(`checkout_sha=${decision.checkoutSha ?? ''}`);
    console.log(`version=${decision.allowed ? (process.env.VERTIQUE_VERSION || declaredVersion()) : ''}`);
    console.log(`reason=${decision.reason}`);
  } else {
    console.log(`snapshot-guard: ${decision.allowed ? 'ALLOW' : 'DENY'} — ${decision.reason}`);
  }
  // Exit 0 either way: a denial is a normal, expected outcome, not a workflow
  // failure. A red run here would be indistinguishable from a real fault.
  process.exit(0);
}
