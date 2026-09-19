// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proofs for how a profile's schema-type override <em>composes</em> with the annotations a
 * body property already carries (TP-005) and for an application-supplied profile whose override
 * needs no binding of its own (TP-006).
 *
 * <p>Three compositions are covered:
 *
 * <ul>
 *   <li><strong>Narrowing.</strong> Swagger property metadata on an overridden property tightens the
 *       profile fragment rather than replacing it, so both bounds apply.</li>
 *   <li><strong>Redirect.</strong> {@code @Schema(implementation = String.class)} over a type the
 *       effective profile overrides is a misconfiguration that fails the mount, while the same
 *       redirect over a type the effective profile does not override still applies (D003).</li>
 *   <li><strong>Custom profile.</strong> An application profile contributed through the fixture's
 *       {@code RestTestContributions.jsonMapperProfiles} builder carries its own override into the
 *       gate with no schema registry, extra binding, or configuration key.</li>
 * </ul>
 *
 * <p>The strict fragment's own bounds are never restated here: the forbidden-disclosure assertions
 * read them through the public path
 * {@code registry.profile(JsonProfileId.of("vertique-strict")).jsonSchemaTypeOverrides().get(0).fragment().canonicalJson()}.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load {@code body()} can succeed with zero bytes while the status code is correct (issue
 * #167).
 */
@ExtendWith(VertxExtension.class)
// 60s rather than testing.md's 20s default: each method builds its own Dagger graph and router, and
// the redirect method builds two mounts.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ProfiledSchemaCompositionIT {

    private static final long ASYNC_TIMEOUT_SECONDS = 20;

    private static final Duration START_TIMEOUT = Duration.ofSeconds(20);

    private static final String LOOPBACK = "127.0.0.1";

    /** The {@code money} profile's id, selected by {@link MoneyResource} and registered below. */
    private static final String MONEY_PROFILE_ID = "money";

    /**
     * A grammar-valid decimal that satisfies the property's own {@code pattern} but is one character
     * past the property's {@code maxLength} of 20, so only the narrowed bound can reject it.
     */
    private static final String OVER_NARROWED_LENGTH_DECIMAL = "1".repeat(18) + ".50";

    // --- Fixture state ---

    private Vertx vertx;

    private WebClient client;

    private final List<HttpServer> servers = new ArrayList<>();

    /**
     * Captures the per-test Vert.x instance and creates the shared {@link WebClient}, bound to a
     * field so {@link #tearDown()} can close it.
     *
     * @param injectedVertx the per-test Vert.x instance injected by vertx-junit5
     */
    @BeforeEach
    void setUp(Vertx injectedVertx) {
        vertx = injectedVertx;
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    /**
     * Closes the {@link WebClient} and then every server the test that just ran started.
     *
     * @throws Exception when a server close fails or times out
     */
    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
            client = null;
        }
        for (HttpServer server : servers) {
            server.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        servers.clear();
    }

    // --- TP-005: property metadata narrows the profile fragment ---

    /**
     * TP-005 (AC-006.1). Swagger property metadata on a {@code BigDecimal} the {@code vertique-strict}
     * profile overrides narrows the profile fragment: the accepted value must satisfy the profile's
     * string form <em>and</em> the property's own {@code pattern} and {@code maxLength}.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("Property pattern and maxLength narrow the strict profile fragment rather than replacing it")
    void propertyMetadataNarrowsTheStrictFragment() throws Exception {
        NarrowedResource resource = new NarrowedResource();
        int port = start(gateMount(RestTestContributions.none()), Set.of(resource));

        assertEquals(
                200,
                post(port, "/narrowed/payment", "{\"amount\":\"1.50\"}").statusCode(),
                "a value satisfying both the profile fragment and the property metadata must be accepted");
        assertEquals(1, resource.invocations.get(), "the accepted body must reach the resource exactly once");

        assertEquals(
                400,
                post(port, "/narrowed/payment", "{\"amount\":\"1.5\"}").statusCode(),
                "one decimal place must be rejected by the property's own pattern, which the fragment does not "
                        + "impose");
        assertEquals(
                400,
                post(port, "/narrowed/payment", "{\"amount\":\"" + OVER_NARROWED_LENGTH_DECIMAL + "\"}")
                        .statusCode(),
                "a 21-character value must be rejected by the property's maxLength of 20, which is tighter than "
                        + "the fragment's own bound");

        assertEquals(1, resource.invocations.get(), "no rejected body may reach the resource");
    }

    /**
     * TP-005 (AC-006.2, D003). {@code @Schema(implementation = String.class)} over a {@code BigDecimal}
     * fails the mount under {@code vertique-strict}, whose profile overrides {@code BigDecimal}: the
     * redirect would silently drop the override's bounds, so it is a configuration error rather than a
     * quietly weaker schema. Under the {@code vertique} floor, which declares no {@code BigDecimal}
     * override, the same redirect still applies and the property validates as a string.
     *
     * <p>The failing half is asserted through {@code RestTestMounts.router}, which builds the router
     * and never binds a server, so "fails before any server binds" is structural rather than timed.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("An implementation redirect fails the strict mount and still applies on the vertique floor")
    void implementationRedirectFailsOnStrictAndAppliesOnVertique() throws Exception {
        ValidationMountComponent component = component(new JsonObject(), RestTestContributions.none());
        String strictFragmentJson = strictBigDecimalFragmentJson(component);
        String strictPatternText = new JsonObject(strictFragmentJson).getString("pattern");

        Throwable failure = assertMountFails(
                component.testMount(),
                new StrictRedirectResource(),
                RestConfigurationException.class,
                List.of(strictFragmentJson, strictPatternText));
        assertTrue(
                String.valueOf(failure.getMessage()).contains("amount"),
                "the diagnostic must name the offending property; was: " + failure.getMessage());

        VertiqueRedirectResource vertiqueResource = new VertiqueRedirectResource();
        int port = start(gateMount(RestTestContributions.none()), Set.of(vertiqueResource));

        assertEquals(
                200,
                post(port, "/vertique-redirect/payment", "{\"amount\":\"1.50\"}")
                        .statusCode(),
                "the redirect must still apply on a profile with no BigDecimal override: the property is a string");
        assertEquals(
                400,
                post(port, "/vertique-redirect/payment", "{\"amount\":1.5}").statusCode(),
                "the redirected property must be validated as a string, so a JSON number is rejected");
        assertEquals(1, vertiqueResource.invocations.get(), "only the accepted body may reach the resource");
    }

    // --- TP-006: a custom application profile ---

    /**
     * TP-006 (AC-011.1). A {@code money} profile contributed through the fixture's
     * {@code RestTestContributions.jsonMapperProfiles} builder — a mapper that deserializes
     * {@link Money} from a JSON string plus one {@code INPUT} schema-type override declaring that same
     * string shape — reaches the gate with no schema registry, no extra Dagger binding, and no
     * configuration key beyond the route's {@code @JsonProfile("money")}.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A custom application profile's override applies with no additional binding")
    void customProfileOverrideAppliesWithNoAdditionalBinding() throws Exception {
        MoneyResource resource = new MoneyResource();
        RestTestContributions contributions = RestTestContributions.builder()
                .addJsonMapperProfile(moneyProfile())
                .build();
        int port = start(gateMount(contributions), Set.of(resource));

        assertEquals(
                200,
                post(port, "/money/price", "{\"price\":\"12.00\"}").statusCode(),
                "the profile's own wire form — a JSON string — must be accepted, which requires the override to "
                        + "have reached the synthesized schema");
        assertEquals(1, resource.invocations.get(), "the accepted body must reach the resource exactly once");

        assertEquals(
                400,
                post(port, "/money/price", "{\"price\":{\"v\":1}}").statusCode(),
                "the object form the mapper cannot read must be rejected");
        assertEquals(1, resource.invocations.get(), "the rejected body must not reach the resource");
    }

    // --- Profile fixtures ---

    /**
     * Builds the {@code money} profile: a mapper that reads {@link Money} from a JSON string, and one
     * {@code INPUT} schema-type override declaring the matching {@code {"type":"string"}} shape.
     *
     * @return the {@code money} profile
     */
    private static JsonMapperProfile moneyProfile() {
        SimpleModule module = new SimpleModule("money-test");
        module.addDeserializer(Money.class, new MoneyDeserializer());
        ObjectMapper mapper = JsonMapper.builder()
                // Vert.x JSON support: the registry probes a contributed mapper with JsonObject.
                .addModule(VertxJsonSupport.module())
                .addModule(module)
                .build();
        return JsonMapperProfiles.of(
                JsonProfileId.of(MONEY_PROFILE_ID),
                mapper,
                List.of(JsonSchemaTypeOverride.input(Money.class, JsonSchemaFragment.parse("{\"type\":\"string\"}"))));
    }

    /**
     * Reads the {@code vertique-strict} profile's declared {@code BigDecimal} fragment through its
     * public path, so this test never restates the fragment's bounds.
     *
     * @param component the assembled mount graph whose registry hands out the built-in profiles
     * @return the strict {@code BigDecimal} fragment's canonical JSON
     */
    private static String strictBigDecimalFragmentJson(ValidationMountComponent component) {
        JsonMapperProfile strict = component.jsonMapperProfileRegistry().profile(JsonProfileId.of("vertique-strict"));
        return strict.jsonSchemaTypeOverrides().get(0).fragment().canonicalJson();
    }

    // --- Body and resource fixtures ---

    /** The narrowed body: the profile's overridden type plus the property's own tighter metadata. */
    public static class NarrowedPayment {

        /** A strict decimal further narrowed to exactly two decimal places and 20 characters. */
        @Schema(pattern = "^[0-9]+\\.[0-9]{2}$", maxLength = 20)
        public BigDecimal amount;
    }

    /** The redirected body: a {@code BigDecimal} declared to render as a {@code String}. */
    public static class RedirectPayment {

        /** A decimal whose schema is redirected to the {@code String} shape. */
        @Schema(implementation = String.class)
        public BigDecimal amount;
    }

    /** The custom-profile body: one {@link Money} property. */
    public static class MoneyBody {

        /** The money property, whose wire shape the {@code money} profile's override declares. */
        public Money price;
    }

    /**
     * An application value type carrying no Jackson or Swagger annotation that would itself imply a
     * string shape — the {@code money} profile's mapper and its override are the only reasons it is a
     * string on the wire. Its one declared property is what the unprofiled generator renders instead.
     */
    public static class Money {

        /** The parsed amount. */
        public BigDecimal v;
    }

    /** Reads {@link Money} from a JSON string and refuses every other token shape. */
    private static final class MoneyDeserializer extends JsonDeserializer<Money> {

        @Override
        public Money deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                throw MismatchedInputException.from(parser, Money.class, "a money value must be a JSON string");
            }
            String text = parser.getText();
            Money money = new Money();
            try {
                money.v = new BigDecimal(text);
            } catch (NumberFormatException notADecimal) {
                // Value-free: the rejected literal is request data and must not reach a diagnostic.
                throw MismatchedInputException.from(parser, Money.class, "a money value must be a decimal literal");
            }
            return money;
        }
    }

    /** Strict-profiled resource whose decimal property carries its own narrower metadata. */
    @Path("/narrowed")
    @JsonProfile("vertique-strict")
    public static class NarrowedResource {

        /** Counts terminal invocations, so "rejected before invocation" is observable. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound amount.
         *
         * @param payment the request body bean
         * @return the echoed amount
         */
        @POST
        @Path("/payment")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "narrowedStrictPaymentEcho")
        public String echo(NarrowedPayment payment) {
            invocations.incrementAndGet();
            return "amount=" + payment.amount;
        }
    }

    /** Strict-profiled resource whose decimal property carries the forbidden redirect. */
    @Path("/strict-redirect")
    @JsonProfile("vertique-strict")
    public static class StrictRedirectResource {

        /**
         * Never runs: the mount that carries this resource must fail at router construction.
         *
         * @param payment the request body bean
         * @return the echoed amount
         */
        @POST
        @Path("/payment")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictRedirectPaymentEcho")
        public String echo(RedirectPayment payment) {
            return "amount=" + payment.amount;
        }
    }

    /** Unannotated resource — the {@code vertique} floor — carrying the same redirect. */
    @Path("/vertique-redirect")
    public static class VertiqueRedirectResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound amount.
         *
         * @param payment the request body bean
         * @return the echoed amount
         */
        @POST
        @Path("/payment")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueRedirectPaymentEcho")
        public String echo(RedirectPayment payment) {
            invocations.incrementAndGet();
            return "amount=" + payment.amount;
        }
    }

    /** Resource selecting the contributed {@code money} profile. */
    @Path("/money")
    @JsonProfile(MONEY_PROFILE_ID)
    public static class MoneyResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound money value.
         *
         * @param body the request body bean
         * @return the echoed value
         */
        @POST
        @Path("/price")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "moneyPriceEcho")
        public String echo(MoneyBody body) {
            invocations.incrementAndGet();
            return "price=" + (body.price == null ? "" : body.price.v);
        }
    }

    // --- Mounts and helpers ---

    /**
     * Builds the assembled graph with the given configuration and contributions.
     *
     * @param config        the application configuration
     * @param contributions the additive test contributions
     * @return the assembled component
     */
    private ValidationMountComponent component(JsonObject config, RestTestContributions contributions) {
        return MountFixtures.component(vertx, config, contributions);
    }

    /**
     * Builds the default mount, whose {@code jaxrs.validationStrategy} stays at {@code web-validation}.
     *
     * @param contributions the additive test contributions
     * @return the gated mount handle
     */
    private RestTestMount gateMount(RestTestContributions contributions) {
        return MountFixtures.mount(vertx, new JsonObject(), contributions);
    }

    /**
     * Asserts that building a router over {@code resource} fails with {@code type} and that no
     * throwable in the failure's cause or suppressed chain discloses any of
     * {@code forbiddenSubstrings}.
     *
     * <p>Uses {@code RestTestMounts.router}, which creates the router without binding a server, so a
     * mount that fails here provably failed before any server bound.
     *
     * @param mount               the mount handle
     * @param resource            the single JAX-RS resource to mount
     * @param type                the expected failure type
     * @param forbiddenSubstrings text that must not appear in any message in the chain
     * @return the failure, for further case-specific assertions
     * @throws Exception when the router build neither succeeds nor fails within the budget
     */
    private Throwable assertMountFails(
            RestTestMount mount, Object resource, Class<? extends Throwable> type, List<String> forbiddenSubstrings)
            throws Exception {
        Throwable failure = awaitFailure(mount, Set.of(resource));
        assertInstanceOf(type, failure, "the mount must fail with " + type.getSimpleName());
        for (Throwable link : chainOf(failure)) {
            String message = String.valueOf(link.getMessage());
            for (String forbidden : forbiddenSubstrings) {
                assertFalse(
                        message.contains(forbidden),
                        "no message in the failure chain may disclose '" + forbidden + "'; "
                                + link.getClass().getName() + " said: " + message);
            }
        }
        return failure;
    }

    /**
     * Builds a router over the given resources and returns the failure it completed with.
     *
     * @param mount     the mount handle
     * @param resources the JAX-RS resources to mount
     * @return the failure the router build produced
     * @throws Exception when the build neither succeeds nor fails within the budget
     */
    private Throwable awaitFailure(RestTestMount mount, Set<Object> resources) throws Exception {
        try {
            RestTestMounts.router(vertx, mount, resources)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            assertNotNull(cause, "a failed router build must carry a cause");
            return cause;
        }
        return fail("the router build must not succeed");
    }

    /**
     * Collects a throwable and every throwable reachable through its cause and suppressed chains.
     *
     * @param root the failure to walk
     * @return every throwable in the chain, root first
     */
    private static List<Throwable> chainOf(Throwable root) {
        List<Throwable> chain = new ArrayList<>();
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (current == null || chain.contains(current)) {
                continue;
            }
            chain.add(current);
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            for (Throwable suppressed : current.getSuppressed()) {
                pending.add(suppressed);
            }
        }
        return chain;
    }

    /**
     * Starts a server for the given mount and resources and records it for teardown.
     *
     * @param mount     the mount handle
     * @param resources the JAX-RS resources to mount
     * @return the bound port
     */
    private int start(RestTestMount mount, Set<Object> resources) {
        HttpServer server = RestTestMounts.startServerBlocking(vertx, mount, resources, START_TIMEOUT);
        servers.add(server);
        return server.actualPort();
    }

    /**
     * POSTs {@code body} as {@code application/json} to {@code path} on the mount bound to
     * {@code port} and returns the aggregated response.
     *
     * @param port the mount's bound port
     * @param path the request path
     * @param body the raw request body
     * @return the aggregated response
     * @throws Exception when the round trip fails or times out
     */
    private HttpResponse<Buffer> post(int port, String path, String body) throws Exception {
        return client.post(port, LOOPBACK, path)
                .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                .sendBuffer(Buffer.buffer(body))
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
