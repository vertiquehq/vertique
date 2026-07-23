// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

import dev.vertique.config.source.ConfigPropertySourceException;

/**
 * Internal seam between the AWS Secrets property source and the AWS SDK.
 *
 * <p>All SDK calls are isolated behind this interface so that unit tests can inject a stub
 * without a live AWS endpoint. The single method fetches and returns the {@code SecretString}
 * for a given secret ID.
 *
 * <p>This interface is package-private: it is an internal detail of the
 * {@code vertique-config-aws-secrets} module and must not be exposed to callers.
 *
 * <h2>Fail-Closed Contract</h2>
 * <p>Implementations are fail-closed: any condition that prevents a clean string value from being
 * returned MUST throw {@link ConfigPropertySourceException}. This includes:
 * <ul>
 *   <li>Binary secrets ({@code SecretBinary} set, {@code SecretString} null)</li>
 *   <li>Secret not found (ResourceNotFoundException)</li>
 *   <li>Access denied (AccessDeniedException)</li>
 *   <li>Network / SDK errors</li>
 * </ul>
 * <p>Exception messages MUST name the secret ID but MUST NOT include the secret value or any
 * credentials.
 */
interface SecretsGateway {

    /**
     * Fetches the {@code SecretString} for the given secret ID.
     *
     * <p>The returned string is the raw {@code SecretString} exactly as stored in AWS Secrets
     * Manager — it may be a plain string or a JSON object string. The caller decides how to
     * interpret it based on the entry's mode ({@link SecretEntry#isPrefixMode()}).
     *
     * @param secretId the secret ID or ARN to fetch; never {@code null} or blank
     * @return the {@code SecretString} value; never {@code null}
     * @throws ConfigPropertySourceException if the secret cannot be fetched for any reason
     *                                        (binary secret, not found, access denied, network
     *                                        error); message names the secret ID but never its value
     */
    String fetchSecretString(String secretId);
}
