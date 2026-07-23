// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

/**
 * Immutable connection and timeout settings for an AWS Secrets Manager gateway instance.
 *
 * <p>This record carries everything needed to build a configured AWS SDK client. It intentionally
 * does not carry secret declarations — those belong to {@link AwsSourceSettings} and are owned by
 * {@link AwsSecretsPropertySource}. This separation keeps the gateway focused on transport.
 *
 * <p>Credentials are resolved via the AWS SDK default credential provider chain; they are never
 * carried in this record.
 *
 * <p>This record is package-private: it is an internal transfer object between the factory and
 * the gateway, not part of the public API.
 *
 * @param region           optional AWS region string (e.g. {@code "us-east-1"}); {@code null} means
 *                         use the SDK default region provider chain
 * @param endpointOverride optional endpoint override URL string (e.g.
 *                         {@code "http://localhost:4566"} for LocalStack); {@code null} means use
 *                         the standard AWS endpoint
 * @param connectTimeoutMs connection timeout in milliseconds; must be positive; default 5000
 * @param readTimeoutMs    read/socket timeout in milliseconds; must be positive; default 5000
 */
record AwsConnectionSettings(String region, String endpointOverride, int connectTimeoutMs, int readTimeoutMs) {}
