// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Property-source SPI for the vertique-config module.
 *
 * <h2>Overview</h2>
 * <p>This package defines the extension points that allow the vertique-config bootstrap engine
 * to resolve placeholder values (e.g. {@code ${db.password}}) from external property sources
 * such as HashiCorp Vault ({@code type: "vault"}), AWS Secrets Manager
 * ({@code type: "aws-secrets"}), Azure Key Vault ({@code type: "azure-keyvault"}), or any
 * custom backend. The engine resolves each {@code ${key}} by walking the declared
 * {@code config.propertySources} chain in order and stopping at the first source that
 * returns a non-empty value. A source can use any key naming convention it supports
 * (e.g. {@code ${db.password}} resolved by a source that maps it to a Vault path).
 *
 * <h2>Progressive Lookup Chain</h2>
 * <p>Sources are tried in declaration order (as listed under {@code config.propertySources}
 * in the application configuration). For each placeholder key, the engine walks the chain and
 * stops at the first source that returns a non-empty {@link java.util.Optional}. If all sources
 * return empty the placeholder is left unresolved and startup is aborted.
 *
 * <h2>Not-Found vs Error Contract</h2>
 * <ul>
 *   <li>Return {@link java.util.Optional#empty()} — key not found; resolution chain continues.</li>
 *   <li>Throw {@link dev.vertique.config.source.ConfigPropertySourceException} — unrecoverable
 *       error; startup is aborted immediately. An error <em>must never</em> silently degrade into a
 *       not-found result.</li>
 * </ul>
 *
 * <h2>ServiceLoader-Discovered Factories</h2>
 * <p>{@link dev.vertique.config.source.ConfigPropertySourceFactory} implementations are
 * discovered via {@link java.util.ServiceLoader} using the factory's fully-qualified interface
 * name. The engine matches each configured source's {@code type} field against
 * {@link dev.vertique.config.source.ConfigPropertySourceFactory#type()} to select the right
 * factory.
 *
 * <h2>Value Redaction Rule</h2>
 * <p>Resolved values are secrets. No implementation in this package or its consumers may include
 * a resolved value in a log message, exception message, diagnostic string, or any other
 * observable output. Exception messages MUST contain only the source name, the key being
 * looked up, and a non-secret detail string.
 */
package dev.vertique.config.source;
