// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.SecretDataFlattener;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eagerly-loaded AWS Secrets Manager property source.
 *
 * <p>All configured secrets are fetched at construction time via the {@link SecretsGateway} and
 * loaded into an immutable in-memory map. {@link #lookup(String)} is a simple map get with no
 * further I/O.
 *
 * <h2>Entry Modes</h2>
 * <p>Each {@link SecretEntry} is processed in one of two modes:
 * <ul>
 *   <li><strong>prefix mode</strong> — the secret's {@code SecretString} is parsed as a JSON
 *       object. Its key-value pairs are flattened under the declared prefix using
 *       {@link SecretDataFlattener}. If the string is not valid JSON, startup is aborted with a
 *       {@link ConfigPropertySourceException} that names the secret ID but not its content.</li>
 *   <li><strong>key mode</strong> — the secret's {@code SecretString} is stored as-is under the
 *       declared key. The content is never inspected or parsed; it may be plain text or JSON —
 *       the operator chose key mode and is responsible for interpreting it.</li>
 * </ul>
 *
 * <h2>Redaction</h2>
 * <p>This class never logs, prints, or includes resolved values in exception messages. Only
 * source names, secret IDs (not their values), and key names appear in diagnostic output.
 *
 * @see AwsSecretsPropertySourceFactory
 */
class AwsSecretsPropertySource implements ConfigPropertySource {

    private static final Logger log = LoggerFactory.getLogger(AwsSecretsPropertySource.class);

    private final String name;
    private final Map<String, String> properties;

    // --- Construction ---

    /**
     * Constructs and eagerly loads the property source.
     *
     * <p>Called by {@link AwsSecretsPropertySourceFactory#create(String,
     * io.vertx.core.json.JsonObject)} after successful schema validation. The gateway is called
     * once per declared secret; results are merged into an immutable map. The gateway is closed
     * immediately after all secrets are fetched — no further SDK calls occur.
     *
     * @param name    the source instance name (from the configuration entry)
     * @param gateway the secrets gateway to use for fetching; closed after eager load
     * @param secrets the ordered list of secret entries to fetch
     * @throws ConfigPropertySourceException if any gateway call fails or if a prefix-mode secret's
     *                                        {@code SecretString} is not valid JSON
     */
    AwsSecretsPropertySource(String name, SecretsGateway gateway, List<SecretEntry> secrets) {
        this.name = name;
        Map<String, String> loaded;
        try {
            loaded = loadAllSecrets(name, gateway, secrets);
        } finally {
            // Close the gateway (SDK client) immediately after eager fetch — no further I/O needed.
            if (gateway instanceof AutoCloseable ac) {
                try {
                    ac.close();
                } catch (Exception ignored) {
                    // close must not throw; swallowed per contract
                }
            }
        }
        this.properties = Collections.unmodifiableMap(loaded);
        log.info(
                "AWS Secrets source '{}': {} keys loaded from {} secret(s)",
                name,
                this.properties.size(),
                secrets.size());
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
     * <p>This implementation returns from the in-memory map loaded at construction time. No
     * further I/O is performed.
     */
    @Override
    public Optional<String> lookup(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The SDK client is closed during construction after the eager fetch. This {@code close()}
     * is a deliberate no-op — there are no resources to release at this point.
     */
    @Override
    public void close() {
        // no-op: SDK client is closed eagerly after the initial fetch; nothing to release here
    }

    // --- Internal helpers ---

    /**
     * Fetches all declared secrets via the gateway and merges them into a single mutable map.
     *
     * <p>Later secret entries win on key collision (same key produced by two different secrets).
     * Any gateway exception propagates as-is (it is already a
     * {@link ConfigPropertySourceException}). JSON parse failures for prefix-mode secrets are
     * wrapped with the secret ID but never the secret content.
     *
     * @param sourceName the source instance name for error messages
     * @param gateway    the gateway to fetch from
     * @param secrets    ordered list of secret entries
     * @return mutable merged map
     * @throws ConfigPropertySourceException if any fetch fails or prefix-mode JSON parse fails
     */
    private static Map<String, String> loadAllSecrets(
            String sourceName, SecretsGateway gateway, List<SecretEntry> secrets) {
        Map<String, String> merged = new HashMap<>();
        for (SecretEntry entry : secrets) {
            String secretString = gateway.fetchSecretString(entry.secretId());
            if (entry.isPrefixMode()) {
                merged.putAll(parseJsonBlob(sourceName, entry.secretId(), secretString, entry.prefix()));
            } else {
                merged.put(entry.key(), secretString);
            }
        }
        return merged;
    }

    /**
     * Parses the given string as a JSON object and flattens it under the declared prefix.
     *
     * <p>Uses Vert.x {@link JsonObject} for parsing. If the string is not a valid JSON object,
     * throws a {@link ConfigPropertySourceException} that names the secret ID but never the
     * content.
     *
     * @param sourceName   the source name for error messages
     * @param secretId     the secret ID for error messages (not the value)
     * @param secretString the raw {@code SecretString} to parse
     * @param prefix       the prefix to apply to all flattened keys
     * @return flattened, prefixed key-value map
     * @throws ConfigPropertySourceException if the string is not a valid JSON object
     */
    private static Map<String, String> parseJsonBlob(
            String sourceName, String secretId, String secretString, String prefix) {
        try {
            JsonObject json = new JsonObject(secretString);
            return SecretDataFlattener.flatten(json.getMap(), prefix);
        } catch (DecodeException | ClassCastException e) {
            // Deliberately NO cause: Jackson decode failures embed fragments of the parsed input
            // in their messages (verbatim for string scalars), and the SecretString is secret
            // material (NFR-CONF-002). The exception class name is the only safe diagnostic kept.
            throw new ConfigPropertySourceException(
                    sourceName,
                    "secret '"
                            + secretId
                            + "' is configured in prefix mode but its SecretString is not a valid JSON object ("
                            + e.getClass().getSimpleName()
                            + ")");
        }
    }
}
