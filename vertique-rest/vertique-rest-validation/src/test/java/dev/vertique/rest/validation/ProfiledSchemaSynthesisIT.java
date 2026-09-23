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
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
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

    // --- rest-020-refresh: the removed compiled-parameter-name creator-parameter join ---

    /**
     * The gate-level half of the fix proof for {@code InputPropertyDescriber#backingField}'s removed
     * compiled-parameter-name candidate. {@link TransformingConstructorBody}'s constructor parameter's
     * <em>compiled Java name</em> ({@code "amount"}) coincides with an unrelated field's own name, but
     * its wire name ({@code "amount_cents"}) does not, and the constructor divides the incoming value
     * by 100 before storing it. Before the fix, the removed join borrowed the field's {@code @Max(10)}
     * onto {@code amount_cents} and the gate rejected {@code {"amount_cents": 500}} — a body the
     * constructor turns into {@code amount = 5}, well under the field's own ceiling.
     *
     * <p>Behavior-change: red at {@code fcc201cb} (the task's baseline commit, with the join still in
     * place) — a 400, never reaching the resource. Green after the fix — a 200, bound through the
     * constructor's own transform. The fixture needs its constructor parameter's compiled name present
     * at runtime for the pre-fix join to have had anything to bite on; {@code pom.xml}'s test-only
     * {@code default-testCompile} override compiles this module's test sources with {@code -parameters}
     * for exactly that reason.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("The gate accepts a transforming constructor's wire value and binds through its transform,"
            + " not a borrowed field constraint")
    void transformingConstructorParameterAcceptsValueTheFieldsConstraintWouldHaveRejected() throws Exception {
        TransformingConstructorResource resource = new TransformingConstructorResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> response = post(gatePort, "/transforming/amount", "{\"amount_cents\":500}");

        assertEquals(
                200,
                response.statusCode(),
                "the gate must accept amount_cents=500: the removed join used to borrow the field's"
                        + " @Max(10) onto the wire name amount_cents and reject this body even though the"
                        + " constructor turns it into amount = 5, well under 10; body: "
                        + response.bodyAsString());
        assertEquals(
                "amount=5",
                response.bodyAsString(),
                "the accepted body must bind through the constructor's own transform, not the field's"
                        + " raw wire value");
        assertEquals(1, resource.invocations.get(), "the accepted body must reach the resource exactly once");
    }

    // --- deserializer-driven-schema spike: the bounded hand-written-builder borrow ---

    /**
     * The gate-level half of the owner ruling's fix proof for {@code InputPropertyDescriber}'s
     * builder-method borrow ({@code BuilderBorrowDetector}). {@link TransformingBuilderBody} is
     * deliberately getter-less — its {@code amount} field has no accessor — so it is the round-2 owner
     * ruling's control: {@code main} never published a getter-less field's constraint either, so this
     * shape must stay unresolved before and after the round-2 fix. (The round-1 fixture it was
     * originally paired with, {@link dev.vertique.json.schema.BuilderWireNameJoinTest.TransformingBuilderDto},
     * carries a getter and was repurposed for the round-2 characterization instead — see that class's
     * Javadoc.) Its hand-written builder method divides the incoming value by 100 before assigning it
     * to the built field of the same wire name, and its builder class is named plainly rather than in
     * the Lombok {@code <Type>Builder} convention, so it matches neither {@code @lombok.Generated} nor
     * the fallback shape.
     *
     * <p>Behavior-change: red at {@code 3802ff0f} (the task's baseline commit, with the borrow still
     * unconditional) — a 400, never reaching the resource, because the schema published the built
     * field's {@code @Max(10)} onto the wire property {@code amount}. Green after the fix — a 200,
     * bound through the builder's own transform: {@code amount=500} divides to {@code amount = 5}, well
     * under the field's own ceiling, which the gate no longer rejects because the property is now
     * published by type only.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("The gate accepts a hand-written builder's transformed value, not a borrowed field constraint"
            + " (owner ruling: hand-written builders go unresolved)")
    void transformingBuilderMethodAcceptsValueTheFieldsConstraintWouldHaveRejected() throws Exception {
        TransformingBuilderResource resource = new TransformingBuilderResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> response = post(gatePort, "/transforming-builder/amount", "{\"amount\":500}");

        assertEquals(
                200,
                response.statusCode(),
                "the gate must accept amount=500: an unbounded builder borrow used to publish the built"
                        + " field's @Max(10) onto the wire property amount and reject this body even though"
                        + " the hand-written builder turns it into amount = 5, well under 10; body: "
                        + response.bodyAsString());
        assertEquals(
                "amount=5",
                response.bodyAsString(),
                "the accepted body must bind through the builder's own transform, not a borrowed field"
                        + " constraint");
        assertEquals(1, resource.invocations.get(), "the accepted body must reach the resource exactly once");
    }

    // --- Round 2 (this task's owner ruling): a getter-backed built property borrows for any builder ---

    /**
     * The gate-level half of the round-2 owner ruling's fix proof. A package review found that {@code
     * BuilderBorrowDetector}'s Lombok-shape gate loosens the gate against {@code main} for a
     * hand-written builder whose built type has getters: {@code main}'s field walk published every
     * getter-backed field's constraints regardless of the builder, but the shape gate drops them, so a
     * value {@code main} would have rejected is silently accepted. {@link Round2WithPrefixBuilderBody},
     * {@link Round2PlainBuilderClassBody}, and {@link Round2RenamedBuildMethodBody} each violate exactly
     * one condition of {@code BuilderBorrowDetector}'s Lombok shape — a non-empty {@code withPrefix}, a
     * builder class not named {@code <Type>Builder}, and a build method not named {@code build} — while
     * both built properties ({@code name} and {@code level}) carry a getter, the schema-level unit
     * counterparts of {@code
     * dev.vertique.json.schema.BuilderWireNameJoinTest#withPrefixBuilderStillBorrowsThroughTheGetterBackedProperty},
     * {@code
     * dev.vertique.json.schema.BuilderWireNameJoinTest#plainlyNamedBuilderClassStillBorrowsThroughTheGetterBackedProperty},
     * and {@code
     * dev.vertique.json.schema.BuilderWireNameJoinTest#renamedBuildMethodStillBorrowsThroughTheGetterBackedProperty}.
     *
     * <p>Expected red now: production code still gates every builder on the Lombok shape regardless of
     * a getter, so each property is published by type only and every out-of-bound value below is
     * accepted with a 200 instead of rejected with a 400.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("Round 2 (owner ruling): the gate rejects an out-of-bound value for each getter-backed built"
            + " property, whatever shape violation keeps the builder from matching the Lombok convention")
    void round2GateRejectsOutOfBoundValuesForEachGetterBackedShapeViolation() throws Exception {
        Round2WithPrefixBuilderResource withPrefix = new Round2WithPrefixBuilderResource();
        Round2PlainBuilderClassResource plainClass = new Round2PlainBuilderClassResource();
        Round2RenamedBuildMethodResource renamedMethod = new Round2RenamedBuildMethodResource();
        int gatePort = start(gateMount(), Set.of(withPrefix, plainClass, renamedMethod));

        assertAll(
                () -> assertEquals(
                        400,
                        post(gatePort, "/round2-with-prefix/fields", "{\"level\":999}")
                                .statusCode(),
                        "ROUND-2 DECISIVE (expected red now): level has a getter, so its @Max(10) must be"
                                + " borrowed regardless of the builder's non-empty withPrefix"),
                () -> assertEquals(
                        400,
                        post(gatePort, "/round2-with-prefix/fields", "{\"name\":\"TOOLONG\"}")
                                .statusCode(),
                        "ROUND-2 DECISIVE (expected red now): name has a getter, so its @Size(max = 3) must be"
                                + " borrowed regardless of the builder's non-empty withPrefix"),
                () -> assertEquals(
                        400,
                        post(gatePort, "/round2-plain-class/fields", "{\"level\":999}")
                                .statusCode(),
                        "ROUND-2 DECISIVE (expected red now): level has a getter, so its @Max(10) must be"
                                + " borrowed regardless of the builder class's plain name"),
                () -> assertEquals(
                        400,
                        post(gatePort, "/round2-plain-class/fields", "{\"name\":\"TOOLONG\"}")
                                .statusCode(),
                        "ROUND-2 DECISIVE (expected red now): name has a getter, so its @Size(max = 3) must be"
                                + " borrowed regardless of the builder class's plain name"),
                () -> assertEquals(
                        400,
                        post(gatePort, "/round2-renamed-method/fields", "{\"level\":999}")
                                .statusCode(),
                        "ROUND-2 DECISIVE (expected red now): level has a getter, so its @Max(10) must be"
                                + " borrowed regardless of the builder's renamed build method"),
                () -> assertEquals(
                        400,
                        post(gatePort, "/round2-renamed-method/fields", "{\"name\":\"TOOLONG\"}")
                                .statusCode(),
                        "ROUND-2 DECISIVE (expected red now): name has a getter, so its @Size(max = 3) must be"
                                + " borrowed regardless of the builder's renamed build method"),
                () -> assertEquals(
                        0, withPrefix.invocations.get(), "no rejected body may reach the resource under the gate"),
                () -> assertEquals(
                        0, plainClass.invocations.get(), "no rejected body may reach the resource under the gate"),
                () -> assertEquals(
                        0, renamedMethod.invocations.get(), "no rejected body may reach the resource under the gate"));
    }

    // --- H4: getter-only collection with no backing field ---

    /**
     * The gate-level half of the H4 proof. {@link GetterOnlyCollectionNoBackingFieldBody#getItems()}
     * has no backing field named {@code items} at all — the property's only storage is a private field
     * named {@code internal}, populated in place through the getter, which is how Jackson binds a
     * getter-only mutable collection with no setter. The unit-level half (that the property is
     * published with its item schema) is {@code
     * dev.vertique.json.schema.GetterOnlyCollectionDescriptionTest
     * .getterOnlyCollectionWithNoBackingFieldPublishesItsItemSchema} in {@code vertique-json-schema}.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("H4: the gate rejects a wrong-typed item and accepts a valid body for a getter-only collection"
            + " with no backing field")
    void getterOnlyCollectionWithNoBackingFieldGateRejectsWrongTypedItemsAndAcceptsValidBody() throws Exception {
        GetterOnlyCollectionResource resource = new GetterOnlyCollectionResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> rejected = post(gatePort, "/getter-only-collection/items", "{\"items\":[\"x\"]}");
        HttpResponse<Buffer> accepted = post(gatePort, "/getter-only-collection/items", "{\"items\":[1,2,3]}");

        assertEquals(
                400,
                rejected.statusCode(),
                "a string item must be rejected against the published items schema (type: integer); body: "
                        + rejected.bodyAsString());
        assertEquals(
                200, accepted.statusCode(), "a well-typed body must be accepted; body: " + accepted.bodyAsString());
        assertEquals(
                "items=[1, 2, 3]",
                accepted.bodyAsString(),
                "the accepted body must bind through the getter-only mutable-collection property");
        assertEquals(
                1,
                resource.invocations.get(),
                "only the well-typed body may have reached the resource; the rejected one must not");
    }

    // --- BG1: Lombok builder, constrained private field, no getter — validator-present case ---

    /**
     * The gate-level half of the BG1 proof, validator-present only: the unit-level half (type-only
     * without a validator, {@code maxLength} with one) is {@code
     * dev.vertique.json.schema.MetadataConstraintSourceCoverageTest
     * .lombokBuilderNoGetterPropertyIsTypeOnlyWithoutAValidatorAndConstrainedWithOne} in {@code
     * vertique-json-schema}. Only the validator-present case has a gate row: without a validator, the
     * constraint is not enforced by the schema at all — nothing for a gate to reject — which is the
     * package's per-mode behavior, not a gap this proof needs to re-demonstrate at the HTTP boundary.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("BG1: on the validator-backed mount, the gate rejects a too-long name and accepts a valid one for"
            + " a Lombok builder's constrained, getter-less private field")
    void bg1GateRejectsTooLongNameAndAcceptsValidNameUnderAValidator() throws Exception {
        Bg1Resource resource = new Bg1Resource();
        int gatePort = start(MountFixtures.validatorBackedMount(vertx, RestTestContributions.none()), Set.of(resource));

        HttpResponse<Buffer> rejected = post(gatePort, "/bg1/name", "{\"name\":\"toolong\"}");
        HttpResponse<Buffer> accepted = post(gatePort, "/bg1/name", "{\"name\":\"ada\"}");

        assertEquals(
                400,
                rejected.statusCode(),
                "a 7-character name must be rejected against the metadata-supplement-rendered maxLength: 5;" + " body: "
                        + rejected.bodyAsString());
        assertEquals(
                200, accepted.statusCode(), "a 3-character name must be accepted; body: " + accepted.bodyAsString());
        assertEquals(
                "name=ada",
                accepted.bodyAsString(),
                "the accepted body must bind through the builder's own setter method");
        assertEquals(
                1,
                resource.invocations.get(),
                "only the well-typed body may have reached the resource; the rejected one must not");
    }

    // --- AC-005.2: case-insensitive non-ASCII key at the gate ---

    /**
     * The gate-level half of the AC-005.2 proof: {@code
     * dev.vertique.json.schema.CaseInsensitiveUnicodeFoldingTest.nonAsciiKeyRefusedByPropertyNames}
     * only matches the generated {@code propertyNames} regex against the confusable spelling —
     * nothing there exercises a real request. This sends the U+212A-folded key through a real HTTP
     * round trip and asserts the gate's actual rejection: a 400 with exactly one value-free detail,
     * never a 200 reaching the resource (which the binder's own case-insensitive lookup would
     * otherwise produce, routing the key straight to the constrained {@code key} member).
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("AC-005.2: the gate rejects a U+212A-folded key on a case-insensitive any-setter type, with one"
            + " value-free detail")
    void ac005NonAsciiKeyRejectedAtTheGateWithAValueFreeDetail() throws Exception {
        Ac005Resource resource = new Ac005Resource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> rejected = post(gatePort, "/ac005/case-insensitive", AC005_KELVIN_KEY_BODY);

        String rejectionBody = rejected.bodyAsString();
        JsonArray errors = problemErrors(rejectionBody);

        assertAll(
                () -> assertEquals(
                        400,
                        rejected.statusCode(),
                        "the propertyNames rule must refuse the U+212A-folded key; body: " + rejectionBody),
                () -> assertEquals(
                        0,
                        resource.invocations.get(),
                        "the binder's own case-insensitive lookup would route this key straight to the real"
                                + " \"key\" member if the gate did not refuse it first — the resource must never"
                                + " see it"),
                () -> assertNotNull(
                        errors,
                        "the rejection must carry an RFC 9457 problem body with an 'errors' array; the response"
                                + " body was: " + rejectionBody),
                () -> assertEquals(
                        1,
                        errors == null ? -1 : errors.size(),
                        "one structural rejection must contribute exactly one detail; body: " + rejectionBody),
                () -> assertFalse(
                        detail(errors).getString("path", "").isBlank(),
                        "the value-free detail must still identify a failing location; detail: "
                                + detail(errors).encode()),
                () -> assertFalse(
                        rejectionBody.contains(AC005_VALUE_MARKER),
                        "the detail must be value-free: the submitted value must never be echoed; body: "
                                + rejectionBody));
    }

    // --- F5 (security review round 1, MEDIUM): propertyNames/patternProperties must not echo the client ---

    /**
     * F5: {@code propertyNames} and {@code patternProperties} are the two keywords this package
     * extends beyond vertx-json-schema's own generated rules, and both had fallen to {@code
     * WebValidationStrategy}'s default {@code detail} branch, which returned the raw validator message
     * verbatim — that message names the client's own submitted key (both keywords) and, for {@code
     * patternProperties}, the generated regex too. Reuses AC-005.2's own fixtures: the confusable
     * U+212A-folded key must be refused with neither the raw key text nor the generated {@code
     * propertyNames} pattern reaching the response.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("F5: a propertyNames rejection echoes neither the submitted key nor the generated pattern")
    void f5PropertyNamesRejectionDoesNotEchoTheKeyOrPattern() throws Exception {
        Ac005Resource resource = new Ac005Resource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> rejected = post(gatePort, "/ac005/case-insensitive", AC005_KELVIN_KEY_BODY);
        String rejectionBody = rejected.bodyAsString();

        assertAll(
                () -> assertEquals(400, rejected.statusCode(), "body: " + rejectionBody),
                () -> assertFalse(
                        rejectionBody.contains(AC005_KELVIN_SIGN + "ey"),
                        "the submitted key spelling must never be echoed; body: " + rejectionBody),
                () -> assertFalse(
                        rejectionBody.contains("does not match schema"),
                        "the raw vertx-json-schema propertyNames message must never reach detail; body: "
                                + rejectionBody),
                () -> assertFalse(
                        rejectionBody.contains("x00-\\x7F") || rejectionBody.contains("x00-x7F"),
                        "the generated non-ASCII fold pattern must never be echoed; body: " + rejectionBody));
    }

    /**
     * F5's {@code patternProperties} half: a case-insensitively bound, <em>closed</em> type (no
     * any-setter) whose only member carries a {@code @Size} bound. A folded key ({@code "NAME"}) whose
     * value fails that bound is rejected by {@code patternProperties}, whose vertx-json-schema wrapper
     * message ("Property \"NAME\" matches pattern \"...\" but does not match associated schema") names
     * both the submitted key and the generated regex.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("F5: a patternProperties rejection echoes neither the submitted key nor the generated pattern")
    void f5PatternPropertiesRejectionDoesNotEchoTheKeyOrPattern() throws Exception {
        F5PatternPropertiesResource resource = new F5PatternPropertiesResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> rejected = post(gatePort, "/f5/case-insensitive", "{\"NAME\":\"toolong\"}");
        String rejectionBody = rejected.bodyAsString();

        assertAll(
                () -> assertEquals(400, rejected.statusCode(), "body: " + rejectionBody),
                () -> assertEquals(
                        0, resource.invocations.get(), "an oversized folded key must never reach the resource"),
                () -> assertFalse(
                        rejectionBody.contains("\"NAME\""),
                        "the submitted key spelling must never be echoed; body: " + rejectionBody),
                () -> assertFalse(
                        rejectionBody.contains("toolong"),
                        "the submitted value must never be echoed; body: " + rejectionBody),
                () -> assertFalse(
                        rejectionBody.contains("matches pattern"),
                        "the raw vertx-json-schema patternProperties message must never reach detail; body: "
                                + rejectionBody),
                () -> assertFalse(
                        rejectionBody.contains("[nN][aA][mM][eE]"),
                        "the generated case-fold pattern must never be echoed; body: " + rejectionBody));
    }

    /** F5: case-insensitively bound, closed (no any-setter) — the patternProperties-only shape. */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public static class F5CaseInsensitiveClosedBody {

        @Size(max = 3)
        public String name;
    }

    /** The resource for {@link F5CaseInsensitiveClosedBody}, on the unannotated floor profile. */
    @Path("/f5")
    public static class F5PatternPropertiesResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound name.
         *
         * @param body the F5 fixture body
         * @return the echoed name
         */
        @POST
        @Path("/case-insensitive")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "f5CaseInsensitiveEcho")
        public String echo(F5CaseInsensitiveClosedBody body) {
            invocations.incrementAndGet();
            return "name=" + body.name;
        }
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
     * vertiquehq/vertique-dev#598. A wrong-typed value under an undeclared key of a type whose
     * any-setter extras are described is reported at a non-structural keyword, so it takes the
     * concrete-detail path, and the instance location that path names ends in the client's own key.
     * The concrete detail's path must be cut back to the location the schema declares exactly as the
     * value-free detail's is, so the key — of whatever length the client sent — never reaches the 400
     * body, while the rejection still carries a detail.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A concrete detail under an undeclared any-setter key never echoes the key")
    void aConcreteDetailUnderAnUndeclaredKeyNeverEchoesTheKey() throws Exception {
        AnySetterResource resource = new AnySetterResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> rejected = post(gatePort, "/anysetter/string", ANY_SETTER_LONG_KEY_NUMBER_BODY);
        String body = rejected.bodyAsString();
        JsonArray errors = problemErrors(body);

        assertAll(
                () -> assertEquals(400, rejected.statusCode(), "the wrong-typed extra must be rejected; body: " + body),
                () -> assertEquals(0, resource.invocations.get(), "the rejected body must not reach the resource"),
                () -> assertTrue(
                        errors != null && !errors.isEmpty(), "the rejection must still carry a detail; body: " + body),
                () -> assertFalse(
                        body.contains(ANY_SETTER_LONG_KEY),
                        "the client's undeclared key must not be echoed in the response; body: " + body));
    }

    /**
     * vertiquehq/vertique-dev#598, the precision half. The schema library publishes a nullable nested
     * object as {@code anyOf: [{"type":"null"}, {"$ref": ...}]}, so a violation inside it is reported
     * at an instance location whose field segment the schema declares only inside a composition
     * branch. The concrete detail must name that field, exactly as it names a field of a plain nested
     * object, while an undeclared client key under the same nullable object is still cut back to the
     * object and never reaches the response.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A concrete detail inside a nullable nested object names the declared field")
    void aConcreteDetailInsideANullableNestedObjectNamesTheField() throws Exception {
        NestedPathResource resource = new NestedPathResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> nullable = post(gatePort, "/nested-path", NULLABLE_NESTED_LONG_NAME_BODY);
        HttpResponse<Buffer> plain = post(gatePort, "/nested-path", PLAIN_NESTED_LONG_NAME_BODY);
        HttpResponse<Buffer> clientKey = post(gatePort, "/nested-path", NULLABLE_NESTED_CLIENT_KEY_BODY);
        HttpResponse<Buffer> plainClientKey = post(gatePort, "/nested-path", PLAIN_NESTED_CLIENT_KEY_BODY);
        String nullableBody = nullable.bodyAsString();
        String plainBody = plain.bodyAsString();
        String clientKeyBody = clientKey.bodyAsString();
        String plainClientKeyBody = plainClientKey.bodyAsString();

        assertAll(
                () -> assertEquals(400, nullable.statusCode(), "body: " + nullableBody),
                () -> assertEquals(
                        List.of("#/pet/name"),
                        paths(problemErrors(nullableBody), "maxLength"),
                        "a violation inside the nullable nested object must name the declared field; body: "
                                + nullableBody),
                () -> assertEquals(400, plain.statusCode(), "body: " + plainBody),
                () -> assertEquals(
                        List.of("#/plain/name"),
                        paths(problemErrors(plainBody), "maxLength"),
                        "a declared plain nested location is named unchanged; body: " + plainBody),
                () -> assertEquals(400, clientKey.statusCode(), "body: " + clientKeyBody),
                () -> assertEquals(
                        List.of("#/pet", "#/pet"),
                        paths(problemErrors(clientKeyBody), "type"),
                        "the null branch's type failure and the extra's, under an undeclared client key, both"
                                + " name the nullable object: the key is cut back; body: " + clientKeyBody),
                () -> assertFalse(
                        clientKeyBody.contains(NESTED_CLIENT_KEY),
                        "the client's undeclared key must not be echoed in the response; body: " + clientKeyBody),
                () -> assertEquals(400, plainClientKey.statusCode(), "body: " + plainClientKeyBody),
                () -> assertEquals(
                        List.of("#/plain"),
                        paths(problemErrors(plainClientKeyBody), "type"),
                        "an undeclared client key under the plain nested object is cut back to it; body: "
                                + plainClientKeyBody),
                () -> assertFalse(
                        plainClientKeyBody.contains(NESTED_CLIENT_KEY),
                        "the client's undeclared key must not be echoed in the response; body: " + plainClientKeyBody),
                () -> assertEquals(0, resource.invocations.get(), "no rejected body may reach the resource"));
    }

    /**
     * Returns the {@code path} of every detail carrying {@code keyword} as its {@code type}.
     *
     * @param errors  the decoded {@code errors} array, possibly {@code null}
     * @param keyword the failed keyword
     * @return the paths, in response order; empty when there is no such detail
     */
    private static List<String> paths(JsonArray errors, String keyword) {
        List<String> paths = new ArrayList<>();
        if (errors != null) {
            for (int index = 0; index < errors.size(); index++) {
                JsonObject error = errors.getJsonObject(index);
                if (keyword.equals(error.getString("type"))) {
                    paths.add(error.getString("path"));
                }
            }
        }
        return paths;
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

    /** AC-005.2: KELVIN SIGN, which folds to ASCII 'k' under Jackson's locale-independent case fold. */
    private static final String AC005_KELVIN_SIGN = "K";

    /**
     * AC-005.2: the confusable spelling's value — deliberately within the real {@code key} member's own
     * {@code @Size(max = 3)}, so a 400 here can only come from the {@code propertyNames} refusal, never
     * a coincidental length violation on the member the binder's case-insensitive lookup would otherwise
     * route it to. Distinctive enough that its absence from the response is still provable.
     */
    private static final String AC005_VALUE_MARKER = "AC5";

    /** AC-005.2: a body spelling the constrained {@code key} member's name with the confusable Kelvin sign. */
    private static final String AC005_KELVIN_KEY_BODY =
            "{\"" + AC005_KELVIN_SIGN + "ey\":\"" + AC005_VALUE_MARKER + "\"}";

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

    /** A very long, distinctive undeclared key: its absence from the response is provable. */
    private static final String ANY_SETTER_LONG_KEY = "CLIENT-KEY-598-MUST-NOT-ECHO-" + "k".repeat(2000);

    /** A JSON number under the long undeclared key, where the string any-setter declares strings. */
    private static final String ANY_SETTER_LONG_KEY_NUMBER_BODY = "{\"name\":\"a\",\"" + ANY_SETTER_LONG_KEY + "\":5}";

    /** A very long, distinctive undeclared key under a nested object. */
    private static final String NESTED_CLIENT_KEY = "CLIENT-KEY-598-NESTED-" + "n".repeat(500);

    /** A too-long declared name inside the nullable nested object. */
    private static final String NULLABLE_NESTED_LONG_NAME_BODY = "{\"pet\":{\"name\":\"toolong\"}}";

    /** A too-long declared name inside the plain nested object. */
    private static final String PLAIN_NESTED_LONG_NAME_BODY = "{\"plain\":{\"name\":\"toolong\"}}";

    /** A number under an undeclared key inside the nullable nested object, whose extras are strings. */
    private static final String NULLABLE_NESTED_CLIENT_KEY_BODY =
            "{\"pet\":{\"name\":\"a\",\"" + NESTED_CLIENT_KEY + "\":5}}";

    /** A number under an undeclared key inside the plain nested object, whose extras are strings. */
    private static final String PLAIN_NESTED_CLIENT_KEY_BODY =
            "{\"plain\":{\"name\":\"a\",\"" + NESTED_CLIENT_KEY + "\":5}}";

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
     * rest-020-refresh: a transforming constructor whose parameter's compiled Java name coincides with
     * an unrelated field's own name. No getter is declared on purpose: a public getter here would let
     * Jackson pair the private field with a getter-implied property of its own name — a second,
     * genuinely field-backed property this fixture does not intend to exercise. The resource below
     * reads {@link #amount} directly; both classes are nested in this same top-level type, so the
     * private field is accessible to it.
     */
    public static class TransformingConstructorBody {

        @Max(10)
        private final int amount;

        @JsonCreator
        public TransformingConstructorBody(@JsonProperty("amount_cents") int amount) {
            this.amount = amount / 100;
        }
    }

    /**
     * The resource on the {@code vertique} floor for {@link TransformingConstructorBody}: it carries no
     * {@code @JsonProfile}, so the gate schema is generated from the unannotated floor profile.
     */
    @Path("/transforming")
    public static class TransformingConstructorResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound (already-transformed) amount.
         *
         * @param body the transforming-constructor fixture body
         * @return the echoed amount
         */
        @POST
        @Path("/amount")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "transformingConstructorAmountEcho")
        public String echo(TransformingConstructorBody body) {
            invocations.incrementAndGet();
            return "amount=" + body.amount;
        }
    }

    /**
     * deserializer-driven-schema spike: a hand-written (non-Lombok) builder whose setter divides the
     * incoming value by 100 before assigning it to the built field of the same wire name — the
     * gate-level counterpart to {@link
     * dev.vertique.json.schema.BuilderWireNameJoinTest.TransformingBuilderDto}. The builder class is
     * named plainly ({@code Builder}), not in the Lombok {@code TransformingBuilderBodyBuilder}
     * convention, so {@code BuilderBorrowDetector} matches neither {@code @lombok.Generated} nor its
     * fallback shape for it.
     */
    @JsonDeserialize(builder = TransformingBuilderBody.Builder.class)
    public static class TransformingBuilderBody {

        @Max(10)
        private final int amount;

        private TransformingBuilderBody(int amount) {
            this.amount = amount;
        }

        @JsonPOJOBuilder(withPrefix = "")
        public static final class Builder {
            private int amount;

            public Builder amount(int amountCents) {
                this.amount = amountCents / 100;
                return this;
            }

            public TransformingBuilderBody build() {
                return new TransformingBuilderBody(amount);
            }
        }
    }

    /**
     * The resource on the {@code vertique} floor for {@link TransformingBuilderBody}: it carries no
     * {@code @JsonProfile}, so the gate schema is generated from the unannotated floor profile.
     */
    @Path("/transforming-builder")
    public static class TransformingBuilderResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound (already-transformed) amount.
         *
         * @param body the transforming-builder fixture body
         * @return the echoed amount
         */
        @POST
        @Path("/amount")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "transformingBuilderAmountEcho")
        public String echo(TransformingBuilderBody body) {
            invocations.incrementAndGet();
            return "amount=" + body.amount;
        }
    }

    /**
     * Round 2: a hand-written builder whose {@code @JsonPOJOBuilder} carries a non-empty
     * {@code withPrefix} ({@code "with"}, never the empty string {@code @Jacksonized} always emits)
     * fails {@code BuilderBorrowDetector}'s shape match on that condition alone. Both built properties
     * carry a getter, so under the round-2 owner ruling the borrow no longer depends on the shape at
     * all.
     */
    @JsonDeserialize(builder = Round2WithPrefixBuilderBody.Builder.class)
    public static class Round2WithPrefixBuilderBody {

        @Size(max = 3)
        private final String name;

        @Max(10)
        private final int level;

        private Round2WithPrefixBuilderBody(String name, int level) {
            this.name = name;
            this.level = level;
        }

        /**
         * Returns the built name.
         *
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the built level.
         *
         * @return the level
         */
        public int getLevel() {
            return level;
        }

        @JsonPOJOBuilder(withPrefix = "with", buildMethodName = "build")
        public static final class Builder {
            private String name;
            private int level;

            public Builder withName(String name) {
                this.name = name;
                return this;
            }

            public Builder withLevel(int level) {
                this.level = level;
                return this;
            }

            public Round2WithPrefixBuilderBody build() {
                return new Round2WithPrefixBuilderBody(name, level);
            }
        }
    }

    /** The resource for {@link Round2WithPrefixBuilderBody}, on the unannotated floor profile. */
    @Path("/round2-with-prefix")
    public static class Round2WithPrefixBuilderResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound name and level.
         *
         * @param body the round-2 with-prefix fixture body
         * @return the echoed name and level
         */
        @POST
        @Path("/fields")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "round2WithPrefixFieldsEcho")
        public String echo(Round2WithPrefixBuilderBody body) {
            invocations.incrementAndGet();
            return "name=" + body.getName() + " level=" + body.getLevel();
        }
    }

    /**
     * Round 2: a hand-written builder class named plainly ({@code Factory}), not in the Lombok
     * {@code <Type>Builder} convention, fails {@code BuilderBorrowDetector}'s naming condition alone.
     * Both built properties carry a getter.
     */
    @JsonDeserialize(builder = Round2PlainBuilderClassBody.Factory.class)
    public static class Round2PlainBuilderClassBody {

        @Size(max = 3)
        private final String name;

        @Max(10)
        private final int level;

        private Round2PlainBuilderClassBody(String name, int level) {
            this.name = name;
            this.level = level;
        }

        /**
         * Returns the built name.
         *
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the built level.
         *
         * @return the level
         */
        public int getLevel() {
            return level;
        }

        @JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
        public static final class Factory {
            private String name;
            private int level;

            public Factory name(String name) {
                this.name = name;
                return this;
            }

            public Factory level(int level) {
                this.level = level;
                return this;
            }

            public Round2PlainBuilderClassBody build() {
                return new Round2PlainBuilderClassBody(name, level);
            }
        }
    }

    /** The resource for {@link Round2PlainBuilderClassBody}, on the unannotated floor profile. */
    @Path("/round2-plain-class")
    public static class Round2PlainBuilderClassResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound name and level.
         *
         * @param body the round-2 plain-builder-class fixture body
         * @return the echoed name and level
         */
        @POST
        @Path("/fields")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "round2PlainClassFieldsEcho")
        public String echo(Round2PlainBuilderClassBody body) {
            invocations.incrementAndGet();
            return "name=" + body.getName() + " level=" + body.getLevel();
        }
    }

    /**
     * Round 2: a hand-written builder whose build method is named {@code create}, not {@code build},
     * fails {@code BuilderBorrowDetector}'s build-method-name condition alone. Both built properties
     * carry a getter.
     */
    @JsonDeserialize(builder = Round2RenamedBuildMethodBody.Builder.class)
    public static class Round2RenamedBuildMethodBody {

        @Size(max = 3)
        private final String name;

        @Max(10)
        private final int level;

        private Round2RenamedBuildMethodBody(String name, int level) {
            this.name = name;
            this.level = level;
        }

        /**
         * Returns the built name.
         *
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Returns the built level.
         *
         * @return the level
         */
        public int getLevel() {
            return level;
        }

        @JsonPOJOBuilder(withPrefix = "", buildMethodName = "create")
        public static final class Builder {
            private String name;
            private int level;

            public Builder name(String name) {
                this.name = name;
                return this;
            }

            public Builder level(int level) {
                this.level = level;
                return this;
            }

            public Round2RenamedBuildMethodBody create() {
                return new Round2RenamedBuildMethodBody(name, level);
            }
        }
    }

    /** The resource for {@link Round2RenamedBuildMethodBody}, on the unannotated floor profile. */
    @Path("/round2-renamed-method")
    public static class Round2RenamedBuildMethodResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound name and level.
         *
         * @param body the round-2 renamed-build-method fixture body
         * @return the echoed name and level
         */
        @POST
        @Path("/fields")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "round2RenamedMethodFieldsEcho")
        public String echo(Round2RenamedBuildMethodBody body) {
            invocations.incrementAndGet();
            return "name=" + body.getName() + " level=" + body.getLevel();
        }
    }

    /**
     * H4: a getter-only {@code List<Integer>} with no backing field named {@code items} at all — its
     * only storage is {@link #internal}, an unrelated field name Jackson populates in place through
     * the getter (no setter is declared). The generator's scoped-member describe path must still find
     * and describe this property through the schema library's own method scope, the same as any other
     * getter, publishing an {@code items} keyword with the element type — not fall back to an opaque,
     * unscoped description.
     */
    public static class GetterOnlyCollectionNoBackingFieldBody {

        private final List<Integer> internal = new ArrayList<>();

        /**
         * Returns the live, mutable backing list.
         *
         * @return the items
         */
        public List<Integer> getItems() {
            return internal;
        }
    }

    /**
     * The resource on the {@code vertique} floor for {@link GetterOnlyCollectionNoBackingFieldBody}: it
     * carries no {@code @JsonProfile}, so the gate schema is generated from the unannotated floor
     * profile.
     */
    @Path("/getter-only-collection")
    public static class GetterOnlyCollectionResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound items.
         *
         * @param body the getter-only-collection-with-no-backing-field fixture body
         * @return the echoed items
         */
        @POST
        @Path("/items")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "getterOnlyCollectionItemsEcho")
        public String echo(GetterOnlyCollectionNoBackingFieldBody body) {
            invocations.incrementAndGet();
            return "items=" + body.getItems();
        }
    }

    /**
     * BG1: a Lombok {@code @Builder @Jacksonized} type with a constrained private field and
     * deliberately no getter — {@code LombokBuilderDto} in {@code corpus}, this fixture's
     * {@code @Getter}-carrying counterpart, stays out of reach here since the corpus set is frozen for
     * byte-exact golden comparisons. Jackson's own introspection does not see {@code name} as a
     * property at all without a public accessor, so the floor's builder-constraint borrow
     * ({@code InputPropertyDescriber#borrowBuilderFieldAttributes}, which reads {@code
     * BeanDescription#findProperties()}) has nothing to find; Bean Validation is unaffected, since it
     * reads the constrained field directly by Java name.
     */
    @Builder
    @Jacksonized
    public static class Bg1NoGetterBody {

        @Size(max = 5)
        private final String name;
    }

    /**
     * The resource on the {@code vertique} floor for {@link Bg1NoGetterBody}, mounted on {@link
     * MountFixtures#validatorBackedMount}: it carries no {@code @JsonProfile}, so the gate schema is
     * generated from the unannotated floor profile, and the mount's own graph supplies a real {@link
     * jakarta.validation.Validator} so the metadata supplement is active.
     */
    @Path("/bg1")
    public static class Bg1Resource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound name.
         *
         * @param body the BG1 fixture body
         * @return the echoed name
         */
        @POST
        @Path("/name")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "bg1NameEcho")
        public String echo(Bg1NoGetterBody body) {
            invocations.incrementAndGet();
            return "name=" + body.name;
        }
    }

    /**
     * AC-005.2: a case-insensitively bound, extras-described (any-setter) type — the gate-level
     * counterpart to {@code CaseInsensitiveUnicodeFoldingTest.CaseInsensitiveWithExtras} in {@code
     * vertique-json-schema}, whose own proof only matches the generated {@code propertyNames} regex
     * against the confusable spelling, never a real request. U+212A KELVIN SIGN folds to ASCII
     * {@code 'k'} under Jackson's locale-independent {@code String#toLowerCase()}, so the binder would
     * route a key spelled with it straight into the real, constrained {@link #key} member — the
     * {@code propertyNames} rule this type's document carries refuses any non-ASCII key outright instead.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    public static class Ac005CaseInsensitiveAnySetterBody {

        @Size(max = 3)
        public String key;

        private final Map<String, Object> extras = new LinkedHashMap<>();

        /**
         * Routes an undeclared property into {@link #extras}.
         *
         * @param name  the property name
         * @param value the property value
         */
        @JsonAnySetter
        public void put(String name, Object value) {
            extras.put(name, value);
        }
    }

    /**
     * The resource on the {@code vertique} floor for {@link Ac005CaseInsensitiveAnySetterBody}: it
     * carries no {@code @JsonProfile}, so the gate schema is generated from the unannotated floor
     * profile.
     */
    @Path("/ac005")
    public static class Ac005Resource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound key.
         *
         * @param body the AC-005.2 fixture body
         * @return the echoed key
         */
        @POST
        @Path("/case-insensitive")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "ac005CaseInsensitiveEcho")
        public String echo(Ac005CaseInsensitiveAnySetterBody body) {
            invocations.incrementAndGet();
            return "key=" + body.key;
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

    // --- rest-023 T002 (D002, N14/N14n): Optional-typed extras value ---

    /** A named property beside an any-setter whose extras value is {@code Optional<Plain>} (N14/N14n). */
    public static class OptionalAnySetterExtrasDto {

        /** An ordinary property the document publishes. */
        public String label;

        /** The collected extras, whose declared value type is {@code Optional<Plain>}. */
        private final Map<String, Optional<Plain>> extras = new LinkedHashMap<>();

        /**
         * Collects an extra key.
         *
         * @param key   the extra key
         * @param value the extra value
         */
        @JsonAnySetter
        public void putExtra(String key, Optional<Plain> value) {
            extras.put(key, value);
        }

        /**
         * Returns the collected extras.
         *
         * @return the collected extras
         */
        public Map<String, Optional<Plain>> collectedExtras() {
            return extras;
        }

        /** {@code Plain}'s own schema is what a declared-{@code Optional} extras value must describe. */
        public static class Plain {

            /** The constraint an {@code Optional<Plain>} extras value must still describe (N14). */
            @Size(max = 3)
            public String name;
        }
    }

    /** The rest-023 T002 resource: a single route accepting the {@code Optional}-typed extras body. */
    @Path("/optional-extras")
    public static class OptionalExtrasResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the label and whether the extras value {@code x} was present, so an accepted body is
         * observable as more than a status code.
         *
         * @param body the {@code Optional}-typed extras body
         * @return the echoed value
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "optionalExtrasEcho")
        public String echo(OptionalAnySetterExtrasDto body) {
            invocations.incrementAndGet();
            Optional<OptionalAnySetterExtrasDto.Plain> x =
                    body.collectedExtras().get("x");
            String rendered = x == null ? "absent" : x.map(plain -> plain.name).orElse("empty");
            return "label=" + body.label + " x=" + rendered;
        }
    }

    // --- rest-023 T003 (D001): TP-004/TP-010, map value shapes and the single any-setter N16 shape ---

    /** S2a/S2b/S2c/S2d — one holder carrying every rest-023 T003 ordinary-map-property value shape. */
    public static class MapValueShapesDto {

        /** S2b — a type-use-constrained String value. */
        public Map<String, @Size(max = 3) String> tags;

        /** S2a — a bean value, whose own constraint must be described too. */
        public Map<String, Plain> labels;

        /** S2d — an Optional-wrapped bean value. */
        public Map<String, Optional<Plain>> opts;

        /** S2c — an opaque, unconstrained control value. */
        public Map<String, com.fasterxml.jackson.databind.JsonNode> raw;

        /** {@code Plain}'s own declared constraint, shared by {@code labels} and {@code opts}. */
        public static class Plain {

            /** The constraint a map's bean value type must still describe. */
            @Size(max = 3)
            public String name;
        }
    }

    /** The rest-023 T003 resource: a single route accepting the map value shapes body. */
    @Path("/map-values")
    public static class MapValuesResource {

        /** Counts successful invocations, so a rejected body's non-entry can be asserted. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes acceptance.
         *
         * @param body the accepted body
         * @return a fixed acknowledgement
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mapValuesEcho")
        public String echo(MapValueShapesDto body) {
            invocations.incrementAndGet();
            return "ok";
        }
    }

    /** N16 — a single, non-conjoined any-setter whose value type carries a type-use constraint. */
    public static class SingleAnySetterTypeUseDto {

        /** The any-setter's backing storage: the type-use-constrained value position under test. */
        @JsonAnySetter
        public Map<String, @Size(max = 3) String> extras = new LinkedHashMap<>();
    }

    /** The rest-023 T003 resource: a single route accepting the single any-setter N16 body. */
    @Path("/any-setter-type-use")
    public static class AnySetterTypeUseResource {

        /** Counts successful invocations, so a rejected body's non-entry can be asserted. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes acceptance.
         *
         * @param body the accepted body
         * @return a fixed acknowledgement
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "anySetterTypeUseEcho")
        public String echo(SingleAnySetterTypeUseDto body) {
            invocations.incrementAndGet();
            return "ok";
        }
    }

    // --- rest-023 T004 (D004): TP-002, several any-setters sharing a wire key ---

    /** UW-2AS child: a type-use-constrained any-setter, sharing its wire key with the parent's own. */
    public static class SharedAnySetterConjunctionChild {

        /** The child's own any-setter, type-use-constrained (N16 shape). */
        @JsonAnySetter
        public Map<String, @Size(max = 3) String> extras = new LinkedHashMap<>();
    }

    /** UW-2AS parent: its own any-setter, plus the unwrapped child's own, sharing every wire key. */
    public static class SharedAnySetterConjunctionDto {

        /** The unwrapped child, declaring its own any-setter. */
        @JsonUnwrapped
        public SharedAnySetterConjunctionChild inner;

        /** The parent's own any-setter, unconstrained. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** The rest-023 T004 resource for the UW-2AS shared-any-setter-key shape. */
    @Path("/shared-any-setter-conjunction")
    public static class SharedAnySetterConjunctionResource {

        /** Counts successful invocations, so a rejected body's non-entry can be asserted. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes acceptance.
         *
         * @param body the accepted body
         * @return a fixed acknowledgement
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "sharedAnySetterConjunctionEcho")
        public String echo(SharedAnySetterConjunctionDto body) {
            invocations.incrementAndGet();
            return "ok";
        }
    }

    /** The parent's own any-setter value type: a bean with its own constraint. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConjunctionLeft {

        /** The constraint this value type's own subschema must carry once inlined. */
        @Size(max = 3)
        public String label;
    }

    /** The unwrapped child's own any-setter value type: a bean with its own, different constraint. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConjunctionRight {

        /** The constraint this value type's own subschema must carry once inlined. */
        @Size(max = 2)
        public String code;
    }

    /** The unwrapped child, declaring its own bean-valued any-setter. */
    public static class ObjectValuedAnySetterPairChild {

        /** The child's own any-setter, bean-valued. */
        @JsonAnySetter
        public Map<String, ConjunctionRight> extras = new LinkedHashMap<>();
    }

    /** An object-valued two-any-setter pair: both value types are beans, each with its own constraint. */
    public static class ObjectValuedAnySetterPairDto {

        /** The unwrapped child, declaring its own bean-valued any-setter. */
        @JsonUnwrapped
        public ObjectValuedAnySetterPairChild inner;

        /** The parent's own any-setter, bean-valued. */
        @JsonAnySetter
        public Map<String, ConjunctionLeft> extras = new LinkedHashMap<>();
    }

    /** The rest-023 T004 resource for the object-valued two-any-setter-pair shape. */
    @Path("/object-valued-any-setter-pair")
    public static class ObjectValuedAnySetterPairResource {

        /** Counts successful invocations, so a rejected body's non-entry can be asserted. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes acceptance.
         *
         * @param body the accepted body
         * @return a fixed acknowledgement
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "objectValuedAnySetterPairEcho")
        public String echo(ObjectValuedAnySetterPairDto body) {
            invocations.incrementAndGet();
            return "ok";
        }
    }

    // --- rest-023 T005 (D005): TP-003, member-level closure ---

    /** The value type both the FALSE-closed member and the unannotated open control member reference. */
    public static class ClosedChild {

        /** An ordinary constrained property, beside the any-setter. */
        @Size(max = 3)
        public String name;

        /** The any-setter the member-level FALSE closes for the annotated member only. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** M10: a member-level FALSE-closed reference to {@link ClosedChild}, plus an unannotated control. */
    public static class MemberLevelClosureDto {

        /** The FALSE-closed member under test. */
        @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
        public ClosedChild child;

        /** The unannotated control, referencing the same value type. */
        public ClosedChild open;
    }

    /** The rest-023 T005 resource for the member-level closure shape. */
    @Path("/member-level-closure")
    public static class MemberLevelClosureResource {

        /** Counts successful invocations, so a rejected body's non-entry can be asserted. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes acceptance.
         *
         * @param body the accepted body
         * @return a fixed acknowledgement
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "memberLevelClosureEcho")
        public String echo(MemberLevelClosureDto body) {
            invocations.incrementAndGet();
            return "ok";
        }
    }

    // --- C1 (spike/deserializer-driven-schema round 4, CRITICAL): sibling-ordered unwrapped pair ---

    /**
     * The first unwrapped sibling, carrying no any-setter of its own: an aliased, constrained member and
     * a hidden, constrained member.
     */
    public static class SiblingUnwrappedA {
        @JsonAlias("ak")
        @Size(max = 3)
        public String aname;

        @Schema(hidden = true)
        @Size(max = 3)
        public String secret;
    }

    /** The second unwrapped sibling: the any-setter lives here, not on {@link SiblingUnwrappedA}. */
    public static class SiblingUnwrappedB {
        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /**
     * C1: two {@code @JsonUnwrapped} siblings, in this order, where only the *second* sibling carries the
     * {@code @JsonAnySetter}.
     */
    public static class SiblingUnwrappedParent {
        @com.fasterxml.jackson.annotation.JsonUnwrapped
        public SiblingUnwrappedA a;

        @com.fasterxml.jackson.annotation.JsonUnwrapped
        public SiblingUnwrappedB b;
    }

    /** The C1 resource: a single route accepting the sibling-ordered unwrapped pair. */
    @Path("/sibling-unwrapped")
    public static class SiblingUnwrappedResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Accepts a sibling-unwrapped body.
         *
         * @param body the body
         * @return a fixed marker
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "siblingUnwrappedEcho")
        public String echo(SiblingUnwrappedParent body) {
            invocations.incrementAndGet();
            return "ok";
        }
    }

    @Test
    @DisplayName("C1: a sibling unwrapped child's hidden member is rejected at the gate even though the"
            + " any-setter is declared on a different sibling, processed later")
    void siblingOrderedUnwrappedHiddenMemberIsRejectedAtTheGate() throws Exception {
        SiblingUnwrappedResource resource = new SiblingUnwrappedResource();
        int gatePort = start(gateMount(), Set.of(resource));

        int aliasOversized =
                post(gatePort, "/sibling-unwrapped", "{\"ak\":\"abcdefgh\"}").statusCode();
        int hiddenMemberKey =
                post(gatePort, "/sibling-unwrapped", "{\"secret\":5}").statusCode();
        int validCanonical =
                post(gatePort, "/sibling-unwrapped", "{\"aname\":\"abc\"}").statusCode();

        assertAll(
                () -> assertEquals(
                        400,
                        aliasOversized,
                        "the first sibling's alias \"ak\" carries its own @Size(max = 3), so an over-long"
                                + " value must be rejected"),
                () -> assertEquals(
                        400,
                        hiddenMemberKey,
                        "C1 DECISIVE: the first-processed unwrapped sibling's hidden member \"secret\" must"
                                + " be rejected at the gate rather than reaching the resource through the"
                                + " extras bucket unconstrained, even though the any-setter is declared on the"
                                + " second sibling, processed later"),
                () -> assertEquals(200, validCanonical, "the first sibling's own canonical property must be admitted"),
                () -> assertEquals(
                        1, resource.invocations.get(), "only the one valid body may have reached the resource"));
    }

    // --- vertique-dev#598 nested-path fixtures ---

    /** A nested object with one constrained name and string extras. */
    public static class NestedPet {

        /** The declared, constrained name. */
        @Size(max = 3)
        public String name;

        /** The collected extras. */
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
    }

    /** One nullable and one plain nested object of the same type. */
    public static class NestedPathDto {

        /** Published as {@code anyOf: [null, $ref]}. */
        @Schema(nullable = true)
        public NestedPet pet;

        /** Published as a plain {@code $ref}. */
        public NestedPet plain;
    }

    /** The nested-path resource on the {@code vertique} floor. */
    @Path("/nested-path")
    public static class NestedPathResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Accepts a nested-path body.
         *
         * @param body the body
         * @return a fixed marker
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "nestedPathEcho")
        public String echo(NestedPathDto body) {
            invocations.incrementAndGet();
            return "ok";
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

    // --- C-1 (round 5 review finding, spike/deserializer-driven-schema): the gate-level half ---

    /**
     * Forwards every operation to the delegate, exactly as a bean-preserving wrapper module would —
     * mirrors {@code DelegatingDeserializerWrapperTest.ForwardingDelegatingDeserializer} in {@code
     * vertique-json-schema}, duplicated here since that fixture is package-private in a sibling
     * module and this class needs its own mapper-wide {@link BeanDeserializerModifier}.
     */
    static final class C1ForwardingDelegatingDeserializer extends DelegatingDeserializer {
        C1ForwardingDelegatingDeserializer(JsonDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
            return new C1ForwardingDelegatingDeserializer(newDelegatee);
        }
    }

    /** An ordinary constrained member, unwrapped onto the parent rather than published as its own object. */
    static final class C1Child {
        @Size(max = 3)
        public String name;
    }

    /** Carries {@link C1Child} through {@code @JsonUnwrapped}, under the mapper-wide wrapper profile below. */
    static final class C1Parent {
        @JsonUnwrapped
        public C1Child child;
    }

    /**
     * Builds the test-scope profile a {@code @JsonProfile("c1-delegating-wrapper")} route selects: a
     * plain mapper whose {@link BeanDeserializerModifier} wraps every bean deserializer in a
     * forwarding {@link DelegatingDeserializer}, the same mapper-wide shape {@code
     * DelegatingDeserializerWrapperTest} exercises at the unit level.
     *
     * @return the wrapper profile, registered through {@link RestTestContributions}
     */
    private static JsonMapperProfile c1DelegatingWrapperProfile() {
        // Vert.x JSON support: the registry probes a contributed mapper with a JsonObject round trip.
        ObjectMapper mapper =
                JsonMapper.builder().addModule(VertxJsonSupport.module()).build();
        mapper.registerModule(new SimpleModule() {
            @Override
            public void setupModule(SetupContext context) {
                super.setupModule(context);
                context.addBeanDeserializerModifier(new BeanDeserializerModifier() {
                    @Override
                    public JsonDeserializer<?> modifyDeserializer(
                            DeserializationConfig config, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
                        return new C1ForwardingDelegatingDeserializer(deserializer);
                    }
                });
            }
        });
        return JsonMapperProfiles.of(JsonProfileId.of("c1-delegating-wrapper"), mapper);
    }

    /**
     * The resource for {@link C1Parent}, mounted under the {@code c1-delegating-wrapper} profile.
     */
    @Path("/c1")
    @JsonProfile("c1-delegating-wrapper")
    public static class C1Resource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the unwrapped child's name, so an accepted body is observable as more than a status code.
         *
         * @param body the request body
         * @return the echoed value
         */
        @POST
        @Path("/unwrapped")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "c1UnwrappedEcho")
        public String echo(C1Parent body) {
            invocations.incrementAndGet();
            return "name=" + (body.child == null ? null : body.child.name);
        }
    }

    /**
     * C-1 (independent review, by-reading finding). Under a mapper-wide {@code
     * BeanDeserializerModifier} that wraps every bean deserializer in a forwarding {@code
     * DelegatingDeserializer}, the review's premise was that {@code InputPropertyDescriber}'s
     * unwrapped-child loop silently skips an {@code @JsonUnwrapped} child, publishing neither its
     * property nor its {@code @Size(max = 3)} constraint — so the gate would accept an oversized value.
     *
     * <p>CORRECTED (round 6 finding): this row never actually characterized the loop, and was never red.
     * {@link C1Child}'s {@code name} member is an ordinary, plain property — not hidden, not an alias,
     * not on an any-setter type — and the schema library's own generation independently publishes a bare
     * {@code @JsonUnwrapped} member's plain properties onto the parent regardless of whether this
     * describer's own loop processes the child at all (measured directly, mirroring {@code
     * DelegatingDeserializerWrapperTest.unwrappedChildUnderMapperWideDelegatingWrapperIsDescribed}'s own
     * corrected Javadoc: forcing the loop to unconditionally skip every unwrapped child still leaves
     * {@code "name"} published with its {@code @Size(max = 3)}). This row is kept as a positive
     * regression guard for that already-independent library behavior, never as the loop's own
     * discriminating proof — the loop's own bug is discriminating only for a shape the library's own
     * flattening does not cover on its own, which the C-2 row below exercises instead.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("C-1: under a mapper-wide DelegatingDeserializer wrapper, the gate rejects an oversized"
            + " unwrapped child value")
    void c1UnwrappedChildUnderDelegatingWrapperIsRejectedAtTheGate() throws Exception {
        C1Resource resource = new C1Resource();
        RestTestContributions contributions = RestTestContributions.builder()
                .addJsonMapperProfile(c1DelegatingWrapperProfile())
                .build();
        int port = start(MountFixtures.mount(vertx, new JsonObject(), contributions), Set.of(resource));

        HttpResponse<Buffer> rejected = post(port, "/c1/unwrapped", "{\"name\":\"abcdef\"}");

        assertEquals(
                400,
                rejected.statusCode(),
                "C-1 DECISIVE: the unwrapped child's own @Size(max = 3) must reject a 6-character value under"
                        + " the mapper-wide DelegatingDeserializer wrapper profile; body: "
                        + rejected.bodyAsString());
        assertEquals(
                0,
                resource.invocations.get(),
                "a rejected body must never reach the resource — this row characterizes the schema library's"
                        + " own independent flattening of a plain unwrapped property (see the corrected"
                        + " Javadoc above), not the unwrapped-child loop's own fix");
    }

    // --- Reopened finding: a transient field with a getter and setter loses its constraint (no validator) ---

    /**
     * A private, {@code transient}, {@code @Max}-constrained numeric field bound through both a getter
     * and a setter — the same shape {@code
     * dev.vertique.json.schema.SetterOnlyFieldBorrowTest.TransientNumericField} pins at the unit level.
     * {@code BeanPropertyDefinition#getField()} is {@code null} for a transient field, and the
     * write-only fallback W1 added only fires when there is also no getter, so neither path currently
     * borrows the field's own {@code @Max(10)} onto the published property without a validator.
     */
    static final class TransientNumericFieldBody {
        @Max(10)
        private transient int level;

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }
    }

    /** The resource for {@link TransientNumericFieldBody}, on the unannotated floor profile. */
    @Path("/transient-numeric")
    public static class TransientNumericFieldResource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the bound level.
         *
         * @param body the transient-field fixture body
         * @return the echoed value
         */
        @POST
        @Path("/level")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "transientNumericFieldLevelEcho")
        public String echo(TransientNumericFieldBody body) {
            invocations.incrementAndGet();
            return "level=" + body.getLevel();
        }
    }

    /**
     * The gate-level half of the reopened W1 proof, no-validator mode. {@link
     * dev.vertique.json.schema.SetterOnlyFieldBorrowTest} carries the unit-level halves: the two
     * decisive no-validator proofs and the validator-backed control that the same shape's constraint is
     * still published under a validator (unaffected by this gap).
     *
     * <p>Expected red now: the gate accepts {@code {"level":999}} with a 200, because the published
     * property carries no {@code maximum} keyword at all.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("REOPENED: the gate rejects an out-of-range value for a transient numeric field with a getter"
            + " and setter, without a validator")
    void transientNumericFieldWithGetterAndSetterIsRejectedAtTheGateWithoutAValidator() throws Exception {
        TransientNumericFieldResource resource = new TransientNumericFieldResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> rejected = post(gatePort, "/transient-numeric/level", "{\"level\":999}");

        assertEquals(
                400,
                rejected.statusCode(),
                "REOPENED DECISIVE (expected red now): a transient field's own @Max(10) must still be"
                        + " borrowed onto the published property even though the property also carries a"
                        + " getter, without a validator; body: " + rejected.bodyAsString());
        assertEquals(0, resource.invocations.get(), "a rejected body must never reach the resource");
    }

    // --- C-2 (reopened, round 6 finding): a hidden member on an unwrapped child of an any-setter parent ---

    /**
     * A {@code @Schema(hidden = true)}-constrained unwrapped member on an any-setter parent — the same
     * shape {@code
     * dev.vertique.json.schema.DelegatingDeserializerWrapperTest.hiddenUnwrappedChildMemberIsReservedUnderMapperWideDelegatingWrapper}
     * pins at the unit level. Unlike {@link C1Child}'s plain property, a hidden member is never
     * published by the type's own class-level members — it can only reach the document through the
     * unwrapped-child loop's own alias/reservation fold, which the mapper-wide wrapper causes the loop to
     * skip entirely, so this shape genuinely discriminates the loop's own bug.
     */
    static final class C2HiddenChild {
        @Schema(hidden = true)
        @Size(max = 3)
        public String token;
    }

    /** Carries {@link C2HiddenChild} through {@code @JsonUnwrapped} on an any-setter type. */
    static final class C2AnySetterParent {
        @JsonUnwrapped
        public C2HiddenChild child;

        @JsonAnySetter
        private final Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** The resource for {@link C2AnySetterParent}, mounted under the {@code c1-delegating-wrapper} profile. */
    @Path("/c2")
    @JsonProfile("c1-delegating-wrapper")
    public static class C2Resource {

        /** Counts terminal invocations. */
        public final AtomicInteger invocations = new AtomicInteger();

        /**
         * Echoes the unwrapped child's hidden token, so an accepted body is observable as more than a
         * status code.
         *
         * @param body the C-2 fixture body
         * @return the echoed value
         */
        @POST
        @Path("/hidden-unwrapped")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "c2HiddenUnwrappedEcho")
        public String echo(C2AnySetterParent body) {
            invocations.incrementAndGet();
            return "token=" + (body.child == null ? null : body.child.token);
        }
    }

    /**
     * C-2 (reopened, round 6 finding). Under the same mapper-wide {@code DelegatingDeserializer} wrapper
     * as C-1, the unwrapped-child loop skips {@link C2HiddenChild} for the same reason C-1's own Javadoc
     * explains — but unlike C-1's plain property, {@code "token"} is hidden, so it can only be reserved
     * through {@code foldUnwrappedChildIntoParentPlan}, which never runs when the loop skips the child.
     * The real Jackson binder still routes {@code "token"} straight into {@code child.token} regardless
     * of the wrapper (the wrapper only forwards, it does not change how the binder's own unwrapped
     * -property machinery works), so an unreserved, unpublished {@code "token"} is a genuine constraint
     * bypass at the gate, not only a description gap — the REST-level counterpart of
     * {@code UnwrappedAnySetterFoldingTest.jacksonBinderRoutesTheHiddenKeyIntoTheConstrainedField}.
     *
     * <p>Expected red now: the gate accepts {@code {"token":"abcdefghijkl"}} (12 characters, over the
     * child's own {@code @Size(max = 3)}) with a 200.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("C-2: under a mapper-wide DelegatingDeserializer wrapper, the gate rejects an unwrapped any"
            + "-setter parent's hidden child value spelling the hidden member's own key")
    void c2HiddenUnwrappedChildUnderDelegatingWrapperIsRejectedAtTheGate() throws Exception {
        C2Resource resource = new C2Resource();
        RestTestContributions contributions = RestTestContributions.builder()
                .addJsonMapperProfile(c1DelegatingWrapperProfile())
                .build();
        int port = start(MountFixtures.mount(vertx, new JsonObject(), contributions), Set.of(resource));

        HttpResponse<Buffer> rejected = post(port, "/c2/hidden-unwrapped", "{\"token\":\"abcdefghijkl\"}");

        assertEquals(
                400,
                rejected.statusCode(),
                "C-2 DECISIVE (expected red now): the unwrapped child's hidden @Size(max = 3) member must be"
                        + " reserved under the mapper-wide wrapper profile on an any-setter parent, refusing"
                        + " the key outright rather than letting it fall through to the extras bucket"
                        + " unconstrained; body: " + rejected.bodyAsString());
        assertEquals(
                0,
                resource.invocations.get(),
                "a rejected body must never reach the resource; a green pre-fix run here would mean the"
                        + " oversized value reached the resource through the extras bucket because the hidden"
                        + " member was never reserved at all");
    }

    // --- rest-023 T002 (D002): TP-002, Optional-typed extras value ---

    /**
     * rest-023 T002 TP-002 (D002; {@code evidence/probe-report-327531b4.md} § N14, § N14n). An
     * any-setter extras value of declared type {@code Optional<Plain>} admits an explicit {@code null}
     * (Jackson's own {@code Optional.empty()} mapping, closing N14n) and rejects a non-null value
     * violating {@code Plain}'s own {@code @Size(max = 3)} constraint (closing N14), distinguished from
     * a broken request path by a companion body that satisfies the constraint.
     *
     * <p>Expected initial result: red for both bodies — {@code main} rejects the {@code null} body with
     * {@code type@#/x} (the false reject) and accepts the constraint-violating body (the undescribed
     * gap).
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("An Optional-typed extras value accepts null and rejects a constraint violation")
    void optionalExtrasValueAcceptsNullAndRejectsAConstraintViolation() throws Exception {
        OptionalExtrasResource resource = new OptionalExtrasResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> nullValue = post(gatePort, "/optional-extras", "{\"label\":\"l\",\"x\":null}");
        HttpResponse<Buffer> violating =
                post(gatePort, "/optional-extras", "{\"label\":\"l\",\"x\":{\"name\":\"TOOLONG\"}}");
        HttpResponse<Buffer> satisfying =
                post(gatePort, "/optional-extras", "{\"label\":\"l\",\"x\":{\"name\":\"ab\"}}");

        assertAll(
                () -> assertEquals(
                        200,
                        nullValue.statusCode(),
                        "N14n: an explicit null must bind to Optional.empty(), matching Jackson's own mapping;"
                                + " body: " + nullValue.bodyAsString()),
                () -> assertEquals(
                        400,
                        violating.statusCode(),
                        "N14: a non-null value violating Plain's own @Size(max = 3) must now be rejected; body: "
                                + violating.bodyAsString()),
                () -> assertEquals(
                        200,
                        satisfying.statusCode(),
                        "sensitivity proof: a non-null value satisfying Plain's own constraint must stay"
                                + " accepted, distinguishing the constraint rejection from a broken request path;"
                                + " body: " + satisfying.bodyAsString()),
                () -> assertEquals(
                        2,
                        resource.invocations.get(),
                        "only the null and constraint-satisfying bodies may have reached the resource"));
    }

    // --- rest-023 T003 (D001): TP-004, map value shapes ---

    /**
     * rest-023 T003 TP-004 (D001; {@code evidence/probe-report-327531b4.md} §§ S2a, S2b, rest023b §
     * S2d). Every {@code tags}/{@code labels}/{@code opts} body is rejected, including a {@code null}
     * inside the non-{@code Optional} {@code tags} map value (the explicit null rule); a companion
     * {@code opts} body carrying {@code null} is accepted, since {@code opts}'s own declared value type
     * is {@code Optional<Plain>}; the {@code raw} body stays accepted, unaffected.
     *
     * <p>Expected initial result: red for every {@code tags}/{@code labels}/{@code opts} body —
     * {@code main} accepts all of them; the {@code raw} body's own acceptance is a characterization
     * control, not a red.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A wrong-typed or constraint-violating map value is rejected at both boundaries; a null in a"
            + " non-Optional map value is rejected; JsonNode values stay open")
    void mapValueRejectsAWrongTypedOrConstraintViolatingValueAtBothBoundaries() throws Exception {
        MapValuesResource resource = new MapValuesResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> tagsTooLong = post(gatePort, "/map-values", "{\"tags\":{\"k\":\"TOOLONG\"}}");
        HttpResponse<Buffer> tagsNull = post(gatePort, "/map-values", "{\"tags\":{\"k\":null}}");
        HttpResponse<Buffer> tagsWrongType = post(gatePort, "/map-values", "{\"tags\":{\"k\":1}}");
        HttpResponse<Buffer> labelsTooLong =
                post(gatePort, "/map-values", "{\"labels\":{\"k\":{\"name\":\"TOOLONG\"}}}");
        HttpResponse<Buffer> optsTooLong = post(gatePort, "/map-values", "{\"opts\":{\"k\":{\"name\":\"TOOLONG\"}}}");
        HttpResponse<Buffer> optsNull = post(gatePort, "/map-values", "{\"opts\":{\"k\":null}}");
        HttpResponse<Buffer> raw = post(gatePort, "/map-values", "{\"raw\":{\"k\":{\"any\":1}}}");

        assertAll(
                () -> assertEquals(
                        400,
                        tagsTooLong.statusCode(),
                        "tags: a constraint-violating value must be rejected; body: " + tagsTooLong.bodyAsString()),
                () -> assertEquals(
                        400,
                        tagsNull.statusCode(),
                        "tags: a null inside a non-Optional map value must be rejected as wrong-typed (the"
                                + " explicit null rule); body: " + tagsNull.bodyAsString()),
                () -> assertEquals(
                        400,
                        tagsWrongType.statusCode(),
                        "tags: a wrong-typed value must be rejected; body: " + tagsWrongType.bodyAsString()),
                () -> assertEquals(
                        400,
                        labelsTooLong.statusCode(),
                        "labels: a bean value's own constraint violation must be rejected; body: "
                                + labelsTooLong.bodyAsString()),
                () -> assertEquals(
                        400,
                        optsTooLong.statusCode(),
                        "opts: a non-null Optional bean value's own constraint violation must be rejected;" + " body: "
                                + optsTooLong.bodyAsString()),
                () -> assertEquals(
                        200,
                        optsNull.statusCode(),
                        "opts: null must be accepted — opts's own declared value type is Optional<Plain>;" + " body: "
                                + optsNull.bodyAsString()),
                () -> assertEquals(
                        200,
                        raw.statusCode(),
                        "raw: an opaque JsonNode value must stay accepted, unaffected; body: " + raw.bodyAsString()),
                () -> assertEquals(
                        2,
                        resource.invocations.get(),
                        "only the opts-null and raw bodies may have reached the resource"));
    }

    // --- rest-023 T003 (D001): TP-010, the single any-setter N16 shape ---

    /**
     * rest-023 T003 TP-010 (N16, architecture round-3 R1's own alternative, ruling X1). A body
     * violating the single any-setter's own type-use constraint is rejected; a companion body
     * satisfying the constraint is accepted, distinguishing the rejection from a broken request path.
     *
     * <p>Expected initial result: red — {@code main} accepts {@code {"k":"TOOLONG"}} (the any-setter's
     * own extras value carries no maxLength today).
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A body violating the single any-setter's own type-use constraint is rejected at the gate")
    void singleAnySetterValueTypeUseConstraintRejectsAViolatingBody() throws Exception {
        AnySetterTypeUseResource resource = new AnySetterTypeUseResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> violating = post(gatePort, "/any-setter-type-use", "{\"k\":\"TOOLONG\"}");
        HttpResponse<Buffer> satisfying = post(gatePort, "/any-setter-type-use", "{\"k\":\"ab\"}");

        assertAll(
                () -> assertEquals(
                        400,
                        violating.statusCode(),
                        "a body violating the any-setter's own type-use constraint must be rejected; body: "
                                + violating.bodyAsString()),
                () -> assertEquals(
                        200,
                        satisfying.statusCode(),
                        "a body satisfying the constraint must stay accepted, distinguishing the rejection"
                                + " from a broken request path; body: " + satisfying.bodyAsString()),
                () -> assertEquals(
                        1, resource.invocations.get(), "only the satisfying body may have reached the resource"));
    }

    // --- rest-023 T004 (D004): TP-002, several any-setters sharing a wire key ---

    /**
     * rest-023 T004 TP-002. A body violating either any-setter's own constraint on a wire key both a
     * parent's own and an unwrapped child's own any-setter share (UW-2AS) is rejected; a companion body
     * satisfying both is accepted. An object-valued two-any-setter pair (both value types are beans) is
     * rejected when either bean's own constraint is violated, and accepted (zero false-reject) when both
     * are satisfied.
     *
     * <p>Expected initial result: red for the object-valued pair's own conjunction (only one any-setter's
     * own value schema is described today, so a violation of the other-side bean's own constraint is not
     * rejected); the UW-2AS body's own rejection may already be green — rest-023 T003 activated the
     * type-use overlay for a single any-setter's own value position, and UW-2AS's own child value is
     * exactly that shape, so {@code "TOOLONG"} may already be rejected before this task even though the
     * document still describes only one any-setter's own value schema, not the conjunction — reported
     * honestly against the actual baseline run, not assumed.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("A body violating either any-setter's own constraint on a shared key is rejected; an"
            + " object-valued pair is rejected on either bean's own violation and accepted otherwise")
    void sharedAnySetterKeyRejectsAValueViolatingEitherAnySettersConstraint() throws Exception {
        SharedAnySetterConjunctionResource sharedResource = new SharedAnySetterConjunctionResource();
        ObjectValuedAnySetterPairResource pairResource = new ObjectValuedAnySetterPairResource();
        int gatePort = start(gateMount(), Set.of(sharedResource, pairResource));

        HttpResponse<Buffer> sharedViolating = post(gatePort, "/shared-any-setter-conjunction", "{\"x\":\"TOOLONG\"}");
        HttpResponse<Buffer> sharedSatisfying = post(gatePort, "/shared-any-setter-conjunction", "{\"x\":\"ab\"}");
        HttpResponse<Buffer> pairSatisfying =
                post(gatePort, "/object-valued-any-setter-pair", "{\"x\":{\"label\":\"ab\",\"code\":\"z\"}}");
        HttpResponse<Buffer> pairLeftViolating =
                post(gatePort, "/object-valued-any-setter-pair", "{\"x\":{\"label\":\"TOOLONGVALUE\",\"code\":\"z\"}}");
        HttpResponse<Buffer> pairRightViolating = post(
                gatePort, "/object-valued-any-setter-pair", "{\"x\":{\"label\":\"ab\",\"code\":\"TOOLONGVALUE\"}}");

        assertAll(
                () -> assertEquals(
                        400,
                        sharedViolating.statusCode(),
                        "UW-2AS: a body violating the unwrapped child's own type-use constraint on the"
                                + " shared key must be rejected; body: " + sharedViolating.bodyAsString()),
                () -> assertEquals(
                        200,
                        sharedSatisfying.statusCode(),
                        "UW-2AS: a body satisfying both any-setters' own constraints on the shared key"
                                + " must stay accepted, distinguishing the rejection from a broken request"
                                + " path; body: " + sharedSatisfying.bodyAsString()),
                () -> assertEquals(
                        200,
                        pairSatisfying.statusCode(),
                        "the object-valued pair: a body satisfying both beans' own constraints must be"
                                + " accepted — the zero-false-reject proof; body: "
                                + pairSatisfying.bodyAsString()),
                () -> assertEquals(
                        400,
                        pairLeftViolating.statusCode(),
                        "the object-valued pair: a body violating the parent's own bean's constraint must"
                                + " be rejected; body: " + pairLeftViolating.bodyAsString()),
                () -> assertEquals(
                        400,
                        pairRightViolating.statusCode(),
                        "the object-valued pair: a body violating the unwrapped child's own bean's"
                                + " constraint must be rejected; body: " + pairRightViolating.bodyAsString()),
                () -> assertEquals(
                        1,
                        sharedResource.invocations.get(),
                        "UW-2AS: only the satisfying body may have reached the resource"),
                () -> assertEquals(
                        1,
                        pairResource.invocations.get(),
                        "the object-valued pair: only the satisfying body may have reached the resource"));
    }

    // --- rest-023 T005 (D005): TP-003, member-level closure ---

    /**
     * rest-023 T005 TP-003 (D005; M10). A body carrying an extra key under the member-level
     * FALSE-closed member is rejected; the same extra-key shape under the unannotated control member
     * is accepted, unaffected — distinguishing this task's own rule's scope from a global tightening. A
     * companion body satisfying the closed member (no extra key) is accepted.
     *
     * <p>Expected initial result: red for the annotated member ({@code main} accepts it —
     * {@code evidence/probe-report-327531b4.md} § M10); the control member's own acceptance is already
     * green and stays green throughout.
     *
     * @throws Exception when a round trip fails or times out
     */
    @Test
    @DisplayName("An extra key under a member-level FALSE-closed member is rejected; the same shape under"
            + " an unannotated control member stays accepted")
    void memberLevelClosureRejectsAnExtraKeyUnderThatMemberOnly() throws Exception {
        MemberLevelClosureResource resource = new MemberLevelClosureResource();
        int gatePort = start(gateMount(), Set.of(resource));

        HttpResponse<Buffer> childRejected =
                post(gatePort, "/member-level-closure", "{\"child\":{\"name\":\"a\",\"x\":\"1\"}}");
        HttpResponse<Buffer> openAccepted =
                post(gatePort, "/member-level-closure", "{\"open\":{\"name\":\"a\",\"x\":\"1\"}}");
        HttpResponse<Buffer> childAccepted = post(gatePort, "/member-level-closure", "{\"child\":{\"name\":\"a\"}}");

        assertAll(
                () -> assertEquals(
                        400,
                        childRejected.statusCode(),
                        "a body carrying an extra key under the FALSE-closed member must be rejected; body: "
                                + childRejected.bodyAsString()),
                () -> assertEquals(
                        200,
                        openAccepted.statusCode(),
                        "the same extra-key shape under the unannotated control member must stay accepted,"
                                + " proving this task's own rule is scoped to the annotated member; body: "
                                + openAccepted.bodyAsString()),
                () -> assertEquals(
                        200,
                        childAccepted.statusCode(),
                        "a body satisfying the closed member (no extra key) must stay accepted; body: "
                                + childAccepted.bodyAsString()),
                () -> assertEquals(
                        2, resource.invocations.get(), "only the two accepted bodies may have reached the resource"));
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
