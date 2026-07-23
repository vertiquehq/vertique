// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.store.ssm;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.SsmClientBuilder;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

/**
 * Production {@link SsmGateway} backed by the AWS SDK v2 {@link SsmClient}.
 *
 * <p>The client is built once from the supplied {@link SsmStoreSettings}, used to perform
 * paginated {@code GetParametersByPath} calls, and closed by the owning
 * {@link SsmConfigStore} via {@link #close()} when the store is closed.
 *
 * <h2>HTTP Client</h2>
 * <p>Uses the AWS SDK {@code url-connection-client} (no Apache HTTP dependency). Connect and
 * socket timeouts are applied from {@link SsmStoreSettings#connectTimeoutMs()} and
 * {@link SsmStoreSettings#readTimeoutMs()}.
 *
 * <h2>Credentials</h2>
 * <p>Credentials are resolved via the AWS SDK default credential provider chain — env vars,
 * system properties, instance profile, etc. No explicit provider is constructed.
 *
 * <h2>Pagination</h2>
 * <p>All pages are collected before returning, using the {@code nextToken} field returned by
 * each response. The caller receives the full list in one call.
 *
 * <h2>NFR-CONF-002</h2>
 * <p>Exception messages name the path (an operator-declared config key, safe to log) but
 * never include parameter values or credentials.
 *
 * <p>This class is package-private.
 */
class SdkSsmGateway implements SsmGateway, AutoCloseable {

    private final SsmClient client;

    // --- Construction ---

    /**
     * Constructs a gateway and builds the AWS SDK client from store settings.
     *
     * @param settings the store configuration including region, endpoint, and timeouts
     */
    SdkSsmGateway(SsmStoreSettings settings) {
        this.client = buildClient(settings);
    }

    /**
     * Package-private constructor for unit tests that supply an already-built {@link SsmClient}.
     *
     * <p>Allows tests to inject a mock or stub client without a real AWS endpoint.
     *
     * @param client the pre-built SSM client to use for all SDK calls
     */
    SdkSsmGateway(SsmClient client) {
        this.client = client;
    }

    // --- SsmGateway ---

    /**
     * {@inheritDoc}
     *
     * <p>Issues repeated {@code GetParametersByPath} calls, following {@code nextToken}
     * pagination until all pages are consumed.
     *
     * @throws software.amazon.awssdk.awscore.exception.AwsServiceException if the AWS service
     *         returns an error response
     * @throws software.amazon.awssdk.core.exception.SdkClientException if a network or client
     *         error occurs
     */
    @Override
    public List<Parameter> fetchAll(String path, boolean recursive, boolean withDecryption) {
        List<Parameter> result = new ArrayList<>();
        String nextToken = null;

        do {
            GetParametersByPathRequest.Builder requestBuilder = GetParametersByPathRequest.builder()
                    .path(path)
                    .recursive(recursive)
                    .withDecryption(withDecryption);
            if (nextToken != null) {
                requestBuilder.nextToken(nextToken);
            }
            GetParametersByPathResponse response = client.getParametersByPath(requestBuilder.build());
            result.addAll(response.parameters());
            nextToken = response.nextToken();
        } while (nextToken != null && !nextToken.isBlank());

        return result;
    }

    // --- AutoCloseable ---

    /**
     * Closes the underlying AWS SDK client, releasing connection pool resources.
     *
     * <p>Called by {@link SsmConfigStore#close()} when the config store is shut down.
     * Must not throw.
     */
    @Override
    public void close() {
        client.close();
    }

    // --- Internal helpers ---

    /**
     * Builds a configured {@link SsmClient} from store settings.
     *
     * @param settings the store configuration
     * @return the built client
     */
    private static SsmClient buildClient(SsmStoreSettings settings) {
        UrlConnectionHttpClient.Builder httpClientBuilder = UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofMillis(settings.connectTimeoutMs()))
                .socketTimeout(Duration.ofMillis(settings.readTimeoutMs()));

        SsmClientBuilder builder = SsmClient.builder().httpClientBuilder(httpClientBuilder);

        if (settings.region() != null) {
            builder.region(Region.of(settings.region()));
        }

        if (settings.endpointOverride() != null) {
            builder.endpointOverride(URI.create(settings.endpointOverride()));
        }

        return builder.build();
    }
}
