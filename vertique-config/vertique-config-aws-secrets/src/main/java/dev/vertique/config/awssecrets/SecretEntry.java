// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

/**
 * A single secret entry from the {@code secrets} array in an AWS Secrets source configuration.
 *
 * <p>Each entry declares one secret to fetch and exactly one of two exposure modes:
 * <ul>
 *   <li><strong>prefix mode</strong> — {@code prefix} is non-null, {@code key} is {@code null}.
 *       The secret's {@code SecretString} must be a JSON object; its keys are flattened under
 *       the prefix using {@link dev.vertique.config.source.SecretDataFlattener}.</li>
 *   <li><strong>key mode</strong> — {@code key} is non-null, {@code prefix} is {@code null}.
 *       The secret's {@code SecretString} is exposed as-is under exactly the declared key,
 *       regardless of whether it looks like JSON.</li>
 * </ul>
 *
 * <p>Exactly one of {@code prefix} or {@code key} must be non-null at construction time.
 * Schema validation in {@link AwsSecretsPropertySourceFactory} enforces this contract before
 * creating entries.
 *
 * <p>This record is package-private: it is an internal transfer object parsed by
 * {@link AwsSecretsPropertySourceFactory} from the source configuration JSON.
 *
 * @param secretId the AWS Secrets Manager secret ID or ARN (required, non-blank)
 * @param prefix   the prefix prepended to all flattened keys for JSON-blob secrets; {@code null}
 *                 when in key mode
 * @param key      the single key under which the plain-string secret is exposed; {@code null}
 *                 when in prefix mode
 */
record SecretEntry(String secretId, String prefix, String key) {

    /**
     * Returns {@code true} if this entry is in prefix (JSON-blob) mode.
     *
     * @return {@code true} if {@code prefix} is non-null
     */
    boolean isPrefixMode() {
        return prefix != null;
    }
}
