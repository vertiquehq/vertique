// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.azurekeyvault;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-demand Azure Key Vault property source.
 *
 * <p>Each call to {@link #lookup(String)} performs a vault lookup via the {@link KeyVaultGateway}
 * after prefix filtering and key normalization. The bootstrap engine memoizes results per
 * {@code (source, key)}, so each secret is fetched at most once per bootstrap run.
 *
 * <h2>Lookup Pipeline</h2>
 * <ol>
 *   <li><strong>Prefix filter</strong> — if a prefix is configured and the key does not start
 *       with it, returns {@link Optional#empty()} immediately without calling the SDK.</li>
 *   <li><strong>Prefix strip</strong> — the prefix is removed from the key.</li>
 *   <li><strong>Normalization</strong> — {@code '.'} characters are replaced with {@code '-'}.</li>
 *   <li><strong>Name validation</strong> — if the normalized name does not match
 *       {@code [0-9a-zA-Z-]{1,127}}, returns {@link Optional#empty()} without calling the SDK.
 *       Azure Key Vault does not support names outside this character set.</li>
 *   <li><strong>Gateway call</strong> — {@link KeyVaultGateway#getSecret(String, String)} is
 *       invoked with the normalized name and the original placeholder key (for error context).</li>
 * </ol>
 *
 * <h2>Redaction</h2>
 * <p>This class never logs, prints, or includes resolved values in exception messages. Only
 * source names and key names (not key values) appear in diagnostic output.
 *
 * @see AzureKeyVaultPropertySourceFactory
 */
class AzureKeyVaultPropertySource implements ConfigPropertySource {

    private static final Logger log = LoggerFactory.getLogger(AzureKeyVaultPropertySource.class);

    /**
     * Pre-compiled pattern for valid Azure Key Vault secret names.
     *
     * <p>Azure Key Vault secret names are restricted to alphanumeric characters and hyphens,
     * with a maximum length of 127. The hyphen is placed at the end of the character class
     * to avoid any ambiguity with range syntax.
     */
    private static final Pattern VALID_SECRET_NAME = Pattern.compile("[0-9a-zA-Z-]{1,127}");

    private final String name;
    private final String prefix;
    private final KeyVaultGateway gateway;

    // --- Construction ---

    /**
     * Constructs an on-demand Azure Key Vault property source.
     *
     * <p>The gateway is created once via the factory and reused for all lookups. The gateway
     * factory follows the same BiFunction seam as the sibling AWS Secrets module:
     * {@code (sourceName, settings) → gateway}.
     *
     * @param name           the source instance name (from the configuration entry)
     * @param settings       the parsed connection, auth, and timeout settings
     * @param gatewayFactory a factory that produces a {@link KeyVaultGateway} given the source
     *                       name and connection settings; in production this creates an
     *                       {@link SdkKeyVaultGateway}
     */
    AzureKeyVaultPropertySource(
            String name,
            AzureConnectionSettings settings,
            BiFunction<String, AzureConnectionSettings, KeyVaultGateway> gatewayFactory) {
        this.name = name;
        this.prefix = settings.prefix();
        this.gateway = gatewayFactory.apply(name, settings);
        log.info("Azure Key Vault source '{}': initialised, endpoint={}", name, settings.canonicalEndpoint());
    }

    // --- ConfigPropertySource ---

    /** {@inheritDoc} */
    @Override
    public String name() {
        return name;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applies the lookup pipeline: prefix filter → strip → normalize → validate → gateway.
     * Each step that cannot produce a valid vault secret name returns {@link Optional#empty()}
     * immediately without calling the SDK. Auth or network failures are fail-closed:
     * {@link ConfigPropertySourceException} is thrown.
     *
     * <p>The bootstrap engine memoizes per {@code (source, key)}, so this method is called at
     * most once per distinct key.
     *
     * @param key the placeholder key to resolve; never {@code null}
     * @return an {@link Optional} containing the resolved value, or {@link Optional#empty()} if
     *         the key is not found in the vault (or filtered out before the SDK call)
     * @throws ConfigPropertySourceException if the vault lookup fails (auth, network, non-404
     *                                        HTTP error)
     */
    @Override
    public Optional<String> lookup(String key) {
        // --- Step 1: Prefix filter ---
        if (prefix != null && !key.startsWith(prefix)) {
            return Optional.empty();
        }

        // --- Step 2: Prefix strip ---
        String stripped = (prefix != null) ? key.substring(prefix.length()) : key;

        // --- Step 3: Normalize '.' → '-' ---
        String normalized = stripped.replace('.', '-');

        // --- Step 4: Validate secret name ---
        if (!VALID_SECRET_NAME.matcher(normalized).matches()) {
            log.debug(
                    "Azure Key Vault source '{}': key '{}' normalized to '{}' which is not a valid "
                            + "Azure Key Vault secret name — skipping",
                    name,
                    key,
                    normalized);
            return Optional.empty();
        }

        // --- Step 5: Gateway call ---
        return gateway.getSecret(normalized, key);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The Azure SDK {@link com.azure.security.keyvault.secrets.SecretClient} has no
     * {@code close()} — all HTTP connections are managed by the underlying HTTP client's
     * connection pool, which is a JVM-managed resource. This method is intentionally a no-op.
     */
    @Override
    public void close() {
        // no-op: SecretClient has no close(); connections are GC-managed
    }
}
