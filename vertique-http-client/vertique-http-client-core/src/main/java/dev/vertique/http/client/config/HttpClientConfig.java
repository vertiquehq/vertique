// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client.config;

import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.config.ConfigSecret;
import dev.vertique.core.config.ConfigSecretRenderer;
import dev.vertique.core.config.JsonConfigPaths;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.http.client.EgressPolicy;
import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.internal.WebClientOptionsHardening;
import dev.vertique.http.client.internal.WebClientOptionsNormalizer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed configuration of one governed HTTP client, read from the {@code httpClient.{name}} section.
 *
 * <p>The {@code httpClient} section is itself the keyed map: client names are the keys at its root and
 * {@link #indexFromConfig} injects each key into {@link #name()}. A component that is {@code null} (or
 * an empty map or bag) was not configured, so the value the builder holds applies and, below it, the
 * default.
 *
 * <p>Parsing fails startup with an {@link HttpClientConfigurationException} for an unknown key (the
 * parser itself ignores unknown keys, so each section's keys are checked first), a value of the wrong
 * type or outside its bounds, a raw Vert.x duration name in {@code webClient}, a rejected default
 * header, and effective {@code webClient} options that weaken TLS. Every message is constant and names
 * the key path {@code httpClient.<name>.<key>}; none shows a configured value or carries a cause.
 *
 * <p>The {@code webClient} bag is the open Vert.x {@code WebClientOptions} pass-through. It is stored
 * with the Vert.x option names (the seven explicit-unit duration keys are translated at parse time).
 * Vert.x ignores keys it does not know, so a misspelt {@code webClient} key is silently ignored. The
 * bag and {@code defaultHeaders} can carry secrets and render as {@code ***} in {@link #toString()},
 * which also redacts user information in {@code baseUrl}.
 *
 * @param name the client name, {@code [A-Za-z0-9._~-]{1,128}}; injected from the section key
 * @param baseUrl the base URL, or {@code null}
 * @param readTimeoutMs the default attempt timeout in milliseconds, above 0, or {@code null}
 * @param pool the connection pool override, or {@code null}
 * @param webClient the {@code WebClientOptions} bag with Vert.x option names, or {@code null}
 * @param resiliencePolicy the name of the {@code resilience.policies} tier calls run in by default, or
 *     {@code null}
 * @param defaultHeaders headers added to every request that does not set them; empty when unset
 * @param egress the egress override, or {@code null}
 * @param allowCredentialsOverPlainHttp whether credentials may go over plain HTTP to a non-loopback
 *     host, or {@code null}
 * @param credentialOrigins the {@code scheme://host[:port]} origins credentials may go to besides the base
 *     URL's origin, or {@code null}
 * @param maxRequestBytes the most request body bytes sent, not negative, 0 for no bound, or {@code null}
 * @param maxResponseBytes the most response body bytes read, not negative, 0 for no bound, or {@code null}
 * @param maxEventBytes the most bytes of one server-sent event, above 0, or {@code null}
 * @param streamIdleTimeoutMs the longest time without received stream bytes in milliseconds, not
 *     negative, 0 to disable, or {@code null}
 */
public record HttpClientConfig(
        String name,
        @Nullable String baseUrl,
        @Nullable Long readTimeoutMs,
        @Nullable HttpClientPoolConfig pool,
        @ConfigSecret @Nullable JsonObject webClient,
        @Nullable String resiliencePolicy,
        @ConfigSecret Map<String, String> defaultHeaders,
        @Nullable HttpClientEgressConfig egress,
        @Nullable Boolean allowCredentialsOverPlainHttp,
        @Nullable List<String> credentialOrigins,
        @Nullable Long maxRequestBytes,
        @Nullable Long maxResponseBytes,
        @Nullable Integer maxEventBytes,
        @Nullable Long streamIdleTimeoutMs) {

    private static final String SECTION = "httpClient";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");

    /** Header names the transport owns; a configured default header may not use them. */
    private static final Set<String> FRAMEWORK_HEADERS =
            Set.of("host", "content-length", "transfer-encoding", "connection", "upgrade", "te");

    private static final String RESERVED_HEADER_PREFIX = "vertique-";

    private enum Kind {
        STRING,
        LONG,
        INTEGER,
        BOOLEAN,
        STRING_LIST,
        OBJECT,
        STRING_OBJECT
    }

    private static final Map<String, Kind> KEYS = keys();
    private static final Map<String, Kind> POOL_KEYS = Map.of(
            "http1MaxSize", Kind.INTEGER,
            "http2MaxSize", Kind.INTEGER,
            "maxWaitQueueSize", Kind.INTEGER,
            "eventLoopSize", Kind.INTEGER,
            "cleanerPeriodMs", Kind.INTEGER,
            "maxLifetimeSeconds", Kind.INTEGER);
    private static final Map<String, Kind> EGRESS_KEYS =
            Map.of("allowHosts", Kind.STRING_LIST, "allowPlainHttp", Kind.BOOLEAN);

    private static Map<String, Kind> keys() {
        Map<String, Kind> keys = new LinkedHashMap<>();
        keys.put("baseUrl", Kind.STRING);
        keys.put("readTimeoutMs", Kind.LONG);
        keys.put("pool", Kind.OBJECT);
        keys.put("webClient", Kind.OBJECT);
        keys.put("resiliencePolicy", Kind.STRING);
        keys.put("defaultHeaders", Kind.STRING_OBJECT);
        keys.put("egress", Kind.OBJECT);
        keys.put("allowCredentialsOverPlainHttp", Kind.BOOLEAN);
        keys.put("credentialOrigins", Kind.STRING_LIST);
        keys.put("maxRequestBytes", Kind.LONG);
        keys.put("maxResponseBytes", Kind.LONG);
        keys.put("maxEventBytes", Kind.INTEGER);
        keys.put("streamIdleTimeoutMs", Kind.LONG);
        return Collections.unmodifiableMap(keys);
    }

    /**
     * Validates the components and copies the mutable ones.
     *
     * @throws HttpClientConfigurationException when the name is invalid or a component is outside its
     *     bounds or not acceptable; the message names the key path and never shows a value
     */
    public HttpClientConfig {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new HttpClientConfigurationException("httpClient.<name> must match [A-Za-z0-9._~-]{1,128}");
        }
        String path = SECTION + "." + name + ".";
        if (readTimeoutMs != null && readTimeoutMs <= 0) {
            throw invalid(path + "readTimeoutMs must be > 0");
        }
        if (maxRequestBytes != null && maxRequestBytes < 0) {
            throw invalid(path + "maxRequestBytes must be >= 0");
        }
        if (maxResponseBytes != null && maxResponseBytes < 0) {
            throw invalid(path + "maxResponseBytes must be >= 0");
        }
        if (maxEventBytes != null && maxEventBytes <= 0) {
            throw invalid(path + "maxEventBytes must be > 0");
        }
        if (streamIdleTimeoutMs != null && streamIdleTimeoutMs < 0) {
            throw invalid(path + "streamIdleTimeoutMs must be >= 0");
        }
        if (resiliencePolicy != null && resiliencePolicy.isBlank()) {
            throw invalid(path + "resiliencePolicy must not be blank");
        }
        validatePool(path, pool);
        validateEgress(path, egress);
        validateCredentialOrigins(path, credentialOrigins);
        defaultHeaders = validatedHeaders(path, defaultHeaders);
        validateWebClient(path, webClient);
        webClient = webClient == null ? null : webClient.copy();
        credentialOrigins = credentialOrigins == null ? null : List.copyOf(credentialOrigins);
    }

    /**
     * Returns a copy of the {@code webClient} bag, so the stored bag cannot be changed after validation.
     *
     * @return a copy of the bag, or {@code null} when none is configured
     */
    @Nullable
    public JsonObject webClient() {
        return webClient == null ? null : webClient.copy();
    }

    /**
     * Parses the {@code httpClient} section of the root configuration into a {@code name -> config}
     * index, in the section's order. An absent section gives an empty index.
     *
     * <p>Each entry is checked against the known keys and value types, its {@code webClient} bag is
     * normalized, and the entry is then parsed through the injected {@link ConfigParser}, whose record
     * validation runs before the index exists.
     *
     * @param rootConfig the root application configuration
     * @param parser the injected configuration parser
     * @return the immutable index
     * @throws HttpClientConfigurationException when any entry is invalid; the message is constant and
     *     names the key path
     */
    public static Map<String, HttpClientConfig> indexFromConfig(JsonObject rootConfig, ConfigParser parser) {
        JsonObject section = JsonConfigPaths.navigateObject(rootConfig, SECTION);
        Map<String, HttpClientConfig> index = new LinkedHashMap<>();
        for (String name : section.fieldNames()) {
            if (!NAME.matcher(name).matches()) {
                throw new HttpClientConfigurationException("httpClient.<name> must match [A-Za-z0-9._~-]{1,128}");
            }
            if (!(section.getValue(name) instanceof JsonObject entry)) {
                throw invalid(SECTION + "." + name + " must be an object");
            }
            JsonObject prepared = prepare(name, entry);
            index.put(name, parse(name, prepared, parser));
        }
        return Collections.unmodifiableMap(index);
    }

    private static HttpClientConfig parse(String name, JsonObject entry, ConfigParser parser) {
        try {
            return parser.parseKeyedObject(new JsonObject().put(name, entry), "name", HttpClientConfig.class)
                    .get(0);
        } catch (HttpClientConfigurationException failure) {
            throw failure;
        } catch (ConfigurationException failure) {
            if (failure.getCause() == null) {
                throw invalid(SECTION + "." + name + ": " + failure.getMessage());
            }
            throw invalid(SECTION + "." + name + " cannot be parsed");
        } catch (RuntimeException failure) {
            throw invalid(SECTION + "." + name + " cannot be parsed");
        }
    }

    /** Checks the keys and value types of one entry and returns a copy with the normalized bag. */
    private static JsonObject prepare(String name, JsonObject entry) {
        String path = SECTION + "." + name + ".";
        checkKeys(path, entry, KEYS);
        validateBaseUrl(path, entry.getValue("baseUrl"));
        validateHeaderSyntax(path, entry.getValue("defaultHeaders"));
        JsonObject copy = entry.copy();
        if (entry.getValue("pool") instanceof JsonObject pool) {
            checkKeys(path + "pool.", pool, POOL_KEYS);
        }
        if (entry.getValue("egress") instanceof JsonObject egress) {
            checkKeys(path + "egress.", egress, EGRESS_KEYS);
        }
        if (entry.getValue("webClient") instanceof JsonObject bag) {
            try {
                copy.put("webClient", WebClientOptionsNormalizer.normalize(bag));
            } catch (HttpClientConfigurationException failure) {
                throw invalid(path + "webClient: " + failure.getMessage());
            }
        }
        return copy;
    }

    /** Startup rule: the base URL is http or https with a host and no user information. */
    private static void validateBaseUrl(String path, @Nullable Object value) {
        if (!(value instanceof String text)) {
            return;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException invalid) {
            throw invalid(path
                    + "baseUrl must be an http or https URL with a host and no user information, query or fragment");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw invalid(path
                    + "baseUrl must be an http or https URL with a host and no user information, query or fragment");
        }
    }

    /** Startup rule: header names are tokens and values hold no control character. */
    private static void validateHeaderSyntax(String path, @Nullable Object value) {
        if (!(value instanceof JsonObject headers)) {
            return;
        }
        for (String headerName : headers.fieldNames()) {
            Object headerValue = headers.getValue(headerName);
            if (!TOKEN.matcher(headerName).matches()
                    || headerValue instanceof String text && hasControlCharacter(text)) {
                throw invalid(path + "defaultHeaders holds a header that is not acceptable");
            }
        }
    }

    private static boolean hasControlCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static void checkKeys(String path, JsonObject object, Map<String, Kind> known) {
        for (String key : object.fieldNames()) {
            Kind kind = known.get(key);
            if (kind == null) {
                throw invalid(path + key + " is not a known configuration key");
            }
            Object value = object.getValue(key);
            if (value != null && !matches(kind, value)) {
                throw invalid(path + key + " has a value of the wrong type");
            }
        }
    }

    private static boolean matches(Kind kind, Object value) {
        return switch (kind) {
            case STRING -> value instanceof String;
            case LONG -> isInteger(value, Long.MIN_VALUE, Long.MAX_VALUE);
            case INTEGER -> isInteger(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
            case BOOLEAN ->
                value instanceof Boolean
                        || value instanceof String text && (text.equals("true") || text.equals("false"));
            case STRING_LIST ->
                value instanceof JsonArray array && array.stream().allMatch(String.class::isInstance);
            case OBJECT -> value instanceof JsonObject;
            case STRING_OBJECT ->
                value instanceof JsonObject object
                        && object.stream().allMatch(entry -> entry.getValue() instanceof String);
        };
    }

    private static boolean isInteger(Object value, long min, long max) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            long number = ((Number) value).longValue();
            return number >= min && number <= max;
        }
        if (value instanceof String text && INTEGER.matcher(text).matches()) {
            long number = Long.parseLong(text);
            return number >= min && number <= max;
        }
        return false;
    }

    private static void validatePool(String path, @Nullable HttpClientPoolConfig pool) {
        if (pool == null) {
            return;
        }
        if (pool.http1MaxSize() != null && pool.http1MaxSize() < 1) {
            throw invalid(path + "pool.http1MaxSize must be >= 1");
        }
        if (pool.http2MaxSize() != null && pool.http2MaxSize() < 1) {
            throw invalid(path + "pool.http2MaxSize must be >= 1");
        }
        if (pool.eventLoopSize() != null && pool.eventLoopSize() < 0) {
            throw invalid(path + "pool.eventLoopSize must be >= 0");
        }
        if (pool.maxLifetimeSeconds() != null && pool.maxLifetimeSeconds() < 0) {
            throw invalid(path + "pool.maxLifetimeSeconds must be >= 0");
        }
    }

    private static void validateEgress(String path, @Nullable HttpClientEgressConfig egress) {
        if (egress == null || egress.allowHosts() == null) {
            return;
        }
        if (egress.allowHosts().isEmpty()) {
            throw invalid(path + "egress.allowHosts must not be empty");
        }
        try {
            EgressPolicy.allowHosts(egress.allowHosts(), false);
        } catch (RuntimeException failure) {
            throw invalid(path + "egress.allowHosts holds an entry that is not a host, a *.suffix or *");
        }
    }

    private static void validateCredentialOrigins(String path, @Nullable List<String> origins) {
        if (origins == null) {
            return;
        }
        if (origins.isEmpty()) {
            throw invalid(path + "credentialOrigins must not be empty");
        }
        for (String origin : origins) {
            if (!isOrigin(origin)) {
                throw invalid(path + "credentialOrigins holds an entry that is not scheme://host[:port]");
            }
        }
    }

    private static boolean isOrigin(@Nullable String text) {
        if (text == null) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException invalid) {
            return false;
        }
        String path = uri.getRawPath();
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && (path == null || path.isEmpty());
    }

    private static Map<String, String> validatedHeaders(String path, @Nullable Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String headerName = header.getKey();
            String value = header.getValue();
            if (headerName == null
                    || value == null
                    || headerName.isBlank()
                    || hasForbiddenCharacter(headerName)
                    || hasForbiddenCharacter(value)
                    || headerName.toLowerCase(Locale.ROOT).startsWith(RESERVED_HEADER_PREFIX)
                    || FRAMEWORK_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
                throw invalid(path + "defaultHeaders holds a header that is not acceptable");
            }
            copy.put(headerName, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static boolean hasForbiddenCharacter(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') {
                return true;
            }
        }
        return false;
    }

    private static void validateWebClient(String path, @Nullable JsonObject bag) {
        if (bag == null) {
            return;
        }
        WebClientOptions options;
        try {
            options = new WebClientOptions(bag.copy());
        } catch (RuntimeException failure) {
            throw invalid(path + "webClient cannot be converted to WebClient options");
        }
        if (WebClientOptionsHardening.isInsecure(options) || WebClientOptionsHardening.isInsecure(bag)) {
            throw invalid(
                    path + "webClient must not trust all certificates, skip host verification or enable TLS below 1.2");
        }
    }

    private static HttpClientConfigurationException invalid(String message) {
        return new HttpClientConfigurationException(message);
    }

    /**
     * Renders the configuration without secrets: the {@code webClient} bag and {@code defaultHeaders}
     * show as {@code ***} when set, and user information in {@code baseUrl} is masked.
     *
     * @return a log-safe rendering
     */
    @Override
    public String toString() {
        return "HttpClientConfig[name=" + name + ", baseUrl=" + ConfigSecretRenderer.redactUri(baseUrl)
                + ", readTimeoutMs=" + readTimeoutMs + ", pool=" + pool
                + ", webClient=" + masked(webClient != null && !webClient.isEmpty())
                + ", resiliencePolicy=" + resiliencePolicy
                + ", defaultHeaders=" + masked(!defaultHeaders.isEmpty())
                + ", egress=" + egress + ", allowCredentialsOverPlainHttp=" + allowCredentialsOverPlainHttp
                + ", credentialOrigins=" + credentialOrigins + ", maxRequestBytes=" + maxRequestBytes
                + ", maxResponseBytes=" + maxResponseBytes + ", maxEventBytes=" + maxEventBytes
                + ", streamIdleTimeoutMs=" + streamIdleTimeoutMs + "]";
    }

    private static String masked(boolean set) {
        return set ? ConfigSecretRenderer.MASK : "{}";
    }
}
