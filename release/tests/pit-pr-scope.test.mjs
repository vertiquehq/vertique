// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * PR-scoped mutation testing contract.
 *
 * Proves that scripts/pit-pr-scope.mjs selects only the production classes a
 * change adds lines to inside the pilot modules, and reports only the mutants
 * that sit on those added lines. A parsing mistake here would silently hide
 * surviving mutants, so every hunk shape `git diff -U0` emits and every
 * report detail the filter depends on is pinned with a fixture.
 */

import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync, spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(TEST_DIR, '..', '..');
const MODULE = pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pit-pr-scope.mjs')).href;
const SCRIPT = path.join(REPO_ROOT, 'scripts', 'pit-pr-scope.sh');

const {
  PILOT_MODULES,
  parseChangedLines,
  selectTargets,
  parseMutations,
  sourcePathOf,
  buildReport,
  renderMarkdown,
} = await import(MODULE);

const RESILIENCE = 'vertique-resilience/src/main/java/dev/vertique/resilience';

const DIFF = [
  `diff --git a/${RESILIENCE}/RetryBackoff.java b/${RESILIENCE}/RetryBackoff.java`,
  'index 1111111..2222222 100644',
  `--- a/${RESILIENCE}/RetryBackoff.java`,
  `+++ b/${RESILIENCE}/RetryBackoff.java`,
  '@@ -10,2 +10,3 @@ public sealed interface RetryBackoff {',
  '-a',
  '-b',
  '+a',
  '+++ b',
  '+c',
  '@@ -40 +41 @@ record Fixed(long delayMs) {',
  '-x',
  '+y',
  '@@ -60,3 +60,0 @@ static Custom custom(BackoffStrategy delegate) {',
  '-gone',
  '-gone',
  '-gone',
  `diff --git a/${RESILIENCE}/Backoff.java b/${RESILIENCE}/Backoff.java`,
  'new file mode 100644',
  'index 0000000..3333333',
  '--- /dev/null',
  `+++ b/${RESILIENCE}/Backoff.java`,
  '@@ -0,0 +1,4 @@',
  '+1',
  '+2',
  '+3',
  '+4',
  `diff --git a/${RESILIENCE}/Removed.java b/${RESILIENCE}/Removed.java`,
  'deleted file mode 100644',
  'index 4444444..0000000',
  `--- a/${RESILIENCE}/Removed.java`,
  '+++ /dev/null',
  '@@ -1,2 +0,0 @@',
  '-1',
  '-2',
  `diff --git a/${RESILIENCE}/Old.java b/${RESILIENCE}/Renamed.java`,
  'similarity index 90%',
  `rename from ${RESILIENCE}/Old.java`,
  `rename to ${RESILIENCE}/Renamed.java`,
  'index 7777777..8888888 100644',
  `--- a/${RESILIENCE}/Old.java`,
  `+++ b/${RESILIENCE}/Renamed.java`,
  '@@ -3 +3 @@',
  '-x',
  '+y',
  `diff --git a/${RESILIENCE}/OnlyDeletions.java b/${RESILIENCE}/OnlyDeletions.java`,
  'index 5555555..6666666 100644',
  `--- a/${RESILIENCE}/OnlyDeletions.java`,
  `+++ b/${RESILIENCE}/OnlyDeletions.java`,
  '@@ -3,1 +2,0 @@',
  '-dropped',
  '',
].join('\n');

/** A mutations.xml entry in PIT 1.30's exact shape. */
function mutation({ status, cls, file, line, mutator = 'ConditionalsBoundaryMutator', method = 'm', description = 'changed conditional boundary' }) {
  const detected = ['KILLED', 'TIMED_OUT', 'MEMORY_ERROR', 'RUN_ERROR'].includes(status) ? 'true' : 'false';
  const killing = status === 'KILLED' ? `<killingTest>${cls}Test.[engine:junit-jupiter]</killingTest>` : '<killingTest/>';
  return `<mutation detected='${detected}' status='${status}' numberOfTestsRun='1'><sourceFile>${file}</sourceFile>`
    + `<mutatedClass>${cls}</mutatedClass><mutatedMethod>${method}</mutatedMethod><methodDescription>(I)J</methodDescription>`
    + `<lineNumber>${line}</lineNumber><mutator>org.pitest.mutationtest.engine.gregor.mutators.${mutator}</mutator>`
    + `<indexes><index>5</index></indexes><blocks><block>1</block></blocks>${killing}`
    + `<description>${description}</description></mutation>`;
}

