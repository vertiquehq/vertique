// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * PR-scoped mutation testing: selection and changed-line report.
 *
 * OSS PIT has no diff-scoped mode, so this script supplies it in two steps
 * that scripts/pit-pr-scope.sh wires around the Maven run:
 *
 *   select  reads `git diff -U0` output, picks the production classes of the
 *           pilot modules that gained lines, and applies the class budget.
 *   report  reads each selected module's target/pit-reports/mutations.xml and
 *           keeps only the mutants on lines the change added.
 *
 * Usage:
 *   node scripts/pit-pr-scope.mjs select --diff <file> --max-classes <n> --out <selection.json>
 *   node scripts/pit-pr-scope.mjs report --diff <file> --selection <selection.json> --out-dir <dir>
 *
 * `select` prints `run=`, `modules=` and `targetClasses=` lines for the shell
 * entry point. `report` writes summary.md and summary.json and prints the
 * Markdown. The report is advisory: surviving mutants never fail it, while a
 * selected module without a PIT report does, so a broken run is never shown
 * as a clean one.
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** Modules whose changed classes are mutated. Measured in the 2026-09-28 spike. */
export const PILOT_MODULES = Object.freeze([
  'vertique-input-processing',
  'vertique-json-schema',
  'vertique-resilience',
  'vertique-rest/vertique-rest-security',
]);

const DETECTED = new Set(['KILLED', 'TIMED_OUT', 'MEMORY_ERROR', 'RUN_ERROR']);
const UNDETECTED = new Set(['SURVIVED', 'NO_COVERAGE']);
const PRODUCTION_SOURCE = /^(.+?)\/src\/main\/java\/(.+)\.java$/;
const NOT_A_CLASS = new Set(['package-info', 'module-info']);

/**
 * Maps each file of a `git diff -U0` to the new-side line numbers it adds.
 * Deleted files and files with deletions only are absent from the result.
 */
export function parseChangedLines(diff) {
  const changed = new Map();
  let current = null;
  for (const line of diff.split('\n')) {
    if (line.startsWith('diff --git ')) {
      current = null;
    } else if (line.startsWith('+++ ')) {
      const target = line.slice(4).trim();
      current = target === '/dev/null' ? null : target.replace(/^b\//, '');
    } else if (current && line.startsWith('@@ ')) {
      const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/.exec(line);
      if (!hunk) continue;
      const start = Number(hunk[1]);
      const count = hunk[2] === undefined ? 1 : Number(hunk[2]);
      if (count === 0) continue;
      if (!changed.has(current)) changed.set(current, new Set());
      const lines = changed.get(current);
      for (let n = start; n < start + count; n++) lines.add(n);
    }
  }
  return changed;
}

/**
 * Selects the pilot production classes among `files` (repository-relative
 * paths) and decides whether the change fits the class budget.
 */
export function selectTargets(files, { maxClasses, pilotModules = PILOT_MODULES }) {
  const selected = [];
  const outOfScope = [];
  for (const file of [...files].sort()) {
    const match = PRODUCTION_SOURCE.exec(file);
    if (!match) continue;
    const [, module, classPath] = match;
    if (NOT_A_CLASS.has(path.posix.basename(classPath))) continue;
    if (pilotModules.includes(module)) {
      selected.push({ module, file, className: classPath.replaceAll('/', '.') });
    } else {
      outOfScope.push(file);
    }
  }
  const classes = selected.map((s) => s.className).sort();
  const modules = [...new Set(selected.map((s) => s.module))].sort();
  const skipped = classes.length > maxClasses ? { reason: 'budget', classes: classes.length, maxClasses } : null;
  return {
    run: classes.length > 0 && skipped === null,
    modules,
    classes,
    files: selected.map((s) => s.file).sort(),
    targetClasses: classes.flatMap((c) => [c, `${c}$*`]).join(','),
    outOfScope,
    skipped,
  };
}

function decode(text) {
  return text
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => String.fromCodePoint(Number(dec)))
    .replace(/&amp;/g, '&');
}

