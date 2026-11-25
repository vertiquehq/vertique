// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.http.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.http.client.config.HttpClientConfig;
import dev.vertique.http.client.exception.HttpClientConfigurationException;
import dev.vertique.http.client.exception.HttpEgressDeniedException;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests of the {@code httpClient.{name}.*} configuration: the boundary parse into
 * {@link HttpClientConfig}, its validation and redaction, the duration-key normalizer, the TLS
 * hardening of the {@code webClient} bag, and the precedence of external configuration over builder
 * setters over defaults.
 *
 * <p>The tests parse the section the way the Dagger provider does, through the injected
 * {@link ConfigParser}, and observe builder effects through the package-private settings of the
 * built client.
 */
class HttpClientConfigTest {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    // --- helpers ---

    private static ConfigParser parser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    /** Parses an {@code httpClient} section (client name to client config) exactly as the provider does. */
    private static Map<String, HttpClientConfig> parse(JsonObject section) {
        return HttpClientConfig.indexFromConfig(new JsonObject().put("httpClient", section), parser());
    }

    private static HttpClientConfig parseA(JsonObject entry) {
        HttpClientConfig config = parse(new JsonObject().put("a", entry)).get("a");
        assertThat(config).as("client a").isNotNull();
        return config;
    }

    private static Throwable parseFailure(JsonObject section) {
        try {
            parse(section);
        } catch (Throwable failure) {
            return failure;
        }
        throw new AssertionError("the configuration was accepted but must fail startup: " + section.encode());
    }

