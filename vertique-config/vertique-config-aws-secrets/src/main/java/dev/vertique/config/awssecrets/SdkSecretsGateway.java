// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

import dev.vertique.config.source.ConfigPropertySourceException;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

/**
 * Production {@link SecretsGateway} backed by the AWS SDK v2 {@link SecretsManagerClient}.
 *
 * <p>The client is built once from the supplied {@link AwsConnectionSettings}, used to fetch
 * all declared secrets, then closed by the caller ({@link AwsSecretsPropertySource}) via
 * {@link #close()} after the eager load phase completes. The same instance must not be reused
 * after {@link #close()}.
 *
 * <h2>HTTP Client</h2>
 * <p>Uses the AWS SDK {@code url-connection-client} (no Apache HTTP dependency). Connect and
 * socket timeouts are applied from {@link AwsConnectionSettings#connectTimeoutMs()} and
 * {@link AwsConnectionSettings#readTimeoutMs()}.
 *
 * <h2>Credentials</h2>
 * <p>Credentials are resolved via the AWS SDK default credential provider chain — env vars,
 * system properties, instance profile, etc. No explicit provider is constructed; the SDK
 * resolves and manages the chain internally. Credentials are never configured inline.
 *
 * <h2>Fail-Closed</h2>
 * <p>Binary secrets, not-found, access-denied, and all SDK exceptions result in a
 * {@link ConfigPropertySourceException} that names the secret ID but never the secret value.
 *
 * <p>This class is package-private.
 */
class SdkSecretsGateway implements SecretsGateway, AutoCloseable {

    /** Source name carried for error message context — not a secret. */
    private final String sourceName;

    private final SecretsManagerClient client;

    // --- Construction ---

    /**
     * Constructs a gateway and builds the AWS SDK client from connection settings.
     *
     * @param sourceName the source instance name; used in error messages only
     * @param settings   the connection and timeout parameters
     */
    SdkSecretsGateway(String sourceName, AwsConnectionSettings settings) {
        this.sourceName = sourceName;
        this.client = buildClient(settings);
    }

    // --- SecretsGateway ---

    /**
     * {@inheritDoc}
     *
     * <p>Issues a {@code GetSecretValue} call. If the response contains {@code SecretBinary}
     * instead of {@code SecretString}, throws because binary secrets are not supported by
     * this source. Any SDK exception is also wrapped.
     *
     * @throws ConfigPropertySourceException if the secret is binary, not found, access is denied,
     *                                        or a network/SDK error occurs
     */
    @Override
    public String fetchSecretString(String secretId) {
        try {
            GetSecretValueResponse response = client.getSecretValue(
                    GetSecretValueRequest.builder().secretId(secretId).build());
            if (response.secretString() == null) {
                // SecretBinary is set — binary secrets are not supported
                throw new ConfigPropertySourceException(
                        sourceName,
                        "secret '" + secretId + "' is a binary secret; only SecretString secrets are supported");
            }
            return response.secretString();
        } catch (ConfigPropertySourceException e) {
            throw e;
        } catch (SecretsManagerException e) {
            throw new ConfigPropertySourceException(
                    sourceName,
                    "failed to fetch secret '" + secretId + "': "
                            + e.awsErrorDetails().errorMessage(),
                    e);
        } catch (Exception e) {
            throw new ConfigPropertySourceException(
                    sourceName, "failed to fetch secret '" + secretId + "': " + e.getMessage(), e);
        }
    }

    // --- AutoCloseable ---

    /**
     * Closes the underlying AWS SDK client, releasing connection pool resources.
     *
     * <p>Called by {@link AwsSecretsPropertySource} after the eager load phase completes.
     * Must not throw.
     */
    @Override
    public void close() {
        client.close();
    }

    // --- Internal helpers ---

    /**
     * Builds a configured {@link SecretsManagerClient} from connection settings.
     *
     * @param settings the connection and timeout parameters
     * @return the built client
     */
    private static SecretsManagerClient buildClient(AwsConnectionSettings settings) {
        UrlConnectionHttpClient.Builder httpClientBuilder = UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofMillis(settings.connectTimeoutMs()))
                .socketTimeout(Duration.ofMillis(settings.readTimeoutMs()));

        SecretsManagerClientBuilder builder = SecretsManagerClient.builder().httpClientBuilder(httpClientBuilder);

        if (settings.region() != null) {
            builder.region(Region.of(settings.region()));
        }

        if (settings.endpointOverride() != null) {
            builder.endpointOverride(URI.create(settings.endpointOverride()));
        }

        return builder.build();
    }
}
