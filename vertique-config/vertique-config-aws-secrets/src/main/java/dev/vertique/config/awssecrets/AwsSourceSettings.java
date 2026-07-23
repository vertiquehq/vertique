// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

import java.util.List;

/**
 * Parsed and validated settings for a single AWS Secrets property source.
 *
 * <p>Bundles the gateway connection settings with the ordered list of secret entries to fetch.
 * This is the complete picture of one {@code aws-secrets} source entry after schema parsing — the
 * factory separates connection concerns (owned by {@link AwsConnectionSettings} and delegated
 * to the gateway) from secret-entry concerns (owned here and delegated to
 * {@link AwsSecretsPropertySource}).
 *
 * <p>This record is package-private: it is an internal transfer object between
 * {@link AwsSecretsPropertySourceFactory} and the construction of the gateway and source.
 *
 * @param connection the gateway connection and timeout parameters
 * @param secrets    the ordered list of secret entries to fetch (at least one)
 */
record AwsSourceSettings(AwsConnectionSettings connection, List<SecretEntry> secrets) {}
