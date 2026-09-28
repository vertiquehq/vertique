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

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(TEST_DIR, '..', '..');
const MODULE = pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pit-pr-scope.mjs')).href;

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
  '+b',
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
  const detected = status === 'KILLED' || status === 'TIMED_OUT' ? 'true' : 'false';
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

  it('renders the no-change, out-of-scope and budget cases without a table', () => {
    const none = renderMarkdown(buildReport({ selection: selectTargets(['vertique-core/src/main/java/a/B.java'], { maxClasses: 25 }), changedLines: new Map(), xmlByModule: {} }));
    assert.match(none, /No production classes changed in the pilot modules/);
    assert.match(none, /vertique-core\/src\/main\/java\/a\/B\.java/);

    const budget = renderMarkdown(buildReport({ selection: selectTargets(files2(30), { maxClasses: 25 }), changedLines: new Map(), xmlByModule: {} }));
    assert.match(budget, /Skipped: 30 changed classes exceed the budget of 25/);
  });
});

function files2(n) {
  return Array.from({ length: n }, (_, i) => `${RESILIENCE}/C${i}.java`);
}
