// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Pull request review aid: reactor diff, OpenAPI diff, and report shape.
 *
 * Golden files live next to the fixtures. UPDATE_GOLDEN=1 rewrites them.
 */

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(TEST_DIR, '..', '..');
const FIXTURES = path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'fixtures');

const graph = await import(pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'maven-graph.mjs')).href);
const diffMod = await import(pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'module-diff.mjs')).href);
const openapi = await import(pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'openapi-diff.mjs')).href);
const reportMod = await import(pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'report.mjs')).href);
const { stableStringify } = await import(pathToFileURL(path.join(REPO_ROOT, 'scripts', 'pr-review-aid', 'util.mjs')).href);

const { extractReactor } = graph;
const { DEFAULT_NODE_CAP, diffModules } = diffMod;
const { changelogIsEmpty, diffOpenApi } = openapi;
const { COMMENT_CHAR_LIMIT, COMMENT_MARKER, buildComment, renderReport, splitSections, summaryText } = reportMod;

const POM_HEADER = `<?xml version="1.0" encoding="UTF-8"?>
<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->
`;

function diffHint(expected, actual) {
  const e = expected.split('\n');
  const a = actual.split('\n');
  const lines = [];
  const count = Math.max(e.length, a.length);
  for (let i = 0; i < count && lines.length < 12; i++) {
    if (e[i] !== a[i]) {
      lines.push(`L${i + 1}\n  expected: ${JSON.stringify(e[i])}\n  actual:   ${JSON.stringify(a[i])}`);
    }
  }
  if (e.length !== a.length) lines.push(`lengths expected ${e.length} actual ${a.length}`);
  return lines.join('\n');
}

function assertGolden(actual, file) {
  const text = actual.endsWith('\n') || actual === '' ? actual : `${actual}\n`;
  if (process.env.UPDATE_GOLDEN === '1') {
    mkdirSync(path.dirname(file), { recursive: true });
    writeFileSync(file, text);
    return;
  }
  assert.equal(existsSync(file), true, `missing golden ${path.relative(REPO_ROOT, file)}; rerun with UPDATE_GOLDEN=1`);
  const expected = readFileSync(file, 'utf8');
  if (expected !== text) {
    assert.fail(`golden mismatch ${path.relative(REPO_ROOT, file)}\n${diffHint(expected, text)}`);
  }
}

function loadCase(name, options) {
  const dir = path.join(FIXTURES, name);
  const base = extractReactor(path.join(dir, 'base'));
  const head = extractReactor(path.join(dir, 'head'));
  const diff = diffModules(base, head, options);
  const report = renderReport({ moduleMarkdown: diff.markdown });
  return { base, head, diff, report };
}

function assertCase(name, options) {
  const loaded = loadCase(name, options);
  assertGolden(loaded.report, path.join(FIXTURES, name, 'expected.md'));
  if (loaded.diff.mermaid != null) assertGolden(loaded.diff.mermaid, path.join(FIXTURES, name, 'expected.mmd'));
  if (loaded.diff.dot != null) assertGolden(loaded.diff.dot, path.join(FIXTURES, name, 'expected.dot'));
  const again = loadCase(name, options);
  assert.equal(again.report, loaded.report);
  assert.equal(again.diff.mermaid, loaded.diff.mermaid);
  assert.equal(again.diff.dot, loaded.diff.dot);
  return loaded;
}

function writeFile(dir, rel, body) {
  const full = path.join(dir, rel);
  mkdirSync(path.dirname(full), { recursive: true });
  writeFileSync(full, body);
}

function parentPom(modules) {
  return `${POM_HEADER}<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.example</groupId>
  <artifactId>root</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
  <modules>
${modules.map((name) => `    <module>${name}</module>`).join('\n')}
  </modules>
</project>
`;
}

