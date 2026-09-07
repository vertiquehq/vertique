// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.ConfigSecretRenderer;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.client.exception.RestClientConfigurationException;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed per-client configuration read from the section-root-keyed {@code restClient.{name}} section.
 *
 * <p>The {@code restClient} section <em>is</em> the keyed map: client names are the keys at the
 * section root (there is no wrapper field). Each entry is parsed into a {@link RestClientConfig} with
 * the entry key injected into {@link #name()} during boundary parsing (see {@link #indexFromConfig}).
 * Module internals — {@code RestClientFactory} and {@code RestClientBuilder} — depend on this typed
 * record (or the {@code name -> RestClientConfig} index), never on the raw {@link JsonObject}.
 *
 * <p>The {@code webClient} component is an intentionally-open Vert.x {@code WebClientOptions}
 * pass-through bag (the framework does not own its schema — config rule R9). Because a Vert.x
 * {@link JsonObject} field cannot round-trip through the shared {@link ConfigParser} mapper (it lacks
 * the Vert.x Jackson serializers, so a mapped {@code JsonObject} would come back empty), the
 * {@code webClient} subtree is read directly from the source section in {@link #indexFromConfig} and
 * attached after the typed fields are parsed. Its 7 framework duration keys (e.g.
 * {@code connectTimeoutMs}, {@code keepAliveTimeoutSeconds}) are normalized to their Vert.x option
 * names at parse time via {@link #normalizeWebClientKeys(JsonObject)}, so the stored bag already uses
 * Vert.x-native names; non-duration fields ({@code ssl}, {@code verifyHost}, …) pass through unchanged.
 *
 * <p>Both the open {@code webClient} bag (which may carry {@code ssl} key-store/trust-store passwords)
 * and {@code baseUrl} (which may embed credentials in its authority userinfo) can hold secrets, so
 * {@link #toString()} is overridden to redact them via the framework-wide
 * {@link ConfigSecretRenderer} while the live {@link #webClient()}/{@link #baseUrl()} accessors keep
 * the real values for runtime use.
 *
 * @param name the logical client name (identity); injected from the keyed-object key
 * @param baseUrl the base URL override, or {@code null} when not configured
 * @param readTimeoutMs the per-request read timeout in milliseconds ({@code > 0}), or {@code null}
 *     when not overridden (the builder default applies)
 * @param circuitBreaker the circuit-breaker override, or {@code null} when not configured
 * @param pool the connection-pool override, or {@code null} when not configured
 * @param webClient the normalized Vert.x {@code WebClientOptions} pass-through bag (R9), or
 *     {@code null} when not configured
 * @param retry the retry (backoff-strategy) override, or {@code null} when not configured
 * @param jsonProfile the named JSON mapper profile id for this client, or {@code null} when not
 *     configured (falls through to the builder-level profile or the reserved {@code vertique}
 *     floor, resolved through a {@link dev.vertique.core.json.JsonMapperProfileRegistry})
 */
public record RestClientConfig(
        String name,
        String baseUrl,
        Long readTimeoutMs,
        RestClientCircuitBreakerConfig circuitBreaker,
        RestClientPoolConfig pool,
        @JsonIgnore JsonObject webClient,
        RestClientRetryConfig retry,
        String jsonProfile) {

    /**
     * The keyed root section name; the {@code restClient} section is itself the keyed map of client
     * name → client config.
     */
    private static final String SECTION = "restClient";

    /**
     * The reserved key in the {@code restClient} section that holds the boundary-wide defaults.
     * This key is excluded from the keyed client map produced by {@link #indexFromConfig} and
     * read separately by {@link #defaultsFromConfig}.
     */
    private static final String RESERVED_DEFAULTS_KEY = "defaults";

    /**
     * The per-client {@code webClient} sub-object key; read directly from the source section because a
     * Vert.x {@link JsonObject} field cannot round-trip through the {@link ConfigParser} mapper.
     */
    private static final String WEB_CLIENT_KEY = "webClient";

    /**
     * Compact validator: {@code name} must be non-blank and a present {@code readTimeoutMs} must be
     * positive. The nested records validate their own bounds. Fails fast at startup so malformed
     * config never reaches runtime.
     *
     * @throws ConfigurationException if {@code name} is blank or {@code readTimeoutMs <= 0}
     */
    public RestClientConfig {
        if (name == null || name.isBlank()) {
            throw new ConfigurationException("restClient.<name> key must be non-blank");
        }
        if (readTimeoutMs != null && readTimeoutMs <= 0) {
            throw new ConfigurationException("restClient." + name + ".readTimeoutMs must be > 0, got " + readTimeoutMs);
        }
    }

    /**
     * Jackson factory for the typed fields, filling identity and passing through nullable overrides.
     * The {@code webClient} bag is intentionally <em>not</em> a Jackson property — it is read from the
     * source section and attached by {@link #indexFromConfig} — so it is always constructed here as
     * {@code null} and replaced during boundary assembly.
     *
     * @param name the client name (injected identity); must be present
     * @param baseUrl the base URL override; passed through (nullable)
     * @param readTimeoutMs the read timeout override; passed through (nullable)
     * @param circuitBreaker the circuit-breaker override; passed through (nullable)
     * @param pool the pool override; passed through (nullable)
     * @param retry the retry override; passed through (nullable)
     * @param jsonProfile the named JSON mapper profile id; passed through (nullable)
     * @return the deserialized config with a {@code null} {@code webClient} bag
     */
    @JsonCreator
    static RestClientConfig fromJson(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("baseUrl") @Nullable String baseUrl,
            @JsonProperty("readTimeoutMs") @Nullable Long readTimeoutMs,
            @JsonProperty("circuitBreaker") @Nullable RestClientCircuitBreakerConfig circuitBreaker,
            @JsonProperty("pool") @Nullable RestClientPoolConfig pool,
            @JsonProperty("retry") @Nullable RestClientRetryConfig retry,
            @JsonProperty("jsonProfile") @Nullable String jsonProfile) {
        return new RestClientConfig(name, baseUrl, readTimeoutMs, circuitBreaker, pool, null, retry, jsonProfile);
    }

    /**
     * Returns a copy of this config with the given {@code webClient} bag attached. Used by the
     * boundary parser to attach the directly-read, normalized {@code webClient} subtree.
     *
     * @param webClient the normalized {@code webClient} bag, or {@code null}
     * @return a copy with {@code webClient} set
     */
    private RestClientConfig withWebClient(@Nullable JsonObject webClient) {
        return new RestClientConfig(name, baseUrl, readTimeoutMs, circuitBreaker, pool, webClient, retry, jsonProfile);
    }

    /**
     * Parses the {@code restClient} section of a root config object into an immutable
     * {@code name -> RestClientConfig} index.
     *
     * <p>The section is itself the keyed map of client name → client config. The typed scalar and
     * nested-record fields are parsed via
     * {@link ConfigParser#parseKeyedObject(JsonObject, String, Class)} (which injects each entry key
     * into {@link #name()}); the per-entry {@code webClient} bag is then read directly from the source
     * section, normalized via {@link #normalizeWebClientKeys(JsonObject)}, and attached.
     *
     * @param rootConfig the root application config, qualified {@code @VertxConfig} at the boundary
     * @param parser the injected config parser
     * @return an immutable index keyed by client name, in the section's insertion order
     */
    public static Map<String, RestClientConfig> indexFromConfig(JsonObject rootConfig, ConfigParser parser) {
        JsonObject section = JsonConfigPaths.navigateObject(rootConfig, SECTION);
        // Exclude the reserved "defaults" key before keyed parsing so it is never treated as a
        // client name. A copy is used to avoid mutating the original config object.
        JsonObject sectionWithoutDefaults = section.copy();
        sectionWithoutDefaults.remove(RESERVED_DEFAULTS_KEY);

        List<RestClientConfig> parsed = parser.parseKeyedObject(sectionWithoutDefaults, "name", RestClientConfig.class);

        Map<String, RestClientConfig> index = new LinkedHashMap<>();
        for (RestClientConfig client : parsed) {
            JsonObject entry = section.getJsonObject(client.name());
            JsonObject rawWebClient = entry != null ? entry.getJsonObject(WEB_CLIENT_KEY) : null;
            JsonObject normalized = normalizeWebClientKeys(rawWebClient);
            index.put(client.name(), client.withWebClient(normalized));
        }
        return Map.copyOf(index);
    }

    /**
     * Parses the {@code restClient.defaults} reserved sub-object from the root config into a typed
     * {@link RestClientDefaults} record using the canonical {@link ConfigParser} (config rule R10).
     *
     * <p>The reserved {@code defaults} key is excluded from the keyed client map produced by
     * {@link #indexFromConfig} — it is parsed separately by this method. The caller (the Dagger
     * module provider) binds the result as a {@link RestClientDefaults} singleton so that
     * {@link dev.vertique.rest.client.RestClientDefaultProfileValidator} and
     * {@link dev.vertique.rest.client.RestClientFactory} can inject it without the old
     * hand-rolled scalar read.
     *
     * <p>When the {@code restClient.defaults} sub-object is absent (the section is empty or the key
     * is not present), returns {@link RestClientDefaults#defaults()} whose {@code jsonProfile()} is
     * {@code null}.
     *
     * @param rootConfig the root application config, qualified {@code @VertxConfig} at the boundary
     * @param parser the injected config parser — used to parse the section (config rule R10)
     * @return the parsed {@link RestClientDefaults}; never {@code null}
     */
    public static RestClientDefaults defaultsFromConfig(JsonObject rootConfig, ConfigParser parser) {
        JsonObject defaults = JsonConfigPaths.navigateObject(rootConfig, SECTION, RESERVED_DEFAULTS_KEY);
        // navigateObject returns an empty JsonObject when the path is absent; treat empty as absent.
        if (defaults.isEmpty()) {
            return RestClientDefaults.defaults();
        }
        return parser.parse(defaults, RestClientDefaults.class);
    }

    /**
     * Translates the framework-normalized {@code webClient} duration field names (with explicit unit
     * suffixes) to Vert.x's native {@code WebClientOptions} field names. Seven duration keys are
     * renamed; all other keys (non-duration options such as {@code ssl}, {@code verifyHost},
     * {@code keepAlive}) pass through unchanged. Operates on a copy; the input is not mutated.
     *
     * <p>The input is validated <em>before</em> any renaming: if the input contains a raw Vert.x
     * native duration key (e.g. {@code connectTimeout} instead of {@code connectTimeoutMs}), a
     * {@link RestClientConfigurationException} is thrown. This covers both the pure-native case and
     * the duplicate native+normalized case — if a native key is present in the input, the call is
     * rejected unconditionally, regardless of whether the normalized key is also present. The seven
     * pairs validated are: {@code connectTimeout}/{@code connectTimeoutMs},
     * {@code idleTimeout}/{@code idleTimeoutSeconds},
     * {@code readIdleTimeout}/{@code readIdleTimeoutSeconds},
     * {@code writeIdleTimeout}/{@code writeIdleTimeoutSeconds},
     * {@code keepAliveTimeout}/{@code keepAliveTimeoutSeconds},
     * {@code http2KeepAliveTimeout}/{@code http2KeepAliveTimeoutSeconds}, and
     * {@code sslHandshakeTimeout}/{@code sslHandshakeTimeoutSeconds}.
     *
     * @param config the raw {@code webClient} config section with framework field names, or
     *     {@code null}
     * @return a new {@link JsonObject} with the duration keys renamed, or {@code null} if {@code
     *     config} is {@code null}
     * @throws RestClientConfigurationException if the input contains a raw Vert.x native duration
     *     key instead of the required explicit-unit framework key
     */
    @Nullable
    public static JsonObject normalizeWebClientKeys(@Nullable JsonObject config) {
        if (config == null) {
            return null;
        }
        // --- Validate: reject raw Vert.x native duration keys in the INPUT ---
        // Each pair: native key (forbidden in input) → required explicit-unit key (correct key).
        rejectNativeKey(config, "connectTimeout", "connectTimeoutMs");
        rejectNativeKey(config, "idleTimeout", "idleTimeoutSeconds");
        rejectNativeKey(config, "readIdleTimeout", "readIdleTimeoutSeconds");
        rejectNativeKey(config, "writeIdleTimeout", "writeIdleTimeoutSeconds");
        rejectNativeKey(config, "keepAliveTimeout", "keepAliveTimeoutSeconds");
        rejectNativeKey(config, "http2KeepAliveTimeout", "http2KeepAliveTimeoutSeconds");
        rejectNativeKey(config, "sslHandshakeTimeout", "sslHandshakeTimeoutSeconds");

        // --- Translate: rename the framework explicit-unit keys to Vert.x native names ---
        JsonObject translated = config.copy();
        renameKey(translated, "connectTimeoutMs", "connectTimeout");
        renameKey(translated, "idleTimeoutSeconds", "idleTimeout");
        renameKey(translated, "readIdleTimeoutSeconds", "readIdleTimeout");
        renameKey(translated, "writeIdleTimeoutSeconds", "writeIdleTimeout");
        renameKey(translated, "keepAliveTimeoutSeconds", "keepAliveTimeout");
        renameKey(translated, "http2KeepAliveTimeoutSeconds", "http2KeepAliveTimeout");
        renameKey(translated, "sslHandshakeTimeoutSeconds", "sslHandshakeTimeout");
        return translated;
    }

    /**
     * Renames a key in a {@link JsonObject} if the old key is present. Removes the old key and inserts
     * its value under the new key.
     *
     * @param json the object to mutate
     * @param oldKey the key to rename
     * @param newKey the replacement key name
     */
    private static void renameKey(JsonObject json, String oldKey, String newKey) {
        if (json.containsKey(oldKey)) {
            json.put(newKey, json.getValue(oldKey));
            json.remove(oldKey);
        }
    }

    /**
     * Throws {@link RestClientConfigurationException} if the given config contains the raw Vert.x
     * native duration key. Operators must use the explicit-unit framework key instead (e.g.
     * {@code connectTimeoutMs} rather than {@code connectTimeout}).
     *
     * @param config the {@code webClient} input config to inspect
     * @param nativeKey the forbidden raw Vert.x native key (e.g. {@code connectTimeout})
     * @param requiredKey the required framework explicit-unit key (e.g. {@code connectTimeoutMs})
     * @throws RestClientConfigurationException if {@code nativeKey} is present in {@code config}
     */
    private static void rejectNativeKey(JsonObject config, String nativeKey, String requiredKey) {
        if (config.containsKey(nativeKey)) {
            throw new RestClientConfigurationException("webClient duration key '" + nativeKey
                    + "' is a raw Vert.x key; use the explicit-unit key '" + requiredKey + "' instead");
        }
    }

    // --- Log-safe rendering ---

    /**
     * Renders this client config with secrets redacted so a log line or exception message never leaks
     * a credential. The {@code webClient} bag is an open Vert.x {@code WebClientOptions} pass-through
     * (config rule R9) that can carry {@code ssl} key-store/trust-store passwords at any depth, and
     * {@code baseUrl} can embed credentials in its authority userinfo ({@code https://user:pass@host}).
     * The default record {@code toString()} would render both verbatim; this override delegates to the
     * framework-wide {@link ConfigSecretRenderer} — masking the {@code webClient} bag at every depth
     * and the {@code baseUrl} userinfo/query credentials — while rendering the remaining typed fields
     * normally. The live {@link #webClient()}/{@link #baseUrl()} accessors are untouched.
     *
     * @return a log-safe string rendering of this client config
     */
    @Override
    public String toString() {
        return "RestClientConfig[name=" + name + ", baseUrl=" + ConfigSecretRenderer.redactUri(baseUrl)
                + ", readTimeoutMs=" + readTimeoutMs + ", circuitBreaker=" + circuitBreaker + ", pool=" + pool
                + ", webClient=" + ConfigSecretRenderer.redactBag(webClient) + ", retry=" + retry
                + ", jsonProfile=" + jsonProfile + "]";
    }
}
