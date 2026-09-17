// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileConfigurationException;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.JsonSchemaFragment;
import dev.vertique.core.json.JsonSchemaTypeOverride;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.json.schema.JsonSchemaGenerationException;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import dev.vertique.rest.validation.corpus.PatternNamedPropertyDto;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * TP-007. Every declared profiled-synthesis failure surfaces at <em>router construction</em>, with a
 * diagnostic that identifies the operation and the position without disclosing the schema fragment or
 * the pattern that failed to compile.
 *
 * <p>Every case builds its router through {@code RestTestMounts.router}, which creates the API router
 * and never binds a server: "the mount fails before any server binds" is therefore structural here
 * rather than a timing observation, and no request is ever evaluated.
 *
 * <p>Six mounts, four of which must fail and two of which must build:
 *
 * <ul>
 *   <li>a profile declaring two {@code BigDecimal} {@code INPUT} overrides — a generator construction
 *       failure ({@link #duplicateOverrideProfileFailsTheMountWithABoundedDiagnostic()});</li>
 *   <li>a profile whose fragment carries an unparseable {@code pattern}, and — folded into the same
 *       method, see the note below — a profile whose {@code pattern}'s only defect is an unknown
 *       character-property name long enough to force the message bound
 *       ({@link #unparseablePatternFailsTheMountAtRouterConstruction()});</li>
 *   <li>a profile whose fragment carries an unparseable {@code patternProperties} key
 *       ({@link #unparseablePatternPropertiesKeyFailsTheMountAtRouterConstruction()});</li>
 *   <li>a DTO with a property literally named {@code pattern}, mounted beside a strict route whose
 *       fragment carries a genuinely compilable pattern, which must build
 *       ({@link #propertyNamedPatternStillMounts()});</li>
 *   <li>the unparseable-pattern profile on a {@code jaxrs.validationStrategy: none} mount, which must
 *       build because the compile check lives in {@link WebValidationStrategy} only
 *       ({@link #noneStrategyAssemblyIgnoresAnUnparseablePattern()});</li>
 *   <li>an unregistered profile id, the preserved guard
 *       ({@link #unknownProfileStillFailsAtRouterBuild()}).</li>
 * </ul>
 *
 * <p><strong>Two deliberate constraints on the disclosure assertions.</strong> First, the frozen bad
 * pattern is the single character {@code (}, so the only mechanical way to assert that the complete
 * pattern text never reaches a diagnostic is to assert that no message in the chain contains that
 * character at all — which means the strategy's own phrasing must not use parentheses either.
 * Second, no expected description or index is restated as a literal: each is read from a
 * {@link PatternSyntaxException} the test itself provokes from the same pattern, so a JDK that
 * reworded a description cannot silently turn this proof green.
 */
@ExtendWith(VertxExtension.class)
// 60s rather than testing.md's 20s default: every method builds its own Dagger graph and router.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class ProfiledSchemaFailureIT {

    private static final long ASYNC_TIMEOUT_SECONDS = 20;

    /** The bound the strategy truncates its assembled message to, in UTF-16 code units (FR-008). */
    private static final int MESSAGE_BOUND_CODE_UNITS = 512;

    /** An unparseable pattern: an unclosed group. */
    private static final String UNPARSEABLE_PATTERN = "(";

    /**
     * A pattern whose only defect is an unknown character-property name longer than the message bound,
     * so the engine's description quotes a token that cannot possibly fit inside it.
     */
    private static final String LONG_TOKEN_PATTERN = "^\\p{Is" + "A".repeat(600) + "}$";

    private Vertx vertx;

    /**
     * Captures the per-test Vert.x instance. No client and no server: every case here stops at router
     * construction.
     *
     * @param injectedVertx the per-test Vert.x instance injected by vertx-junit5
     */
    @BeforeEach
    void setUp(Vertx injectedVertx) {
        vertx = injectedVertx;
    }

    // --- Generator construction failures ---

    /**
     * TP-007 (AC-008.1). A profile declaring two {@code BigDecimal} overrides for the {@code INPUT}
     * direction fails the mount: the generator refuses to construct, and the failure surfaces as a
     * {@link RestConfigurationException} naming the operation, with the generator's
     * {@link JsonSchemaGenerationException} preserved as a cause and neither fragment's JSON nor its
     * pattern text anywhere in the chain.
     *
     * <p>One of the two duplicated fragments is the {@code vertique-strict} profile's own declared
     * {@code BigDecimal} fragment, read through its public path, so the pattern text this assertion
     * forbids is a real one that a careless diagnostic could genuinely disclose.
     *
     * @throws Exception when the router build neither succeeds nor fails within the budget
     */
    @Test
    @DisplayName("A profile with two INPUT overrides for one type fails the mount with a bounded diagnostic")
    void duplicateOverrideProfileFailsTheMountWithABoundedDiagnostic() throws Exception {
        String strictFragmentJson = strictBigDecimalFragmentJson();
        String strictPatternText = new JsonObject(strictFragmentJson).getString("pattern");
        JsonSchemaFragment secondFragment =
                fragment(node -> node.put("type", "string").put("maxLength", 8));

        JsonMapperProfile duplicate = profile(
                "dup-override",
                JsonSchemaTypeOverride.input(BigDecimal.class, JsonSchemaFragment.parse(strictFragmentJson)),
                JsonSchemaTypeOverride.input(BigDecimal.class, secondFragment));

        Throwable failure = assertMountFails(
                mount(new JsonObject(), contributions(duplicate)),
                Set.of(new DuplicateOverrideResource()),
                RestConfigurationException.class,
                List.of("duplicateOverrideAmountEcho"),
                List.of(strictFragmentJson, strictPatternText, secondFragment.canonicalJson()));

        assertTrue(
                chainOf(failure).stream().anyMatch(link -> link instanceof JsonSchemaGenerationException),
                "the generator's own JsonSchemaGenerationException must be preserved as a cause; chain was: "
                        + chainOf(failure));
    }

    // --- Regex precompilation failures ---

    /**
     * TP-007 (AC-008.2). A profile fragment carrying an unparseable {@code pattern} fails the mount at
     * router construction, where the gate compiles every string-valued member keyed {@code pattern} at
     * any depth — not per request, and not at the first request that happens to exercise the route.
     *
     * <p>The long-token case is asserted here as well. The frozen proof set names six methods for
     * TP-007 and none of them is dedicated to it, so it lands beside the case it is a variant of: the
     * same failure, distinguished only by a description too long to fit the message bound. Its
     * assertion is the bound itself — at most {@value #MESSAGE_BOUND_CODE_UNITS} UTF-16 code units,
     * with the operation id, the pointer, and the index intact and the description elided rather than
     * the identifying parts.
     *
     * @throws Exception when a router build neither succeeds nor fails within the budget
     */
    @Test
    @DisplayName("An unparseable pattern in a profile fragment fails the mount at router construction")
    void unparseablePatternFailsTheMountAtRouterConstruction() throws Exception {
        PatternSyntaxException unclosedGroup = syntaxErrorOf(UNPARSEABLE_PATTERN);
        JsonMapperProfile badPattern = profile("bad-pattern", bigDecimalOverride(node -> node.put("type", "string")
                .put("pattern", UNPARSEABLE_PATTERN)));

        Throwable failure = assertMountFails(
                mount(new JsonObject(), contributions(badPattern)),
                Set.of(new BadPatternResource()),
                RestConfigurationException.class,
                List.of("badPatternAmountEcho", "/properties/amount/pattern", unclosedGroup.getDescription()),
                List.of(UNPARSEABLE_PATTERN));
        assertContainsIndex(failure.getMessage(), unclosedGroup.getIndex());
        assertNoPatternSyntaxExceptionInChain(failure);

        PatternSyntaxException unknownProperty = syntaxErrorOf(LONG_TOKEN_PATTERN);
        JsonMapperProfile longToken = profile("long-token", bigDecimalOverride(node -> node.put("type", "string")
                .put("pattern", LONG_TOKEN_PATTERN)));

        Throwable longTokenFailure = assertMountFails(
                mount(new JsonObject(), contributions(longToken)),
                Set.of(new LongTokenResource()),
                RestConfigurationException.class,
                List.of(
                        "longTokenAmountEcho",
                        "/properties/amount/pattern",
                        // The fixed prefix of the engine's description, not the token it quotes: the
                        // token is what the bound elides.
                        descriptionPrefix(unknownProperty)),
                List.of(LONG_TOKEN_PATTERN));
        assertContainsIndex(longTokenFailure.getMessage(), unknownProperty.getIndex());
        assertNoPatternSyntaxExceptionInChain(longTokenFailure);
        assertTrue(
                longTokenFailure.getMessage().length() <= MESSAGE_BOUND_CODE_UNITS,
                "the assembled message must be truncated to " + MESSAGE_BOUND_CODE_UNITS + " UTF-16 code units; it was "
                        + longTokenFailure.getMessage().length());
        assertFalse(
                endsInsideSurrogatePair(longTokenFailure.getMessage()), "truncation must never split a surrogate pair");
    }

    /**
     * TP-007 (AC-008.2). The same failure for a {@code patternProperties} <em>key</em>: the walk
     * compiles every key of every object keyed {@code patternProperties}, at any depth, with no
     * position allowlist.
     *
     * <p>The required pointer deliberately stops at the {@code patternProperties} object rather than
     * descending into the offending key, because the key <em>is</em> the pattern text and the same
     * assertion forbids disclosing it.
     *
     * @throws Exception when the router build neither succeeds nor fails within the budget
     */
    @Test
    @DisplayName("An unparseable patternProperties key fails the mount at router construction")
    void unparseablePatternPropertiesKeyFailsTheMountAtRouterConstruction() throws Exception {
        PatternSyntaxException unclosedGroup = syntaxErrorOf(UNPARSEABLE_PATTERN);
        JsonMapperProfile badKey = profile("bad-pattern-properties", bigDecimalOverride(node -> {
            node.put("type", "object");
            node.putObject("patternProperties").putObject(UNPARSEABLE_PATTERN);
        }));

        Throwable failure = assertMountFails(
                mount(new JsonObject(), contributions(badKey)),
                Set.of(new PatternPropertiesResource()),
                RestConfigurationException.class,
                List.of(
                        "patternPropertiesAmountEcho",
                        "/properties/amount/patternProperties",
                        unclosedGroup.getDescription()),
                List.of(UNPARSEABLE_PATTERN));
        assertContainsIndex(failure.getMessage(), unclosedGroup.getIndex());
        assertNoPatternSyntaxExceptionInChain(failure);
    }

    // --- Characterizations: what the walk must NOT fail on ---

    /**
     * TP-007 (AC-008.2, characterization). A DTO with a property literally named {@code pattern} still
     * mounts: under {@code properties}, {@code pattern} is a subschema object, not a regular
     * expression, and it is never compiled.
     *
     * <p>Mounted beside a {@code vertique-strict} route whose fragment carries a genuinely compilable
     * {@code pattern}, so this mount exercises both branches of the walk: the string-valued member
     * that must compile, and the same-named object that must be descended into rather than compiled.
     *
     * @throws Exception when the router build fails or times out
     */
    @Test
    @DisplayName("A body property literally named pattern still mounts")
    void propertyNamedPatternStillMounts() throws Exception {
        Router router = awaitRouter(
                mount(new JsonObject(), RestTestContributions.none()),
                Set.of(new PatternNamedResource(), new StrictAmountResource()));

        assertNotNull(
                router,
                "a properties member named 'pattern' is an object and must never be compiled as a regular "
                        + "expression, while a real pattern beside it must compile cleanly");
    }

    /**
     * TP-007 (AC-008.2, characterization). The unparseable-pattern profile on a mount configured with
     * {@code jaxrs.validationStrategy: none} still builds: the precompilation check lives in
     * {@link WebValidationStrategy#gateFor}, and {@link NoneValidationStrategy} installs no gate, so
     * nothing walks the document.
     *
     * <p>This is a disclosed consequence, not an oversight: an application that disables the gate
     * keeps a schema the gate would have refused.
     *
     * @throws Exception when the router build fails or times out
     */
    @Test
    @DisplayName("A none-strategy mount ignores an unparseable pattern in the schema it never gates")
    void noneStrategyAssemblyIgnoresAnUnparseablePattern() throws Exception {
        JsonMapperProfile badPattern = profile("bad-pattern", bigDecimalOverride(node -> node.put("type", "string")
                .put("pattern", UNPARSEABLE_PATTERN)));
        JsonObject config =
                new JsonObject().put("jaxrs", new JsonObject().put("validationStrategy", NoneValidationStrategy.ID));

        Router router = awaitRouter(mount(config, contributions(badPattern)), Set.of(new BadPatternResource()));

        assertNotNull(
                router,
                "with no gate there is no compile step, so the same fragment that fails web-validation must "
                        + "still assemble");
    }

    // --- Preserved guard ---

    /**
     * TP-007. An unregistered {@code @JsonProfile} id still fails the router build with
     * {@link JsonProfileConfigurationException}, exactly as it does today: the profiled schema seam
     * never gets the chance to run, because the route's profile cannot be resolved.
     *
     * @throws Exception when the router build neither succeeds nor fails within the budget
     */
    @Test
    @DisplayName("An unregistered profile id still fails the router build")
    void unknownProfileStillFailsAtRouterBuild() throws Exception {
        Throwable failure = awaitFailure(
                mount(new JsonObject(), RestTestContributions.none()), Set.of(new UnknownProfileResource()));

        assertInstanceOf(
                JsonProfileConfigurationException.class,
                failure,
                "an unknown profile id must keep failing fast at router build");
    }

    // --- Body and resource fixtures ---

    /** The one-decimal body every profile fixture's override applies to. */
    public static class Amount {

        /** The decimal property whose schema the profile fragment replaces. */
        public BigDecimal amount;
    }

    /** Resource selecting the duplicate-override profile. */
    @Path("/duplicate-override")
    @JsonProfile("dup-override")
    public static class DuplicateOverrideResource {

        /**
         * Never runs: the mount that carries it must fail at router construction.
         *
         * @param body the request body bean
         * @return the echoed amount
         */
        @POST
        @Path("/amount")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "duplicateOverrideAmountEcho")
        public String echo(Amount body) {
            return "amount=" + body.amount;
        }
    }

    /** Resource selecting the unparseable-pattern profile. */
    @Path("/bad-pattern")
    @JsonProfile("bad-pattern")
    public static class BadPatternResource {

        /**
         * Never runs under {@code web-validation}; mounts, but is never exercised, under {@code none}.
         *
         * @param body the request body bean
         * @return the echoed amount
         */
        @POST
        @Path("/amount")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "badPatternAmountEcho")
        public String echo(Amount body) {
            return "amount=" + body.amount;
        }
    }

    /** Resource selecting the long-token unknown-character-property profile. */
    @Path("/long-token")
    @JsonProfile("long-token")
    public static class LongTokenResource {

        /**
         * Never runs: the mount that carries it must fail at router construction.
         *
         * @param body the request body bean
         * @return the echoed amount
         */
        @POST
        @Path("/amount")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "longTokenAmountEcho")
        public String echo(Amount body) {
            return "amount=" + body.amount;
        }
    }

    /** Resource selecting the unparseable-{@code patternProperties}-key profile. */
    @Path("/pattern-properties")
    @JsonProfile("bad-pattern-properties")
    public static class PatternPropertiesResource {

        /**
         * Never runs: the mount that carries it must fail at router construction.
         *
         * @param body the request body bean
         * @return the echoed amount
         */
        @POST
        @Path("/amount")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "patternPropertiesAmountEcho")
        public String echo(Amount body) {
            return "amount=" + body.amount;
        }
    }

    /** Resource whose body carries a property literally named {@code pattern}. */
    @Path("/pattern-named")
    public static class PatternNamedResource {

        /**
         * Echoes the property whose name collides with the JSON Schema keyword.
         *
         * @param body the request body bean
         * @return the echoed value
         */
        @POST
        @Path("/value")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "patternNamedValueEcho")
        public String echo(PatternNamedPropertyDto body) {
            return "pattern=" + body.pattern;
        }
    }

    /**
     * Strict-profiled resource mounted beside {@link PatternNamedResource}, so the same walk also sees
     * a genuinely compilable {@code pattern} — the one the {@code vertique-strict} fragment declares.
     */
    @Path("/strict-amount")
    @JsonProfile("vertique-strict")
    public static class StrictAmountResource {

        /**
         * Echoes the bound amount.
         *
         * @param body the request body bean
         * @return the echoed amount
         */
        @POST
        @Path("/amount")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "strictAmountEcho")
        public String echo(Amount body) {
            return "amount=" + body.amount;
        }
    }

    /** Resource selecting a profile id no registry knows. */
    @Path("/unknown-profile")
    @JsonProfile("no-such-profile")
    public static class UnknownProfileResource {

        /**
         * Never runs: the profile cannot be resolved at router build.
         *
         * @param body the request body bean
         * @return the echoed amount
         */
        @POST
        @Path("/amount")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "unknownProfileAmountEcho")
        public String echo(Amount body) {
            return "amount=" + body.amount;
        }
    }

    // --- Profile fixtures ---

    /**
     * Builds an application profile over a probe-passing mapper. The mapper is deliberately plain: the
     * registry probes every application-contributed mapper, and what these fixtures exercise is the
     * override list, not the mapper.
     *
     * @param id        the profile id
     * @param overrides the schema-type overrides to declare
     * @return the profile
     */
    private static JsonMapperProfile profile(String id, JsonSchemaTypeOverride... overrides) {
        // Vert.x JSON support: the registry probes a contributed mapper with a JsonObject round trip.
        ObjectMapper mapper =
                JsonMapper.builder().addModule(VertxJsonSupport.module()).build();
        return JsonMapperProfiles.of(JsonProfileId.of(id), mapper, List.of(overrides));
    }

    /**
     * Builds an {@code INPUT} {@code BigDecimal} override from a fragment the caller populates.
     *
     * @param populate populates the fragment's members
     * @return the override
     */
    private static JsonSchemaTypeOverride bigDecimalOverride(Consumer<ObjectNode> populate) {
        return JsonSchemaTypeOverride.input(BigDecimal.class, fragment(populate));
    }

    /**
     * Builds a fragment from a populated node rather than a JSON string literal, so a pattern carrying
     * backslashes is never double-escaped by hand.
     *
     * @param populate populates the fragment's members
     * @return the parsed fragment
     */
    private static JsonSchemaFragment fragment(Consumer<ObjectNode> populate) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        populate.accept(node);
        return JsonSchemaFragment.parse(node.toString());
    }

    /**
     * Wraps one contributed profile as the fixture's additive contributions.
     *
     * @param profile the profile to register
     * @return the contributions
     */
    private static RestTestContributions contributions(JsonMapperProfile profile) {
        return RestTestContributions.builder().addJsonMapperProfile(profile).build();
    }

    /**
     * Reads the {@code vertique-strict} profile's declared {@code BigDecimal} fragment through its
     * public path, so this test never restates the fragment's bounds.
     *
     * @return the strict {@code BigDecimal} fragment's canonical JSON
     */
    private String strictBigDecimalFragmentJson() {
        JsonMapperProfile strict = MountFixtures.component(vertx, new JsonObject(), RestTestContributions.none())
                .jsonMapperProfileRegistry()
                .profile(JsonProfileId.of("vertique-strict"));
        return strict.jsonSchemaTypeOverrides().get(0).fragment().canonicalJson();
    }

    // --- Mount helpers ---

    /**
     * Builds a mount handle with the given configuration and contributions.
     *
     * @param config        the application configuration
     * @param contributions the additive test contributions
     * @return the mount handle
     */
    private RestTestMount mount(JsonObject config, RestTestContributions contributions) {
        return MountFixtures.mount(vertx, config, contributions);
    }

    /**
     * Asserts that building a router over {@code resources} fails with {@code exceptionType}, that its
     * message carries every required substring, and that no message anywhere in its cause or
     * suppressed chain discloses any forbidden substring.
     *
     * @param mount               the mount handle
     * @param resources           the JAX-RS resources to mount
     * @param exceptionType       the expected failure type
     * @param requiredSubstrings  text the failure's own message must carry
     * @param forbiddenSubstrings text no message in the chain may carry
     * @return the failure, for further case-specific assertions
     * @throws Exception when the router build neither succeeds nor fails within the budget
     */
    private Throwable assertMountFails(
            RestTestMount mount,
            Set<Object> resources,
            Class<? extends Throwable> exceptionType,
            List<String> requiredSubstrings,
            List<String> forbiddenSubstrings)
            throws Exception {
        Throwable failure = awaitFailure(mount, resources);
        assertInstanceOf(exceptionType, failure, "the mount must fail with " + exceptionType.getSimpleName());

        String message = String.valueOf(failure.getMessage());
        for (String required : requiredSubstrings) {
            assertTrue(message.contains(required), "the diagnostic must name '" + required + "'; it said: " + message);
        }
        for (Throwable link : chainOf(failure)) {
            String linkMessage = String.valueOf(link.getMessage());
            for (String forbidden : forbiddenSubstrings) {
                assertFalse(
                        linkMessage.contains(forbidden),
                        "no message in the failure chain may disclose '" + forbidden + "'; "
                                + link.getClass().getName() + " said: " + linkMessage);
            }
        }
        return failure;
    }

    /**
     * Asserts that no throwable in the chain is a {@link PatternSyntaxException}: the engine's own
     * exception quotes the complete pattern in its message, so it may be read but never propagated.
     *
     * @param failure the router-build failure
     */
    private static void assertNoPatternSyntaxExceptionInChain(Throwable failure) {
        for (Throwable link : chainOf(failure)) {
            assertFalse(
                    link instanceof PatternSyntaxException,
                    "a PatternSyntaxException quotes the complete pattern and must not appear in the chain; "
                            + "found one at " + link.getClass().getName());
        }
    }

    /**
     * Asserts that {@code message} carries {@code index} as a standalone number, so a digit that
     * happens to appear inside a pointer or an identifier cannot stand in for the reported index.
     *
     * @param message the diagnostic
     * @param index   the index the engine reported
     */
    private static void assertContainsIndex(String message, int index) {
        String standalone = "(?<![0-9])" + index + "(?![0-9])";
        assertTrue(
                Pattern.compile(standalone).matcher(String.valueOf(message)).find(),
                "the diagnostic must carry the failure index " + index + "; it said: " + message);
    }

    /**
     * Returns the fixed part of a syntax error's description — everything before the braced token it
     * quotes — so a proof can require the phrasing without requiring the quoted token.
     *
     * @param error the engine's syntax error
     * @return the description up to the quoted token, or the whole description when none is quoted
     */
    private static String descriptionPrefix(PatternSyntaxException error) {
        String description = error.getDescription();
        int quoted = description.indexOf(" {");
        return quoted < 0 ? description : description.substring(0, quoted);
    }

    /**
     * Compiles {@code pattern} and returns the syntax error it raises, so no expected description or
     * index is restated as a literal in this test.
     *
     * @param pattern the pattern expected to be unparseable
     * @return the engine's own syntax error
     */
    private static PatternSyntaxException syntaxErrorOf(String pattern) {
        return assertThrows(
                PatternSyntaxException.class,
                () -> Pattern.compile(pattern),
                "the fixture pattern must be unparseable, or this proof asserts nothing");
    }

    /**
     * Reports whether a string ends inside a surrogate pair, which a naive truncation to a code-unit
     * bound would produce.
     *
     * @param message the diagnostic
     * @return {@code true} when the last code unit is an unpaired high surrogate
     */
    private static boolean endsInsideSurrogatePair(String message) {
        return !message.isEmpty() && Character.isHighSurrogate(message.charAt(message.length() - 1));
    }

    /**
     * Builds a router over the given resources and returns it.
     *
     * @param mount     the mount handle
     * @param resources the JAX-RS resources to mount
     * @return the assembled API router
     * @throws Exception when the build fails or times out
     */
    private Router awaitRouter(RestTestMount mount, Set<Object> resources) throws Exception {
        return RestTestMounts.router(vertx, mount, resources)
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
}