function childPom(artifactId, deps = []) {
  const blocks = deps.map((dep) => {
    const version = dep.version ? `\n      <version>${dep.version}</version>` : '';
    const scope = dep.scope ? `\n      <scope>${dep.scope}</scope>` : '';
    return `    <dependency>
      <groupId>${dep.groupId}</groupId>
      <artifactId>${dep.artifactId}</artifactId>${version}${scope}
    </dependency>`;
  });
  const dependencies = blocks.length ? `\n  <dependencies>\n${blocks.join('\n')}\n  </dependencies>` : '';
  return `${POM_HEADER}<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>dev.example</groupId>
    <artifactId>root</artifactId>
    <version>1.0.0</version>
  </parent>
  <artifactId>${artifactId}</artifactId>${dependencies}
</project>
`;
}

describe('module graph fixtures', () => {
  it('keeps the inline diagram cap at 60 modules', () => {
    assert.equal(DEFAULT_NODE_CAP, 60);
  });

  it('reports nothing when the reactor is unchanged', () => {
    const { base, head, report, diff } = assertCase('no-change');
    assert.equal(report, '');
    assert.equal(diff.mermaid, null);
    assert.equal(diff.nodeCount, 0);
    assert.equal(buildComment(report), null);
    assert.equal(stableStringify(base), stableStringify(head));
    const app = base.modules.find((mod) => mod.id === 'dev.example:app');
    assert.equal(app.version, '1.0.0');
    assert.equal(app.parent, 'dev.example:root');
    assert.equal(base.modules.some((mod) => mod.id.includes('hidden')), false);
    const junit = app.externalDependencies.find((dep) => dep.id === 'org.junit.jupiter:junit-jupiter');
    assert.deepEqual({ scope: junit.scope, version: junit.version }, { scope: 'test', version: 'managed' });
  });

  it('marks an added module', () => {
    const { diff } = assertCase('module-added');
    assert.match(diff.mermaid, /classDef added fill:#d1fae5,stroke:#10b981,color:#064e3b/);
    assert.match(diff.mermaid, /dev\.example:extra/);
    assert.doesNotMatch(diff.mermaid, /dev\.example:app/);
    assert.equal(diff.touched.includes('dev.example:extra'), true);
  });

  it('marks a removed module', () => {
    const { diff } = assertCase('module-removed');
    assert.match(diff.mermaid, /classDef removed fill:#fee2e2,stroke:#ef4444,color:#7f1d1d/);
    assert.match(diff.mermaid, /dev\.example:extra/);
    assert.equal(diff.touched.includes('dev.example:extra'), true);
  });

  it('marks dependency edge changes and an external version bump', () => {
    const { diff, report } = assertCase('deps-changed');
    assert.match(diff.mermaid, /class m_dev_example_a changed/);
    assert.match(diff.mermaid, /class m_dev_example_b context/);
    assert.match(diff.mermaid, /class m_dev_example_c context/);
    assert.match(diff.mermaid, /linkStyle \d+ stroke:#ef4444,stroke-dasharray:5 5/);
    assert.match(report, /org\.example:lib/);
    assert.match(report, /`1\.0\.0` -> `2\.0\.0`/);
    assert.doesNotMatch(diff.mermaid, /dev\.example:root/);
  });

  it('draws one hop of context and hides a two-hop module', () => {
    const { diff } = assertCase('one-hop');
    assert.match(diff.mermaid, /dev\.example:a/);
    assert.match(diff.mermaid, /dev\.example:b/);
    assert.match(diff.mermaid, /dev\.example:c/);
    assert.doesNotMatch(diff.mermaid, /dev\.example:d/);
    assert.doesNotMatch(diff.mermaid, /dev\.example:e/);
    assert.equal(diff.mermaid.includes('m_dev_example_a -->|"compile"| m_dev_example_c'), false);
    assert.deepEqual(diff.context, ['dev.example:a', 'dev.example:c']);
    assert.deepEqual(diff.touched, ['dev.example:b']);
  });

  it('stays silent for a reactor-wide version bump', () => {
    const { diff, report } = assertCase('version-bump');
    assert.equal(diff.markdown, '');
    assert.equal(diff.mermaid, null);
    assert.deepEqual(diff.touched, []);
    assert.deepEqual(diff.reactorVersion, { from: '1.0.0', to: '2.0.0' });
    assert.equal(buildComment(report), null);
  });

  it('notes a reactor version bump beside a real module change', () => {
    const { diff } = assertCase('version-bump-added');
    assert.match(diff.markdown, /Reactor version: `1\.0\.0` -> `2\.0\.0`/);
    assert.deepEqual(diff.touched, ['dev.example:extra']);
    assert.doesNotMatch(diff.mermaid, /dev\.example:app/);
    assert.doesNotMatch(diff.mermaid, /dev\.example:root/);
  });
});

describe('module graph limits and parsing', () => {
  it('falls back to text and DOT above the node cap', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-cap-'));
    const base = path.join(dir, 'base');
    const head = path.join(dir, 'head');
    const modules = ['a', 'b', 'c'];
    writeFile(base, 'pom.xml', parentPom(modules));
    writeFile(head, 'pom.xml', parentPom(modules));
    for (const name of modules) {
      const deps = name === 'a' ? [{ groupId: 'dev.example', artifactId: 'b' }] : name === 'b' ? [{ groupId: 'dev.example', artifactId: 'c' }, { groupId: 'org.example', artifactId: 'lib', version: '1.0.0' }] : [];
      writeFile(base, `${name}/pom.xml`, childPom(name, deps));
    }
    for (const name of modules) {
      const deps = name === 'a' ? [{ groupId: 'dev.example', artifactId: 'b' }] : name === 'b' ? [{ groupId: 'dev.example', artifactId: 'c' }, { groupId: 'org.example', artifactId: 'lib', version: '2.0.0' }] : [];
      writeFile(head, `${name}/pom.xml`, childPom(name, deps));
    }
    const diff = diffModules(extractReactor(base), extractReactor(head), { nodeCap: 2 });
    assert.equal(diff.overCap, true);
    assert.equal(diff.mermaid, null);
    assert.equal(diff.nodeCount, 3);
    assert.match(diff.markdown, /inline limit of 2/);
    assert.doesNotMatch(diff.markdown, /```mermaid/);
    const report = renderReport({ moduleMarkdown: diff.markdown });
    assertGolden(report, path.join(FIXTURES, 'over-cap', 'expected.md'));
    assertGolden(diff.dot, path.join(FIXTURES, 'over-cap', 'expected.dot'));
    rmSync(dir, { recursive: true, force: true });
  });

  it('inlines 60 nodes and falls back on the 61st', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-many-'));
    const writeSide = (side, count) => {
      const names = Array.from({ length: count }, (_, index) => `m${String(index).padStart(3, '0')}`);
      writeFile(dir, `${side}/pom.xml`, parentPom(names));
      for (const name of names) writeFile(dir, `${side}/${name}/pom.xml`, childPom(name));
    };
    writeSide('base', 0);
    writeSide('sixty', 60);
    writeSide('sixtyone', 61);
    const atCap = diffModules(extractReactor(path.join(dir, 'base')), extractReactor(path.join(dir, 'sixty')));
    const over = diffModules(extractReactor(path.join(dir, 'base')), extractReactor(path.join(dir, 'sixtyone')));
    assert.equal(atCap.nodeCount, 60);
    assert.equal(atCap.overCap, false);
    assert.match(atCap.mermaid, /^flowchart LR\n/);
    assert.equal(over.nodeCount, 61);
    assert.equal(over.overCap, true);
    assert.equal(over.mermaid, null);
    assert.match(over.dot, /^digraph module_graph \{/);
    assert.match(over.markdown, /inline limit of 60/);
    rmSync(dir, { recursive: true, force: true });
  });

  it('is byte-identical across runs and directory order', () => {
    const once = stableStringify(extractReactor(REPO_ROOT));
    const twice = stableStringify(extractReactor(REPO_ROOT));
    assert.equal(once, twice);
    let calls = 0;
    const shuffled = stableStringify(extractReactor(REPO_ROOT, {
      listDir(dir) {
        calls += 1;
        return readdirSync(dir).reverse();
      },
    }));
    assert.ok(calls > 10);
    assert.equal(shuffled, once);
    const fixture = path.join(FIXTURES, 'deps-changed', 'base');
    assert.equal(
      stableStringify(extractReactor(fixture, { listDir: (dir) => readdirSync(dir).reverse() })),
      stableStringify(extractReactor(fixture)),
    );
  });

  it('keeps a version that did not follow the reactor root', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-version-'));
    const writeSide = (side, revision, oddVersion) => {
      writeFile(dir, `${side}/pom.xml`, `${POM_HEADER}<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.example</groupId>
  <artifactId>root</artifactId>
  <version>\${revision}</version>
  <packaging>pom</packaging>
  <properties><revision>${revision}</revision></properties>
  <modules>
    <module>app</module>
    <module>odd</module>
    <module>sidecar</module>
  </modules>
</project>
`);
      writeFile(dir, `${side}/app/pom.xml`, childPom('app'));
      writeFile(dir, `${side}/odd/pom.xml`, `${POM_HEADER}<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>dev.example</groupId>
    <artifactId>root</artifactId>
    <version>\${revision}</version>
  </parent>
  <artifactId>odd</artifactId>
  <version>${oddVersion}</version>
</project>
`);
      writeFile(dir, `${side}/sidecar/pom.xml`, childPom('sidecar', [
        { groupId: 'dev.example', artifactId: 'not-a-module', version: revision },
        { groupId: 'org.other', artifactId: 'lib', version: revision },
      ]));
    };
    writeSide('base', '1.0.0', '9.0.0');
    writeSide('head', '2.0.0', '9.1.0');
    const diff = diffModules(extractReactor(path.join(dir, 'base')), extractReactor(path.join(dir, 'head')));
    assert.deepEqual(diff.touched, ['dev.example:odd', 'dev.example:sidecar']);
    assert.match(diff.markdown, /Reactor version: `1\.0\.0` -> `2\.0\.0`/);
    assert.match(diff.markdown, /`dev\.example:odd` \| version `9\.0\.0` -> `9\.1\.0`/);
    assert.match(diff.markdown, /org\.other:lib/);
    assert.doesNotMatch(diff.markdown, /not-a-module/);
    assert.doesNotMatch(diff.mermaid, /dev\.example:app/);

    const pinned = mkdtempSync(path.join(tmpdir(), 'pr-review-pinned-'));
    for (const [side, version] of [['base', '3.0.0'], ['head', '3.1.0']]) {
      writeFile(pinned, `${side}/pom.xml`, parentPom(['app']));
      writeFile(pinned, `${side}/app/pom.xml`, `${POM_HEADER}<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>dev.example</groupId>
    <artifactId>root</artifactId>
    <version>1.0.0</version>
  </parent>
  <artifactId>app</artifactId>
  <version>${version}</version>
</project>
`);
    }
    const alone = diffModules(extractReactor(path.join(pinned, 'base')), extractReactor(path.join(pinned, 'head')));
    assert.equal(alone.reactorVersion, null);
    assert.deepEqual(alone.touched, ['dev.example:app']);
    assert.match(alone.markdown, /version `3\.0\.0` -> `3\.1\.0`/);
    rmSync(dir, { recursive: true, force: true });
    rmSync(pinned, { recursive: true, force: true });
  });

  it('reads this repository reactor', () => {
    const model = extractReactor(REPO_ROOT);
    const root = model.modules.find((mod) => mod.id === 'dev.vertique:vertique-parent');
    const core = model.modules.find((mod) => mod.id === 'dev.vertique:vertique-core');
    assert.ok(model.modules.length > 100);
    assert.ok(model.edges.length > 100);
    assert.equal(root.packaging, 'pom');
    assert.equal(root.externalDependencies.length, 0);
    assert.doesNotMatch(root.version, /\$\{/);
    assert.equal(core.path, 'vertique-core');
    assert.equal(core.parent, 'dev.vertique:vertique-parent');
    const vertx = core.externalDependencies.find((dep) => dep.id === 'io.vertx:vertx-core');
    assert.deepEqual({ scope: vertx.scope, version: vertx.version }, { scope: 'compile', version: 'managed' });
    assert.equal(model.modules.some((mod) => mod.externalDependencies.some((dep) => dep.scope === 'import')), false);
    assert.equal(
      model.modules.every((mod) => mod.path === '.' || (!mod.path.startsWith('/') && !mod.path.includes('..'))),
      true,
    );
  });

  it('ignores comments, management blocks, and plugin dependencies', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-parse-'));
    writeFile(dir, 'pom.xml', `${POM_HEADER}<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>dev.example</groupId>
  <artifactId>root</artifactId>
  <version>\${revision}</version>
  <packaging>pom</packaging>
  <properties>
    <revision>9.9.9</revision>
    <kept>\${missing.property}</kept>
  </properties>
  <modules>
    <!-- <module>hidden</module> -->
    <module>app</module>
  </modules>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.example</groupId>
        <artifactId>bom</artifactId>
        <version>1</version>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <build>
    <plugins>
      <plugin>
        <artifactId>example-plugin</artifactId>
        <dependencies>
          <dependency>
            <groupId>org.example</groupId>
            <artifactId>plugin-only</artifactId>
            <version>1</version>
          </dependency>
        </dependencies>
      </plugin>
    </plugins>
  </build>
  <description><![CDATA[ignore <module>also-hidden</module>]]></description>
</project>
`);
    writeFile(dir, 'app/pom.xml', `${POM_HEADER}<project>
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>dev.example</groupId>
    <artifactId>root</artifactId>
    <version>\${revision}</version>
  </parent>
  <artifactId>lib|extra</artifactId>
  <dependencies>
    <dependency>
      <groupId>org.example</groupId>
      <artifactId>used</artifactId>
      <version>\${kept}</version>
    </dependency>
  </dependencies>
</project>
`);
    const model = extractReactor(dir);
    assert.deepEqual(model.modules.map((mod) => mod.id), ['dev.example:lib|extra', 'dev.example:root']);
    const app = model.modules.find((mod) => mod.artifactId === undefined && mod.id.endsWith('lib|extra'));
    assert.equal(app.version, '9.9.9');
    assert.deepEqual(app.externalDependencies, [{ id: 'org.example:used', scope: 'compile', version: '${missing.property}' }]);
    assert.equal(model.edges.length, 0);
    const rendered = diffModules(
      { modules: [{ ...app, id: 'dev.example:before', externalDependencies: [], packaging: 'jar', parent: null, path: 'app', version: '1' }], edges: [], parentEdges: [] },
      { modules: [app], edges: [], parentEdges: [] },
    );
    assert.match(rendered.markdown, /lib\\\|extra/);
    assert.doesNotMatch(rendered.markdown, /\|extra \|/);
    rmSync(dir, { recursive: true, force: true });
  });
});

describe('report text', () => {
  it('truncates the comment on a section boundary and keeps the marker', () => {
    const spec = (index) => `### spec-${index}\n\n${'x'.repeat(4000)}\n`;
    const body = `## OpenAPI changes\n\n${Array.from({ length: 20 }, (_, index) => spec(index)).join('\n')}`;
    assert.ok(body.length > COMMENT_CHAR_LIMIT);
    const comment = buildComment(body);
    assert.equal(comment.split('\n')[0], COMMENT_MARKER);
    assert.ok(comment.length <= COMMENT_CHAR_LIMIT);
    assert.match(comment, /pr-review-aid/);
    assert.match(comment, /job summary/);
    assert.match(comment, /### spec-0/);
    assert.doesNotMatch(comment, /spec-19/);
    assert.equal(comment.includes('x'.repeat(4000)), true);
    assert.equal(comment.includes('x'.repeat(4001)), false);
  });

  it('truncates the job summary on a section boundary', () => {
    const text = `## OpenAPI changes\n\nok\n\n## Module graph changes\n\n${'z'.repeat(400)}\n`;
    const summary = summaryText(text, 180);
    assert.ok(Buffer.byteLength(summary) <= 180);
    assert.match(summary, /pr-review-aid/);
    assert.match(summary, /OpenAPI changes/);
    assert.doesNotMatch(summary, /Module graph changes/);
  });

  it('splits the report on headings', () => {
    const chunks = splitSections('## One\n\na\n### Two\n\nb\n');
    assert.deepEqual(chunks, ['## One\n\na\n', '### Two\n\nb\n']);
  });

  it('treats an oasdiff no-change changelog as empty', () => {
    assert.equal(changelogIsEmpty('# API Changelog 1.0.0 vs. 1.0.0\n\nNo changes detected\n'), true);
    assert.equal(changelogIsEmpty(''), true);
    assert.equal(changelogIsEmpty('## API Changes\n\n- removed\n'), false);
  });

  it('shifts oasdiff headings under the spec heading', () => {
    const source = '# API Changelog 0.1.0 vs. 0.1.0\n\n## API Changes\n\n### GET /hello\n- removed\n';
    assert.equal(reportMod.demoteChangelog(source), '#### API Changes\n\n##### GET /hello\n- removed');
    assert.equal(reportMod.changedOperationCount(source), 1);
  });
});

describe('openapi diff', () => {
  function writeStub(dir) {
    const log = path.join(dir, 'calls.txt');
    writeFileSync(log, '');
    const file = path.join(dir, 'oasdiff');
    writeFileSync(file, `#!/usr/bin/env node
import { appendFileSync, readFileSync } from 'node:fs';
const cmd = process.argv[2];
const base = process.argv[3];
const head = process.argv[4];
appendFileSync(${JSON.stringify(log)}, cmd + '\\n');
const baseText = readFileSync(base, 'utf8');
const headText = readFileSync(head, 'utf8');
if (baseText === headText || (baseText.includes('UNCHANGED') && headText.includes('UNCHANGED'))) {
  if (cmd === 'changelog') process.stdout.write('# API Changelog 1.0.0 vs. 1.0.0\\n\\nNo changes detected\\n');
  process.exit(0);
}
if (cmd === 'changelog') {
  process.stdout.write('# API Changelog 1.0.0 vs. 1.0.0\\n\\n## API Changes\\n\\n### GET /hello\\n- removed the path\\n');
  process.exit(0);
}
if (cmd === 'breaking') {
  process.stdout.write('1 changes: 1 error, 0 warning, 0 info\\n');
  process.exit(1);
}
process.exit(2);
`);
    chmodSync(file, 0o755);
    return { bin: file, log };
  }

  function specTree(dir, specs) {
    for (const [rel, body] of Object.entries(specs)) writeFile(dir, rel, body);
  }

  it('omits an unchanged spec, renders a change, and records new and removed documents', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-oas-'));
    const { bin, log } = writeStub(dir);
    const base = path.join(dir, 'base');
    const head = path.join(dir, 'head');
    specTree(base, {
      'same/openapi.yaml': 'UNCHANGED\n',
      'changed/openapi.yaml': 'base\n',
      'gone/openapi.yaml': 'only-base\n',
    });
    specTree(head, {
      'same/openapi.yaml': 'UNCHANGED\n',
      'changed/openapi.yaml': 'head\n',
      'fresh/openapi.yaml': 'only-head\n',
    });
    const diff = diffOpenApi({ baseDir: base, headDir: head, bin });
    const calls = readFileSync(log, 'utf8').trim().split('\n').filter(Boolean);
    assert.deepEqual(calls, ['changelog', 'breaking', 'changelog', 'breaking']);
    assert.equal(diff.breaking, true);
    assert.deepEqual(diff.sections.map((section) => section.path), ['changed/openapi.yaml', 'fresh/openapi.yaml', 'gone/openapi.yaml']);
    assert.match(diff.sections[0].markdown, /removed the path/);
    assert.equal(diff.sections[1].markdown, 'new API document');
    assert.equal(diff.sections[2].markdown, 'removed API document');
    assert.equal(diff.runs.find((entry) => entry.path === 'same/openapi.yaml').kind, 'unchanged');
    const rendered = renderReport({ openApiMarkdown: reportMod.renderOpenApiMarkdown(diff) });
    assert.match(rendered, /## OpenAPI changes/);
    assert.doesNotMatch(rendered, /same\/openapi\.yaml/);
    assert.match(rendered, /Breaking changes \(ERR\)/);
    assert.match(rendered, /#### API Changes/);
    assert.match(rendered, /##### GET \/hello/);
    assert.doesNotMatch(rendered, /# API Changelog/);
    assert.match(rendered, /<details>\n<summary>`changed\/openapi\.yaml` \(1 operation\)<\/summary>/);
    assert.doesNotMatch(rendered, /<details open>/);
    assert.match(rendered, /new API document/);
    assert.match(rendered, /removed API document/);
    rmSync(dir, { recursive: true, force: true });
  });

  it('opens the changelog when it is the only spec change', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-oas-one-'));
    const { bin } = writeStub(dir);
    writeFile(dir, 'base/only/openapi.yaml', 'base\n');
    writeFile(dir, 'head/only/openapi.yaml', 'head\n');
    const diff = diffOpenApi({ baseDir: path.join(dir, 'base'), headDir: path.join(dir, 'head'), bin });
    const rendered = reportMod.renderOpenApiMarkdown(diff);
    assert.match(rendered, /<details open>\n<summary>`only\/openapi\.yaml` \(1 operation\)<\/summary>/);
    assert.match(rendered, /Breaking changes \(ERR\)/);
    const breakingAt = rendered.indexOf('Breaking changes (ERR)');
    const detailsAt = rendered.indexOf('<details');
    assert.ok(breakingAt > 0 && breakingAt < detailsAt);
    rmSync(dir, { recursive: true, force: true });
  });

  it('fails when the oasdiff binary is missing', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'pr-review-oas-missing-'));
    writeFile(dir, 'base/only/openapi.yaml', 'a\n');
    writeFile(dir, 'head/only/openapi.yaml', 'b\n');
    assert.throws(
      () => diffOpenApi({ baseDir: path.join(dir, 'base'), headDir: path.join(dir, 'head'), bin: path.join(dir, 'missing-oasdiff') }),
      /oasdiff executable not found/,
    );
    rmSync(dir, { recursive: true, force: true });
  });

  it('diffs a real breaking change when oasdiff is installed', (t) => {
    const fromEnv = process.env.OASDIFF_BIN;
    let bin = fromEnv && existsSync(fromEnv) ? fromEnv : '';
    if (!bin) {
      try {
        bin = execFileSync('bash', ['-lc', 'command -v oasdiff'], { encoding: 'utf8' }).trim();
      } catch {
        bin = '';
      }
    }
    if (!bin) {
      t.skip('oasdiff is not on PATH and OASDIFF_BIN is unset');
      return;
    }
    const diff = diffOpenApi({
      baseDir: path.join(FIXTURES, 'openapi', 'base'),
      headDir: path.join(FIXTURES, 'openapi', 'head'),
      bin,
    });
    assert.equal(diff.breaking, true);
    assert.equal(diff.runs[0].breakingStatus, 1);
    assert.match(diff.sections[0].markdown, /api path removed/i);
    const rendered = reportMod.renderOpenApiMarkdown(diff);
    assert.match(rendered, /<details open>/);
    assert.match(rendered, /Breaking changes \(ERR\)/);
    assert.match(rendered, /api path removed without deprecation/);
    assert.match(rendered, /##### POST \/hello/);
    assert.doesNotMatch(rendered, /# API Changelog/);
    const same = path.join(FIXTURES, 'openapi', 'base');
    const unchanged = diffOpenApi({ baseDir: same, headDir: same, bin });
    assert.equal(unchanged.breaking, false);
    assert.equal(unchanged.sections.length, 0);
    assert.equal(unchanged.runs[0].kind, 'unchanged');
  });
});
