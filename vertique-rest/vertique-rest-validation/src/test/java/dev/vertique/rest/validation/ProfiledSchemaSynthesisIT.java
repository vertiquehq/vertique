// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import dev.vertique.rest.validation.corpus.EnumDefaultValueDto;
import dev.vertique.rest.validation.corpus.GetterOnlyListDto;
import dev.vertique.rest.validation.corpus.GetterOnlyMapDto;
import dev.vertique.rest.validation.corpus.InstantPropertyDto;
import dev.vertique.rest.validation.corpus.LocalDatePropertyDto;
import dev.vertique.rest.validation.corpus.LombokBuilderDto;
import dev.vertique.rest.validation.corpus.NestedPrivateDateDto;
import dev.vertique.rest.validation.corpus.OptionalPropertyDto;
import dev.vertique.rest.validation.corpus.PrivateDatePropertyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * End-to-end proof that the body schema a {@code web-validation} route validates against is
 * synthesized through the route's <em>effective</em> JSON mapper profile (TP-001), and that the
 * property-model decisions the gate already makes under the built-in profiles are preserved
 * (TP-010).
 *
 * <p><strong>The two halves are deliberately at different baselines.</strong> TP-001 is a
 * behavior-change proof: at the task's baseline commit {@code {"amount":"1.50"}} receives a 400,
 * because the unprofiled generator still says {@code "type":"number"} for a {@code BigDecimal}.
 * TP-010 is a behavior-preservation proof and is green at that same commit; it is written before the
 * generator changes precisely so the "what did the binder alone decide?" baseline is recorded on
 * unchanged production code. The three TP-010 methods are therefore meaningful on their own and are
 * named individually for the pre-change run:
 *
 * <ul>
 *   <li>{@link #unknownEnumStringIsStillRejectedUnderVertique()}</li>
 *   <li>{@link #optionalInstantAndLocalDatePropertiesAreRejectedUnderTheGate()}</li>
 *   <li>{@link #bindersDecisionOnEachPropertyModelFixtureIsRecordedWithoutTheGate()}</li>
 * </ul>
 *
 * <p><strong>T004 TP-006 (FR-013) is a third half, at a third baseline.</strong>
 * {@link #restoredShapesRejectWrongTypesUnderTheGate()} is a behavior-change proof against T004's
 * parent commit, where four of its five rows answer 200 because the property is not described at all;
 * {@link #binderDecidesTheRestoredShapesWithoutTheGate()} is the gate-disabled characterization,
 * green at that commit and asserted rather than recorded, so the gated rejection cannot silently
 * become redundant.
 *
 * <p><strong>T007 TP-006 (FR-015) is a fourth half, at a fourth baseline.</strong>
 * {@link #anySetterExtrasAreTypedUnderTheGate()} is a behavior-change proof against T007's parent
 * commit, where every wrong-typed extra and every reserved name answers 200 because the any-setter
 * type's object is open; {@link #binderDecidesAnySetterBodiesWithoutTheGate()} is the gate-disabled
 * characterization, green at that commit and asserted rather than recorded. Its fixtures are declared
 * in this class rather than in the corpus package: no corpus fixture has an any-setter, and the pinned
 * corpus documents must not change.
 *
 * <p>Bodies are otherwise the frozen corpus fixtures from {@code dev.vertique.rest.validation.corpus},
 * so the subjects these HTTP proofs exercise are the very types the document corpus pins.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load {@code body()} can succeed with zero bytes while the status code
 * is correct (issue #167). The {@code none}-mount date case pairs its status with the echoed bound
 * value, so an emptied body would fail it for a reason unrelated to the path under test.
 */
@ExtendWith(VertxExtension.class)
// 60s rather than testing.md's 20s default: the heaviest method starts a mount and issues seven
// sequential round trips, and every method builds its own Dagger graph and router.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ProfiledSchemaSynthesisIT {

    private static final long ASYNC_TIMEOUT_SECONDS = 20;

    private static final Duration START_TIMEOUT = Duration.ofSeconds(20);

    private static final String LOOPBACK = "127.0.0.1";

    /**
     * A grammar-valid plain decimal one character past the strict deserializer's 100-character bound
     * ({@code BigDecimalStrictStringDeserializer.MAX_LENGTH}, which is package-private in
     * {@code dev.vertique.json} and therefore not readable from here; the bound itself is pinned to
     * that constant by {@code VertiqueStrictProfileOverrideTest}).
     */
    private static final String OVER_LENGTH_DECIMAL = "1".repeat(99) + ".5";

    // --- Fixture state ---

    private Vertx vertx;

    private WebClient client;

    private final List<HttpServer> servers = new ArrayList<>();

    /** Whether the test that just ran installed a mapper as the process JSON codec. */
    private boolean processCodecInstalled;

    /**
     * Captures the per-test Vert.x instance and creates the shared {@link WebClient}. The client is
     * bound to a field so {@link #tearDown()} can close it; an unbound client can never be closed at
     * all.
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
        if (processCodecInstalled) {
            // The process codec is global state: a test that installed one must restore it, or every
            // later test in this JVM decodes through a mapper it never chose. The module's Failsafe
            // configuration already arms the reset seam.
            VertiqueJson.resetForTests();
            processCodecInstalled = false;
        }
    }

    // --- TP-001: the strict decimal body form ---

    /**
     * TP-001 (AC-009.1). A {@code vertique-strict} route under {@code web-validation} accepts the
     * decimal <em>string</em> its profile actually parses and rejects every other form before the
     * resource runs.
     *
     * <p>Each rejected form is rejected for its own reason: a JSON number contradicts the profile
     * fragment's {@code "type":"string"}; the exponent, the comma, and the trailing line terminators
     * contradict its anchored plain-decimal grammar (Java's {@code Matcher.matches} requires a full
     * match, so a trailing {@code \n} does not satisfy the trailing {@code $}); the 101-character
     * literal exceeds its {@code maxLength}. The single invocation counter carries the
     * "rejected before invocation" half for all six.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A vertique-strict body accepts its decimal string form and rejects the other six before invocation")
    void strictProfileAcceptsDecimalStringAndRejectsOtherForms() throws Exception {
        PaymentResource resource = new PaymentResource();
        int port = start(gateMount(), Set.of(resource));

        assertEquals(
                200,
                post(port, "/strict/payment", "{\"amount\":\"1.50\"}").statusCode(),
                "the strict profile's own wire form — a plain decimal string — must be accepted");
        assertEquals(1, resource.invocations.get(), "the accepted body must reach the resource exactly once");

        assertEquals(
                400,
                post(port, "/strict/payment", "{\"amount\":1.5}").statusCode(),
                "a JSON number must be rejected: the strict profile's BigDecimal wire shape is a string");
        assertEquals(
                400,
                post(port, "/strict/payment", "{\"amount\":\"1e3\"}").statusCode(),
                "an exponent string must be rejected by the anchored plain-decimal grammar");
        assertEquals(
                400,
                post(port, "/strict/payment", "{\"amount\":\"" + OVER_LENGTH_DECIMAL + "\"}")
                        .statusCode(),
                "a 101-character grammar-valid decimal string must be rejected by the fragment's maxLength");
        assertEquals(
                400,
                post(port, "/strict/payment", "{\"amount\":\"1,5\"}").statusCode(),
                "a comma-separated string must be rejected by the anchored plain-decimal grammar");
        assertEquals(
                400,
                post(port, "/strict/payment", "{\"amount\":\"1.50\\n\"}").statusCode(),
                "a string with a trailing line feed must be rejected, not silently trimmed");
        assertEquals(
                400,
                post(port, "/strict/payment", "{\"amount\":\"1.50\\r\\n\"}").statusCode(),
                "a string with a trailing CRLF must be rejected, not silently trimmed");

        assertEquals(1, resource.invocations.get(), "no rejected body may reach the resource");
    }

    // --- TP-010: enum protection and the property-model negatives ---

    /**
     * TP-010 (AC-010.2). The gate's {@code enum} keyword still refuses an unrecognized constant on a
     * route whose effective profile is the {@code vertique} floor, even though {@link
     * dev.vertique.rest.validation.corpus.CorpusStatus#UNKNOWN} carries {@code @JsonEnumDefaultValue}
     * — the protection the {@code 0.2.0} migration note §8 documents.
     *
     * <p>Behavior-preservation: green at the task's baseline commit and green after it. A profiled
     * document that dropped the {@code enum} keyword would fail here, and the corpus delta list would
     * show that drop as a loosening entry.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("An unknown enum string is still rejected under the vertique floor profile")
    void unknownEnumStringIsStillRejectedUnderVertique() throws Exception {
        VertiqueModelResource vertiqueResource = new VertiqueModelResource();
        SystemModelResource systemResource = new SystemModelResource();
        int gatePort = start(gateMount(), Set.of(vertiqueResource, systemResource));

        assertEquals(
                400,
                post(gatePort, "/vertique/enum", "{\"status\":\"NOT_A_CONSTANT\"}")
                        .statusCode(),
                "an unrecognized enum string must stay rejected: @JsonEnumDefaultValue must not become "
                        + "a silent accept");
        assertEquals(
                0,
                vertiqueResource.invocations.get(),
                "the gate must reject the unknown enum string before the resource runs");
    }

    /**
     * TP-010 (AC-010.3, the gated half). Under {@code web-validation} the gate rejects a JSON object
     * for an {@code Optional<String>}, a boolean for an {@code Instant}, and a number for a
     * {@code LocalDate} — on both built-in profiles, the unannotated {@code vertique} floor and an
     * explicitly selected {@code system} — and no request reaches a resource.
     *
     * <p>Behavior-preservation: green at the task's baseline commit and green after it.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("Optional, Instant and LocalDate property negatives are rejected by the gate under both profiles")
    void optionalInstantAndLocalDatePropertiesAreRejectedUnderTheGate() throws Exception {
        VertiqueModelResource vertiqueResource = new VertiqueModelResource();
        SystemModelResource systemResource = new SystemModelResource();
        int gatePort = start(gateMount(), Set.of(vertiqueResource, systemResource));

        assertEquals(
                400,
                post(gatePort, "/vertique/optional", OPTIONAL_OBJECT_BODY).statusCode(),
                "a JSON object for an Optional<String> must be rejected under the vertique floor");
        assertEquals(
                400,
                post(gatePort, "/system/optional", OPTIONAL_OBJECT_BODY).statusCode(),
                "a JSON object for an Optional<String> must be rejected under system");
        assertEquals(
                400,
                post(gatePort, "/vertique/instant", INSTANT_BOOLEAN_BODY).statusCode(),
                "a boolean for an Instant must be rejected under the vertique floor");
        assertEquals(
                400,
                post(gatePort, "/system/instant", INSTANT_BOOLEAN_BODY).statusCode(),
                "a boolean for an Instant must be rejected under system");
        assertEquals(
                400,
                post(gatePort, "/vertique/date", LOCAL_DATE_NUMBER_BODY).statusCode(),
                "a number for a LocalDate must be rejected under the vertique floor");
        assertEquals(
                400,
                post(gatePort, "/system/date", LOCAL_DATE_NUMBER_BODY).statusCode(),
                "a number for a LocalDate must be rejected under system");

        assertEquals(
                0, vertiqueResource.invocations.get(), "the gate must reject every negative before the resource runs");
        assertEquals(
                0, systemResource.invocations.get(), "the gate must reject every negative before the resource runs");
    }

    /**
     * TP-010 (AC-010.3, the gate-disabled half, and the binder evidence AC-002.2 accepts).
     *
     * <p>The same two resources are mounted on a fixture configured with
     * {@code jaxrs.validationStrategy: none}, where {@link NoneValidationStrategy} installs no gate
     * and the profile's Jackson binder is the only component that can refuse a body. The recorded
     * outcomes, each row naming the deciding component:
     *
     * <table>
     *   <caption>Binder-only decisions on the property-model fixtures</caption>
     *   <tr><th>Body</th><th>Outcome</th><th>Decided by</th></tr>
     *   <tr><td>{@code {"note":{...}}} into {@code Optional<String>}</td><td>400</td>
     *       <td>binder — Jackson {@code MismatchedInputException}</td></tr>
     *   <tr><td>{@code {"occurredAt":true}} into {@code Instant}</td><td>400</td>
     *       <td>binder — Jackson {@code InvalidFormatException}</td></tr>
     *   <tr><td>{@code {"due":123}} into {@code LocalDate}</td><td>200, bound to {@code 1970-05-04}</td>
     *       <td>nobody — the binder reads 123 as an epoch day; the gate is this position's only
     *       defense</td></tr>
     * </table>
     *
     * <p>This is the only evidence that can ever justify a loosening corpus delta, and it cannot be
     * reconstructed once generation changes, which is why it is recorded here at the baseline commit.
     * It is falsifiable in both directions: a mount that silently kept a gate would fail the
     * {@code LocalDate} row, and a binder that started rejecting epoch-day numbers would fail it too
     * and must be re-recorded rather than relaxed. A 400 from the {@code web-validation} mount is
     * never binder evidence.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("Without the gate, the binder alone decides each property-model fixture and its verdict is recorded")
    void bindersDecisionOnEachPropertyModelFixtureIsRecordedWithoutTheGate() throws Exception {
        VertiqueModelResource vertiqueResource = new VertiqueModelResource();
        SystemModelResource systemResource = new SystemModelResource();
        int nonePort = start(noGateMount(), Set.of(vertiqueResource, systemResource));

        assertEquals(
                400,
                post(nonePort, "/vertique/optional", OPTIONAL_OBJECT_BODY).statusCode(),
                "with no gate, the binder itself must reject a JSON object for an Optional<String> (vertique)");
        assertEquals(
                400,
                post(nonePort, "/system/optional", OPTIONAL_OBJECT_BODY).statusCode(),
                "with no gate, the binder itself must reject a JSON object for an Optional<String> (system)");
        assertEquals(
                400,
                post(nonePort, "/vertique/instant", INSTANT_BOOLEAN_BODY).statusCode(),
                "with no gate, the binder itself must reject a boolean for an Instant (vertique)");
        assertEquals(
                400,
                post(nonePort, "/system/instant", INSTANT_BOOLEAN_BODY).statusCode(),
                "with no gate, the binder itself must reject a boolean for an Instant (system)");
        assertEquals(
                0,
                vertiqueResource.invocations.get(),
                "a body the binder refuses must not reach the resource, gate or no gate");
        assertEquals(
                0,
                systemResource.invocations.get(),
                "a body the binder refuses must not reach the resource, gate or no gate");

        HttpResponse<Buffer> vertiqueDate = post(nonePort, "/vertique/date", LOCAL_DATE_NUMBER_BODY);
        assertEquals(
                200,
                vertiqueDate.statusCode(),
                "with no gate, the binder accepts a number for a LocalDate (vertique) — the gate is this "
                        + "position's only defense");
        assertEquals(
                "due=1970-05-04",
                vertiqueDate.bodyAsString(),
                "the binder reads 123 as an epoch day; a changed reading must be re-recorded, not relaxed");

        HttpResponse<Buffer> systemDate = post(nonePort, "/system/date", LOCAL_DATE_NUMBER_BODY);
        assertEquals(
                200,
                systemDate.statusCode(),
                "with no gate, the binder accepts a number for a LocalDate (system) — the gate is this "
                        + "position's only defense");
        assertEquals(
                "due=1970-05-04",
                systemDate.bodyAsString(),
                "the binder reads 123 as an epoch day; a changed reading must be re-recorded, not relaxed");

        assertEquals(1, vertiqueResource.invocations.get(), "only the LocalDate body may have reached the resource");
        assertEquals(1, systemResource.invocations.get(), "only the LocalDate body may have reached the resource");
    }

    // --- T004 TP-006: the restored input shapes, gate versus binder ---

    /**
     * T004 TP-006 (AC-013.4, the gated half). A {@code vertique} route under {@code web-validation}
     * rejects a value the binder would coerce at every value position FR-013 restores, and no request
     * reaches the resource.
     *
     * <p>Behavior-change: at T004's parent commit the date, integer, list, and nested rows are 200,
     * because the property is not described at all and the gate has nothing to check. The map row is
     * a green characterization at that commit — its array is refused by the binder, not by the gate —
     * so this method's value is the other four rows; the map property's description is proven by the
     * corpus (TP-005) and by the unit proof (TP-001), never by this status code.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("The gate rejects a coercible value at every restored value position")
    void restoredShapesRejectWrongTypesUnderTheGate() throws Exception {
        RestoredShapeResource resource = new RestoredShapeResource();
        int gatePort = start(gateMount(), Set.of(resource));

        int date = post(gatePort, "/restored/date", RESTORED_DATE_NUMBER_BODY).statusCode();
        int builder = post(gatePort, "/restored/builder", RESTORED_BUILDER_STRING_BODY)
                .statusCode();
        int list = post(gatePort, "/restored/list", RESTORED_LIST_NUMBERS_BODY).statusCode();
        int nested =
                post(gatePort, "/restored/nested", RESTORED_NESTED_NUMBER_BODY).statusCode();
        int map = post(gatePort, "/restored/map", RESTORED_MAP_ARRAY_BODY).statusCode();

        assertAll(
                () -> assertEquals(400, date, "the gate must reject a number for the private field's LocalDate"),
                () -> assertEquals(400, builder, "the gate must reject a numeric string for the builder's Integer"),
                () -> assertEquals(400, list, "the gate must reject numeric items for the getter-only List<String>"),
                () -> assertEquals(400, nested, "the gate must reject a number for the nested type's LocalDate"),
                () -> assertEquals(400, map, "an array for the getter-only Map must be rejected"),
                () -> assertEquals(
                        0, resource.invocations.get(), "no rejected body may reach the resource under the gate"));
    }

    /**
     * T004 TP-006 (AC-013.4, the gate-disabled half). On the {@code jaxrs.validationStrategy: none}
     * mount the profile's Jackson binder is the only component that can refuse a body, and each
     * outcome is asserted rather than merely recorded, so the gated rejection above cannot silently
     * become redundant:
     *
     * <table>
     *   <caption>Binder-only decisions on the restored shapes</caption>
     *   <tr><th>Body</th><th>Outcome</th><th>Decided by</th></tr>
     *   <tr><td>{@code {"due":19000}} into a private {@code LocalDate}</td>
     *       <td>200, bound to {@code 2022-01-08}</td>
     *       <td>nobody — the binder reads 19000 as an epoch day</td></tr>
     *   <tr><td>{@code {"quantity":"2"}} into the builder's {@code Integer}</td><td>200, bound to {@code 2}</td>
     *       <td>nobody — the binder coerces the numeric string</td></tr>
     *   <tr><td>{@code {"tags":[1,2]}} into {@code List<String>}</td><td>200, bound to {@code ["1","2"]}</td>
     *       <td>nobody — the binder coerces the numeric items</td></tr>
     *   <tr><td>{@code {"detail":{"due":19000}}} into the nested shape</td>
     *       <td>200, bound to {@code 2022-01-08}</td><td>nobody</td></tr>
     *   <tr><td>{@code {"labels":["x"]}} into {@code Map<String, String>}</td><td>400</td>
     *       <td>binder — Jackson {@code MismatchedInputException}</td></tr>
     * </table>
     *
     * <p>A binder that stopped coercing fails this method and must be re-recorded, not relaxed. A 400
     * from the {@code web-validation} mount is never binder evidence.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("Without the gate, the binder coerces every restored position and refuses only the map's array")
    void binderDecidesTheRestoredShapesWithoutTheGate() throws Exception {
        RestoredShapeResource resource = new RestoredShapeResource();
        int nonePort = start(noGateMount(), Set.of(resource));

        HttpResponse<Buffer> date = post(nonePort, "/restored/date", RESTORED_DATE_NUMBER_BODY);
        assertEquals(200, date.statusCode(), "with no gate, the binder accepts a number for the private LocalDate");
        assertEquals("due=2022-01-08", date.bodyAsString(), "the binder reads 19000 as an epoch day");

        HttpResponse<Buffer> builder = post(nonePort, "/restored/builder", RESTORED_BUILDER_STRING_BODY);
        assertEquals(200, builder.statusCode(), "with no gate, the binder accepts a numeric string for an Integer");
        assertEquals("quantity=2", builder.bodyAsString(), "the binder coerces \"2\" to 2 through the builder");

        HttpResponse<Buffer> list = post(nonePort, "/restored/list", RESTORED_LIST_NUMBERS_BODY);
        assertEquals(200, list.statusCode(), "with no gate, the binder accepts numeric items for a List<String>");
        assertEquals("tags=1|2", list.bodyAsString(), "the binder coerces the numeric items to strings");

        HttpResponse<Buffer> nested = post(nonePort, "/restored/nested", RESTORED_NESTED_NUMBER_BODY);
        assertEquals(200, nested.statusCode(), "with no gate, the binder accepts a number at the nested position");
        assertEquals("detail.due=2022-01-08", nested.bodyAsString(), "the nested binder reads 19000 as an epoch day");

        assertEquals(
                400,
                post(nonePort, "/restored/map", RESTORED_MAP_ARRAY_BODY).statusCode(),
                "with no gate, the " + "binder itself must reject an array for a Map<String, String>");

        assertEquals(4, resource.invocations.get(), "only the four coerced bodies may have reached the resource");
    }

    // --- T007 TP-006: any-setter extras and reserved names, gate versus binder ---

    /**
     * T007 TP-006 (AC-015.6, the gated half). A {@code vertique} route under {@code web-validation}
     * types an any-setter's extra keys and refuses every name the type binds but never publishes, and
     * no rejected body reaches the resource.
     *
     * <p>Three shapes carry the distinct risk at the gate: a {@code String} any-setter beside a named
     * property, a {@code LocalDate} any-setter whose extra a number would silently become a date, and
     * the reserved-name type with a read-only {@code role}, an ignored {@code id}, and a method
     * {@code @JsonAnyGetter} over the storage field {@code extras}. The class-level
     * {@code additionalProperties = FALSE} type and the map subclass are proven at document level by
     * {@code AnySetterDescriptionTest}, which is where their risk lies.
     *
     * <p>Behavior-change: at T007's parent commit every row but the two valid ones answers 200,
     * because the object is open — an extra key is never checked at all, and a name the document does
     * not publish is indistinguishable from an extra key. The two valid rows are green
     * characterizations at that commit: describing extras must not start rejecting legal traffic.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("The gate types an any-setter's extras and rejects every reserved name")
    void anySetterExtrasAreTypedUnderTheGate() throws Exception {
        AnySetterResource resource = new AnySetterResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> validString = post(gatePort, "/anysetter/string", ANY_SETTER_VALID_STRING_BODY);
        HttpResponse<Buffer> validDate = post(gatePort, "/anysetter/date", ANY_SETTER_VALID_DATE_BODY);
        int wrongTypedExtra = post(gatePort, "/anysetter/string", ANY_SETTER_NUMBER_EXTRA_BODY)
                .statusCode();
        int wrongTypedDateExtra =
                post(gatePort, "/anysetter/date", ANY_SETTER_NUMBER_DATE_BODY).statusCode();
        int readOnlyName =
                post(gatePort, "/anysetter/reserved", ANY_SETTER_ROLE_BODY).statusCode();
        int ignoredName =
                post(gatePort, "/anysetter/reserved", ANY_SETTER_ID_BODY).statusCode();
        int storageName =
                post(gatePort, "/anysetter/reserved", ANY_SETTER_STORAGE_BODY).statusCode();

        assertAll(
                () -> assertEquals(200, validString.statusCode(), "a valid string extra must still be accepted"),
                () -> assertEquals(
                        "name=a extras=x=y:String",
                        validString.bodyAsString(),
                        "the accepted extra must reach the resource intact, under its own key"),
                () -> assertEquals(200, validDate.statusCode(), "a valid date extra must still be accepted"),
                () -> assertEquals(
                        "extras=x=2022-01-08:LocalDate",
                        validDate.bodyAsString(),
                        "the accepted date extra must reach the resource intact"),
                () -> assertEquals(
                        400,
                        wrongTypedExtra,
                        "the gate must reject a number where the any-setter declares String values: the binder"
                                + " would coerce it to the string \"5\""),
                () -> assertEquals(
                        400,
                        wrongTypedDateExtra,
                        "the gate must reject a bare number for a LocalDate extra: the binder would read it as"
                                + " an epoch day"),
                () -> assertEquals(
                        400,
                        readOnlyName,
                        "the gate must reject the read-only name 'role': it is bound on input, never published,"
                                + " and lands in the extras map"),
                () -> assertEquals(400, ignoredName, "the gate must reject the ignored name 'id' for the same reason"),
                () -> assertEquals(
                        400,
                        storageName,
                        "the gate must reject the any-getter's storage name 'extras': Jackson fills that map"
                                + " through its getter, so {\"extras\":{\"role\":\"admin\"}} sets a read-only"
                                + " name (design proof v4, SG1)"),
                () -> assertEquals(
                        2, resource.invocations.get(), "only the two valid bodies may have reached the resource"));
    }

    /**
     * T007 TP-006 (AC-015.6, the gate-disabled half). On the {@code jaxrs.validationStrategy: none}
     * mount the profile's Jackson binder is the only component that can refuse a body. Every outcome
     * is asserted rather than merely recorded, so the gated rejections above cannot silently become
     * redundant:
     *
     * <table>
     *   <caption>Binder-only decisions on the any-setter shapes</caption>
     *   <tr><th>Body</th><th>Outcome</th><th>Decided by</th></tr>
     *   <tr><td>{@code {"x":5}} into a {@code String} any-setter</td>
     *       <td>200, stored as the string {@code "5"}</td>
     *       <td>nobody — the binder coerces the number</td></tr>
     *   <tr><td>{@code {"x":19000}} into a {@code LocalDate} any-setter</td>
     *       <td>200, bound to {@code 2022-01-08}</td>
     *       <td>nobody — the binder reads 19000 as an epoch day</td></tr>
     *   <tr><td>{@code {"role":"admin"}} on the reserved-name type</td>
     *       <td>200, stored in the extras map</td>
     *       <td>nobody — a read-only name is routed to the any-setter</td></tr>
     *   <tr><td>{@code {"id":"forged"}} on the reserved-name type</td>
     *       <td>200, stored in the extras map</td>
     *       <td>nobody — an ignored name is routed to the any-setter</td></tr>
     *   <tr><td>{@code {"extras":{"role":"admin"}}} on the reserved-name type</td>
     *       <td>200, the any-getter's storage filled through its getter</td>
     *       <td>nobody — the key names a real member, not an extra</td></tr>
     * </table>
     *
     * <p>A binder that stopped coercing or routing fails this method and must be re-recorded, not
     * relaxed. A 400 from the {@code web-validation} mount is never binder evidence.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("Without the gate, the binder coerces every any-setter extra and routes every reserved name")
    void binderDecidesAnySetterBodiesWithoutTheGate() throws Exception {
        AnySetterResource resource = new AnySetterResource();
        int nonePort = start(noGateMount(), Set.of(resource));

        HttpResponse<Buffer> numberExtra = post(nonePort, "/anysetter/string", ANY_SETTER_NUMBER_EXTRA_BODY);
        assertEquals(200, numberExtra.statusCode(), "with no gate, the binder accepts a number for a String extra");
        assertEquals(
                "name=null extras=x=5:String",
                numberExtra.bodyAsString(),
                "the binder stores 5 as the string \"5\": the extras map is the any-setter's declared value type");

        HttpResponse<Buffer> dateExtra = post(nonePort, "/anysetter/date", ANY_SETTER_NUMBER_DATE_BODY);
        assertEquals(200, dateExtra.statusCode(), "with no gate, the binder accepts a number for a LocalDate extra");
        assertEquals(
                "extras=x=2022-01-08:LocalDate",
                dateExtra.bodyAsString(),
                "the binder reads 19000 as an epoch day at the extras position too");

        HttpResponse<Buffer> readOnlyName = post(nonePort, "/anysetter/reserved", ANY_SETTER_ROLE_BODY);
        assertEquals(200, readOnlyName.statusCode(), "with no gate, the binder accepts the read-only name 'role'");
        assertEquals(
                "extras={role=admin}",
                readOnlyName.bodyAsString(),
                "a read-only name is not rejected by the binder: it lands in the extras map");

        HttpResponse<Buffer> ignoredName = post(nonePort, "/anysetter/reserved", ANY_SETTER_ID_BODY);
        assertEquals(200, ignoredName.statusCode(), "with no gate, the binder accepts the ignored name 'id'");
        assertEquals(
                "extras={id=forged}",
                ignoredName.bodyAsString(),
                "an ignored name is not rejected by the binder either: it lands in the extras map");

        HttpResponse<Buffer> storageName = post(nonePort, "/anysetter/reserved", ANY_SETTER_STORAGE_BODY);
        assertEquals(200, storageName.statusCode(), "with no gate, the binder accepts the any-getter's storage name");
        assertEquals(
                "extras={role=admin}",
                storageName.bodyAsString(),
                "the binder fills the any-getter's storage through its getter, so the body sets a read-only"
                        + " name inside the map the response echoes");

        assertEquals(5, resource.invocations.get(), "every body must have reached the resource without the gate");
    }

    // --- T008 TP-004 and TP-006: alias spellings and repeated keys, gate versus binder ---

    /**
     * One gated row: a path, a body, the status the gate must answer, and why.
     *
     * @param path     the request path, which selects both the profile and the body type
     * @param body     the raw request body
     * @param expected the expected status code
     * @param why      the reason, for the failure message
     */
    private record AliasCase(String path, String body, int expected, String why) {}

    /**
     * Builds the sixteen gated rows for one profile's alias resource.
     *
     * @param prefix         the resource path prefix, which selects the profile
     * @param bothSpellings  the status both spellings of one property must receive: 200 under a
     *                       lenient profile, 400 under a strict one
     * @return the rows, in request order
     */
    private static List<AliasCase> aliasCases(String prefix, int bothSpellings) {
        String strictness = bothSpellings == 400 ? "strict" : "lenient";
        return List.of(
                new AliasCase(prefix + "/optional", "{\"quantity\":5}", 200, "the canonical spelling is accepted"),
                new AliasCase(prefix + "/optional", "{\"qty\":5}", 200, "the alias alone is accepted and validated"),
                new AliasCase(
                        prefix + "/optional",
                        "{\"quantity\":5,\"qty\":5}",
                        bothSpellings,
                        "both spellings of one optional property under a " + strictness + " profile"),
                new AliasCase(
                        prefix + "/optional", "{\"note\":\"n\"}", 200, "neither spelling, for an optional property"),
                new AliasCase(
                        prefix + "/optional",
                        "{\"qty\":11}",
                        400,
                        "a constraint violation under the alias: without the alias description the gate never"
                                + " checks the value and @Max(10) is bypassed (security round 7, N1)"),
                new AliasCase(
                        prefix + "/optional",
                        "{\"qty\":5,\"note\":\"n\"}",
                        200,
                        "an unrelated valid field beside the alias"),
                new AliasCase(prefix + "/required", "{\"quantity\":5}", 200, "the canonical spelling is accepted"),
                new AliasCase(
                        prefix + "/required",
                        "{\"qty\":5}",
                        200,
                        "the alias alone satisfies a required property: the rule replaces the top-level required"
                                + " entry, which would otherwise reject this legal body"),
                new AliasCase(
                        prefix + "/required",
                        "{\"quantity\":5,\"qty\":5}",
                        bothSpellings,
                        "both spellings of one required property under a " + strictness + " profile"),
                new AliasCase(
                        prefix + "/required",
                        "{\"note\":\"n\"}",
                        400,
                        "neither spelling, for a required property: the rule must still require one"),
                new AliasCase(prefix + "/twoalias", "{\"nm\":\"abc\"}", 200, "the first of two spellings"),
                new AliasCase(prefix + "/twoalias", "{\"nm2\":\"abc\"}", 200, "the second of two spellings"),
                new AliasCase(
                        prefix + "/twoalias",
                        "{\"name\":\"abc\",\"nm\":\"abc\"}",
                        bothSpellings,
                        "a spelling beside the property's own name under a " + strictness + " profile"),
                new AliasCase(
                        prefix + "/anysetter",
                        "{\"qty\":5,\"x\":\"1\"}",
                        200,
                        "valid extras beside the alias on an any-setter type: the spelling is published, so it is"
                                + " released from the reserved set rather than rejected as a reserved name"),
                new AliasCase(prefix + "/enum", "{\"r\":\"USER\"}", 200, "a known constant under the enum's alias"),
                new AliasCase(
                        prefix + "/enum",
                        "{\"r\":\"ADMIN\"}",
                        400,
                        "an unknown constant under the enum's alias: under vertique and vertique-strict the"
                                + " binder falls back to the @JsonEnumDefaultValue constant, so only the described"
                                + " alias lets the gate's enum keyword refuse it"));
    }

    /**
     * T008 TP-004 (AC-016.4, the gated half). Under {@code web-validation} every published alias
     * spelling is validated against its property's own constraints, on {@code system},
     * {@code vertique}, and {@code vertique-strict} routes.
     *
     * <table>
     *   <caption>The alias decision table, per profile</caption>
     *   <tr><th>Body</th><th>system</th><th>vertique</th><th>vertique-strict</th></tr>
     *   <tr><td>{@code {"quantity":5}}</td><td>200</td><td>200</td><td>200</td></tr>
     *   <tr><td>{@code {"qty":5}}</td><td>200</td><td>200</td><td>200</td></tr>
     *   <tr><td>{@code {"quantity":5,"qty":5}}</td><td>200</td><td>200</td><td>400</td></tr>
     *   <tr><td>{@code {"note":"n"}}, optional</td><td>200</td><td>200</td><td>200</td></tr>
     *   <tr><td>{@code {"note":"n"}}, required</td><td>400</td><td>400</td><td>400</td></tr>
     *   <tr><td>{@code {"qty":11}}</td><td>400</td><td>400</td><td>400</td></tr>
     *   <tr><td>{@code {"qty":5,"note":"n"}}</td><td>200</td><td>200</td><td>200</td></tr>
     *   <tr><td>{@code {"qty":5,"x":"1"}}, any-setter</td><td>200</td><td>200</td><td>200</td></tr>
     *   <tr><td>{@code {"r":"ADMIN"}}</td><td>400</td><td>400</td><td>400</td></tr>
     * </table>
     *
     * <p>Behavior-change at T008's parent commit, which describes no alias: {@code {"qty":11}} is
     * accepted on the optional types, {@code {"qty":5}} is rejected on the required one as a missing
     * {@code quantity}, and both spellings are accepted under {@code vertique-strict}. The
     * {@code {"r":"ADMIN"}} row is split by the parser, not by the gate: {@code system} applies
     * {@code JacksonDefaults.applySystem} only and does not enable
     * {@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE}, so its binder already refuses the unknown
     * constant and the route answers 400 without any gate; {@code vertique} and
     * {@code vertique-strict} bind it to the {@code @JsonEnumDefaultValue} constant, so that row is
     * green at the parent under {@code system} and red under the other two.
     *
     * <p>Every row is posted before any assertion runs, so one wrong status cannot hide the other
     * forty-seven or the invocation counts.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("The gate validates every alias spelling on system, vertique and vertique-strict routes")
    void aliasSpellingsAreValidatedUnderTheGate() throws Exception {
        SystemAliasResource system = new SystemAliasResource();
        VertiqueAliasResource vertique = new VertiqueAliasResource();
        StrictAliasResource strict = new StrictAliasResource();
        int gatePort = start(gateMount(), Set.of(system, vertique, strict));

        List<AliasCase> cases = new ArrayList<>();
        cases.addAll(aliasCases("/system-alias", 200));
        cases.addAll(aliasCases("/vertique-alias", 200));
        cases.addAll(aliasCases("/strict-alias", 400));

        List<Integer> observed = new ArrayList<>();
        for (AliasCase gated : cases) {
            observed.add(post(gatePort, gated.path(), gated.body()).statusCode());
        }

        List<Executable> checks = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            AliasCase gated = cases.get(index);
            int status = observed.get(index);
            checks.add(() -> assertEquals(
                    gated.expected(), status, "POST " + gated.path() + " " + gated.body() + ": " + gated.why()));
        }
        // 13 of the 16 rows are accepted under a lenient profile and 10 under vertique-strict, which
        // is the "rejected before invocation" half of every 400 above.
        checks.add(() -> assertEquals(
                13, system.invocations.get(), "no body the system route rejected may have reached the resource"));
        checks.add(() -> assertEquals(
                13, vertique.invocations.get(), "no body the vertique route rejected may have reached the resource"));
        checks.add(() -> assertEquals(
                10,
                strict.invocations.get(),
                "no body the vertique-strict route rejected may have reached the resource"));
        assertAll(checks);
    }

    /**
     * T008 TP-004 (AC-016.4, the gate-disabled half). On the {@code jaxrs.validationStrategy: none}
     * mount the profile's Jackson binder is the only component that can refuse a body, and it accepts
     * every spelling under every profile — which is what makes the strict schema rule above stricter
     * than the binder rather than redundant with it.
     *
     * <table>
     *   <caption>Binder-only decisions on the alias shapes</caption>
     *   <tr><th>Body</th><th>Outcome</th><th>Decided by</th></tr>
     *   <tr><td>{@code {"qty":5}}</td><td>200, {@code quantity} bound to 5</td>
     *       <td>nobody — Jackson binds the alias into its property</td></tr>
     *   <tr><td>{@code {"quantity":5,"qty":5}}</td><td>200 under every profile</td>
     *       <td>nobody — two different key names are not a repeated key</td></tr>
     *   <tr><td>{@code {"qty":11}}</td><td>200, bound past {@code @Max(10)}</td>
     *       <td>nobody — Bean Validation does not run here</td></tr>
     *   <tr><td>{@code {"r":"ADMIN"}}</td><td>400 under {@code system}, 200 elsewhere</td>
     *       <td>the {@code system} mapper alone, which has no unknown-enum default</td></tr>
     * </table>
     *
     * <p>A binder that started refusing several spellings fails this method and must be re-recorded,
     * not relaxed. A 400 from the {@code web-validation} mount is never binder evidence.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("Without the gate, the binder accepts every spelling under every profile")
    void binderAcceptsEverySpellingWithoutTheGate() throws Exception {
        SystemAliasResource system = new SystemAliasResource();
        VertiqueAliasResource vertique = new VertiqueAliasResource();
        StrictAliasResource strict = new StrictAliasResource();
        int nonePort = start(noGateMount(), Set.of(system, vertique, strict));

        List<Executable> checks = new ArrayList<>();
        for (String prefix : List.of("/system-alias", "/vertique-alias", "/strict-alias")) {
            HttpResponse<Buffer> aliasOnly = post(nonePort, prefix + "/optional", "{\"qty\":5}");
            HttpResponse<Buffer> bothSpellings = post(nonePort, prefix + "/optional", "{\"quantity\":5,\"qty\":5}");
            HttpResponse<Buffer> violation = post(nonePort, prefix + "/optional", "{\"qty\":11}");
            checks.add(() -> assertEquals(
                    200, aliasOnly.statusCode(), prefix + ": with no gate, the binder accepts the alias alone"));
            checks.add(() -> assertEquals(
                    "quantity=5 note=null",
                    aliasOnly.bodyAsString(),
                    prefix + ": the binder must bind 'quantity' from the alias spelling 'qty'"));
            checks.add(() -> assertEquals(
                    200,
                    bothSpellings.statusCode(),
                    prefix + ": the binder accepts both spellings of one property under every profile, so the"
                            + " one-spelling rule is the schema's alone"));
            checks.add(() -> assertEquals(
                    200,
                    violation.statusCode(),
                    prefix + ": the binder applies no Bean Validation constraint, so {\"qty\":11} is bound;"
                            + " the gate is the only component that refuses it"));
        }

        HttpResponse<Buffer> systemEnum = post(nonePort, "/system-alias/enum", "{\"r\":\"ADMIN\"}");
        HttpResponse<Buffer> vertiqueEnum = post(nonePort, "/vertique-alias/enum", "{\"r\":\"ADMIN\"}");
        HttpResponse<Buffer> strictEnum = post(nonePort, "/strict-alias/enum", "{\"r\":\"ADMIN\"}");
        checks.add(() -> assertEquals(
                400,
                systemEnum.statusCode(),
                "the system mapper does not enable READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, so its binder"
                        + " already refuses an unknown constant with no gate at all"));
        checks.add(() -> assertEquals(
                "role=GUEST",
                vertiqueEnum.bodyAsString(),
                "vertique binds an unknown constant to the @JsonEnumDefaultValue constant, which is the bypass"
                        + " the described alias closes at the gate"));
        checks.add(() -> assertEquals(
                "role=GUEST", strictEnum.bodyAsString(), "vertique-strict inherits the same unknown-enum fallback"));
        assertAll(checks);
    }

    /**
     * T008 TP-006 (AC-017.2, the ordinary binder route). With the process codec left at its default,
     * a {@code vertique-strict} route rejects a body repeating an identical key before the resource
     * runs, because the binder parses the raw body bytes with that profile's own mapper; a
     * {@code vertique} route accepts it and binds the last value.
     *
     * <p>Behavior-change at T008's parent commit, where {@code vertique-strict}'s mapper does not
     * enable {@code STRICT_DUPLICATE_DETECTION} and the strict route answers 200; the {@code
     * vertique} row is a green characterization there.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A vertique-strict route rejects a repeated key while vertique keeps the last value")
    void strictRouteRejectsARepeatedKeyAndVertiqueKeepsTheLastValue() throws Exception {
        VertiqueAliasResource vertique = new VertiqueAliasResource();
        StrictAliasResource strict = new StrictAliasResource();
        int gatePort = start(gateMount(), Set.of(vertique, strict));

        HttpResponse<Buffer> strictResponse = post(gatePort, "/strict-alias/optional", REPEATED_KEY_BODY);
        HttpResponse<Buffer> vertiqueResponse = post(gatePort, "/vertique-alias/optional", REPEATED_KEY_BODY);

        assertAll(
                () -> assertEquals(
                        400,
                        strictResponse.statusCode(),
                        "a vertique-strict route must reject a body repeating an identical key: its mapper"
                                + " parses the raw body bytes (DefaultBoundRequest.bindProfiledJsonBody) and the"
                                + " binder translates the parse rejection to 400"),
                () -> assertEquals(0, strict.invocations.get(), "the rejected body must not reach the resource"),
                () -> assertEquals(
                        200,
                        vertiqueResponse.statusCode(),
                        "vertique is unchanged by FR-017 and must keep accepting the body"),
                () -> assertEquals(
                        "quantity=2 note=null",
                        vertiqueResponse.bodyAsString(),
                        "vertique keeps the last value of the repeated key"));
    }

    /**
     * T008 TP-006 (AC-017.2, the process-codec route). When the route's profile <em>is</em> the
     * installed process codec, the binder swallows the codec's {@code DecodeException} and binds the
     * raw {@code Buffer} instead; under {@code web-validation} the gate then refuses that buffer, so
     * the client still receives a 400 before the resource runs — but the rejection is the validator
     * refusing a value it does not support, and its detail names an internal buffer class rather than
     * the repeated key (design proof v7, {@code runs/process-codec-v7.txt} and
     * {@code runs/buf-validate-v7.txt}).
     *
     * <p>This is pinned rather than glossed over: FR-017 claims nothing for the {@code none} strategy
     * or for a route with no body schema on that path, where the body parameter binds to null and the
     * resource runs — a REST binder defect filed outside this package.
     *
     * <p>Behavior-change at T008's parent commit, where the installed mapper does not enable the
     * feature and the route answers 200.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A vertique-strict process-codec route still rejects a repeated key, at the gate")
    void strictProcessCodecRouteStillRejectsARepeatedKeyAtTheGate() throws Exception {
        StrictAliasResource strict = new StrictAliasResource();
        int gatePort = startWithStrictProcessCodec(Set.of(strict));

        HttpResponse<Buffer> response = post(gatePort, "/strict-alias/optional", REPEATED_KEY_BODY);

        assertAll(
                () -> assertEquals(
                        400,
                        response.statusCode(),
                        "on the process-codec path the binder swallows the parse rejection and binds the raw"
                                + " buffer, which the gate then refuses, so the client still sees a 400 before the"
                                + " resource runs"),
                () -> assertEquals(0, strict.invocations.get(), "the rejected body must not reach the resource"),
                () -> assertFalse(
                        response.bodyAsString().contains("Duplicate"),
                        "the detail names no repeated key on this path: the rejection comes from the validator"
                                + " refusing a Buffer, not from the parser; body: " + response.bodyAsString()));
    }

    // --- T009 TP-002: a closed object and its undeclared property ---

    /**
     * T009 TP-002 (AC-018.1, the closed-object shape). A body type whose class-level
     * {@code @Schema(additionalProperties = FALSE)} publishes a closed object is validated under
     * {@code web-validation}: a body carrying one undeclared property beside the declared one is
     * rejected with 400 before the resource runs, and the rejection carries exactly one body detail
     * that names the failing instance location, carries no keyword and no constraint arguments, and
     * echoes neither the submitted value nor the raw validator message.
     *
     * <p><strong>What the validator reports for this shape</strong>, measured at T009's parent against
     * the schema the mount itself synthesizes ({@code {"type":"object","additionalProperties":false,
     * "properties":{"quantity":{"type":"integer"}}}}): {@code getValid()} is {@code false} and the
     * result carries exactly one error, whose keyword location is {@code #/additionalProperties} and
     * whose instance location is {@code #/surprise}. The root result reports no instance location of
     * its own — vertx-json-schema's Basic output leaves it {@code null} — so the location this detail
     * must name is the one the reported error names.
     *
     * <p>Behavior-change at T009's parent commit, where the single error's keyword is structural, the
     * gate's detail list therefore stays empty, and the request succeeds with 200 although its own
     * validator reported the body invalid. This is the second instance of the same defect as
     * {@link #aliasSpellingsAreValidatedUnderTheGate()}, reached through a different keyword.
     *
     * <p>The gate-disabled row is what makes the 400 the gate's: the fixture's binder is told to ignore
     * an undeclared property, so with no gate the same body reaches the resource. A 400 there would
     * mean the binder, not the gate, refused the body and this proof would be measuring the wrong
     * component — which is exactly what the first parent measurement showed for a fixture without
     * {@code @JsonIgnoreProperties}, and why {@link ClosedQuantity} carries it.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A closed object rejects an undeclared property at the gate, with one value-free detail")
    void aClosedObjectRejectsAnUndeclaredPropertyAtTheGate() throws Exception {
        ClosedObjectResource gated = new ClosedObjectResource();
        int gatePort = start(gateMount(), Set.of(gated));
        ClosedObjectResource ungated = new ClosedObjectResource();
        int nonePort = start(noGateMount(), Set.of(ungated));

        HttpResponse<Buffer> rejected = post(gatePort, "/closed/quantity", CLOSED_UNDECLARED_BODY);
        HttpResponse<Buffer> longKeyRejected = post(gatePort, "/closed/quantity", CLOSED_UNDECLARED_LONG_KEY_BODY);
        int invocationsAfterRejection = gated.invocations.get();
        HttpResponse<Buffer> accepted = post(gatePort, "/closed/quantity", CLOSED_DECLARED_BODY);
        HttpResponse<Buffer> withoutGate = post(nonePort, "/closed/quantity", CLOSED_UNDECLARED_BODY);

        String rejectionBody = rejected.bodyAsString();
        JsonArray errors = problemErrors(rejectionBody);
        String longKeyBody = longKeyRejected.bodyAsString();
        JsonArray longKeyErrors = problemErrors(longKeyBody);
        String longKeyPath = detail(longKeyErrors).getString("path");

        assertAll(
                () -> assertEquals(
                        400,
                        rejected.statusCode(),
                        "the validator reports this body invalid on its sole additionalProperties error, so the"
                                + " gate must reject it; body: " + rejectionBody),
                () -> assertEquals(0, invocationsAfterRejection, "the rejected body must not reach the resource"),
                () -> assertNotNull(
                        errors,
                        "the rejection must carry an RFC 9457 problem body with an 'errors' array; the response"
                                + " body was: " + rejectionBody),
                () -> assertEquals(
                        1,
                        errors == null ? -1 : errors.size(),
                        "a call that reported one structural error must contribute exactly one detail, not none"
                                + " and not two; body: " + rejectionBody),
                () -> assertEquals(
                        "body",
                        detail(errors).getString("location"),
                        "the detail belongs to the body call that failed"),
                // FR-018 as round 10 corrects it: the detail still identifies a failing location, but the
                // location it names carries no unbounded client-chosen text. Which bounded spelling it
                // takes is the executor's recorded choice under AC-018.1 — bounding what the path may
                // carry, or falling back to the containing location when the reported location's last
                // segment is not a name the schema declares — so this row asserts the property both
                // choices must have, not one of their two literals.
                () -> assertNotNull(
                        detail(errors).getString("path"),
                        "the value-free detail must still identify a failing location; detail: "
                                + detail(errors).encode()),
                () -> assertFalse(
                        detail(errors).getString("path", "").isBlank(),
                        "the value-free detail must still identify a failing location; detail: "
                                + detail(errors).encode()),
                () -> assertTrue(
                        detail(errors).getString("path", "").length() <= VALUE_FREE_PATH_BOUND,
                        "the value-free detail's path is bounded; detail: "
                                + detail(errors).encode()),
                () -> assertFalse(
                        detail(errors).containsKey("type"),
                        "the detail carries no keyword: additionalProperties stays structural and no concrete"
                                + " keyword may be fabricated; detail: "
                                + detail(errors).encode()),
                () -> assertFalse(
                        detail(errors).containsKey("args"),
                        "the detail carries no constraint arguments; detail: "
                                + detail(errors).encode()),
                () -> assertFalse(
                        rejectionBody.contains(UNDECLARED_MARKER),
                        "no submitted value may reach the response; body: " + rejectionBody),
                () -> assertFalse(
                        rejectionBody.contains(RAW_ADDITIONAL_PROPERTIES_MESSAGE),
                        "the detail must be composed without the raw validator message, which is the formatter"
                                + " fallback this detail must not be routed through; body: " + rejectionBody),
                () -> assertEquals(
                        200,
                        accepted.statusCode(),
                        "a body the validator reports valid is still accepted: no detail and no rejection"),
                () -> assertEquals(
                        "quantity=5", accepted.bodyAsString(), "the accepted body must reach the resource bound"),
                () -> assertEquals(1, gated.invocations.get(), "exactly the accepted body reached the resource"),
                () -> assertEquals(
                        200,
                        withoutGate.statusCode(),
                        "with no gate the binder ignores the undeclared property, so the 400 above is the"
                                + " gate's decision and not the binder's; body: " + withoutGate.bodyAsString()),
                () -> assertEquals(
                        "quantity=5",
                        withoutGate.bodyAsString(),
                        "without the gate the undeclared property is dropped and the declared one is bound"),
                () -> assertEquals(
                        1, ungated.invocations.get(), "the gate-disabled body must have reached the resource"),

                // --- T010 TP-003: the long client-chosen key ---
                () -> assertEquals(
                        400,
                        longKeyRejected.statusCode(),
                        "the same closed object rejects an undeclared property whatever its key is named;"
                                + " body length: " + longKeyBody.length()),
                () -> assertNotNull(
                        longKeyErrors,
                        "the long-key rejection must carry an RFC 9457 problem body with an 'errors' array;"
                                + " body length: " + longKeyBody.length()),
                () -> assertEquals(
                        1,
                        longKeyErrors == null ? -1 : longKeyErrors.size(),
                        "the long-key call reported one structural error and must contribute exactly one"
                                + " detail; body length: " + longKeyBody.length()),
                () -> assertFalse(
                        longKeyBody.contains(LONG_KEY_MARKER),
                        "DECISIVE (CO-008): no part of the client's own key may reach the response. The key"
                                + " is the last segment of the instance location the validator reports for an"
                                + " undeclared property under a closed object, and the value-free detail"
                                + " passed that location through verbatim, so a client chooses the response's"
                                + " size and content; detail path length: "
                                + (longKeyPath == null ? -1 : longKeyPath.length())),
                () -> assertTrue(
                        longKeyPath != null && longKeyPath.length() <= VALUE_FREE_PATH_BOUND,
                        "DECISIVE (CO-008): the value-free detail's path is bounded. A repair that only"
                                + " strips the leading slash or the '#' still returns a path of the client's"
                                + " chosen length and does not satisfy this; path length: "
                                + (longKeyPath == null ? -1 : longKeyPath.length())),
                () -> assertTrue(
                        longKeyPath != null && !longKeyPath.isBlank(),
                        "the bounded detail must still identify a failing location, so bounding it does not"
                                + " empty it; detail: " + detail(longKeyErrors).encode()),
                () -> assertFalse(
                        detail(longKeyErrors).containsKey("type"),
                        "the long-key detail carries no keyword either; detail path length: "
                                + (longKeyPath == null ? -1 : longKeyPath.length())),
                () -> assertFalse(
                        detail(longKeyErrors).containsKey("args"),
                        "the long-key detail carries no constraint arguments either; detail path length: "
                                + (longKeyPath == null ? -1 : longKeyPath.length())),
                () -> assertFalse(
                        longKeyBody.contains(RAW_ADDITIONAL_PROPERTIES_MESSAGE),
                        "the long-key detail is composed without the raw validator message too; body length: "
                                + longKeyBody.length()),
                () -> assertEquals(0, invocationsAfterRejection, "neither rejected body reached the resource"));
    }

    /**
     * Decodes an RFC 9457 problem body and returns its {@code errors} array, or {@code null} when the
     * response is not a JSON problem body at all — which is what a 200 from the resource looks like,
     * so the status assertion above reports the real failure rather than a decode exception.
     *
     * @param responseBody the raw response body
     * @return the {@code errors} array, or {@code null} when the body is not a JSON object carrying one
     */
    private static JsonArray problemErrors(String responseBody) {
        try {
            return new JsonObject(responseBody).getJsonArray("errors");
        } catch (DecodeException e) {
            return null;
        }
    }

    /**
     * Returns the single detail of a rejection, for the assertions that describe its shape.
     *
     * @param errors the decoded {@code errors} array, possibly {@code null} or empty
     * @return the first detail, or an empty object when there is none, so each assertion fails on what
     *     it asserts rather than on a null dereference
     */
    private static JsonObject detail(JsonArray errors) {
        return errors == null || errors.isEmpty() ? new JsonObject() : errors.getJsonObject(0);
    }

    // --- Request bodies ---

    /** The undeclared property's value: distinctive, so its absence from the response is provable. */
    private static final String UNDECLARED_MARKER = "MARKER-4711-MUST-NOT-ECHO";

    /** A closed-object body carrying the declared property beside one undeclared property. */
    private static final String CLOSED_UNDECLARED_BODY = "{\"quantity\":5,\"surprise\":\"" + UNDECLARED_MARKER + "\"}";

    /**
     * The undeclared property's <strong>name</strong> for the bounded-location row: a distinctive
     * literal repeated past 4096 characters.
     *
     * <p>T009's existing assertions cover the submitted <em>value</em>; this marker is the key itself,
     * which the validator reports as the last segment of the failing instance location and which the
     * gate's value-free detail therefore carried into the response verbatim, of unbounded length
     * (CO-008).
     */
    private static final String LONG_KEY_MARKER = "LONGKEY-2f9c".repeat(512);

    /** The same closed-object body whose single undeclared property is named by the long marker. */
    private static final String CLOSED_UNDECLARED_LONG_KEY_BODY = "{\"quantity\":5,\"" + LONG_KEY_MARKER + "\":1}";

    /**
     * The bound the value-free detail's path must stay inside. It is far below the marker's length and
     * far above any location the schema's own declared names can spell, so it discriminates a path
     * carrying a client-chosen key from either repair AC-018.1 admits — bounding what the path may
     * carry, or falling back to the containing location — without pinning the executor's choice.
     */
    private static final int VALUE_FREE_PATH_BOUND = 256;

    /** The same body with the declared property alone, which the validator reports valid. */
    private static final String CLOSED_DECLARED_BODY = "{\"quantity\":5}";

    /**
     * The distinguishing fragment of the raw vertx-json-schema message for this failure, measured at
     * T009's parent: {@code Property "surprise" does not match additional properties schema}. The
     * value-free detail must not be composed from it.
     */
    private static final String RAW_ADDITIONAL_PROPERTIES_MESSAGE = "does not match additional properties schema";

    /** A valid string extra beside the named property the string any-setter type publishes. */
    private static final String ANY_SETTER_VALID_STRING_BODY = "{\"name\":\"a\",\"x\":\"y\"}";

    /** A JSON number where the string any-setter declares {@code String} extras values. */
    private static final String ANY_SETTER_NUMBER_EXTRA_BODY = "{\"x\":5}";

    /** A valid date extra for the {@code LocalDate} any-setter. */
    private static final String ANY_SETTER_VALID_DATE_BODY = "{\"x\":\"2022-01-08\"}";

    /** A bare number where the {@code LocalDate} any-setter declares date extras values. */
    private static final String ANY_SETTER_NUMBER_DATE_BODY = "{\"x\":19000}";

    /** The read-only name the reserved-name type binds on input but never publishes. */
    private static final String ANY_SETTER_ROLE_BODY = "{\"role\":\"admin\"}";

    /** The ignored name the reserved-name type binds on input but never publishes. */
    private static final String ANY_SETTER_ID_BODY = "{\"id\":\"forged\"}";

    /** The any-getter's storage name, which Jackson fills through its getter (SG1). */
    private static final String ANY_SETTER_STORAGE_BODY = "{\"extras\":{\"role\":\"admin\"}}";

    /** A JSON number where the private-field fixture declares a date string property. */
    private static final String RESTORED_DATE_NUMBER_BODY = "{\"due\":19000}";

    /** A numeric string where the Lombok builder declares an integer property. */
    private static final String RESTORED_BUILDER_STRING_BODY = "{\"quantity\":\"2\"}";

    /** Numeric items where the getter-only list declares string items. */
    private static final String RESTORED_LIST_NUMBERS_BODY = "{\"tags\":[1,2]}";

    /** A JSON number at the nested restored shape's date position. */
    private static final String RESTORED_NESTED_NUMBER_BODY = "{\"detail\":{\"due\":19000}}";

    /** A JSON array where the getter-only map declares an object property. */
    private static final String RESTORED_MAP_ARRAY_BODY = "{\"labels\":[\"x\"]}";

    /** A JSON object where the {@code Optional<String>} fixture declares a string property. */
    private static final String OPTIONAL_OBJECT_BODY = "{\"note\":{\"nested\":true}}";

    /** A JSON boolean where the {@code Instant} fixture declares a date-time string property. */
    private static final String INSTANT_BOOLEAN_BODY = "{\"occurredAt\":true}";

    /** A JSON number where the {@code LocalDate} fixture declares a date string property. */
    private static final String LOCAL_DATE_NUMBER_BODY = "{\"due\":123}";

    // --- Resource fixtures ---

    /** The TP-001 body: one {@code BigDecimal} value position under the strict profile. */
    public static class Payment {

        /** The decimal amount, whose wire shape the effective profile decides. */
        public BigDecimal amount;
    }

    /** Resource selecting {@code vertique-strict} at the class level, with one decimal body. */
    @Path("/strict")
    @JsonProfile("vertique-strict")
    public static class PaymentResource {

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
        @Operation(operationId = "strictPaymentEcho")
        public String echo(Payment payment) {
            invocations.incrementAndGet();
            return "amount=" + payment.amount;
        }
    }

    /**
     * The property-model resource on the {@code vertique} floor: it carries no {@code @JsonProfile},
     * so the effective profile is the reserved floor.
     */
    @Path("/vertique")
    public static class VertiqueModelResource {

        /** Counts terminal invocations across all four routes. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound enum constant.
         *
         * @param body the enum fixture body
         * @return the echoed constant
         */
        @POST
        @Path("/enum")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueEnumEcho")
        public String enumEcho(EnumDefaultValueDto body) {
            invocations.incrementAndGet();
            return "status=" + body.status;
        }

        /**
         * Echoes the bound optional string.
         *
         * @param body the optional-property fixture body
         * @return the echoed value
         */
        @POST
        @Path("/optional")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueOptionalEcho")
        public String optionalEcho(OptionalPropertyDto body) {
            invocations.incrementAndGet();
            return "note=" + body.note;
        }

        /**
         * Echoes the bound instant.
         *
         * @param body the instant-property fixture body
         * @return the echoed value
         */
        @POST
        @Path("/instant")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueInstantEcho")
        public String instantEcho(InstantPropertyDto body) {
            invocations.incrementAndGet();
            return "occurredAt=" + body.occurredAt;
        }

        /**
         * Echoes the bound local date, so the binder's own reading of a number is observable.
         *
         * @param body the local-date fixture body
         * @return the echoed value
         */
        @POST
        @Path("/date")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueDateEcho")
        public String dateEcho(LocalDatePropertyDto body) {
            invocations.incrementAndGet();
            return "due=" + body.due;
        }
    }

    /** The same four routes under an explicitly selected {@code system} profile. */
    @Path("/system")
    @JsonProfile("system")
    public static class SystemModelResource {

        /** Counts terminal invocations across all four routes. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound enum constant.
         *
         * @param body the enum fixture body
         * @return the echoed constant
         */
        @POST
        @Path("/enum")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "systemEnumEcho")
        public String enumEcho(EnumDefaultValueDto body) {
            invocations.incrementAndGet();
            return "status=" + body.status;
        }

        /**
         * Echoes the bound optional string.
         *
         * @param body the optional-property fixture body
         * @return the echoed value
         */
        @POST
        @Path("/optional")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "systemOptionalEcho")
        public String optionalEcho(OptionalPropertyDto body) {
            invocations.incrementAndGet();
            return "note=" + body.note;
        }

        /**
         * Echoes the bound instant.
         *
         * @param body the instant-property fixture body
         * @return the echoed value
         */
        @POST
        @Path("/instant")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "systemInstantEcho")
        public String instantEcho(InstantPropertyDto body) {
            invocations.incrementAndGet();
            return "occurredAt=" + body.occurredAt;
        }

        /**
         * Echoes the bound local date, so the binder's own reading of a number is observable.
         *
         * @param body the local-date fixture body
         * @return the echoed value
         */
        @POST
        @Path("/date")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "systemDateEcho")
        public String dateEcho(LocalDatePropertyDto body) {
            invocations.incrementAndGet();
            return "due=" + body.due;
        }
    }

    /**
     * The restored-shape resource on the {@code vertique} floor: it carries no {@code @JsonProfile},
     * so the effective profile is the reserved floor, the profile AC-013.4 names. Every route echoes
     * what the binder produced, so the gate-disabled half can assert the bound value and not merely
     * the status code.
     */
    @Path("/restored")
    public static class RestoredShapeResource {

        /** Counts terminal invocations across all five routes. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound date of a private field behind a getter.
         *
         * @param body the private-field fixture body
         * @return the echoed value
         */
        @POST
        @Path("/date")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "restoredDateEcho")
        public String dateEcho(PrivateDatePropertyDto body) {
            invocations.incrementAndGet();
            return "due=" + body.getDue();
        }

        /**
         * Echoes the integer the Lombok builder bound.
         *
         * @param body the builder fixture body
         * @return the echoed value
         */
        @POST
        @Path("/builder")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "restoredBuilderEcho")
        public String builderEcho(LombokBuilderDto body) {
            invocations.incrementAndGet();
            return "quantity=" + body.getQuantity();
        }

        /**
         * Echoes the bound list joined by {@code |}, so the element type is observable.
         *
         * @param body the getter-only list fixture body
         * @return the echoed value
         */
        @POST
        @Path("/list")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "restoredListEcho")
        public String listEcho(GetterOnlyListDto body) {
            invocations.incrementAndGet();
            return "tags=" + String.join("|", body.getTags());
        }

        /**
         * Echoes the bound map.
         *
         * @param body the getter-only map fixture body
         * @return the echoed value
         */
        @POST
        @Path("/map")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "restoredMapEcho")
        public String mapEcho(GetterOnlyMapDto body) {
            invocations.incrementAndGet();
            return "labels=" + body.getLabels();
        }

        /**
         * Echoes the bound date at the nested position.
         *
         * @param body the nested fixture body
         * @return the echoed value
         */
        @POST
        @Path("/nested")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "restoredNestedEcho")
        public String nestedEcho(NestedPrivateDateDto body) {
            invocations.incrementAndGet();
            return "detail.due=" + body.getDetail().getDue();
        }
    }

    // --- T007 TP-006 fixtures: the three any-setter shapes AC-015.6 names ---

    /**
     * Renders an extras map as {@code key=value:SimpleClassName} pairs, so a proof can assert what the
     * binder <em>stored</em> and not merely that something arrived: a number coerced into a
     * {@code String} extra and a number read as a {@code LocalDate} are both invisible in a plain
     * rendering.
     *
     * @param extras the bound extras map
     * @return the rendered pairs, comma-separated in insertion order
     */
    private static String renderTypedExtras(Map<String, ?> extras) {
        return extras.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue() + ":"
                        + entry.getValue().getClass().getSimpleName())
                .collect(Collectors.joining(","));
    }

    /** A named property beside a {@code String} any-setter: the commonest open-extras shape. */
    public static class StringExtrasDto {

        /** An ordinary property the document publishes. */
        public String name;

        /** The collected extras, whose declared value type the gate must enforce. */
        private final Map<String, String> extras = new LinkedHashMap<>();

        /**
         * Collects an extra key.
         *
         * @param key   the extra key
         * @param value the extra value
         */
        @JsonAnySetter
        public void putExtra(String key, String value) {
            extras.put(key, value);
        }

        /**
         * Returns the collected extras. Deliberately not a {@code get}-prefixed method: it is test
         * instrumentation, not a property of the body type.
         *
         * @return the collected extras
         */
        public Map<String, String> collectedExtras() {
            return extras;
        }
    }

    /** An any-setter over {@code LocalDate} values, where a bare number binds as an epoch day. */
    public static class LocalDateExtrasDto {

        /** The collected extras. */
        private final Map<String, LocalDate> extras = new LinkedHashMap<>();

        /**
         * Collects an extra key.
         *
         * @param key   the extra key
         * @param value the extra value
         */
        @JsonAnySetter
        public void putExtra(String key, LocalDate value) {
            extras.put(key, value);
        }

        /**
         * Returns the collected extras.
         *
         * @return the collected extras
         */
        public Map<String, LocalDate> collectedExtras() {
            return extras;
        }
    }

    /**
     * The reserved-name shape: a read-only {@code role}, an ignored {@code id}, and a method
     * {@code @JsonAnyGetter} over the storage field {@code extras}. All three names are bound on input
     * and published by no document, so each reaches a real member through the open object.
     */
    public static class ReservedNameExtrasDto {

        /** An ordinary property the document publishes. */
        public String name;

        /** Server-assigned: never accepted on input, never published, routed into the extras map. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String role;

        /** Never bound by name, never published, routed into the extras map. */
        @JsonIgnore
        public String id;

        /** The storage the any-getter returns and Jackson fills through it. */
        private final Map<String, Object> extras = new LinkedHashMap<>();

        /**
         * Collects an extra key.
         *
         * @param key   the extra key
         * @param value the extra value
         */
        @JsonAnySetter
        public void putExtra(String key, Object value) {
            extras.put(key, value);
        }

        /**
         * Returns the collected extras, and is the member Jackson fills for a body whose key is the
         * storage name itself.
         *
         * @return the collected extras
         */
        @JsonAnyGetter
        public Map<String, Object> getExtras() {
            return extras;
        }
    }

    /**
     * The any-setter resource on the {@code vertique} floor: it carries no {@code @JsonProfile}, so
     * the effective profile is the reserved floor, the profile AC-015.6 names. Every route echoes what
     * the binder produced, so the gate-disabled half asserts the stored value and not merely a status
     * code.
     */
    @Path("/anysetter")
    public static class AnySetterResource {

        /** Counts terminal invocations across all three routes. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the named property and every stored extra with its Java type.
         *
         * @param body the string any-setter body
         * @return the echoed value
         */
        @POST
        @Path("/string")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "anySetterStringEcho")
        public String stringEcho(StringExtrasDto body) {
            invocations.incrementAndGet();
            return "name=" + body.name + " extras=" + renderTypedExtras(body.collectedExtras());
        }

        /**
         * Echoes every stored extra with its Java type, so an epoch-day reading is observable.
         *
         * @param body the LocalDate any-setter body
         * @return the echoed value
         */
        @POST
        @Path("/date")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "anySetterDateEcho")
        public String dateEcho(LocalDateExtrasDto body) {
            invocations.incrementAndGet();
            return "extras=" + renderTypedExtras(body.collectedExtras());
        }

        /**
         * Echoes the extras map itself, so a reserved name that reached a real member is visible.
         *
         * @param body the reserved-name body
         * @return the echoed value
         */
        @POST
        @Path("/reserved")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "anySetterReservedEcho")
        public String reservedEcho(ReservedNameExtrasDto body) {
            invocations.incrementAndGet();
            return "extras=" + body.getExtras();
        }
    }

    // --- T008 alias fixtures and resources ---

    /** A body repeating an identical key, which only a strict-duplicate-detecting mapper refuses. */
    private static final String REPEATED_KEY_BODY = "{\"quantity\":1,\"quantity\":2}";

    /** An optional aliased, constrained property beside an unaliased one. */
    public static class AliasedQuantity {

        /** The aliased property: {@code qty} must carry its {@code maximum} at the gate. */
        @JsonAlias("qty")
        @Max(10)
        public Integer quantity;

        /** An unaliased property, so "neither spelling" is a body the type can still carry. */
        public String note;
    }

    /** The same property, required, so the alias must satisfy the requirement on its own. */
    public static class RequiredAliasedQuantity {

        /** The required aliased property. */
        @JsonAlias("qty")
        @Max(10)
        @NotNull
        public Integer quantity;

        /** An unaliased property. */
        public String note;
    }

    /** One property claiming two spellings. */
    public static class TwoAliasedName {

        /** The doubly aliased property. */
        @JsonAlias({"nm", "nm2"})
        @Size(max = 3)
        public String name;
    }

    /** An aliased property on an any-setter type, where the spelling must also be released. */
    public static class AliasedQuantityWithExtras {

        /** The aliased property. */
        @JsonAlias("qty")
        @Max(10)
        public Integer quantity;

        /** The any-setter's backing storage, typed so a valid extra is describable. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** An aliased enum property whose type carries a {@code @JsonEnumDefaultValue} constant. */
    public static class AliasedRole {

        /** The aliased enum property. */
        @JsonAlias("r")
        public Role role;
    }

    /** The enum behind {@link AliasedRole}: an unknown constant falls back to {@code GUEST}. */
    public enum Role {

        /** The fallback constant an unknown string binds to under {@code vertique}. */
        @JsonEnumDefaultValue
        GUEST,

        /** An ordinary constant, so a known value is observably different from the fallback. */
        USER
    }

    /** The five alias routes under an explicitly selected {@code system} profile. */
    @Path("/system-alias")
    @JsonProfile("system")
    public static class SystemAliasResource {

        /** Counts terminal invocations across all five routes. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the optional aliased property.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/optional")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "systemAliasOptionalEcho")
        public String optionalEcho(AliasedQuantity body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity + " note=" + body.note;
        }

        /**
         * Echoes the required aliased property.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/required")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "systemAliasRequiredEcho")
        public String requiredEcho(RequiredAliasedQuantity body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity + " note=" + body.note;
        }

        /**
         * Echoes the doubly aliased property.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/twoalias")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "systemAliasTwoAliasEcho")
        public String twoAliasEcho(TwoAliasedName body) {
            invocations.incrementAndGet();
            return "name=" + body.name;
        }

        /**
         * Echoes the aliased property and the extras beside it.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/anysetter")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "systemAliasAnySetterEcho")
        public String anySetterEcho(AliasedQuantityWithExtras body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity + " extras=" + body.extras;
        }

        /**
         * Echoes the aliased enum constant.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/enum")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "systemAliasEnumEcho")
        public String enumEcho(AliasedRole body) {
            invocations.incrementAndGet();
            return "role=" + body.role;
        }
    }

    /** The same five routes on the {@code vertique} floor: no {@code @JsonProfile} at all. */
    @Path("/vertique-alias")
    public static class VertiqueAliasResource {

        /** Counts terminal invocations across all five routes. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the optional aliased property.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/optional")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueAliasOptionalEcho")
        public String optionalEcho(AliasedQuantity body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity + " note=" + body.note;
        }

        /**
         * Echoes the required aliased property.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/required")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueAliasRequiredEcho")
        public String requiredEcho(RequiredAliasedQuantity body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity + " note=" + body.note;
        }

        /**
         * Echoes the doubly aliased property.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/twoalias")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueAliasTwoAliasEcho")
        public String twoAliasEcho(TwoAliasedName body) {
            invocations.incrementAndGet();
            return "name=" + body.name;
        }

        /**
         * Echoes the aliased property and the extras beside it.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/anysetter")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueAliasAnySetterEcho")
        public String anySetterEcho(AliasedQuantityWithExtras body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity + " extras=" + body.extras;
        }

        /**
         * Echoes the aliased enum constant.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/enum")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "vertiqueAliasEnumEcho")
        public String enumEcho(AliasedRole body) {
            invocations.incrementAndGet();
            return "role=" + body.role;
        }
    }

    /** The same five routes under {@code vertique-strict}, the one strict built-in profile. */
    @Path("/strict-alias")
    @JsonProfile("vertique-strict")
    public static class StrictAliasResource {

        /** Counts terminal invocations across all five routes. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the optional aliased property.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/optional")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictAliasOptionalEcho")
        public String optionalEcho(AliasedQuantity body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity + " note=" + body.note;
        }

        /**
         * Echoes the required aliased property.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/required")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictAliasRequiredEcho")
        public String requiredEcho(RequiredAliasedQuantity body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity + " note=" + body.note;
        }

        /**
         * Echoes the doubly aliased property.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/twoalias")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictAliasTwoAliasEcho")
        public String twoAliasEcho(TwoAliasedName body) {
            invocations.incrementAndGet();
            return "name=" + body.name;
        }

        /**
         * Echoes the aliased property and the extras beside it.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/anysetter")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictAliasAnySetterEcho")
        public String anySetterEcho(AliasedQuantityWithExtras body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity + " extras=" + body.extras;
        }

        /**
         * Echoes the aliased enum constant.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/enum")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictAliasEnumEcho")
        public String enumEcho(AliasedRole body) {
            invocations.incrementAndGet();
            return "role=" + body.role;
        }
    }

    // --- T009 closed-object fixture and resource ---

    /**
     * A closed object: one declared property beside a class-level rule that publishes
     * {@code "additionalProperties": false}, so an undeclared property is a violation the schema — and
     * only the schema — can see.
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is what makes this fixture a proof of the
     * <em>gate</em>. Measured at T009's parent: without it every built-in profile's binder refuses an
     * undeclared property first, answering 400 with the profile's own {@code "Request body rejected by
     * JSON profile"} problem body and no {@code errors} array at all, on the gate-disabled mount as
     * well as the gated one — so the gate's verdict on this shape would never be observable. Telling
     * the binder to ignore the key changes nothing about the published document: the synthesized body
     * schema is byte-identical with and without the annotation
     * ({@code {"type":"object","additionalProperties":false,"properties":{"quantity":{"type":"integer"}}}}),
     * and the validator reports the same single {@code #/additionalProperties} error for the same body.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public static class ClosedQuantity {

        /** The one declared property. */
        public Integer quantity;
    }

    /**
     * The closed-object route on the {@code vertique} floor: it carries no {@code @JsonProfile}, so the
     * effective profile is the unannotated floor AC-018.1 names for this shape.
     */
    @Path("/closed")
    public static class ClosedObjectResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the declared property, so an accepted body is observable as more than a status code.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/quantity")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "closedObjectQuantityEcho")
        public String quantityEcho(ClosedQuantity body) {
            invocations.incrementAndGet();
            return "quantity=" + body.quantity;
        }
    }

    // --- Mounts and helpers ---

    /**
     * Builds the default mount: every {@code jaxrs}/{@code http} setting at its framework default, so
     * {@code jaxrs.validationStrategy} resolves to {@code web-validation} and the gate is installed.
     *
     * @return the gated mount handle
     */
    private RestTestMount gateMount() {
        return MountFixtures.mount(vertx, new JsonObject(), RestTestContributions.none());
    }

    /**
     * Builds the gate-disabled mount by selecting {@link NoneValidationStrategy} — the strategy
     * {@code RestModule} always binds, so no extra module or contribution is needed — through the
     * mount helper's configuration argument. Everything else is identical to {@link #gateMount()}:
     * the same graph, the same middlewares, the same binder.
     *
     * @return the gate-disabled mount handle
     */
    private RestTestMount noGateMount() {
        JsonObject config =
                new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", NoneValidationStrategy.ID));
        return MountFixtures.mount(vertx, config, RestTestContributions.none());
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
     * Starts a gated mount whose process JSON codec runs the graph's own {@code vertique-strict}
     * mapper — what {@code json.systemProfile: vertique-strict} installs in a booted application — so
     * a {@code vertique-strict} route's profile <em>is</em> the installed codec.
     *
     * <p>The install happens before the router is built: the REST body resolver captures its "no
     * override" sentinel by identity against {@code VertiqueJson.mapper()} at router-build time, so an
     * install afterwards would no longer match the routes already decided. The codec is restored in
     * {@link #tearDown()}.
     *
     * @param resources the JAX-RS resources to mount
     * @return the bound port
     */
    private int startWithStrictProcessCodec(Set<Object> resources) {
        ValidationMountComponent component =
                MountFixtures.component(vertx, new JsonObject(), RestTestContributions.none());
        JsonProfileId strictId = JsonProfileId.of("vertique-strict");
        VertiqueJson.install(strictId, component.jsonMapperProfileRegistry().mapper(strictId));
        processCodecInstalled = true;
        return start(component.testMount(), resources);
    }

    /**
     * POSTs {@code body} as {@code application/json} to {@code path} on the mount bound to
     * {@code port} and returns the aggregated response.
     *
     * @param port the mount's bound port — which mount a case runs against
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
