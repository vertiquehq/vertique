// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Workflow contract for the pull request review aid.
 *
 * The workflow binds the event and the permissions. Builds, the oasdiff
 * install, and the diff live in repository scripts.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(TEST_DIR, '..', '..');
const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'pr-review-aid.yml');
const { changedPaths, classifyPaths, isRelevantPath, mergeBase } = await import(
  pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'classify.mjs')).href,
);

function runStepBodies(yaml) {
  const bodies = [];
  const lines = yaml.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const match = /^(\s*)-?\s*run:\s*(\|[-+]?|>[-+]?)?\s*(.*)$/.exec(lines[i]);
    if (!match) continue;
    const [, indent, block, inline] = match;
    if (!block) {
      bodies.push(inline.trim());
      continue;
    }
    const body = [];
    for (let j = i + 1; j < lines.length; j++) {
      if (lines[j].trim() === '') continue;
      if (lines[j].match(/^\s*/)[0].length <= indent.length) break;
      body.push(lines[j].trim());
    }
    bodies.push(body.join('\n'));
  }
  return bodies;
}

describe('review aid path classification', () => {
  it('selects poms, main sources, and this tool', () => {
    assert.equal(isRelevantPath('pom.xml'), true);
    assert.equal(isRelevantPath('vertique-core/pom.xml'), true);
    assert.equal(isRelevantPath('vertique-core/src/main/java/A.java'), true);
    assert.equal(isRelevantPath('src/main/resources/openapi.yaml'), true);
    assert.equal(isRelevantPath('scripts/pr-review-aid/run.mjs'), true);
    assert.equal(isRelevantPath('vertique-core/src/test/java/A.java'), false);
    assert.equal(isRelevantPath('README.md'), false);
    assert.equal(isRelevantPath('.github/workflows/pr-review-aid.yml'), false);
    assert.equal(classifyPaths(['README.md', 'vertique-core/pom.xml']), true);
    assert.equal(classifyPaths(['README.md']), false);
    assert.equal(classifyPaths([]), false);
  });

  it('classifies a three-dot diff', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-classify-'));
    const git = (args) => execFileSync('git', args, { cwd: dir, stdio: 'pipe' });
    git(['init', '--quiet', '--initial-branch=main']);
    git(['config', 'user.email', 'test@invalid']);
    git(['config', 'user.name', 'test']);
    git(['config', 'commit.gpgsign', 'false']);
    writeFileSync(path.join(dir, 'README.md'), 'a\n');
    git(['add', 'README.md']);
    git(['commit', '-q', '-m', 'base']);
    const base = git(['rev-parse', 'HEAD']).toString().trim();
    mkdirSync(path.join(dir, 'src', 'main'), { recursive: true });
    writeFileSync(path.join(dir, 'src', 'main', 'A.java'), 'class A {}\n');
    git(['add', 'src/main/A.java']);
    git(['commit', '-q', '-m', 'head']);
    const head = git(['rev-parse', 'HEAD']).toString().trim();
    const script = path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'classify.mjs');
    const relevant = execFileSync('node', [script, '--base', base, '--head', head], { cwd: dir, encoding: 'utf8' });
    assert.equal(relevant.trim(), 'relevant=true');
    const docs = execFileSync('node', [script, '--base', base, '--head', base], { cwd: dir, encoding: 'utf8' });
    assert.equal(docs.trim(), 'relevant=false');
    rmSync(dir, { recursive: true, force: true });
  });

  it('uses the merge base when the base branch has moved', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-merge-base-'));
    const baseDir = mkdtempSync(path.join(tmpdir(), 'pr-review-merge-base-wt-'));
    rmSync(baseDir, { recursive: true, force: true });
    const git = (args, cwd = dir) => execFileSync('git', args, { cwd, stdio: 'pipe' });
    git(['init', '--quiet', '--initial-branch=main']);
    git(['config', 'user.email', 'test@invalid']);
    git(['config', 'user.name', 'test']);
    git(['config', 'commit.gpgsign', 'false']);
    writeFileSync(path.join(dir, 'README.md'), 'start\n');
    git(['add', 'README.md']);
    git(['commit', '-q', '-m', 'start']);
    const branchPoint = git(['rev-parse', 'HEAD']).toString().trim();
    git(['checkout', '-q', '-b', 'feature']);
    writeFileSync(path.join(dir, 'README.md'), 'feature\n');
    git(['add', 'README.md']);
    git(['commit', '-q', '-m', 'feature']);
    const head = git(['rev-parse', 'HEAD']).toString().trim();
    git(['checkout', '-q', 'main']);
    mkdirSync(path.join(dir, 'src', 'main'), { recursive: true });
    writeFileSync(path.join(dir, 'src', 'main', 'Later.java'), 'class Later {}\n');
    git(['add', 'src/main/Later.java']);
    git(['commit', '-q', '-m', 'main moved']);
    const baseTip = git(['rev-parse', 'HEAD']).toString().trim();

    assert.equal(mergeBase(baseTip, head, dir), branchPoint);
    assert.deepEqual(changedPaths(baseTip, head, dir), ['README.md']);
    assert.equal(classifyPaths(changedPaths(baseTip, head, dir)), false);
    const script = path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'classify.mjs');
    const relevant = execFileSync('node', [script, '--base', baseTip, '--head', head], { cwd: dir, encoding: 'utf8' });
    assert.equal(relevant.trim(), 'relevant=false');

    execFileSync('bash', [path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'checkout-base.sh')], {
      cwd: dir,
      env: { ...process.env, BASE_SHA: baseTip, HEAD_SHA: head, BASE_DIR: baseDir },
    });
    assert.equal(git(['rev-parse', 'HEAD'], baseDir).toString().trim(), branchPoint);
    assert.equal(readFileSync(path.join(baseDir, 'README.md'), 'utf8'), 'start\n');
    assert.equal(existsSync(path.join(baseDir, 'src', 'main', 'Later.java')), false);
    git(['worktree', 'remove', '--force', baseDir]);
    rmSync(dir, { recursive: true, force: true });
  });
});

