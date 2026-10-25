// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Deterministic Maven reactor model.
 *
 * Reads pom.xml files from a checkout. No Maven process and no network.
 * Reactor membership is the root `<modules>` list, followed recursively.
 * Dependencies are the project's own `<dependencies>` block: dependency
 * management, profiles, and plugin dependencies are not edges.
 *
 *   node scripts/pr-review-aid/maven-graph.mjs --root <checkout>
 *
 * Prints the model as JSON with sorted keys. Paths are relative to the
 * checkout. `${revision}` and other properties declared in the reactor are
 * resolved for display; a property this checkout does not define stays literal.
 */

import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { cmp, decodeXml, relativePosix, sortBy, stableStringify } from './util.mjs';

const COMMENT_OR_CDATA = /<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>/g;

export function parseXml(text) {
  const src = String(text).replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').replace(COMMENT_OR_CDATA, '');
  const root = { name: '#root', children: [], text: '' };
  const stack = [root];
  const re = /<([^!?][^>]*?)>|([^<]+)/g;
  let match;
  while ((match = re.exec(src))) {
    if (match[2] !== undefined) {
      stack[stack.length - 1].text += match[2];
      continue;
    }
    let tag = match[1].trim();
    if (tag.startsWith('/')) {
      const name = tag.slice(1).trim().split(/\s+/)[0];
      for (let i = stack.length - 1; i >= 1; i--) {
        if (stack[i].name === name) {
          stack.length = i;
          break;
        }
      }
      continue;
    }
    const selfClosing = /\/\s*$/.test(tag);
    if (selfClosing) tag = tag.replace(/\/\s*$/, '').trim();
    const name = tag.split(/\s+/)[0];
    const node = { name, children: [], text: '' };
    stack[stack.length - 1].children.push(node);
    if (!selfClosing) stack.push(node);
  }
  return root.children.find((child) => child.name === 'project') ?? null;
}

function textOf(node) {
  if (!node) return '';
  return decodeXml(node.text).replace(/\s+/g, ' ').trim();
}

function childrenNamed(node, name) {
  return (node?.children ?? []).filter((child) => child.name === name);
}

function firstChild(node, name) {
  return childrenNamed(node, name)[0] ?? null;
}

function interpolateOnce(value, props) {
  return String(value).replace(/\$\{([^}]+)}/g, (all, key) => (Object.prototype.hasOwnProperty.call(props, key) ? props[key] : all));
}

function resolveProps(raw) {
  const props = { ...raw };
  for (let pass = 0; pass < 20; pass++) {
    let changed = false;
    for (const key of Object.keys(props)) {
      const next = interpolateOnce(props[key], props);
      if (next !== props[key]) {
        props[key] = next;
        changed = true;
      }
    }
    if (!changed) break;
  }
  return props;
}

function interpolate(value, props) {
  let current = String(value);
  for (let pass = 0; pass < 20; pass++) {
    const next = interpolateOnce(current, props);
    if (next === current) return current;
    current = next;
  }
  return current;
}

function moduleNames(project) {
  const names = childrenNamed(project, 'modules').flatMap((block) => childrenNamed(block, 'module').map(textOf).filter(Boolean));
  return names.sort(cmp);
}

function directDependencies(project) {
  return childrenNamed(project, 'dependencies').flatMap((block) => childrenNamed(block, 'dependency'));
}

function directoryNames(listDir, dir) {
  return listDir(dir)
    .map((entry) => (typeof entry === 'string' ? entry : entry.name))
    .sort(cmp);
}

function readProject(dir, rel, listDir) {
  const entries = directoryNames(listDir, dir);
  if (!entries.includes('pom.xml')) throw new Error(`missing pom.xml for ${rel}`);
  const xml = readFileSync(path.join(dir, 'pom.xml'), 'utf8');
  const project = parseXml(xml);
  if (!project) throw new Error(`pom.xml has no project element: ${rel}`);
  return project;
}

function parentPomPath(rootDir, dir, project) {
  const parent = firstChild(project, 'parent');
  if (!parent) return null;
  const relative = firstChild(parent, 'relativePath');
  if (relative && !textOf(relative)) return null;
  const relText = relative ? textOf(relative) : '../pom.xml';
  let candidate = path.resolve(dir, relText);
  if (path.basename(candidate) !== 'pom.xml') candidate = path.join(candidate, 'pom.xml');
  const rel = path.relative(rootDir, candidate);
  if (rel.startsWith('..') || path.isAbsolute(rel)) return null;
  return candidate;
}

