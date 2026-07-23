// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.awssecrets;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.ConfigPropertySourceFactory;
import dev.vertique.config.source.SourceConfigValues;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * {@link ConfigPropertySourceFactory} for AWS Secrets Manager.
 *
 * <p>This factory is discovered via {@link java.util.ServiceLoader} (type key:
 * {@code "aws-secrets"}) and creates eagerly-loaded {@link AwsSecretsPropertySource} instances
 * from the per-source configuration block.
 *
 * <h2>Configuration Schema</h2>
 * <p>The {@code sourceConfig} JsonObject (the full entry from
 * {@code config.propertySources[*]}) supports the following fields:
 *
 * <pre>{@code
 * {
 *   "type": "aws-secrets",
 *   "region": "us-east-1",              // optional; uses SDK default region chain if absent
 *   "endpointOverride": "http://...",   // optional; for LocalStack / test endpoints
 *   "connectTimeoutMs": 5000,           // optional; default 5000 ms
 *   "readTimeoutMs": 5000,              // optional; default 5000 ms
 *   "secrets": [                        // required; non-empty
 *     { "secretId": "my/db-creds", "prefix": "db." },      // JSON-blob mode
 *     { "secretId": "my/api-token", "key": "api.token" }   // plain-string mode
 *   ]
 * }
 * }</pre>
 *
 * <h2>Secret Entry Modes</h2>
 * <p>Each entry in {@code secrets} must specify exactly one of {@code prefix} or {@code key}:
 * <ul>
 *   <li><strong>{@code prefix} mode</strong> — the secret's {@code SecretString} must be a JSON
 *       object. Its keys are flattened under {@code prefix} using
 *       {@link dev.vertique.config.source.SecretDataFlattener}.</li>
 *   <li><strong>{@code key} mode</strong> — the secret's {@code SecretString} is stored
 *       as-is under exactly {@code key}, regardless of its content.</li>
 * </ul>
 *
 * <h2>Timeouts</h2>
 * <p>{@code connectTimeoutMs} and {@code readTimeoutMs} are in milliseconds (matching the
 * framework naming convention) and are applied directly to the
 * {@link software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient} via
 * {@link java.time.Duration#ofMillis(long)}.
 *
 * <h2>Credentials</h2>
 * <p>Credentials are resolved via the AWS SDK default credential provider chain. They are
 * never configured inline and never appear in error messages.
 *
 * <h2>Redaction</h2>
 * <p>Error messages include field names and structural descriptions. They MUST NOT include any
 * resolved secret value or credentials. Secret IDs are structural identifiers and safe to echo.
 *
 * @see AwsSecretsPropertySource
 */
public class AwsSecretsPropertySourceFactory implements ConfigPropertySourceFactory {

    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private final BiFunction<String, AwsConnectionSettings, SecretsGateway> gatewayFactory;

    // --- Constructors ---

    /**
     * Public no-arg constructor used in production (registered via ServiceLoader).
     *
     * <p>Uses {@link SdkSecretsGateway} as the gateway implementation.
     */
    public AwsSecretsPropertySourceFactory() {
        this(SdkSecretsGateway::new);
    }

    /**
     * Package-private constructor for unit tests.
     *
     * <p>Accepts a custom gateway factory so tests can inject a stub without a real AWS
     * endpoint. The factory receives the source instance name and the fully-parsed connection
     * settings; the name is carried into the gateway for accurate error reporting.
     *
     * @param gatewayFactory function that produces a {@link SecretsGateway} from source name and
     *                       connection settings
     */
    AwsSecretsPropertySourceFactory(BiFunction<String, AwsConnectionSettings, SecretsGateway> gatewayFactory) {
        this.gatewayFactory = gatewayFactory;
    }

    // --- ConfigPropertySourceFactory ---

    /** {@inheritDoc} */
    @Override
    public String type() {
        return "aws-secrets";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validates the configuration schema, builds an {@link AwsConnectionSettings}, creates the
     * gateway via the gateway factory, and constructs an {@link AwsSecretsPropertySource} that
     * eagerly fetches all declared secrets. The SDK client is closed by the source constructor
     * immediately after the eager fetch.
     *
     * @throws ConfigPropertySourceException if schema validation fails or if any secret fetch
     *                                        fails during eager load
     */
    @Override
    public ConfigPropertySource create(String name, JsonObject sourceConfig) {
        AwsSourceSettings settings = parseSettings(name, sourceConfig);
        SecretsGateway gateway = gatewayFactory.apply(name, settings.connection());
        return new AwsSecretsPropertySource(name, gateway, settings.secrets());
    }

    // --- Schema parsing and validation ---

    /**
     * Parses and validates the {@code sourceConfig} JSON object into an {@link AwsSourceSettings}.
     *
     * @param name         the source instance name for error messages
     * @param sourceConfig the per-source configuration object
     * @return validated source settings including connection and secret list
     * @throws ConfigPropertySourceException if any required field is missing or invalid
     */
    private static AwsSourceSettings parseSettings(String name, JsonObject sourceConfig) {
        // --- region (optional) ---
        String region = sourceConfig.getString("region");

        // --- endpointOverride (optional) ---
        String endpointOverride = sourceConfig.getString("endpointOverride");

        // --- timeouts ---
        int connectTimeoutMs =
                SourceConfigValues.positiveInt(name, sourceConfig, "connectTimeoutMs", DEFAULT_TIMEOUT_MS);
        int readTimeoutMs = SourceConfigValues.positiveInt(name, sourceConfig, "readTimeoutMs", DEFAULT_TIMEOUT_MS);

        // --- secrets (required, non-empty) ---
        JsonArray secretsArray = sourceConfig.getJsonArray("secrets");
        if (secretsArray == null || secretsArray.isEmpty()) {
            throw new ConfigPropertySourceException(name, "required field 'secrets' is missing or empty");
        }
        List<SecretEntry> secrets = parseSecrets(name, secretsArray);

        AwsConnectionSettings connection =
                new AwsConnectionSettings(region, endpointOverride, connectTimeoutMs, readTimeoutMs);
        return new AwsSourceSettings(connection, secrets);
    }

    /**
     * Parses the {@code secrets} JSON array into an ordered list of {@link SecretEntry} records.
     *
     * <p>Validates that each entry has a non-blank {@code secretId}, and exactly one of
     * {@code prefix} or {@code key} (not both, not neither).
     *
     * @param name         the source instance name for error messages
     * @param secretsArray the secrets JSON array
     * @return ordered list of secret entries
     * @throws ConfigPropertySourceException if any entry is malformed
     */
    private static List<SecretEntry> parseSecrets(String name, JsonArray secretsArray) {
        List<SecretEntry> result = new ArrayList<>(secretsArray.size());
        for (int i = 0; i < secretsArray.size(); i++) {
            JsonObject entry = secretsArray.getJsonObject(i);

            // --- secretId (required, non-blank) ---
            String secretId = entry.getString("secretId");
            if (secretId == null || secretId.isBlank()) {
                throw new ConfigPropertySourceException(
                        name, "secrets entry at index " + i + " has missing or blank 'secretId' field");
            }

            // --- prefix / key (exactly one required) ---
            String prefix = entry.getString("prefix");
            String key = entry.getString("key");

            boolean hasPrefix = prefix != null;
            boolean hasKey = key != null;

            if (hasPrefix && hasKey) {
                throw new ConfigPropertySourceException(
                        name,
                        "secrets entry at index "
                                + i
                                + " (secretId='"
                                + secretId
                                + "') must have exactly one of 'prefix' or 'key', not both");
            }
            if (!hasPrefix && !hasKey) {
                throw new ConfigPropertySourceException(
                        name,
                        "secrets entry at index "
                                + i
                                + " (secretId='"
                                + secretId
                                + "') must have exactly one of 'prefix' or 'key'");
            }

            // --- blank key validation (key mode only) ---
            if (hasKey && key.isBlank()) {
                throw new ConfigPropertySourceException(
                        name,
                        "secrets entry at index "
                                + i
                                + " (secretId='"
                                + secretId
                                + "') has a blank 'key' field; 'key' must be non-blank");
            }

            result.add(new SecretEntry(secretId, prefix, key));
        }
        return List.copyOf(result);
    }
}
