// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Sticky pull-request comment.
 *
 * Finds the first issue comment whose body starts with the review marker and
 * updates it in place. An identical body is left untouched. A null body
 * deletes that comment when one exists and never creates an empty comment.
 *
 * pull_request_target is deliberately not used, so untrusted pull request
 * code never runs with a write token. A fork (head repository different from
 * the base) skips every comment write. A 403 from the token, including
 * "Resource not accessible by integration", is logged and ignored: the job
 * summary and the artifact remain the record of the diff.
 */

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

import { COMMENT_MARKER } from './report.mjs';

export function readEvent(env = process.env) {
  const file = env.GITHUB_EVENT_PATH;
  if (!file) return null;
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch (err) {
    if (err?.code === 'ENOENT') return null;
    throw err;
  }
}

/** Same-repository pull requests can be commented on. Forks cannot, on purpose. */
export function commentWritesAllowed(event) {
  if (!event?.pull_request) return true;
  const head = event.pull_request.head?.repo?.full_name;
  const base = event.pull_request.base?.repo?.full_name;
  if (!head || !base) return false;
  return head === base;
}

export function isPermissionDenied(err) {
  const text = `${err?.status ?? ''}\n${err?.message ?? ''}\n${err?.stderr ?? ''}\n${err?.stdout ?? ''}`;
  return err?.status === 403 || /Resource not accessible by integration/.test(text);
}

function gh(args, input) {
  try {
    return execFileSync('gh', args, {
      encoding: 'utf8',
      input,
      stdio: ['pipe', 'pipe', 'pipe'],
      maxBuffer: 16 * 1024 * 1024,
    });
  } catch (err) {
    const stderr = err.stderr?.toString?.() ?? '';
    const stdout = err.stdout?.toString?.() ?? '';
    const wrapped = new Error(stderr || stdout || err.message);
    wrapped.status = err.status;
    wrapped.stderr = stderr;
    wrapped.stdout = stdout;
    throw wrapped;
  }
}

export function parseJsonValues(text) {
  const src = String(text ?? '').trim();
  if (!src) return [];
  const values = [];
  let depth = 0;
  let start = -1;
  let inString = false;
  let escaped = false;
  for (let i = 0; i < src.length; i++) {
    const ch = src[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (ch === '\\') escaped = true;
      else if (ch === '"') inString = false;
      continue;
    }
    if (ch === '"') {
      inString = true;
      continue;
    }
    if (ch === '{' || ch === '[') {
      if (depth === 0) start = i;
      depth += 1;
    } else if (ch === '}' || ch === ']') {
      depth -= 1;
      if (depth === 0 && start >= 0) {
        values.push(JSON.parse(src.slice(start, i + 1)));
        start = -1;
      }
    }
  }
  return values.flatMap((value) => (Array.isArray(value) ? value : [value]));
}

export function defaultClient() {
  return {
    listComments({ repo, pr }) {
      const body = gh(['api', '--paginate', `repos/${repo}/issues/${pr}/comments`]);
      return parseJsonValues(body);
    },
    createComment({ repo, pr, body }) {
      gh(['api', '--method', 'POST', `repos/${repo}/issues/${pr}/comments`, '--input', '-'], JSON.stringify({ body }));
    },
    updateComment({ repo, commentId, body }) {
      gh(['api', '--method', 'PATCH', `repos/${repo}/issues/comments/${commentId}`, '--input', '-'], JSON.stringify({ body }));
    },
    deleteComment({ repo, commentId }) {
      gh(['api', '--method', 'DELETE', `repos/${repo}/issues/comments/${commentId}`]);
    },
  };
}

export function dryRunClient(log = console.log) {
  return {
    listComments() {
      log('pr-review-aid: would list review comments');
      return [];
    },
    createComment() {
      log('pr-review-aid: would create a review comment');
    },
    updateComment({ commentId }) {
      log(`pr-review-aid: would update comment ${commentId}`);
    },
    deleteComment({ commentId }) {
      log(`pr-review-aid: would delete comment ${commentId}`);
    },
  };
}

function normalize(text) {
  return String(text).replace(/\r\n/g, '\n');
}

function permission(log) {
  log('::notice::The review comment was not updated (Resource not accessible by integration).');
  return { action: 'skip-permission' };
}

/**
 * @param {{ repo: string, pr: string | number, body: string | null, client?: object, event?: object | null, marker?: string, log?: Function }} options
 */
export function reconcile(options) {
  const { repo, pr, body, event = null, marker = COMMENT_MARKER, log = console.log } = options;
  const client = options.client ?? defaultClient();
  if (!/^\d+$/.test(String(pr))) throw new Error('pull request number must be digits');
  if (!/^[\w.-]+\/[\w.-]+$/.test(String(repo))) throw new Error('repository must be owner/name');
  if (!commentWritesAllowed(event)) {
    log('::notice::Pull request head is not in this repository, so the review comment was not updated.');
    return { action: 'skip-fork' };
  }

  let comments;
  try {
    comments = client.listComments({ repo, pr: String(pr) });
  } catch (err) {
    if (isPermissionDenied(err)) return permission(log);
    throw err;
  }
  const existing = (comments ?? []).find((comment) => typeof comment?.body === 'string' && normalize(comment.body).startsWith(marker));

  try {
    if (body == null) {
      if (!existing) return { action: 'noop' };
      client.deleteComment({ repo, pr: String(pr), commentId: existing.id });
      return { action: 'delete', commentId: existing.id };
    }
    if (existing && normalize(existing.body) === normalize(body)) return { action: 'unchanged', commentId: existing.id };
    if (existing) {
      client.updateComment({ repo, pr: String(pr), commentId: existing.id, body });
      return { action: 'update', commentId: existing.id };
    }
    client.createComment({ repo, pr: String(pr), body });
    return { action: 'create' };
  } catch (err) {
    if (isPermissionDenied(err)) return permission(log);
    throw err;
  }
}
