// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.store.ssm;

import io.vertx.config.spi.ConfigStore;
import io.vertx.config.spi.ConfigStoreFactory;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * {@link ConfigStoreFactory} for AWS SSM Parameter Store.
 *
 * <p>This factory is discovered via {@link java.util.ServiceLoader} (type key: {@code "aws-ssm"})
 * and creates {@link SsmConfigStore} instances from the per-store configuration block supplied
 * via the {@code config.stores} array.
 *
 * <h2>Store Configuration Schema</h2>
 * <p>The {@code configuration} JSON object (the per-entry {@code config} field from
 * {@code config.stores[*]}) supports the following fields:
 *
 * <pre>{@code
 * {
 *   "path": "/myapp/prod/",          // required — non-blank SSM path prefix
 *   "region": "eu-west-1",           // optional — SDK default chain if absent
 *   "endpointOverride": "http://...",// optional — for LocalStack / IT endpoints
 *   "recursive": true,               // optional — default true
 *   "withDecryption": true,          // optional — default true
 *   "prefix": "app",                 // optional — dot-separated re-root (e.g. "app" or "app.db")
 *   "connectTimeoutMs": 5000,        // optional — default 5000
 *   "readTimeoutMs": 5000            // optional — default 5000
 * }
 * }</pre>
 *
 * <h2>Path Normalization</h2>
 * <p>The {@code path} value is normalized: a leading {@code "/"} is added if absent,
 * and a trailing {@code "/"} is added if absent. The values {@code "/myapp/prod"},
 * {@code "/myapp/prod/"}, {@code "myapp/prod"}, and {@code "myapp/prod/"} all
 * normalize to {@code "/myapp/prod/"}.
 *
 * <h2>Validation Exceptions</h2>
 * <p>Schema validation failures ({@code path} missing or blank; {@code prefix} blank or
 * containing blank dot-segments such as {@code "a..b"}; negative/zero timeouts)
 * throw {@link IllegalArgumentException} from {@link #create(Vertx, JsonObject)}. The Vert.x
 * {@link io.vertx.config.ConfigRetriever} surfaces factory-creation failures as a load failure
 * which {@code BootstrapConfigLoader} maps to a {@code BootstrapConfigException}. Using
 * {@code IllegalArgumentException} (rather than a property-source-specific type) keeps this
 * store independent of the {@code config.propertySources} subsystem.
 *
 * <h2>Credentials</h2>
 * <p>Credentials are resolved via the AWS SDK default credential provider chain. They are
 * never configured inline and never appear in error messages.
 *
 * @see SsmConfigStore
 */
public class SsmConfigStoreFactory implements ConfigStoreFactory {

    private static final int DEFAULT_TIMEOUT_MS = 5000;

    private final GatewayFactory gatewayFactory;

    // --- Constructors ---

    /**
     * Public no-arg constructor used in production (registered via ServiceLoader).
     *
     * <p>Uses {@link SdkSsmGateway} as the gateway implementation.
     */
    public SsmConfigStoreFactory() {
        this(SdkSsmGateway::new);
    }

    /**
     * Package-private constructor for unit tests.
     *
     * <p>Accepts a custom gateway factory so tests can inject a stub without a real AWS
     * endpoint.
     *
     * @param gatewayFactory function that produces a {@link SsmGateway} from store settings
     */
    SsmConfigStoreFactory(GatewayFactory gatewayFactory) {
        this.gatewayFactory = gatewayFactory;
    }

    // --- ConfigStoreFactory ---

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "aws-ssm";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validates the configuration schema, builds {@link SsmStoreSettings}, creates the
     * {@link SsmGateway} (and its underlying AWS SDK client), and returns an
     * {@link SsmConfigStore}.
     *
     * @param vertx         the Vert.x instance; used by the store for {@code executeBlocking}
     * @param configuration the store configuration JSON object; never {@code null}
     * @return the configured config store
     * @throws IllegalArgumentException if the configuration is invalid (missing or blank
     *                                   {@code path}; non-positive timeout values)
     */
    @Override
    public ConfigStore create(Vertx vertx, JsonObject configuration) {
        SsmStoreSettings settings = parseSettings(configuration);
        SsmGateway gateway = gatewayFactory.create(settings);
        return new SsmConfigStore(vertx, settings, gateway);
    }

    // --- Schema parsing and validation ---

    /**
     * Parses and validates the store {@code configuration} JSON object into
     * {@link SsmStoreSettings}.
     *
     * @param config the store configuration object
     * @return validated and normalized store settings
     * @throws IllegalArgumentException if any required field is missing or any value is invalid
     */
    private static SsmStoreSettings parseSettings(JsonObject config) {
        // --- path (required, non-blank, and must name at least one segment after normalization) ---
        String rawPath = config.getString("path");
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("AWS SSM store: required config field 'path' is missing or blank");
        }
        String path = normalizePath(rawPath);
        // After normalization, "/" means the caller provided only slash(es) with no real segment.
        // Reading the entire parameter store (recursive from "/") is not allowed: it would ingest
        // all parameters regardless of application, environment, or account boundary.
        if ("/".equals(path)) {
            throw new IllegalArgumentException("AWS SSM store: 'path' must name at least one segment; "
                    + "reading the entire parameter store is not allowed (got: '"
                    + rawPath.trim() + "')");
        }

        // --- region (optional) ---
        String region = config.getString("region");

        // --- endpointOverride (optional) ---
        String endpointOverride = config.getString("endpointOverride");

        // --- recursive (default true) ---
        boolean recursive = config.getBoolean("recursive", true);

        // --- withDecryption (default true) ---
        boolean withDecryption = config.getBoolean("withDecryption", true);

        // --- prefix (optional, but if present must be non-blank with no blank dot-segments) ---
        String prefix = config.getString("prefix");
        if (prefix != null) {
            if (prefix.isBlank()) {
                throw new IllegalArgumentException("AWS SSM store: field 'prefix' must not be blank when set");
            }
            String[] prefixParts = prefix.split("\\.", -1);
            for (String part : prefixParts) {
                if (part.isBlank()) {
                    throw new IllegalArgumentException(
                            "AWS SSM store: field 'prefix' must not contain blank segments (e.g. 'a..b'); got: "
                                    + prefix);
                }
            }
        }

        // --- timeouts ---
        int connectTimeoutMs = parsePositiveInt(config, "connectTimeoutMs", DEFAULT_TIMEOUT_MS);
        int readTimeoutMs = parsePositiveInt(config, "readTimeoutMs", DEFAULT_TIMEOUT_MS);

        return new SsmStoreSettings(
                path, region, endpointOverride, recursive, withDecryption, prefix, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * Normalizes a path value: ensures it starts with {@code "/"} and ends with {@code "/"}.
     *
     * @param path the raw path value from config
     * @return the normalized path
     */
    private static String normalizePath(String path) {
        String result = path.trim();
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        if (!result.endsWith("/")) {
            result = result + "/";
        }
        return result;
    }

    /**
     * Reads a positive integer field from {@code config}, returning {@code defaultValue} when
     * the field is absent, or throwing {@link IllegalArgumentException} when the value is
     * non-integer or not positive.
     *
     * @param config       the JSON object to read from
     * @param field        the config field name
     * @param defaultValue the value to use when {@code field} is absent
     * @return the validated positive integer, or {@code defaultValue} when the field is absent
     * @throws IllegalArgumentException if the field is non-integer or not positive
     */
    private static int parsePositiveInt(JsonObject config, String field, int defaultValue) {
        Object raw = config.getValue(field);
        if (raw == null) {
            return defaultValue;
        }
        if (!(raw instanceof Number num)) {
            throw new IllegalArgumentException("AWS SSM store: field '" + field + "' must be an integer; got: "
                    + raw.getClass().getSimpleName());
        }
        // Normalise to BigDecimal so that BigDecimal, BigInteger, Double, Float, Long, and
        // Integer are all handled uniformly. Using new BigDecimal(value.toString()) avoids
        // the floating-point representation noise of BigDecimal.valueOf(double).
        BigDecimal bd;
        try {
            bd = new BigDecimal(num.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "AWS SSM store: field '" + field + "' could not be parsed as a number; got: " + num);
        }
        // Reject any fractional value (e.g. 1.5, 5000.5).
        if (bd.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "AWS SSM store: field '" + field + "' must be an exact integer (no fractional part); got: " + num);
        }
        // Reject values outside int range (e.g. BigInteger 2^40, Long 3_000_000_000).
        BigInteger bigInt = bd.toBigIntegerExact();
        if (bigInt.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                || bigInt.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0) {
            throw new IllegalArgumentException("AWS SSM store: field '"
                    + field
                    + "' value "
                    + bigInt
                    + " is out of int range ["
                    + Integer.MIN_VALUE
                    + ", "
                    + Integer.MAX_VALUE
                    + "]");
        }
        int value = bigInt.intValueExact();
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "AWS SSM store: field '" + field + "' must be a positive integer (> 0); got: " + value);
        }
        return value;
    }

    // --- Internal functional interface ---

    /**
     * Factory function that creates an {@link SsmGateway} from store settings.
     *
     * <p>The default implementation creates an {@link SdkSsmGateway}. Tests inject a stub.
     */
    @FunctionalInterface
    interface GatewayFactory {

        /**
         * Creates an {@link SsmGateway} from the given settings.
         *
         * @param settings the validated store settings
         * @return the created gateway
         */
        SsmGateway create(SsmStoreSettings settings);
    }
}