function resolveModule(mod, byPom, cache, stack) {
  if (cache.has(mod.pomPath)) return cache.get(mod.pomPath);
  if (stack.has(mod.pomPath)) throw new Error(`parent cycle involving ${mod.rel}`);
  stack.add(mod.pomPath);
  const parentPath = mod.parentPom;
  const parentMod = parentPath ? byPom.get(parentPath) : null;
  const parent = parentMod ? resolveModule(parentMod, byPom, cache, stack) : null;
  stack.delete(mod.pomPath);

  const parentEl = firstChild(mod.project, 'parent');
  const rawProps = { ...(parent?.props ?? {}) };
  const properties = firstChild(mod.project, 'properties');
  if (properties) {
    for (const child of properties.children) rawProps[child.name] = textOf(child);
  }
  let props = resolveProps(rawProps);

  const groupRaw = textOf(firstChild(mod.project, 'groupId')) || textOf(firstChild(parentEl, 'groupId')) || parent?.groupId || '';
  const artifactRaw = textOf(firstChild(mod.project, 'artifactId'));
  const versionRaw = textOf(firstChild(mod.project, 'version')) || textOf(firstChild(parentEl, 'version')) || parent?.version || '';
  const packagingRaw = textOf(firstChild(mod.project, 'packaging')) || 'jar';
  if (!artifactRaw) throw new Error(`module has no artifactId: ${mod.rel}`);

  const seeded = {
    ...props,
    'project.groupId': interpolate(groupRaw, props),
    'project.artifactId': interpolate(artifactRaw, props),
    'project.version': interpolate(versionRaw, props),
    'project.packaging': interpolate(packagingRaw, props) || 'jar',
  };
  if (parentEl) {
    seeded['project.parent.groupId'] = interpolate(textOf(firstChild(parentEl, 'groupId')), props);
    seeded['project.parent.artifactId'] = interpolate(textOf(firstChild(parentEl, 'artifactId')), props);
    seeded['project.parent.version'] = interpolate(textOf(firstChild(parentEl, 'version')), props);
  }
  props = resolveProps(seeded);

  const groupId = interpolate(groupRaw, props);
  const artifactId = interpolate(artifactRaw, props);
  const version = interpolate(versionRaw, props);
  const packaging = interpolate(packagingRaw, props) || 'jar';
  props['project.groupId'] = groupId;
  props['project.artifactId'] = artifactId;
  props['project.version'] = version;
  props['project.packaging'] = packaging;

  let parentId = null;
  if (parent) parentId = `${parent.groupId}:${parent.artifactId}`;
  else if (parentEl) {
    const pg = interpolate(textOf(firstChild(parentEl, 'groupId')), props);
    const pa = interpolate(textOf(firstChild(parentEl, 'artifactId')), props);
    if (pg && pa) parentId = `${pg}:${pa}`;
  }

  const resolved = { groupId, artifactId, version, packaging, props, parentId, rel: mod.rel };
  cache.set(mod.pomPath, resolved);
  return resolved;
}

function dependencyRecords(project, props, rel) {
  const records = [];
  for (const dep of directDependencies(project)) {
    const groupId = interpolate(textOf(firstChild(dep, 'groupId')), props);
    const artifactId = interpolate(textOf(firstChild(dep, 'artifactId')), props);
    if (!groupId || !artifactId) throw new Error(`dependency is missing groupId or artifactId in ${rel}`);
    const versionText = textOf(firstChild(dep, 'version'));
    const version = versionText ? interpolate(versionText, props) : 'managed';
    const scopeText = textOf(firstChild(dep, 'scope'));
    const scope = scopeText ? interpolate(scopeText, props) : 'compile';
    records.push({ id: `${groupId}:${artifactId}`, version, scope });
  }
  const seen = new Set();
  const unique = [];
  for (const record of sortBy(records, 'id', 'scope', 'version')) {
    const key = `${record.id}\0${record.scope}\0${record.version}`;
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push(record);
  }
  return unique;
}

/**
 * @param {string} rootDir checkout root that contains the reactor pom.xml
 * @param {{ listDir?: (dir: string) => string[] }} [options]
 */
export function extractReactor(rootDir, options = {}) {
  const listDir = options.listDir ?? readdirSync;
  const root = path.resolve(rootDir);
  const modules = [];
  const seen = new Set();

  function visit(dir) {
    const resolved = path.resolve(dir);
    const relToRoot = path.relative(root, resolved);
    if (relToRoot.startsWith('..') || path.isAbsolute(relToRoot)) {
      throw new Error('module path escapes the checkout');
    }
    const rel = relativePosix(root, resolved);
    if (seen.has(rel)) return;
    seen.add(rel);
    const project = readProject(resolved, rel, listDir);
    const pomPath = path.resolve(resolved, 'pom.xml');
    modules.push({
      dir: resolved,
      rel,
      pomPath,
      project,
      parentPom: parentPomPath(root, resolved, project),
    });
    for (const name of moduleNames(project)) {
      const next = path.resolve(resolved, name);
      visit(next);
    }
  }

  visit(root);

  const byPom = new Map(modules.map((mod) => [mod.pomPath, mod]));
  const cache = new Map();
  const stack = new Set();
  const resolved = modules.map((mod) => resolveModule(mod, byPom, cache, stack));

  const ids = new Set();
  for (const mod of resolved) {
    const id = `${mod.groupId}:${mod.artifactId}`;
    if (ids.has(id)) throw new Error(`duplicate module id ${id}`);
    ids.add(id);
  }

  const moduleModels = [];
  const edges = [];
  const parentEdges = [];
  for (const mod of resolved) {
    const id = `${mod.groupId}:${mod.artifactId}`;
    const raw = modules.find((item) => item.rel === mod.rel);
    const deps = dependencyRecords(raw.project, mod.props, mod.rel);
    const external = [];
    for (const dep of deps) {
      if (ids.has(dep.id)) {
        edges.push({ from: id, to: dep.id, scope: dep.scope });
      } else {
        external.push({ id: dep.id, scope: dep.scope, version: dep.version });
      }
    }
    if (mod.parentId && ids.has(mod.parentId)) parentEdges.push({ from: id, to: mod.parentId });
    moduleModels.push({
      externalDependencies: external,
      id,
      packaging: mod.packaging,
      parent: mod.parentId,
      path: mod.rel,
      version: mod.version,
    });
  }

  return {
    edges: sortBy(edges, 'from', 'to', 'scope'),
    modules: sortBy(moduleModels, 'id'),
    parentEdges: sortBy(parentEdges, 'from', 'to'),
  };
}

function arg(name) {
  const index = process.argv.indexOf(`--${name}`);
  if (index === -1 || index + 1 >= process.argv.length) throw new Error(`missing --${name}`);
  return process.argv[index + 1];
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.stdout.write(stableStringify(extractReactor(arg('root'))));
}
