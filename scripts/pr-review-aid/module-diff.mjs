// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Module-graph delta between two reactor models from maven-graph.mjs.
 *
 * The diagram shows modules that were added, removed, or changed, plus one
 * hop of dependency neighbours in either direction. An edge is drawn only
 * when at least one end is one of those modules. Parent links are not
 * diagram edges. Above the node cap the diagram is Graphviz DOT instead of
 * inline Mermaid; the textual table is always included.
 *
 * A module version that moves exactly with the reactor root version is not
 * a per-module change. The same is true of an external dependency in the
 * root group whose version moves the same way. Parent links and reactor
 * edges do not carry a version. The move is one line, and only when some
 * other module change is present. A version move on its own is silent.
 */

import { cmp, escapeDot, escapeMermaid, inlineCode, sortBy } from './util.mjs';

export const DEFAULT_NODE_CAP = 60;

const CLASS_DEFS = {
  added: 'classDef added fill:#d1fae5,stroke:#10b981,color:#064e3b',
  removed: 'classDef removed fill:#fee2e2,stroke:#ef4444,color:#7f1d1d',
  changed: 'classDef changed fill:#fef9c3,stroke:#eab308,color:#713f12',
  context: 'classDef context fill:#f3f4f6,stroke:#9ca3af,color:#6b7280',
};

const DOT_NODE = {
  added: 'style=filled fillcolor="#d1fae5" color="#10b981" fontcolor="#064e3b"',
  removed: 'style=filled fillcolor="#fee2e2" color="#ef4444" fontcolor="#7f1d1d"',
  changed: 'style=filled fillcolor="#fef9c3" color="#eab308" fontcolor="#713f12"',
  context: 'style=filled fillcolor="#f3f4f6" color="#9ca3af" fontcolor="#6b7280"',
};

export function nodeCapFrom(options = {}, env = process.env) {
  if (options.nodeCap != null && options.nodeCap !== '') return parseCap(options.nodeCap);
  if (env.PR_REVIEW_AID_NODE_CAP) return parseCap(env.PR_REVIEW_AID_NODE_CAP);
  return DEFAULT_NODE_CAP;
}

function parseCap(value) {
  const cap = Number(value);
  if (!Number.isInteger(cap) || cap < 1) throw new Error(`node cap must be a positive integer, got ${value}`);
  return cap;
}

function byId(model) {
  return new Map(model.modules.map((mod) => [mod.id, mod]));
}

function edgeMap(edges) {
  const map = new Map();
  for (const edge of edges) {
    const key = `${edge.from}\0${edge.to}`;
    if (!map.has(key)) map.set(key, new Map());
    map.get(key).set(edge.scope, edge);
  }
  return map;
}

function outgoing(model, id) {
  return sortBy(
    model.edges.filter((edge) => edge.from === id),
    'to',
    'scope',
  );
}

function rootModule(model) {
  return model.modules.find((mod) => mod.path === '.') ?? null;
}

function followsReactor(oldVersion, newVersion, baseRoot, headRoot) {
  if (!baseRoot || !headRoot || baseRoot.version === headRoot.version) return false;
  return oldVersion === baseRoot.version && newVersion === headRoot.version;
}

function reactorGroup(depId, baseRoot) {
  if (!baseRoot) return false;
  const group = baseRoot.id.slice(0, baseRoot.id.indexOf(':'));
  const depGroup = String(depId).slice(0, String(depId).indexOf(':'));
  return group.length > 0 && group === depGroup;
}

function reactorVersionMove(baseRoot, headRoot) {
  if (!baseRoot || !headRoot || baseRoot.version === headRoot.version) return null;
  return { from: baseRoot.version, to: headRoot.version };
}

function depPhrase(prefix, dep) {
  return `dependency ${prefix} ${inlineCode(dep.id)} ${inlineCode(dep.version)} (${dep.scope})`;
}

function edgePhrase(prefix, edge) {
  return `edge ${prefix} ${inlineCode(edge.to)} (${edge.scope})`;
}

function describeAddedOrRemoved(prefix, mod, model) {
  const phrases = [prefix];
  for (const edge of outgoing(model, mod.id)) phrases.push(edgePhrase(prefix, edge));
  for (const dep of sortBy(mod.externalDependencies, 'id', 'scope', 'version')) phrases.push(depPhrase(prefix, dep));
  return phrases.join('; ');
}

