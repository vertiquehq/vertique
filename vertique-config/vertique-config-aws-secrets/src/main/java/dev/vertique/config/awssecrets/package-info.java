// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * AWS Secrets Manager property source for bootstrap placeholder resolution.
 *
 * <h2>Overview</h2>
 * <p>This package provides a {@link dev.vertique.config.source.ConfigPropertySourceFactory}
 * implementation (type {@code "aws-secrets"}) that fetches secrets from AWS Secrets Manager
 * during application bootstrap, before Dagger wiring. It implements the
 * {@link dev.vertique.config.source.ConfigPropertySource} SPI defined in the
 * {@code vertique-config} module.
 *
 * <h2>Eager Fetch Model</h2>
 * <p>All declared secrets are fetched at factory {@code create()} time — before the source is
 * returned to the bootstrap engine. The AWS SDK client is created, each secret is fetched via
 * {@code GetSecretValue}, and the client is closed immediately after. The resulting in-memory
 * map is used for all subsequent {@link dev.vertique.config.source.ConfigPropertySource#lookup}
 * calls with no further I/O. Any SDK exception at creation time aborts startup immediately with
 * a {@link dev.vertique.config.source.ConfigPropertySourceException}.
 *
 * <h2>Entry Modes</h2>
 * <p>Each secret entry in the {@code secrets} array is one of two modes:
 * <ul>
 *   <li><strong>JSON-blob ({@code prefix} mode)</strong> — the secret's {@code SecretString} is
 *       parsed as a JSON object whose key-value pairs are flattened under the declared prefix using
 *       {@link dev.vertique.config.source.SecretDataFlattener}.</li>
 *   <li><strong>Plain-string ({@code key} mode)</strong> — the secret's {@code SecretString} is
 *       exposed as-is under exactly the declared key, regardless of its content.</li>
 * </ul>
 *
 * <p>HTTP client and credential details are documented on
 * {@link dev.vertique.config.awssecrets.SdkSecretsGateway}.
 *
 * <h2>Redaction Rule</h2>
 * <p>Resolved secret values MUST NEVER appear in log messages, exception messages, or any other
 * observable output. Only source names, secret IDs (not secret values), and structural/diagnostic
 * detail strings are safe to include. This module logs one INFO line per source after successful
 * load (key count and secret count only — no values).
 *
 * <h2>ServiceLoader Registration</h2>
 * <p>The factory is registered in
 * {@code META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory} so that the
 * bootstrap engine discovers it automatically via {@link java.util.ServiceLoader}.
 */
package dev.vertique.config.awssecrets;
