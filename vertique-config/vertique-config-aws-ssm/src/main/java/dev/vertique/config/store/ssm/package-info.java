// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * AWS SSM Parameter Store config store for the Vert.x {@link io.vertx.config.ConfigRetriever}.
 *
 * <h2>Overview</h2>
 * <p>This package provides a {@link io.vertx.config.spi.ConfigStoreFactory} implementation
 * (type {@code "aws-ssm"}) that fetches a subtree of parameters from AWS SSM Parameter Store
 * and merges them into the bootstrap configuration tree as a nested {@link io.vertx.core.json.JsonObject}.
 * It integrates with the {@code config.stores} declaration mechanism defined in the
 * {@code vertique-config} module.
 *
 * <h2>Usage</h2>
 * <p>Declare the store under {@code config.stores} in application configuration:
 * <pre>{@code
 * {
 *   "config": {
 *     "stores": [
 *       {
 *         "type": "aws-ssm",
 *         "config": {
 *           "path": "/my-app/prod/",
 *           "region": "eu-west-1",
 *           "prefix": "app"
 *         }
 *       }
 *     ]
 *   }
 * }
 * }</pre>
 *
 * <h2>Parameter Mapping</h2>
 * <p>Each parameter name is mapped to a nested key by stripping the configured {@code path} prefix
 * and splitting the remainder on {@code "/"}. For example, with {@code path="/myapp/prod/"},
 * the parameter {@code /myapp/prod/db/password} maps to {@code {"db":{"password":"..."}}}.
 *
 * <h2>Parameter Types</h2>
 * <ul>
 *   <li>{@code String} and {@code SecureString} → string value.</li>
 *   <li>{@code StringList} → {@link io.vertx.core.json.JsonArray} of comma-split strings.</li>
 * </ul>
 *
 * <h2>Prefix Re-rooting</h2>
 * <p>The optional {@code prefix} config field (dot-separated) nests the entire result under
 * additional path segments. For example, {@code "prefix": "app"} nests the result under
 * {@code {"app": {...}}}.
 *
 * <h2>Empty Path Semantics</h2>
 * <p>An empty result (no parameters found under the configured path) yields a succeeded
 * {@link io.vertx.core.Future} carrying an empty {@link io.vertx.core.json.JsonObject}. This is
 * intentional: a declared-but-empty path is not an error — stores merge subtrees, and an empty
 * contribution is safe. Note that this contrasts with property sources, where a missing secret
 * is fail-closed. A store that fails to reach AWS (network error, auth failure) returns a failed
 * {@link io.vertx.core.Future} and aborts bootstrap.
 *
 * <h2>NFR-CONF-002 — Value Redaction</h2>
 * <p>Parameter values MUST NOT appear in log messages, exception messages, or any other
 * observable output. Only parameter names (which are operator-declared paths, not secrets),
 * counts, and structural diagnostics are safe to include. This module logs only counts at INFO
 * level after a successful load.
 *
 * <h2>ServiceLoader Registration</h2>
 * <p>The factory is registered in
 * {@code META-INF/services/io.vertx.config.spi.ConfigStoreFactory} so that the Vert.x
 * {@link io.vertx.config.ConfigRetriever} discovers it automatically via
 * {@link java.util.ServiceLoader}.
 */
package dev.vertique.config.store.ssm;