    /** The message of the failure and of every cause, so a value hidden in a cause is also found. */
    private static String everyMessage(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n').append(t);
        }
        return all.toString();
    }

    /**
     * The failure at startup: the parse of the section and, for the values the builder checks, the build of
     * each parsed client.
     */
    private static Throwable startupFailure(JsonObject section) {
        try {
            for (HttpClientConfig config : parse(section).values()) {
                HttpClientBuilder.create(vertx)
                        .name(config.name())
                        .config(config)
                        .build();
            }
        } catch (Throwable failure) {
            return failure;
        }
        throw new AssertionError("the configuration was accepted but must fail startup: " + section.encode());
    }

    private static HttpClient build(HttpClientBuilder builder) {
        return builder.build();
    }

    private static ClientSettings settings(HttpClient client) {
        return ((HttpClient) client).settings();
    }

    /** One invalid configuration: where it sits, which key path the failure must name, and the value it must not show. */
    private record Invalid(String label, JsonObject entry, String keyPath, String value) {}

    private static List<Invalid> invalidConfigurations() {
        List<Invalid> cases = new ArrayList<>();
        cases.add(new Invalid(
                "unknown key",
                new JsonObject().put("bogus", "zzbogus-value-91"),
                "httpClient.a.bogus",
                "zzbogus-value-91"));
        cases.add(new Invalid(
                "maxEventBytes zero", new JsonObject().put("maxEventBytes", 0), "httpClient.a.maxEventBytes", null));
        cases.add(new Invalid(
                "maxEventBytes negative",
                new JsonObject().put("maxEventBytes", -48211),
                "httpClient.a.maxEventBytes",
                "48211"));
        cases.add(new Invalid(
                "streamIdleTimeoutMs negative",
                new JsonObject().put("streamIdleTimeoutMs", -48212),
                "httpClient.a.streamIdleTimeoutMs",
                "48212"));
        cases.add(new Invalid(
                "maxRequestBytes negative",
                new JsonObject().put("maxRequestBytes", -48213),
                "httpClient.a.maxRequestBytes",
                "48213"));
        cases.add(new Invalid(
                "maxResponseBytes negative",
                new JsonObject().put("maxResponseBytes", -48214),
                "httpClient.a.maxResponseBytes",
                "48214"));
        cases.add(new Invalid(
                "readTimeoutMs not a number",
                new JsonObject().put("readTimeoutMs", "abc"),
                "httpClient.a.readTimeoutMs",
                "abc"));
        cases.add(new Invalid(
                "readTimeoutMs zero", new JsonObject().put("readTimeoutMs", 0), "httpClient.a.readTimeoutMs", null));
        cases.add(new Invalid(
                "readTimeoutMs negative",
                new JsonObject().put("readTimeoutMs", -48215),
                "httpClient.a.readTimeoutMs",
                "48215"));
        cases.add(new Invalid(
                "pool.http1MaxSize zero",
                new JsonObject().put("pool", new JsonObject().put("http1MaxSize", 0)),
                "httpClient.a.pool.http1MaxSize",
                null));
        cases.add(new Invalid(
                "egress.allowHosts empty",
                new JsonObject().put("egress", new JsonObject().put("allowHosts", new JsonArray())),
                "httpClient.a.egress.allowHosts",
                null));
        cases.add(new Invalid(
                "defaultHeaders Host",
                defaultHeaders("Host", "zz-host-value-31"),
                "httpClient.a.defaultHeaders",
                "zz-host-value-31"));
        cases.add(new Invalid(
                "defaultHeaders Content-Length",
                defaultHeaders("content-length", "zz-length-value-32"),
                "httpClient.a.defaultHeaders",
                "zz-length-value-32"));
        cases.add(new Invalid(
                "defaultHeaders Transfer-Encoding",
                defaultHeaders("Transfer-Encoding", "zz-te-value-33"),
                "httpClient.a.defaultHeaders",
                "zz-te-value-33"));
        cases.add(new Invalid(
                "defaultHeaders Connection",
                defaultHeaders("Connection", "zz-conn-value-34"),
                "httpClient.a.defaultHeaders",
                "zz-conn-value-34"));
        cases.add(new Invalid(
                "defaultHeaders Upgrade",
                defaultHeaders("Upgrade", "zz-up-value-35"),
                "httpClient.a.defaultHeaders",
                "zz-up-value-35"));
        cases.add(new Invalid(
                "defaultHeaders TE",
                defaultHeaders("TE", "zz-tev-value-36"),
                "httpClient.a.defaultHeaders",
                "zz-tev-value-36"));
        cases.add(new Invalid(
                "defaultHeaders value with CR LF",
                defaultHeaders("X-App", "zz-crlf-value\r\nInjected: 1"),
                "httpClient.a.defaultHeaders",
                "zz-crlf-value"));
        cases.add(new Invalid(
                "defaultHeaders value with NUL",
                defaultHeaders("X-App", "zz-nul-value\u0000x"),
                "httpClient.a.defaultHeaders",
                "zz-nul-value"));
        cases.add(new Invalid(
                "baseUrl scheme",
                new JsonObject().put("baseUrl", "ftp://zz-ftp-host-51"),
                "httpClient.a.baseUrl",
                "zz-ftp-host-51"));
        cases.add(new Invalid(
                "baseUrl user information",
                new JsonObject().put("baseUrl", "https://zzuser52:zzpass52@zz-host-52.example/"),
                "httpClient.a.baseUrl",
                "zzpass52"));
        cases.add(new Invalid(
                "baseUrl without host",
                new JsonObject().put("baseUrl", "http:///zz-path-53"),
                "httpClient.a.baseUrl",
                "zz-path-53"));
        cases.add(new Invalid(
                "defaultHeaders name with space",
                defaultHeaders("Bad Name", "zz-space-value-54"),
                "httpClient.a.defaultHeaders",
                "zz-space-value-54"));
        cases.add(new Invalid(
                "defaultHeaders name with colon",
                defaultHeaders("X:Y", "zz-colon-value-55"),
                "httpClient.a.defaultHeaders",
                "zz-colon-value-55"));
        cases.add(new Invalid(
                "defaultHeaders value with control character",
                defaultHeaders("X-App", "zz-bell-value-56\u0007"),
                "httpClient.a.defaultHeaders",
                "zz-bell-value-56"));
        cases.add(new Invalid(
                "pool unknown key",
                new JsonObject().put("pool", new JsonObject().put("zzPoolBogus", 5)),
                "httpClient.a.pool.zzPoolBogus",
                null));
        cases.add(new Invalid(
                "egress unknown key",
                new JsonObject().put("egress", new JsonObject().put("zzEgressBogus", true)),
                "httpClient.a.egress.zzEgressBogus",
                null));
        cases.add(new Invalid(
                "webClient raw connectTimeout",
                new JsonObject().put("webClient", new JsonObject().put("connectTimeout", 7351)),
                "httpClient.a.webClient",
                "7351"));
        cases.add(new Invalid(
                "webClient raw idleTimeout",
                new JsonObject().put("webClient", new JsonObject().put("idleTimeout", 7352)),
                "httpClient.a.webClient",
                "7352"));
        return cases;
    }

    private static JsonObject defaultHeaders(String name, String value) {
        return new JsonObject().put("defaultHeaders", new JsonObject().put(name, value));
    }

    @Test
    @DisplayName("invalid configuration fails startup naming the key path and never the value")
    void invalidConfigurationFailsStartupNamingTheKeyPathAndNeverTheValue() {
        for (Invalid invalid : invalidConfigurations()) {
            // Given one invalid configuration of httpClient.a
            JsonObject section = new JsonObject().put("a", invalid.entry());

            // When the application parses it and builds the client
            Throwable failure = startupFailure(section);

            // Then startup fails with the configuration exception, its message names the key path
            assertThat(failure)
                    .as(invalid.label())
                    .isInstanceOf(HttpClientConfigurationException.class)
                    .hasMessageContaining(invalid.keyPath());

            // And neither the message nor any cause shows the configured value
            if (invalid.value() != null) {
                assertThat(everyMessage(failure))
                        .as("%s must not show the value", invalid.label())
                        .doesNotContain(invalid.value());
            }
        }

        // And a client name outside [A-Za-z0-9._~-]{1,128} fails startup the same way
        assertThat(parseFailure(new JsonObject().put("bad name", new JsonObject())))
                .isInstanceOf(HttpClientConfigurationException.class)
                .hasMessageContaining("httpClient");
        assertThat(parseFailure(new JsonObject().put("n".repeat(129), new JsonObject())))
                .isInstanceOf(HttpClientConfigurationException.class)
                .hasMessageContaining("httpClient");

        // And a misspelt webClient key is accepted and ignored, as Vert.x ignores unknown keys
        assertThatCode(() -> parseA(new JsonObject().put("webClient", new JsonObject().put("connectTimeoutMz", 100))))
                .doesNotThrowAnyException();

        // And a valid section with every key is accepted
        JsonObject valid = new JsonObject()
                .put("baseUrl", "http://127.0.0.1:8080/api")
                .put("readTimeoutMs", 250)
                .put("pool", new JsonObject().put("http1MaxSize", 3).put("maxLifetimeSeconds", 60))
                .put("webClient", new JsonObject().put("connectTimeoutMs", 1500).put("followRedirects", true))
                .put("defaultHeaders", new JsonObject().put("X-App", "search"))
                .put(
                        "egress",
                        new JsonObject()
                                .put("allowHosts", new JsonArray().add("*.example"))
                                .put("allowPlainHttp", true))
                .put("allowCredentialsOverPlainHttp", true)
                .put("maxRequestBytes", 0)
                .put("maxResponseBytes", 1024)
                .put("maxEventBytes", 4096)
                .put("streamIdleTimeoutMs", 0);
        assertThat(parseA(valid).name()).isEqualTo("a");
    }

    /** The redaction rule of the configuration record. */
    @Test
    @DisplayName("toString redacts the webClient bag, the default headers and the user information of the base URL")
    void toStringRedactsTheWebClientBagDefaultHeadersAndBaseUrlUserInformation() {
        // Given a record built in code (only startup parsing rejects userinfo) carrying secrets
        HttpClientConfig config = new HttpClientConfig(
                "a",
                "https://zz-user:zz-pass-4471@api.example/v1",
                null,
                null,
                new JsonObject()
                        .put("keyStoreOptions", new JsonObject().put("password", "zz-store-4473"))
                        .put("proxyOptions", new JsonObject().put("password", "zz-proxy-4474")),
                Map.of("Authorization", "Bearer zz-token-4472"),
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        // When it is rendered
        String text = config.toString();

        // Then no secret appears, while the client name still does
        assertThat(text)
                .doesNotContain("zz-pass-4471")
                .doesNotContain("zz-user")
                .doesNotContain("zz-token-4472")
                .doesNotContain("zz-store-4473")
                .doesNotContain("zz-proxy-4474")
                .contains("a");
    }

    private static JsonObject webClientEntry(JsonObject bag) {
        return new JsonObject().put("webClient", bag);
    }

    @Test
    @DisplayName("insecure TLS settings in configuration fail startup")
    void insecureTlsSettingsInConfigurationFailStartup() throws Exception {
        // Given each insecure webClient configuration in turn, whichever key or nesting sets it
        record Insecure(String label, JsonObject bag, String value) {}
        List<Insecure> insecure = List.of(
                new Insecure("verifyHost false", new JsonObject().put("verifyHost", false), null),
                new Insecure("trustAll true", new JsonObject().put("trustAll", true), null),
                new Insecure(
                        "TLSv1.1 only",
                        new JsonObject().put("enabledSecureTransportProtocols", new JsonArray().add("TLSv1.1")),
                        "TLSv1.1"),
                new Insecure(
                        "nested TLSv1 among TLSv1.2",
                        new JsonObject()
                                .put(
                                        "sslOptions",
                                        new JsonObject()
                                                .put(
                                                        "enabledSecureTransportProtocols",
                                                        new JsonArray()
                                                                .add("TLSv1")
                                                                .add("TLSv1.2"))),
                        null),
                new Insecure(
                        "nested trustAll",
                        new JsonObject().put("sslOptions", new JsonObject().put("trustAll", true)),
                        null),
                new Insecure(
                        "top-level blank hostname verification algorithm",
                        new JsonObject().put("hostnameVerificationAlgorithm", " "),
                        null),
                new Insecure(
                        "nested verifyHost false",
                        new JsonObject().put("sslOptions", new JsonObject().put("verifyHost", false)),
                        null),
                new Insecure("verifyHost false as a string", new JsonObject().put("verifyHost", "false"), null),
                new Insecure(
                        "SSLv3 among TLSv1.2",
                        new JsonObject()
                                .put(
                                        "enabledSecureTransportProtocols",
                                        new JsonArray().add("TLSv1.2").add("SSLv3")),
                        "SSLv3"),
                new Insecure(
                        "nested blank hostname verification algorithm",
                        new JsonObject().put("sslOptions", new JsonObject().put("hostnameVerificationAlgorithm", "")),
                        null));

        for (Insecure each : insecure) {
            // When the application parses it
            Throwable failure = parseFailure(new JsonObject().put("a", webClientEntry(each.bag())));

            // Then startup fails with a constant message naming httpClient.a.webClient, without the value
            assertThat(failure)
                    .as(each.label())
                    .isInstanceOf(HttpClientConfigurationException.class)
                    .hasMessageContaining("httpClient.a.webClient");
            if (each.value() != null) {
                assertThat(everyMessage(failure)).as(each.label()).doesNotContain(each.value());
            }
        }

        // And secure TLS settings are accepted
        assertThatCode(() -> parseA(webClientEntry(new JsonObject()
                        .put("verifyHost", true)
                        .put(
                                "enabledSecureTransportProtocols",
                                new JsonArray().add("TLSv1.2").add("TLSv1.3")))))
                .doesNotThrowAnyException();

        // And a builder given insecure WebClientOptions builds and logs one constant WARN naming no value
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        HttpClientBuilder builder = HttpClientBuilder.create(vertx);
        try {
            builder.webClientOptions(new WebClientOptions().setVerifyHost(false));
            builder.build();
            List<String> afterVerifyHost = warnings(appender);
            builder.webClientOptions(new WebClientOptions().setTrustAll(true));
            builder.build();
            List<String> afterTrustAll = warnings(appender);

            assertThat(afterVerifyHost)
                    .as("one WARN for skipped host verification")
                    .hasSize(1);
            assertThat(afterTrustAll).as("one more WARN for trust-all").hasSize(2);
            assertThat(afterTrustAll.get(1)).as("the message is constant").isEqualTo(afterVerifyHost.get(0));

            // And a secure builder logs nothing
            builder.webClientOptions(new WebClientOptions());
            builder.build();
            assertThat(warnings(appender)).hasSize(2);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    /** WARN and ERROR messages logged by this module so far. */
    private static List<String> warnings(ListAppender<ILoggingEvent> appender) {
        return new ArrayList<>(appender.list)
                .stream()
                        .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                        .filter(e -> e.getLoggerName().startsWith("dev.vertique.http.client"))
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList();
    }

    /** The seven explicit-unit keys and the Vert.x names they become. */
    private static final Map<String, String> DURATION_KEYS = durationKeys();

    private static Map<String, String> durationKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("connectTimeoutMs", "connectTimeout");
        keys.put("idleTimeoutSeconds", "idleTimeout");
        keys.put("readIdleTimeoutSeconds", "readIdleTimeout");
        keys.put("writeIdleTimeoutSeconds", "writeIdleTimeout");
        keys.put("keepAliveTimeoutSeconds", "keepAliveTimeout");
        keys.put("http2KeepAliveTimeoutSeconds", "http2KeepAliveTimeout");
        keys.put("sslHandshakeTimeoutSeconds", "sslHandshakeTimeout");
        return keys;
    }

    @Test
    @DisplayName("the normalizer maps the seven duration keys and rejects the raw Vert.x names")
    void normalizerMapsTheSevenDurationKeysAndRejectsRawVertxNames() {
        // Given a bag with the seven explicit-unit keys and options that are not durations
        JsonObject bag = new JsonObject().put("keepAlive", false).put("maxPoolSize", 9);
        int value = 11;
        for (String explicit : DURATION_KEYS.keySet()) {
            bag.put(explicit, value++);
        }
        JsonObject before = bag.copy();

        // When the normalizer runs
        JsonObject normalized = WebClientOptionsNormalizer.normalize(bag);

        // Then every duration key carries its value under the Vert.x name and the explicit name is gone
        int expected = 11;
        for (Map.Entry<String, String> pair : DURATION_KEYS.entrySet()) {
            assertThat(normalized.getInteger(pair.getValue()))
                    .as(pair.getValue())
                    .isEqualTo(expected++);
            assertThat(normalized.containsKey(pair.getKey()))
                    .as(pair.getKey() + " is renamed")
                    .isFalse();
        }

        // And the other options pass through unchanged
        assertThat(normalized.getBoolean("keepAlive")).isFalse();
        assertThat(normalized.getInteger("maxPoolSize")).isEqualTo(9);

        // And the input is not mutated
        assertThat(bag).isEqualTo(before);

        // And an absent bag stays absent
        assertThat(WebClientOptionsNormalizer.normalize(null)).isNull();

        // And each raw Vert.x duration name is rejected, naming the raw key and the key to use
        for (Map.Entry<String, String> pair : DURATION_KEYS.entrySet()) {
            JsonObject raw = new JsonObject().put(pair.getValue(), 5);
            assertThatThrownBy(() -> WebClientOptionsNormalizer.normalize(raw))
                    .as(pair.getValue())
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("'" + pair.getValue() + "'")
                    .hasMessageContaining("'" + pair.getKey() + "'");

            // And a raw name beside its explicit-unit key is rejected too
            JsonObject both = new JsonObject().put(pair.getValue(), 5).put(pair.getKey(), 6);
            assertThatThrownBy(() -> WebClientOptionsNormalizer.normalize(both))
                    .as(pair.getValue() + " beside " + pair.getKey())
                    .isInstanceOf(ConfigurationException.class);
        }

        // And the converted bag of a configured client reaches Vert.x with its durations intact
        JsonObject converted = WebClientOptionsNormalizer.normalize(new JsonObject().put("connectTimeoutMs", 1500));
        assertThat(new WebClientOptions(converted).getConnectTimeout()).isEqualTo(1500);
    }

    @Test
    @DisplayName("external configuration wins over builder setters and the defaults match the contract")
    void externalConfigurationWinsOverBuilderSettersAndDefaultsMatchTheContract() {
        HttpClientBuilder builder = HttpClientBuilder.create(vertx);
        // Given a configuration that sets some keys and a builder that sets those and others
        HttpClientConfig config = parseA(new JsonObject()
                .put("baseUrl", "http://config.example/api")
                .put("readTimeoutMs", 250)
                .put("maxResponseBytes", 1024)
                .put("defaultHeaders", new JsonObject().put("X-App", "config"))
                .put(
                        "egress",
                        new JsonObject()
                                .put("allowHosts", new JsonArray().add("config.example"))
                                .put("allowPlainHttp", true)));
        builder.baseUrl("http://builder.example/builder")
                .readTimeout(5, TimeUnit.SECONDS)
                .maxResponseBytes(111)
                .maxRequestBytes(222)
                .defaultHeader("X-App", "builder")
                .egress(EgressPolicy.allowAll());

        // When the configuration is applied to the builder and a client is built
        ClientSettings settings = settings(build(builder.config(config)));

        // Then the keys the configuration sets win
        assertThat(settings.basePath()).isEqualTo("/api");
        assertThat(settings.readTimeoutMs()).isEqualTo(250);
        assertThat(settings.maxResponseBytes()).isEqualTo(1024);
        assertThat(settings.defaultHeaders()).containsEntry("X-App", "config");
        assertThatCode(() -> settings.egress().check(URI.create("http://config.example/x")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> settings.egress().check(URI.create("http://other.example/x")))
                .isInstanceOf(HttpEgressDeniedException.class);

        // And the keys it leaves out keep the builder's value
        assertThat(settings.maxRequestBytes()).isEqualTo(222);

        // And a null configuration leaves the builder as it is
        ClientSettings untouched = settings(build(HttpClientBuilder.create(vertx)
                .baseUrl("http://builder.example/builder")
                .readTimeout(5, TimeUnit.SECONDS)
                .config(null)));
        assertThat(untouched.basePath()).isEqualTo("/builder");
        assertThat(untouched.readTimeoutMs()).isEqualTo(5_000);

        // And with neither, every default of the contract applies
        ClientSettings defaults = settings(build(HttpClientBuilder.create(vertx)
                .config(parseA(new JsonObject().put("baseUrl", "http://defaults.example")))));
        assertThat(defaults.readTimeoutMs()).isEqualTo(30_000);
        assertThat(defaults.maxRequestBytes()).isZero();
        assertThat(defaults.maxResponseBytes()).isEqualTo(10_485_760);
        assertThat(defaults.allowCredentialsOverPlainHttp()).isFalse();
        assertThat(defaults.defaultHeaders()).isEmpty();
        assertThatCode(() -> defaults.egress().check(URI.create("http://anywhere.example/x")))
                .as("absent egress.allowHosts allows every host")
                .doesNotThrowAnyException();

        // And the same defaults hold for a client with no section at all
        ClientSettings none = settings(build(HttpClientBuilder.create(vertx)));
        assertThat(none.readTimeoutMs()).isEqualTo(30_000);
        assertThat(none.maxResponseBytes()).isEqualTo(10_485_760);

        // And a configuration that allows credentials over plain HTTP wins over the builder's false
        ClientSettings plain = settings(build(HttpClientBuilder.create(vertx)
                .allowCredentialsOverPlainHttp(false)
                .config(parseA(new JsonObject().put("allowCredentialsOverPlainHttp", true)))));
        assertThat(plain.allowCredentialsOverPlainHttp()).isTrue();
    }
}
