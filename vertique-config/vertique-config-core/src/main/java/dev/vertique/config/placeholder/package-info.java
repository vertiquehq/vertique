// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Placeholder grammar parser for the vertique-config bootstrap engine.
 *
 * <h2>Grammar summary</h2>
 * <p>The grammar is Spring-faithful (no provider prefixes):
 * <ul>
 *   <li>{@code ${key}} — a placeholder reference resolved via the progressive lookup chain.</li>
 *   <li>{@code ${key:default}} — bare-colon default: the <em>first top-level</em> {@code :}
 *       (at brace depth 1, not inside a nested {@code ${}}) splits key from default. Everything
 *       after that colon is the default text, so URL-shaped defaults like
 *       {@code ${endpoint:https://collector:4317}} work as expected.</li>
 *   <li>{@code ${key:}} — empty-string default.</li>
 *   <li>No colon = no default (resolver treats the absence as fail-closed).</li>
 *   <li>Defaults may contain nested placeholders: {@code ${a:${b}}} — the parser does
 *       <em>not</em> recurse into the default text; nested resolution is the engine's job
 *       (see {@link dev.vertique.config.bootstrap.BootstrapConfigLoader} — three-pass model).</li>
 *   <li>{@code \${} — escape: scanning left-to-right, when the three chars {@code \${}
 *       appear consecutively the sequence emits the literal text {@code ${} and advances 3.
 *       Therefore {@code \\${a}} (chars: {@code \}, {@code \}, {@code $}, {@code {}}) is
 *       parsed as: {@code \} (first backslash is not an escape because the next char is
 *       {@code \}, not {@code $}) then {@code \${} (escape) followed by the content
 *       {@code a}} — yielding the literal string {@code \${a}}.  A lone {@code \} or a
 *       {@code $} not followed by {@code {} is always a literal.</li>
 * </ul>
 *
 * <h2>Three-pass model pointer</h2>
 * <p>This package implements only the grammar layer (pass 0 / tokenisation). The three-pass
 * resolution model (pass 1: resolve {@code config.propertySources} subtree against the tree;
 * pass 2: instantiate sources; pass 3: resolve whole tree against the full chain) is
 * orchestrated by {@link dev.vertique.config.bootstrap.BootstrapConfigLoader}.
 *
 * <h2>NFR-CONF-002 — value redaction</h2>
 * <p>Resolved values are secrets. No implementation in this package or its consumers may
 * include a resolved value in a log message, exception message, {@code toString()}, or any
 * other observable output. Log placeholder <em>references</em> (keys) and key counts only.
 */
package dev.vertique.config.placeholder;
