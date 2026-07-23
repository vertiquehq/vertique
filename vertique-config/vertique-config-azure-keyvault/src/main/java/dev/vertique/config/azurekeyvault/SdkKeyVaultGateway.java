// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import dev.vertique.config.source.ConfigPropertySourceException;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link KeyVaultGateway} backed by the Azure SDK {@link SecretClient}.
 *
 * <p>The client is built once from the supplied {@link AzureConnectionSettings} at construction
 * time. The same client instance is reused for all subsequent lookups. The Azure SDK's
 * {@link SecretClient} has no {@code close()} — no resources are held beyond what the
 * SDK's connection pool manages, so {@link AzureKeyVaultPropertySource#close()} is a no-op.
 *
 * <h2>HTTP Client and Timeouts</h2>
 * <p>Timeouts are applied via {@link HttpClientOptions} passed to
 * {@link HttpClient#createDefault(HttpClientOptions)}. This is the only effective path for
 * configuring the underlying Netty HTTP client: {@code connectTimeout} and {@code readTimeout}
 * bound the TCP-connect and per-read-chunk phases respectively. In addition,
 * {@code responseTimeout} is set to {@code readTimeoutMs} to bound the total time-to-first-byte
 * from the server (the Azure SDK default is 60 seconds, which would stall bootstrap on auth
 * misconfiguration). Using {@link SecretClientBuilder#clientOptions} in addition to the explicit
 * {@code httpClient} call is redundant — the explicit HTTP client wins; only the explicit path
 * is applied here.
 *
 * <h2>Credentials</h2>
 * <p>The credential is built via {@link AzureCredentials#build(String, String)}. Credential
 * construction does not make network calls; auth errors surface at the first vault lookup.
 *
 * <h2>Fail-Closed Contract</h2>
 * <p>{@link ResourceNotFoundException} (HTTP 404) → {@link Optional#empty()}.
 * Any other {@link HttpResponseException} or runtime exception →
 * {@link ConfigPropertySourceException} naming the source and original placeholder key. The
 * SDK cause is intentionally NOT attached to sever any potential secret content in the
 * SDK exception chain (NFR-CONF-002). Azure SDK error messages for {@code getSecret} are
 * HTTP-status + request-ID shaped (generally safe), but the discipline is maintained.
 *
 * <h2>Null-Response Guard</h2>
 * <p>Azure SDK credential exceptions (e.g. {@code CredentialUnavailableException},
 * {@code ClientAuthenticationException}) extend {@link HttpResponseException} but are constructed
 * with a {@code null} {@link com.azure.core.http.HttpResponse}. Without the null guard,
 * {@code e.getResponse().getStatusCode()} would itself throw a {@link NullPointerException}
 * inside the catch block on auth misconfiguration. The catch block guards against this explicitly:
 * when the response is {@code null}, the class simple name of the exception is used as the detail.
 *
 * <p>This class is package-private.
 */
class SdkKeyVaultGateway implements KeyVaultGateway {

    private static final Logger log = LoggerFactory.getLogger(SdkKeyVaultGateway.class);

    /** Source name carried for error message context — not a secret. */
    private final String sourceName;

    private final SecretClient secretClient;

    // --- Construction ---

    /**
     * Constructs a gateway and builds the Azure SDK client from connection settings.
     *
     * <p>The {@link SecretClient} is built eagerly so that endpoint or credential-builder
     * errors that the SDK validates at build time (e.g. a malformed endpoint URL) surface
     * as create-time failures rather than per-lookup failures. Actual credential validation
     * does not occur until the first network call.
     *
     * @param sourceName the source instance name; used in error messages only
     * @param settings   the connection, auth, and timeout parameters
     */
    SdkKeyVaultGateway(String sourceName, AzureConnectionSettings settings) {
        this.sourceName = sourceName;
        this.secretClient = buildClient(settings);
    }

    /**
     * Test-seam constructor that accepts a pre-built {@link SecretClient}.
     *
     * <p>This constructor is intentionally package-private so that integration tests in the same
     * package can inject a {@link SecretClient} configured for a test double (e.g. Lowkey Vault)
     * without touching the production constructor or leaking test configuration into production
     * code. The production gateway is always constructed via
     * {@link #SdkKeyVaultGateway(String, AzureConnectionSettings)}.
     *
     * @param sourceName   the source instance name; used in error messages only
     * @param secretClient the pre-built Azure SDK secret client to use for all lookups
     */
    SdkKeyVaultGateway(String sourceName, SecretClient secretClient) {
        this.sourceName = sourceName;
        this.secretClient = secretClient;
    }

    // --- KeyVaultGateway ---

    /**
     * {@inheritDoc}
     *
     * <p>Issues a {@code getSecret(normalizedName)} call. A {@link ResourceNotFoundException}
     * (HTTP 404) is mapped to {@link Optional#empty()}. Any other
     * {@link HttpResponseException} or runtime exception results in a
     * {@link ConfigPropertySourceException} that names the source and original placeholder
     * key, but NEVER the secret value. The SDK exception cause is intentionally NOT attached —
     * the cause chain is severed as a belt-and-suspenders measure per NFR-CONF-002.
     *
     * <p>The {@link HttpResponseException} catch block guards against a null
     * {@link com.azure.core.http.HttpResponse}: Azure SDK credential exceptions
     * ({@code CredentialUnavailableException}, {@code ClientAuthenticationException}) extend
     * {@link HttpResponseException} but are constructed with a {@code null} response. In that
     * case the class simple name of the exception is used as the error detail instead of the
     * HTTP status code.
     *
     * @param normalizedName the pre-validated Azure Key Vault secret name
     * @param originalKey    the original placeholder key; used only in error messages
     * @return {@link Optional#empty()} if the secret does not exist; otherwise the secret value
     * @throws ConfigPropertySourceException if the lookup fails for any reason other than 404
     */
    @Override
    public Optional<String> getSecret(String normalizedName, String originalKey) {
        try {
            String value = secretClient.getSecret(normalizedName).getValue();
            return Optional.of(value);
        } catch (ResourceNotFoundException e) {
            // HTTP 404 — secret does not exist in the vault; not an error, propagate as empty
            log.debug(
                    "Azure Key Vault source '{}': secret '{}' not found (404) for key '{}'",
                    sourceName,
                    normalizedName,
                    originalKey);
            return Optional.empty();
        } catch (HttpResponseException e) {
            throw mapHttpError(sourceName, originalKey, normalizedName, e);
        } catch (Exception e) {
            // Network, auth, or other runtime failure — fail-closed, sever cause chain.
            throw new ConfigPropertySourceException(
                    sourceName,
                    originalKey,
                    "Azure Key Vault lookup failed for secret '"
                            + normalizedName
                            + "': "
                            + e.getClass().getSimpleName());
        }
    }

    // --- Internal helpers ---

    /**
     * Maps an {@link HttpResponseException} to a {@link ConfigPropertySourceException}.
     *
     * <p>This method is package-private to allow direct unit testing of the null-response guard
     * without requiring a real or mocked {@link SecretClient}.
     *
     * <p>Azure SDK credential exceptions (e.g. {@code CredentialUnavailableException},
     * {@code ClientAuthenticationException}) extend {@link HttpResponseException} but are
     * constructed with a {@code null} {@link com.azure.core.http.HttpResponse}. When that happens,
     * this method falls back to the exception's class simple name as the detail, preventing an
     * NPE inside the catch block.
     *
     * @param sourceName     the source instance name for error messages
     * @param originalKey    the original placeholder key for error messages
     * @param normalizedName the pre-validated Azure Key Vault secret name for error messages
     * @param e              the non-404 HTTP response exception
     * @return a {@link ConfigPropertySourceException} suitable for propagation
     */
    static ConfigPropertySourceException mapHttpError(
            String sourceName, String originalKey, String normalizedName, HttpResponseException e) {
        // Guard against null response: Azure SDK credential exceptions extend
        // HttpResponseException but are constructed with a null HttpResponse. Without this
        // guard, getResponse().getStatusCode() would itself NPE (NFR-CONF-002).
        String detail = e.getResponse() != null
                ? "HTTP " + e.getResponse().getStatusCode()
                : e.getClass().getSimpleName();
        return new ConfigPropertySourceException(
                sourceName,
                originalKey,
                "Azure Key Vault lookup failed for secret '" + normalizedName + "': " + detail);
    }

    /**
     * Builds a configured {@link SecretClient} from connection settings.
     *
     * <p>Timeouts are applied via {@link HttpClientOptions} passed to
     * {@link HttpClient#createDefault(HttpClientOptions)} — the only effective path for the
     * underlying Netty client. {@code connectTimeout} and {@code readTimeout} bound TCP-connect
     * and per-chunk read phases; {@code responseTimeout} is set to {@code readTimeoutMs} to
     * bound time-to-first-byte (otherwise the Azure SDK default of 60 s would stall bootstrap).
     * The redundant {@link SecretClientBuilder#clientOptions(com.azure.core.util.ClientOptions)}
     * call is intentionally omitted: when an explicit {@code httpClient} is supplied, the builder's
     * {@code clientOptions} setting has no effect on the HTTP client configuration.
     *
     * @param settings the connection, auth, and timeout parameters
     * @return the built {@link SecretClient}
     */
    private static SecretClient buildClient(AzureConnectionSettings settings) {
        HttpClientOptions httpOptions = new HttpClientOptions()
                .setConnectTimeout(Duration.ofMillis(settings.connectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(settings.readTimeoutMs()))
                .setResponseTimeout(Duration.ofMillis(settings.readTimeoutMs()));

        // Pass the CANONICAL endpoint (scheme://host[:port]) so that the raw and effective
        // endpoint can never diverge — path rejection at create time ensures the canonical
        // form is always a valid vault URL.
        return new SecretClientBuilder()
                .vaultUrl(settings.canonicalEndpoint())
                .credential(AzureCredentials.build(settings.authMethod(), settings.clientId()))
                .httpClient(HttpClient.createDefault(httpOptions))
                .buildClient();
    }
}