describe('PR review aid workflow', () => {
  const yaml = () => readFileSync(WORKFLOW, 'utf8');

  it('runs on pull requests without a branch or path filter', () => {
    const text = yaml();
    assert.match(text, /^name: PR Review Aid\n/m);
    assert.match(text, /^on:\n {2}pull_request:\n {4}types: \[opened, synchronize, reopened, ready_for_review\]\n\n/m);
    assert.doesNotMatch(text, /^\s+branches:/m);
    assert.doesNotMatch(text, /^\s+paths:/m);
    assert.doesNotMatch(text, /^\s*pull_request_target:/m);
    assert.doesNotMatch(text, /^\s*workflow_run:/m);
    assert.match(text, /pull_request_target is deliberately not used/);
    assert.match(text, /[Nn]ot a required check/);
  });

  it('grants read contents at the top and comment write only on the job', () => {
    const text = yaml();
    const granted = [...text.matchAll(/^( *)(contents|packages|pull-requests|id-token|actions|checks|deployments|issues|statuses|security-events|attestations|pages):\s*(\S+)\s*$/gm)];
    assert.deepEqual(
      granted.map((match) => `${match[1].length}:${match[2]}:${match[3]}`),
      ['2:contents:read', '6:contents:read', '6:pull-requests:write'],
    );
    const secrets = [...text.matchAll(/secrets\.([A-Za-z0-9_]+)/g)].map((match) => match[1]);
    assert.deepEqual(secrets, ['GITHUB_TOKEN']);
  });

  it('pins every action and the oasdiff release', () => {
    const text = yaml();
    const uses = [...text.matchAll(/^\s*-?\s*uses:\s*(\S+)\s+#\s*(v\d[\w.]*)\s*$/gm)];
    const allUses = [...text.matchAll(/^\s*-?\s*uses:/gm)];
    assert.equal(uses.length, allUses.length);
    assert.ok(uses.length >= 5);
    for (const [, spec, version] of uses) {
      assert.match(spec, /@[0-9a-f]{40}$/, spec);
      assert.match(version, /^v\d/);
    }
    assert.match(text, /OASDIFF_VERSION:\s*"1\.34\.1"/);
    assert.match(text, /OASDIFF_SHA256:\s*"75775f65b0adebd26a8e013af51a799342dfdc6262d89efa9e3b7bdf89657d90"/);
    assert.doesNotMatch(text, /oasdiff-action/);
    assert.doesNotMatch(text, /review:\s*true/);
    const install = readFileSync(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'install-oasdiff.sh'), 'utf8');
    const verifyAt = install.search(/sha256sum -c -|shasum -a 256 -c -/);
    const extractAt = install.indexOf('tar -xzf');
    assert.ok(verifyAt > 0 && verifyAt < extractAt, 'checksum must be verified before the archive is extracted');
    const installStep = text.indexOf('bash scripts/pr-review-aid/install-oasdiff.sh');
    const runStep = text.indexOf('node scripts/pr-review-aid/run.mjs');
    assert.ok(installStep > 0 && installStep < runStep);
  });

  it('keeps run steps on repository entry points', () => {
    const bodies = runStepBodies(yaml());
    const allowed = /^(node scripts\/pr-review-aid\/[A-Za-z0-9._-]+\.mjs|bash scripts\/pr-review-aid\/[A-Za-z0-9._-]+\.sh|bash scripts\/compute-maven-cache-key\.sh)( >> "\$GITHUB_OUTPUT")?$/;
    assert.ok(bodies.length > 0);
    for (const body of bodies) {
      for (const command of body.split('\n').filter(Boolean)) {
        assert.match(command, allowed, command);
      }
      assert.doesNotMatch(body, /\b(if|for|while|case)\b\s|&&|\|\||;\s*\w|\$\{\{/, body);
    }
    assert.ok(bodies.includes('node scripts/pr-review-aid/run.mjs'));
    assert.ok(bodies.includes('node scripts/pr-review-aid/check-breaking.mjs'));
    assert.equal(bodies.filter((body) => body === 'bash scripts/pr-review-aid/build-specs.sh').length, 2);
  });

  it('uploads the artifact before the breaking-change failure', () => {
    const text = yaml();
    const upload = text.indexOf('name: pr-review-aid');
    const fail = text.indexOf('node scripts/pr-review-aid/check-breaking.mjs');
    assert.ok(upload > 0 && upload < fail);
    assert.match(text, /if-no-files-found: ignore/);
    assert.match(text, /continue-on-error: true/);
    assert.match(text, /timeout-minutes: 40/);
    assert.match(text, /cancel-in-progress: true/);
    assert.match(text, /persist-credentials: false/);
    assert.match(text, /fetch-depth: 0/);
    const checkout = readFileSync(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'checkout-base.sh'), 'utf8');
    const classify = readFileSync(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'classify.mjs'), 'utf8');
    assert.match(checkout, /git merge-base/);
    assert.match(classify, /merge-base/);
    assert.equal([...text.matchAll(/HEAD_SHA: \$\{\{ github\.event\.pull_request\.head\.sha \}\}/g)].length, 2);
  });
});