function describeChanged(baseMod, headMod, baseModel, headModel, baseRoot, headRoot) {
  const phrases = [];
  if (baseMod.packaging !== headMod.packaging) {
    phrases.push(`packaging ${inlineCode(baseMod.packaging)} -> ${inlineCode(headMod.packaging)}`);
  }
  if (baseMod.version !== headMod.version && !followsReactor(baseMod.version, headMod.version, baseRoot, headRoot)) {
    phrases.push(`version ${inlineCode(baseMod.version)} -> ${inlineCode(headMod.version)}`);
  }
  if ((baseMod.parent ?? null) !== (headMod.parent ?? null)) {
    phrases.push(`parent ${inlineCode(baseMod.parent ?? 'none')} -> ${inlineCode(headMod.parent ?? 'none')}`);
  }

  const baseEdges = new Map(outgoing(baseModel, baseMod.id).map((edge) => [`${edge.to}\0${edge.scope}`, edge]));
  const headEdges = new Map(outgoing(headModel, headMod.id).map((edge) => [`${edge.to}\0${edge.scope}`, edge]));
  const pairScopes = (edges) => {
    const byTarget = new Map();
    for (const edge of edges.values()) {
      if (!byTarget.has(edge.to)) byTarget.set(edge.to, []);
      byTarget.get(edge.to).push(edge.scope);
    }
    return byTarget;
  };
  const baseByTarget = pairScopes(baseEdges);
  const headByTarget = pairScopes(headEdges);
  const targets = [...new Set([...baseByTarget.keys(), ...headByTarget.keys()])].sort(cmp);
  for (const target of targets) {
    const before = (baseByTarget.get(target) ?? []).slice().sort(cmp);
    const after = (headByTarget.get(target) ?? []).slice().sort(cmp);
    if (before.join('\0') === after.join('\0')) continue;
    if (before.length === 1 && after.length === 1) {
      phrases.push(`edge ${inlineCode(target)} scope ${inlineCode(before[0])} -> ${inlineCode(after[0])}`);
      continue;
    }
    for (const scope of before) {
      if (!after.includes(scope)) phrases.push(edgePhrase('removed', { to: target, scope }));
    }
    for (const scope of after) {
      if (!before.includes(scope)) phrases.push(edgePhrase('added', { to: target, scope }));
    }
  }

  const baseDeps = new Map(baseMod.externalDependencies.map((dep) => [`${dep.id}\0${dep.scope}`, dep]));
  const headDeps = new Map(headMod.externalDependencies.map((dep) => [`${dep.id}\0${dep.scope}`, dep]));
  const depIds = [...new Set([...baseMod.externalDependencies, ...headMod.externalDependencies].map((dep) => dep.id))].sort(cmp);
  for (const id of depIds) {
    const before = sortBy(
      [...baseDeps.values()].filter((dep) => dep.id === id),
      'scope',
    );
    const after = sortBy(
      [...headDeps.values()].filter((dep) => dep.id === id),
      'scope',
    );
    const beforeKey = before.map((dep) => `${dep.scope}\0${dep.version}`).join('\0');
    const afterKey = after.map((dep) => `${dep.scope}\0${dep.version}`).join('\0');
    if (beforeKey === afterKey) continue;
    if (before.length === 1 && after.length === 1 && before[0].scope === after[0].scope) {
      if (reactorGroup(id, baseRoot) && followsReactor(before[0].version, after[0].version, baseRoot, headRoot)) continue;
      phrases.push(`dependency ${inlineCode(id)} ${inlineCode(before[0].version)} -> ${inlineCode(after[0].version)}`);
      continue;
    }
    if (before.length === 1 && after.length === 1 && before[0].version === after[0].version) {
      phrases.push(`dependency ${inlineCode(id)} scope ${inlineCode(before[0].scope)} -> ${inlineCode(after[0].scope)}`);
      continue;
    }
    for (const dep of before) {
      const match = after.find((item) => item.scope === dep.scope && item.version === dep.version);
      if (!match) phrases.push(depPhrase('removed', dep));
    }
    for (const dep of after) {
      const match = before.find((item) => item.scope === dep.scope && item.version === dep.version);
      if (!match) phrases.push(depPhrase('added', dep));
    }
  }

  return phrases.join('; ');
}