function mutationsXml(...entries) {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<mutations partial="true">\n${entries.join('\n')}\n</mutations>\n`;
}

describe('parseChangedLines', () => {
  const changed = parseChangedLines(DIFF);

  it('records every new-side line of a multi-line hunk', () => {
    assert.deepEqual([...changed.get(`${RESILIENCE}/RetryBackoff.java`)].sort((a, b) => a - b), [10, 11, 12, 41]);
  });

  it('reads an added line that starts with "++ " as content, not as a file header', () => {
    assert.ok(changed.get(`${RESILIENCE}/RetryBackoff.java`).has(11));
    assert.equal(changed.has('b'), false);
  });

  it('records a renamed file under its new path', () => {
    assert.deepEqual([...changed.get(`${RESILIENCE}/Renamed.java`)], [3]);
    assert.equal(changed.has(`${RESILIENCE}/Old.java`), false);
  });

  it('records all lines of a new file', () => {
    assert.deepEqual([...changed.get(`${RESILIENCE}/Backoff.java`)], [1, 2, 3, 4]);
  });

  it('ignores deleted files and files with deletions only', () => {
    assert.equal(changed.has(`${RESILIENCE}/Removed.java`), false);
    assert.equal(changed.has('/dev/null'), false);
    assert.equal(changed.has(`${RESILIENCE}/OnlyDeletions.java`), false);
  });

  it('returns an empty map for an empty diff', () => {
    assert.equal(parseChangedLines('').size, 0);
  });
});

describe('selectTargets', () => {
  const files = [
    `${RESILIENCE}/RetryBackoff.java`,
    'vertique-rest/vertique-rest-security/src/main/java/dev/vertique/rest/security/SecurityPolicyEnforcer.java',
    'vertique-resilience/src/main/java/dev/vertique/resilience/package-info.java',
    'vertique-resilience/src/test/java/dev/vertique/resilience/RetryBackoffTest.java',
    'vertique-core/src/main/java/dev/vertique/core/Json.java',
    'pom.xml',
  ];

  it('pins the pilot allowlist', () => {
    assert.deepEqual(PILOT_MODULES, [
      'vertique-input-processing',
      'vertique-json-schema',
      'vertique-resilience',
      'vertique-rest/vertique-rest-security',
    ]);
  });

  it('selects pilot production classes with their nested classes', () => {
    const selection = selectTargets(files, { maxClasses: 25 });
    assert.deepEqual(selection.modules, ['vertique-resilience', 'vertique-rest/vertique-rest-security']);
    assert.deepEqual(selection.classes, [
      'dev.vertique.resilience.RetryBackoff',
      'dev.vertique.rest.security.SecurityPolicyEnforcer',
    ]);
    assert.equal(
      selection.targetClasses,
      'dev.vertique.resilience.RetryBackoff,dev.vertique.resilience.RetryBackoff$*,'
        + 'dev.vertique.rest.security.SecurityPolicyEnforcer,dev.vertique.rest.security.SecurityPolicyEnforcer$*'
    );
    assert.equal(selection.run, true);
  });

  it('lists production classes outside the pilot without selecting them', () => {
    const selection = selectTargets(files, { maxClasses: 25 });
    assert.deepEqual(selection.outOfScope, ['vertique-core/src/main/java/dev/vertique/core/Json.java']);
  });

  it('does not run when no pilot production class changed', () => {
    const selection = selectTargets(['pom.xml', 'vertique-core/src/main/java/dev/vertique/core/Json.java'], { maxClasses: 25 });
    assert.equal(selection.run, false);
    assert.deepEqual(selection.classes, []);
    assert.equal(selection.skipped, null);
  });

  it('skips a change above the class budget instead of running it', () => {
    const selection = selectTargets(files, { maxClasses: 1 });
    assert.equal(selection.run, false);
    assert.deepEqual(selection.skipped, { reason: 'budget', classes: 2, maxClasses: 1 });
  });
});

describe('parseMutations', () => {
  const xml = mutationsXml(
    mutation({ status: 'SURVIVED', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 11, method: '&lt;init&gt;', description: 'replaced a &amp;&amp; b with &quot;false&quot; &apos;x&apos;' }),
    mutation({ status: 'KILLED', cls: 'dev.vertique.resilience.RetryBackoff$Exponential', file: 'RetryBackoff.java', line: 41 }),
    `<mutation detected="false" status="NO_COVERAGE" numberOfTestsRun="0"><sourceFile>RetryBackoff.java</sourceFile><mutatedClass>dev.vertique.resilience.Helper</mutatedClass><mutatedMethod>h</mutatedMethod><lineNumber>12</lineNumber><mutator>org.pitest.X</mutator><killingTest/><description>d</description></mutation>`
  );
  const mutations = parseMutations(xml);

  it('reads every mutation with its status and line', () => {
    assert.equal(mutations.length, 3);
    assert.deepEqual(mutations.map((m) => [m.status, m.lineNumber]), [['SURVIVED', 11], ['KILLED', 41], ['NO_COVERAGE', 12]]);
  });

  it('decodes XML entities in method names and descriptions', () => {
    assert.equal(mutations[0].mutatedMethod, '<init>');
    assert.equal(mutations[0].description, 'replaced a && b with "false" \'x\'');
  });

  it('maps nested and secondary top-level classes to their source file', () => {
    assert.equal(sourcePathOf(mutations[1]), 'dev/vertique/resilience/RetryBackoff.java');
    assert.equal(sourcePathOf(mutations[2]), 'dev/vertique/resilience/RetryBackoff.java');
  });
});

describe('buildReport and renderMarkdown', () => {
  const changed = parseChangedLines(DIFF);
  const selection = selectTargets([...changed.keys()], { maxClasses: 25 });
  const xmlByModule = {
    'vertique-resilience': mutationsXml(
      mutation({ status: 'SURVIVED', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 11, description: 'a | b' }),
      mutation({ status: 'NO_COVERAGE', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 41 }),
      mutation({ status: 'KILLED', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 12 }),
      mutation({ status: 'TIMED_OUT', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 10 }),
      mutation({ status: 'SURVIVED', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 99 }),
      mutation({ status: 'SURVIVED', cls: 'dev.vertique.resilience.Backoff', file: 'Backoff.java', line: 3 })
    ),
  };
  const report = buildReport({ selection, changedLines: changed, xmlByModule });

  it('keeps only mutants on added lines', () => {
    assert.deepEqual(report.changed.total, 5);
    assert.deepEqual(
      report.changed.undetected.map((m) => `${m.file}:${m.lineNumber}:${m.status}`),
      [
        `${RESILIENCE}/Backoff.java:3:SURVIVED`,
        `${RESILIENCE}/RetryBackoff.java:11:SURVIVED`,
        `${RESILIENCE}/RetryBackoff.java:41:NO_COVERAGE`,
      ]
    );
  });

  it('counts detected mutants, including timeouts, on added lines', () => {
    assert.equal(report.changed.detected, 2);
  });

  it('keeps whole-class totals as context', () => {
    assert.deepEqual(report.wholeClass, { total: 6, undetected: 4 });
  });

  it('renders undetected mutants on added lines as a table with escaped cells', () => {
    const md = renderMarkdown(report);
    assert.match(md, /Mutation testing \(advisory\)/);
    assert.match(md, /3 of 5 mutants on added lines were not detected/);
    assert.match(md, /RetryBackoff\.java:11/);
    assert.match(md, /a \\\| b/);
    assert.doesNotMatch(md, /RetryBackoff\.java:99/);
  });

  it('reports a missing module report as an error instead of a clean result', () => {
    assert.throws(
      () => buildReport({ selection, changedLines: changed, xmlByModule: {} }),
      /no PIT report for vertique-resilience/
    );
  });

  it('treats an empty report as changed classes PIT reported no mutations for', () => {
    const empty = buildReport({ selection, changedLines: changed, xmlByModule: { 'vertique-resilience': '' } });
    assert.deepEqual(empty.wholeClass, { total: 0, undetected: 0 });
    assert.match(renderMarkdown(empty), /PIT reported no mutations for the changed classes \(see the Maven log\)/);
  });

  it('uses PIT\'s detected flag and leaves non-viable mutants out', () => {
    const statuses = buildReport({
      selection,
      changedLines: changed,
      xmlByModule: {
        'vertique-resilience': mutationsXml(
          ...['MEMORY_ERROR', 'RUN_ERROR', 'NOT_STARTED', 'NON_VIABLE'].map((status) =>
            mutation({ status, cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 12 }))
        ),
      },
    });
    assert.equal(statuses.changed.total, 3);
    assert.equal(statuses.changed.detected, 2);
    assert.deepEqual(statuses.changed.undetected.map((m) => m.status), ['NOT_STARTED']);
  });

  it('says so when every mutant on added lines was detected', () => {
    const allKilled = buildReport({
      selection,
      changedLines: changed,
      xmlByModule: { 'vertique-resilience': mutationsXml(mutation({ status: 'KILLED', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 12 })) },
    });
    assert.match(renderMarkdown(allKilled), /All 1 mutants on added lines were detected/);
  });

  it('says so when no mutant falls on an added line', () => {
    const elsewhere = buildReport({
      selection,
      changedLines: changed,
      xmlByModule: { 'vertique-resilience': mutationsXml(mutation({ status: 'SURVIVED', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 99 })) },
    });
    assert.match(renderMarkdown(elsewhere), /No mutants fall on lines this change added/);
  });

  it('renders the no-change, out-of-scope and budget cases without a table', () => {
    const none = renderMarkdown(buildReport({ selection: selectTargets(['vertique-core/src/main/java/a/B.java'], { maxClasses: 25 }), changedLines: new Map(), xmlByModule: {} }));
    assert.match(none, /No production classes changed in the pilot modules/);
    assert.match(none, /vertique-core\/src\/main\/java\/a\/B\.java/);

    const budget = renderMarkdown(buildReport({ selection: selectTargets(files2(30), { maxClasses: 25 }), changedLines: new Map(), xmlByModule: {} }));
    assert.match(budget, /Skipped: 30 changed classes exceed the budget of 25/);
    assert.match(budget, /PIT_MAX_CLASSES=30 bash scripts\/pit-pr-scope\.sh/);
  });
});

function files2(n) {
  return Array.from({ length: n }, (_, i) => `${RESILIENCE}/C${i}.java`);
}

// --- scripts/pit-pr-scope.sh, end to end against synthetic repositories ---

const RETRY_BACKOFF = `${RESILIENCE}/RetryBackoff.java`;
const BASE_SOURCE = 'package dev.vertique.resilience;\n\nclass RetryBackoff {\n    int a() {\n        return 1;\n    }\n}\n';
const CHANGED_SOURCE = 'package dev.vertique.resilience;\n\nclass RetryBackoff {\n    int a() {\n        return 2;\n    }\n}\n';

function git(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
}

function commitFiles(repo, files, message) {
  for (const [file, content] of Object.entries(files)) {
    const target = path.join(repo, file);
    mkdirSync(path.dirname(target), { recursive: true });
    writeFileSync(target, content);
  }
  git(repo, 'add', '.');
  git(repo, 'commit', '--quiet', '-m', message);
}

/** A repository whose main holds RetryBackoff (plus `baseFiles`); `branch` adds `files` on top. */
function makeRepo(branch, files, baseFiles = {}) {
  const repo = mkdtempSync(path.join(tmpdir(), 'pit-pr-scope-'));
  git(repo, 'init', '--quiet', '--initial-branch=main');
  git(repo, 'config', 'user.email', 'test@invalid');
  git(repo, 'config', 'user.name', 'test');
  git(repo, 'config', 'commit.gpgsign', 'false');
  commitFiles(repo, { [RETRY_BACKOFF]: BASE_SOURCE, 'pom.xml': '<project/>\n', ...baseFiles }, 'base');
  git(repo, 'checkout', '--quiet', '-b', branch);
  commitFiles(repo, files, branch);
  return repo;
}

/**
 * A stand-in for ./mvnw that records its arguments and writes the report a PIT
 * run would, with one undetected mutant on the changed line 5 and one on the
 * unchanged line 4.
 */
function stubMaven(repo, { exitCode = 0, writeReport = true, compileClass = true } = {}) {
  const stub = path.join(repo, 'stub-mvnw.sh');
  const report = path.join(repo, 'vertique-resilience', 'target', 'pit-reports');
  const classes = path.join(repo, 'vertique-resilience', 'target', 'classes', 'dev', 'vertique', 'resilience');
  const lines = [
    '#!/usr/bin/env bash',
    `printf '%s\\n' "$@" > "${path.join(repo, 'mvn-args.txt')}"`,
  ];
  if (compileClass) lines.push(`mkdir -p "${classes}"`, `touch "${path.join(classes, 'RetryBackoff.class')}"`);
  if (writeReport) {
    lines.push(
      `mkdir -p "${report}"`,
      `cat > "${path.join(report, 'mutations.xml')}" <<'XML'`,
      mutationsXml(
        mutation({ status: 'SURVIVED', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 5, method: 'a', description: 'replaced int return with 0' }),
        mutation({ status: 'SURVIVED', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 4 })
      ).trimEnd(),
      'XML'
    );
  }
  lines.push(`exit ${exitCode}`, '');
  writeFileSync(stub, lines.join('\n'));
  chmodSync(stub, 0o755);
  return stub;
}

function runScript(repo, args, env = {}) {
  const result = spawnSync('bash', [SCRIPT, ...args], {
    cwd: repo,
    encoding: 'utf8',
    env: { ...process.env, GITHUB_STEP_SUMMARY: '', PIT_ALLOW_ROOT: '', PIT_EFFECTIVE_UID: '1000', ...env },
  });
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
}

describe('pit-pr-scope.sh', () => {
  const repos = [];
  const repo = (branch, files, baseFiles) => {
    const created = makeRepo(branch, files, baseFiles);
    repos.push(created);
    return created;
  };
  after(() => {
    for (const created of repos) rmSync(created, { recursive: true, force: true });
  });

  it('does not run Maven when no production class changed', () => {
    const r = repo('docs', { 'README.md': 'docs\n' });
    const stub = stubMaven(r);
    const result = runScript(r, ['--base', 'main'], { PIT_MVN: stub });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /No production classes changed in the pilot modules/);
    assert.equal(existsSync(path.join(r, 'mvn-args.txt')), false);
  });

  it('lists a non-pilot change without running Maven', () => {
    const r = repo('core', { 'vertique-core/src/main/java/dev/vertique/core/Json.java': 'class Json {}\n' });
    const stub = stubMaven(r);
    const result = runScript(r, ['--base', 'main'], { PIT_MVN: stub });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /vertique-core\/src\/main\/java\/dev\/vertique\/core\/Json\.java/);
    assert.equal(existsSync(path.join(r, 'mvn-args.txt')), false);
  });

  it('prints the exact Maven command for a pilot change in dry-run mode', () => {
    const r = repo('pilot', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    const result = runScript(r, ['--base', 'main', '--dry-run']);
    assert.equal(result.status, 0, result.stderr);
    // Printed shell-quoted, so pasting it cannot expand `$*` and narrow the run.
    assert.match(
      result.stdout,
      /\.\/mvnw -ntp -T 1 -pl vertique-resilience -am process-test-classes org\.pitest:pitest-maven:mutationCoverage -Dpitest\.skip=false -DtargetClasses=dev\.vertique\.resilience\.RetryBackoff\\,dev\.vertique\.resilience\.RetryBackoff\\\$\\\*/
    );
  });

  it('runs Maven and reports only the undetected mutant on the added line', () => {
    const r = repo('pilot-run', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    const stub = stubMaven(r);
    const summary = path.join(r, 'step-summary.md');
    const result = runScript(r, ['--base', 'main'], { PIT_MVN: stub, GITHUB_STEP_SUMMARY: summary });
    assert.equal(result.status, 0, result.stderr);
    const args = readFileSync(path.join(r, 'mvn-args.txt'), 'utf8').split('\n');
    assert.deepEqual(args.slice(0, 10), [
      '-ntp', '-T', '1', '-pl', 'vertique-resilience', '-am', 'process-test-classes',
      'org.pitest:pitest-maven:mutationCoverage', '-Dpitest.skip=false',
      '-DtargetClasses=dev.vertique.resilience.RetryBackoff,dev.vertique.resilience.RetryBackoff$*',
    ]);
    assert.match(result.stdout, /1 of 1 mutants on added lines were not detected/);
    assert.match(result.stdout, /RetryBackoff\.java:5/);
    assert.doesNotMatch(result.stdout, /RetryBackoff\.java:4/);
    assert.equal(readFileSync(summary, 'utf8'), readFileSync(path.join(r, 'target', 'pit-pr', 'summary.md'), 'utf8'));
  });

  it('skips a change above the class budget with exit 0', () => {
    const r = repo('budget', { [RETRY_BACKOFF]: CHANGED_SOURCE, [`${RESILIENCE}/Other.java`]: 'class Other {}\n' });
    const stub = stubMaven(r);
    const result = runScript(r, ['--base', 'main'], { PIT_MVN: stub, PIT_MAX_CLASSES: '1' });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /Skipped: 2 changed classes exceed the budget of 1/);
    assert.equal(existsSync(path.join(r, 'mvn-args.txt')), false);
  });

  it('reports changed classes without mutable code when PIT writes no report', () => {
    const r = repo('no-mutants', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    const stub = stubMaven(r, { writeReport: false });
    const result = runScript(r, ['--base', 'main'], { PIT_MVN: stub });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /PIT reported no mutations for the changed classes/);
  });

  it('fails when PIT writes no report for a class that was never compiled', () => {
    const r = repo('no-class', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    const stub = stubMaven(r, { writeReport: false, compileClass: false });
    const result = runScript(r, ['--base', 'main'], { PIT_MVN: stub });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /no PIT report for vertique-resilience/);
  });

  it('never reuses a report left by an earlier run', () => {
    const r = repo('stale', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    const reports = path.join(r, 'vertique-resilience', 'target', 'pit-reports');
    mkdirSync(reports, { recursive: true });
    writeFileSync(path.join(reports, 'mutations.xml'), mutationsXml(
      mutation({ status: 'SURVIVED', cls: 'dev.vertique.resilience.RetryBackoff', file: 'RetryBackoff.java', line: 5, description: 'stale survivor' })
    ));
    const stub = stubMaven(r, { writeReport: false });
    const result = runScript(r, ['--base', 'main'], { PIT_MVN: stub });
    assert.equal(result.status, 0, result.stderr);
    assert.doesNotMatch(result.stdout, /stale survivor/);
  });

  it('fails instead of showing an earlier result when selection fails', () => {
    const r = repo('select-fails', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    const stub = stubMaven(r);
    assert.equal(runScript(r, ['--base', 'main'], { PIT_MVN: stub }).status, 0);
    const result = runScript(r, ['--base', 'main'], { PIT_MVN: stub, PIT_MAX_CLASSES: 'abc' });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /--max-classes must be a positive integer/);
    assert.doesNotMatch(result.stdout, /not detected/);
  });

  it('diffs from the merge base, not from a base that moved on', () => {
    // main drops a line of Other after the branch point: diffed against main
    // instead of the merge base, the branch would appear to add it back.
    const other = `${RESILIENCE}/Other.java`;
    const r = repo('behind', { [RETRY_BACKOFF]: CHANGED_SOURCE }, { [other]: 'class Other {\n    int a;\n    int b;\n}\n' });
    git(r, 'checkout', '--quiet', 'main');
    commitFiles(r, { [other]: 'class Other {\n    int a;\n}\n' }, 'main drops a field');
    git(r, 'checkout', '--quiet', 'behind');
    const result = runScript(r, ['--base', 'main', '--dry-run']);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /mutationCoverage .*-DtargetClasses=dev\.vertique\.resilience\.RetryBackoff/);
    assert.doesNotMatch(result.stdout, /Other/);
  });

  it('ignores the user\'s diff prefix configuration', () => {
    const r = repo('prefix', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    git(r, 'config', 'diff.mnemonicPrefix', 'true');
    git(r, 'config', 'diff.noprefix', 'false');
    git(r, 'config', 'diff.dstPrefix', 'new/');
    const result = runScript(r, ['--base', 'main', '--dry-run']);
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /mutationCoverage .*-DtargetClasses=dev\.vertique\.resilience\.RetryBackoff/);
    assert.doesNotMatch(result.stdout, /outside the pilot modules/);
  });

  it('fails when the Maven run fails', () => {
    const r = repo('broken', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    const stub = stubMaven(r, { exitCode: 1 });
    const result = runScript(r, ['--base', 'main'], { PIT_MVN: stub });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /PIT run failed/);
  });

  it('runs the Maven command through the container runner with --sandbox', () => {
    const r = repo('sandbox', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    const result = runScript(r, ['--base', 'main', '--sandbox', '--dry-run'], { PIT_EFFECTIVE_UID: '0' });
    assert.equal(result.status, 0, result.stderr);
    assert.match(result.stdout, /scripts\/pit-sandbox\.sh -ntp -T 1 -pl vertique-resilience /);
  });

  it('refuses to run as root unless explicitly allowed', () => {
    const r = repo('root', { [RETRY_BACKOFF]: CHANGED_SOURCE });
    const refused = runScript(r, ['--base', 'main', '--dry-run'], { PIT_EFFECTIVE_UID: '0' });
    assert.notEqual(refused.status, 0);
    assert.match(refused.stderr, /refusing to run as root/);
    const allowed = runScript(r, ['--base', 'main', '--dry-run'], { PIT_EFFECTIVE_UID: '0', PIT_ALLOW_ROOT: '1' });
    assert.equal(allowed.status, 0, allowed.stderr);
  });

  it('rejects unknown arguments and a --base without a ref', () => {
    const r = repo('args', { 'README.md': 'x\n' });
    const unknown = runScript(r, ['--nope']);
    assert.notEqual(unknown.status, 0);
    assert.match(unknown.stderr, /unrecognised argument: --nope/);
    const bare = runScript(r, ['--base']);
    assert.notEqual(bare.status, 0);
    assert.match(bare.stderr, /--base requires a ref/);
  });
});

// --- .github/workflows/mutation.yml ---

/** `run:` step bodies of a workflow, split textually as PublicCiContractTest does. */
function runStepBodies(yaml) {
  const bodies = [];
  const lines = yaml.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const m = /^(\s*)-?\s*run:\s*(\|[-+]?|>[-+]?)?\s*(.*)$/.exec(lines[i]);
    if (!m) continue;
    const [, indent, block, inline] = m;
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

describe('MutationWorkflowContractTest', () => {
  const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'mutation.yml');
  const workflow = () => readFileSync(WORKFLOW, 'utf8');

  it('runsOnlyOnPullRequestsWithReadOnlyContentsAndNoSecrets', () => {
    const yaml = workflow();
    assert.match(
      yaml,
      /^on:\n  pull_request:\n    branches: \[main\]\n    paths:\n      - "\*\*\/src\/main\/java\/\*\*"\n      - "scripts\/pit-pr-scope\.\*"\n      - "\.github\/workflows\/mutation\.yml"\n      - "pom\.xml"\n\n/m,
      'mutation.yml must run on pull requests to main that touch production Java or the tooling'
    );
    assert.doesNotMatch(yaml, /^\s*(push|pull_request_target|workflow_run|schedule):/m, 'mutation.yml must run on nothing but pull_request');
    const granted = [...yaml.matchAll(/^\s*(contents|packages|pull-requests|id-token|actions|checks|deployments|issues|statuses|security-events|attestations|pages):\s*(\S+)\s*$/gm)]
      .map(([, scope, level]) => `${scope}: ${level}`);
    assert.deepEqual(granted, ['contents: read'], 'mutation.yml may only read repository contents');
    assert.deepEqual([...yaml.matchAll(/\$\{\{\s*secrets\./g)], [], 'mutation.yml must reference no secrets');
  });

  it('delegatesToTheLocallyRunnableEntryPoint', () => {
    const bodies = runStepBodies(workflow());
    assert.ok(bodies.includes('bash scripts/pit-pr-scope.sh --base HEAD^1'), 'mutation.yml must run the PR-scoped entry point against the merge commit\'s base parent');
    const ENTRY_POINT = /^(\.\/mvnw|bash\s+\S+\.sh|node\s+(--test\s+)?\S+\.mjs|npm\s+\S+)\b/;
    for (const body of bodies) {
      for (const command of body.split('\n').filter(Boolean)) {
        assert.match(command, ENTRY_POINT, `mutation.yml step embeds logic instead of calling an entry point: "${command}"`);
      }
      assert.doesNotMatch(body, /\b(if|for|while|case)\b\s|&&|\|\||;\s*\w|\$\{\{/, `mutation.yml step contains inline logic or an expression:\n${body}`);
    }
  });

  it('boundsTheRunAndKeepsTheReports', () => {
    const yaml = workflow();
    const timeout = /^\s*timeout-minutes:\s*(\d+)\s*$/m.exec(yaml);
    assert.ok(timeout && Number(timeout[1]) <= 30, 'mutation.yml must cap the job at 30 minutes or less');
    assert.match(yaml, /fetch-depth: 2/, 'the merge commit and its base parent must be fetched');
    assert.match(yaml, /persist-credentials: false/);
    assert.match(yaml, /if: \$\{\{ always\(\) \}\}\n\s+uses: actions\/upload-artifact@\S+ # v[\d.]+\n\s+with:\n\s+name: mutation-reports\n\s+path: \|\n\s+target\/pit-pr\/\*\*\n\s+\*\*\/target\/pit-reports\/\*\*/);
  });

  it('staysOutOfTheRequiredCheck', () => {
    const ci = readFileSync(path.join(REPO_ROOT, '.github', 'workflows', 'ci.yml'), 'utf8');
    assert.doesNotMatch(ci, /pit-pr-scope|pitest-maven|mutationCoverage|mutation\.yml/, 'required CI must not run or depend on mutation testing');
  });
});

// --- scripts/pit-sandbox.sh ---

describe('PitSandboxContractTest', () => {
  const sandbox = () => readFileSync(path.join(REPO_ROOT, 'scripts', 'pit-sandbox.sh'), 'utf8');
  const dockerfile = () => readFileSync(path.join(REPO_ROOT, 'scripts', 'pit-sandbox', 'Dockerfile'), 'utf8');

  it('neverMountsAHostPathWritable', () => {
    const binds = [...sandbox().matchAll(/--mount "(type=bind[^"]*)"/g)].map((m) => m[1]);
    assert.ok(binds.length > 0, 'expected the read-only host Maven cache mounts');
    for (const bind of binds) assert.match(bind, /,readonly$/, `host bind mount must be read-only: ${bind}`);
    assert.doesNotMatch(sandbox(), /\s(?:-v|--volume)\s+["$~/]/, 'use --mount so read-only is explicit');
  });

  it('dropsPrivilegesAndNetworkByDefault', () => {
    const script = sandbox();
    assert.match(script, /network="\$\{PIT_SANDBOX_NETWORK:-none\}"/);
    assert.match(script, /--cap-drop ALL --security-opt no-new-privileges/);
    assert.match(script, /--memory "\$memory" --pids-limit \d+/);
    assert.match(dockerfile(), /^USER pit$/m, 'the container must not run as root');
    assert.match(dockerfile(), /^FROM eclipse-temurin:21-jdk@sha256:[0-9a-f]{64}$/m, 'pin the base image by digest');
  });
});
