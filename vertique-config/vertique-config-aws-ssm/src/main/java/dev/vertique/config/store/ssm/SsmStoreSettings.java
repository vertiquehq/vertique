// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.store.ssm;

/**
 * Immutable, validated configuration for an {@link SsmConfigStore} instance.
 *
 * <p>Carries everything derived from the store {@code config} JSON object. Validation
 * (required fields, positive timeouts, path normalization) happens in
 * {@link SsmConfigStoreFactory} before this record is constructed.
 *
 * <p>This record is package-private: it is an internal transfer object between the factory
 * and the store, not part of the public API.
 *
 * @param path             the SSM path prefix, always starts with {@code "/"} and ends with
 *                         {@code "/"}; e.g. {@code "/myapp/prod/"}
 * @param region           optional AWS region string (e.g. {@code "us-east-1"}); {@code null}
 *                         means use the SDK default region provider chain
 * @param endpointOverride optional endpoint override URL string (e.g.
 *                         {@code "http://localhost:4566"} for LocalStack); {@code null} means
 *                         use the standard AWS endpoint
 * @param recursive        whether to fetch parameters recursively under {@code path};
 *                         default {@code true}
 * @param withDecryption   whether to decrypt {@code SecureString} parameters; default
 *                         {@code true}
 * @param prefix           optional dot-separated re-root prefix that nests the entire result
 *                         under additional path segments (e.g. {@code "app"} or {@code "app.db"});
 *                         {@code null} means no re-rooting
 * @param connectTimeoutMs connection timeout in milliseconds; must be positive; default 5000
 * @param readTimeoutMs    read/socket timeout in milliseconds; must be positive; default 5000
 */
record SsmStoreSettings(
        String path,
        String region,
        String endpointOverride,
        boolean recursive,
        boolean withDecryption,
        String prefix,
        int connectTimeoutMs,
        int readTimeoutMs) {}