/** Parses PIT's mutations.xml into plain mutation records. */
export function parseMutations(xml) {
  const mutations = [];
  for (const [, attributes, body] of xml.matchAll(/<mutation\b([^>]*)>([\s\S]*?)<\/mutation>/g)) {
    const record = {};
    for (const [, name, , value] of attributes.matchAll(/(\w+)=(['"])(.*?)\2/g)) record[name] = decode(value);
    for (const [, tag, value] of body.matchAll(/<(\w+)>([\s\S]*?)<\/\1>/g)) record[tag] = decode(value);
    mutations.push({
      status: record.status,
      sourceFile: record.sourceFile,
      mutatedClass: record.mutatedClass,
      mutatedMethod: record.mutatedMethod,
      lineNumber: Number(record.lineNumber),
      mutator: (record.mutator ?? '').split('.').pop(),
      description: record.description ?? '',
    });
  }
  return mutations;
}

/**
 * The source path of a mutant relative to its module's source root. PIT names
 * the file, not its directory; the package of the mutated class supplies it,
 * which holds for nested and secondary top-level classes alike.
 */
export function sourcePathOf(mutation) {
  const cut = mutation.mutatedClass.lastIndexOf('.');
  return cut < 0 ? mutation.sourceFile : `${mutation.mutatedClass.slice(0, cut).replaceAll('.', '/')}/${mutation.sourceFile}`;
}

/**
 * Filters the selected modules' mutants to the added lines. Throws when a
 * selected module has no report: a missing report is a failed run.
 */
export function buildReport({ selection, changedLines, xmlByModule }) {
  const report = { selection, changed: null, wholeClass: null };
  if (!selection.run) return report;

  const selectedFiles = new Set(selection.files);
  const changed = { total: 0, detected: 0, undetected: [] };
  const wholeClass = { total: 0, undetected: 0 };
  for (const module of selection.modules) {
    const xml = xmlByModule[module];
    if (xml === undefined) throw new Error(`no PIT report for ${module}`);
    for (const mutation of parseMutations(xml)) {
      const file = `${module}/src/main/java/${sourcePathOf(mutation)}`;
      if (!selectedFiles.has(file)) continue;
      wholeClass.total++;
      if (UNDETECTED.has(mutation.status)) wholeClass.undetected++;
      if (!changedLines.get(file)?.has(mutation.lineNumber)) continue;
      changed.total++;
      if (DETECTED.has(mutation.status)) changed.detected++;
      if (UNDETECTED.has(mutation.status)) changed.undetected.push({ file, ...mutation });
    }
  }
  changed.undetected.sort((a, b) => a.file.localeCompare(b.file) || a.lineNumber - b.lineNumber);
  report.changed = changed;
  report.wholeClass = wholeClass;
  return report;
}

function cell(text) {
  return String(text).replace(/\r?\n/g, ' ').replace(/`/g, "'").replace(/\|/g, '\\|');
}

/** Renders the report as the Markdown shown in the job summary. */
export function renderMarkdown(report) {
  const { selection, changed, wholeClass } = report;
  const out = ['## Mutation testing (advisory)', ''];

  if (selection.skipped) {
    out.push(
      `Skipped: ${selection.skipped.classes} changed classes exceed the budget of ${selection.skipped.maxClasses} (\`PIT_MAX_CLASSES\`).`,
      'Run `bash scripts/pit-pr-scope.sh` locally to mutate them.'
    );
  } else if (!selection.run) {
    out.push('No production classes changed in the pilot modules, so nothing was mutated.');
  } else if (changed.total === 0) {
    out.push('No mutants fall on lines this change added (declarations, comments or formatting only).');
  } else if (changed.undetected.length === 0) {
    out.push(`All ${changed.total} mutants on added lines were detected by the unit tests of their module.`);
  } else {
    out.push(
      `**${changed.undetected.length} of ${changed.total} mutants on added lines were not detected** by the unit tests of their module.`,
      '',
      '| Location | Status | Mutation |',
      '|---|---|---|'
    );
    for (const m of changed.undetected) {
      out.push(`| \`${cell(m.file)}:${m.lineNumber}\` | ${m.status} | ${cell(m.description)} (\`${cell(m.mutatedMethod)}\`, ${cell(m.mutator)}) |`);
    }
  }

  if (wholeClass) {
    out.push('', `Whole changed classes: ${wholeClass.total} mutants, ${wholeClass.undetected} undetected. Only mutants on added lines are listed.`);
  }
  if (selection.outOfScope.length > 0) {
    out.push('', 'Changed production classes outside the pilot modules (not mutated):', '');
    for (const file of selection.outOfScope.slice(0, 20)) out.push(`- \`${cell(file)}\``);
    if (selection.outOfScope.length > 20) out.push(`- and ${selection.outOfScope.length - 20} more`);
  }
  out.push(
    '',
    '> Advisory: this check never blocks a merge. An undetected mutant means no unit test fails when that line is changed as described; add an assertion, or note why the mutant is equivalent. '
      + `Pilot modules: ${PILOT_MODULES.map((m) => `\`${m}\``).join(', ')}.`
  );
  return `${out.join('\n')}\n`;
}

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 2) {
    if (!argv[i].startsWith('--') || argv[i + 1] === undefined) throw new Error(`unrecognised argument: ${argv[i]}`);
    args[argv[i].slice(2)] = argv[i + 1];
  }
  return args;
}

function requireArg(args, name) {
  if (!args[name]) throw new Error(`--${name} is required`);
  return args[name];
}

function main([command, ...rest]) {
  const args = parseArgs(rest);
  const changedLines = parseChangedLines(readFileSync(requireArg(args, 'diff'), 'utf8'));
  if (command === 'select') {
    const maxClasses = Number(requireArg(args, 'max-classes'));
    if (!Number.isInteger(maxClasses) || maxClasses < 1) throw new Error('--max-classes must be a positive integer');
    const selection = selectTargets([...changedLines.keys()], { maxClasses });
    writeFileSync(requireArg(args, 'out'), `${JSON.stringify(selection, null, 2)}\n`);
    process.stdout.write(`run=${selection.run}\nmodules=${selection.modules.join(',')}\ntargetClasses=${selection.targetClasses}\n`);
  } else if (command === 'report') {
    const selection = JSON.parse(readFileSync(requireArg(args, 'selection'), 'utf8'));
    const xmlByModule = {};
    for (const module of selection.run ? selection.modules : []) {
      const xml = path.join(module, 'target', 'pit-reports', 'mutations.xml');
      if (existsSync(xml)) xmlByModule[module] = readFileSync(xml, 'utf8');
    }
    const report = buildReport({ selection, changedLines, xmlByModule });
    const markdown = renderMarkdown(report);
    const outDir = requireArg(args, 'out-dir');
    mkdirSync(outDir, { recursive: true });
    writeFileSync(path.join(outDir, 'summary.md'), markdown);
    writeFileSync(path.join(outDir, 'summary.json'), `${JSON.stringify(report, null, 2)}\n`);
    process.stdout.write(markdown);
  } else {
    throw new Error(`unknown command: ${command ?? '(none)'}; expected select or report`);
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`pit-pr-scope: ${error.message}\n`);
    process.exit(1);
  }
}
