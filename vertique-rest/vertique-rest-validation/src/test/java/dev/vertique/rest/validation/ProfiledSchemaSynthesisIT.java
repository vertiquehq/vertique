// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.json.JsonProfile;
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

    // --- Request bodies ---

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