function assignNodeIds(ids) {
  const used = new Set();
  const map = new Map();
  for (const id of [...ids].sort(cmp)) {
    let base = `m_${id.replace(/[^A-Za-z0-9_]/g, '_')}`;
    let candidate = base;
    let suffix = 2;
    while (used.has(candidate)) {
      candidate = `${base}_${suffix}`;
      suffix += 1;
    }
    used.add(candidate);
    map.set(id, candidate);
  }
  return map;
}

function classifyEdges(baseModel, headModel) {
  const base = edgeMap(baseModel.edges);
  const head = edgeMap(headModel.edges);
  const keys = [...new Set([...base.keys(), ...head.keys()])].sort(cmp);
  const classified = [];
  for (const key of keys) {
    const [from, to] = key.split('\0');
    const before = [...(base.get(key)?.keys() ?? [])].sort(cmp);
    const after = [...(head.get(key)?.keys() ?? [])].sort(cmp);
    if (before.length === 1 && after.length === 1 && before[0] !== after[0]) {
      classified.push({ from, to, kind: 'changed', scope: `${before[0]} to ${after[0]}`, baseScope: before[0], headScope: after[0] });
      continue;
    }
    for (const scope of before) {
      if (!after.includes(scope)) classified.push({ from, to, kind: 'removed', scope });
    }
    for (const scope of after) {
      if (!before.includes(scope)) classified.push({ from, to, kind: 'added', scope });
    }
    for (const scope of before) {
      if (after.includes(scope)) classified.push({ from, to, kind: 'unchanged', scope });
    }
  }
  return sortBy(classified, 'from', 'to', 'kind', 'scope');
}

function renderTable(rows) {
  const lines = ['| Module | Change |', '| --- | --- |'];
  for (const row of rows) lines.push(`| ${inlineCode(row.id)} | ${row.change} |`);
  return `${lines.join('\n')}\n`;
}

function renderMermaid(nodes, edges) {
  const lines = ['flowchart LR'];
  for (const node of nodes) {
    lines.push(`  ${node.nodeId}["${escapeMermaid(node.id)}"]`);
  }
  for (const edge of edges) {
    lines.push(`  ${edge.fromId} -->|"${escapeMermaid(edge.scope)}"| ${edge.toId}`);
  }
  edges.forEach((edge, index) => {
    if (edge.kind === 'removed') lines.push(`  linkStyle ${index} stroke:#ef4444,stroke-dasharray:5 5`);
    else if (edge.kind === 'changed') lines.push(`  linkStyle ${index} stroke:#eab308`);
  });
  for (const kind of ['added', 'removed', 'changed', 'context']) {
    const members = nodes.filter((node) => node.kind === kind);
    if (members.length === 0) continue;
    lines.push(`  ${CLASS_DEFS[kind]}`);
    for (const node of members) lines.push(`  class ${node.nodeId} ${kind}`);
  }
  return `${lines.join('\n')}\n`;
}

function renderDot(nodes, edges) {
  const lines = ['digraph module_graph {', '  rankdir=LR;', '  node [shape=box];'];
  for (const node of nodes) {
    lines.push(`  "${escapeDot(node.nodeId)}" [label="${escapeDot(node.id)}" ${DOT_NODE[node.kind]}];`);
  }
  for (const edge of edges) {
    const attrs = [`label="${escapeDot(edge.scope)}"`];
    if (edge.kind === 'removed') attrs.push('style=dashed', 'color="#ef4444"');
    else if (edge.kind === 'added') attrs.push('color="#10b981"');
    else if (edge.kind === 'changed') attrs.push('color="#eab308"');
    else attrs.push('color="#9ca3af"');
    lines.push(`  "${escapeDot(edge.fromId)}" -> "${escapeDot(edge.toId)}" [${attrs.join(' ')}];`);
  }
  lines.push('}');
  return `${lines.join('\n')}\n`;
}

function overCapNote(nodeCount, nodeCap) {
  return (
    `The module diagram has ${nodeCount} nodes, above the inline limit of ${nodeCap}, so it is not inlined here. ` +
    'The graph is in the job artifact as `module-graph.dot`, and as `module-graph.svg` when Graphviz `dot` is on PATH.\n'
  );
}

/**
 * @returns {{
 *   markdown: string,
 *   mermaid: string | null,
 *   dot: string | null,
 *   overCap: boolean,
 *   nodeCount: number,
 *   touched: string[],
 *   context: string[],
 *   reactorVersion: { from: string, to: string } | null,
 * }}
 */
export function diffModules(baseModel, headModel, options = {}) {
  const nodeCap = nodeCapFrom(options);
  const baseIds = byId(baseModel);
  const headIds = byId(headModel);
  const baseRoot = rootModule(baseModel);
  const headRoot = rootModule(headModel);
  const reactorVersion = reactorVersionMove(baseRoot, headRoot);
  const added = [];
  const removed = [];
  const changed = [];
  for (const id of headIds.keys()) if (!baseIds.has(id)) added.push(id);
  for (const id of baseIds.keys()) if (!headIds.has(id)) removed.push(id);
  for (const id of baseIds.keys()) {
    if (!headIds.has(id)) continue;
    const change = describeChanged(baseIds.get(id), headIds.get(id), baseModel, headModel, baseRoot, headRoot);
    if (change) changed.push({ id, change });
  }
  added.sort(cmp);
  removed.sort(cmp);
  changed.sort((a, b) => cmp(a.id, b.id));
  const touched = [...added, ...removed, ...changed.map((row) => row.id)].sort(cmp);

  const rows = [];
  for (const id of added) rows.push({ id, change: describeAddedOrRemoved('added', headIds.get(id), headModel) });
  for (const id of removed) rows.push({ id, change: describeAddedOrRemoved('removed', baseIds.get(id), baseModel) });
  for (const row of changed) rows.push(row);
  rows.sort((a, b) => cmp(a.id, b.id));

  if (touched.length === 0) {
    return {
      markdown: '',
      mermaid: null,
      dot: null,
      overCap: false,
      nodeCount: 0,
      touched: [],
      context: [],
      reactorVersion,
    };
  }

  const neighbours = new Map();
  const link = (from, to) => {
    if (!neighbours.has(from)) neighbours.set(from, new Set());
    neighbours.get(from).add(to);
  };
  for (const edge of [...baseModel.edges, ...headModel.edges]) {
    link(edge.from, edge.to);
    link(edge.to, edge.from);
  }
  const touchedSet = new Set(touched);
  const context = [];
  for (const id of touched) {
    for (const neighbour of neighbours.get(id) ?? []) {
      if (touchedSet.has(neighbour)) continue;
      if (!baseIds.has(neighbour) && !headIds.has(neighbour)) continue;
      if (!context.includes(neighbour)) context.push(neighbour);
    }
  }
  context.sort(cmp);

  const visibleIds = [...touched, ...context].sort(cmp);
  const nodeIds = assignNodeIds(visibleIds);
  const changedIds = new Set(changed.map((row) => row.id));
  const kindOf = (id) => {
    if (added.includes(id)) return 'added';
    if (removed.includes(id)) return 'removed';
    if (changedIds.has(id)) return 'changed';
    return 'context';
  };
  const nodes = visibleIds.map((id) => ({ id, nodeId: nodeIds.get(id), kind: kindOf(id) }));
  const visible = new Set(visibleIds);
  const edges = classifyEdges(baseModel, headModel)
    .filter((edge) => visible.has(edge.from) && visible.has(edge.to))
    .filter((edge) => touchedSet.has(edge.from) || touchedSet.has(edge.to))
    .map((edge) => ({ ...edge, fromId: nodeIds.get(edge.from), toId: nodeIds.get(edge.to) }));

  const overCap = nodes.length > nodeCap;
  const table = renderTable(rows);
  const dot = overCap ? renderDot(nodes, edges) : null;
  const mermaid = overCap ? null : renderMermaid(nodes, edges);
  const parts = [];
  if (reactorVersion) parts.push(`Reactor version: ${inlineCode(reactorVersion.from)} -> ${inlineCode(reactorVersion.to)}`);
  if (overCap) parts.push(overCapNote(nodes.length, nodeCap).trimEnd());
  if (mermaid) parts.push(`\`\`\`mermaid\n${mermaid.trimEnd()}\n\`\`\``);
  parts.push(table.trimEnd());
  return {
    markdown: `${parts.join('\n\n')}\n`,
    mermaid,
    dot,
    overCap,
    nodeCount: nodes.length,
    touched,
    context,
    reactorVersion,
  };
}
