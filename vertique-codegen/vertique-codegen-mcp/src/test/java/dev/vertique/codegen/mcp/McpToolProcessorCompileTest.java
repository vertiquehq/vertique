// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-001 — the frozen T002 contract matrix for {@link McpToolProcessor}.
 *
 * <p>Every row compiles one source set once with the real processor and inspects the diagnostics
 * plus the generated module/invoker source. The valid {@code WeatherTools} baseline must compile
 * and emit {@code com.example.tools.GeneratedMcpToolsModule} plus a direct package-private
 * {@code WeatherTools_lookup_McpToolInvoker}; every declared invalid source must fail with exactly
 * one targeted diagnostic and emit no usable invoker.
 *
 * <p>Four rows isolate four boundaries, one each:
 *
 * <ol>
 *   <li>{@link #shouldGenerateDirectInvokerTypedInputCarrierAndExplicitDaggerModule()} — the
 *       generation shape: direct invoker, typed {@code Input} carrier, explicit Dagger multibinding
 *       module, and the negatives that prove no reflective fallback, no unwired tool type, no
 *       non-public declaring type, and no overloaded tool method slip through.</li>
 *   <li>{@link #shouldCompileUnannotatedAsNoneAndActionOnlyAsRestricted()} — access-mode
 *       derivation. An unannotated tool resolves no base policy at all (REST's
 *       {@code SecurityPolicy.None}), which on the wire is a public tool: descriptor access mode
 *       {@code PERMIT_ALL} with no roles and no action. A tool carrying only {@code @RequiresAction}
 *       is action-only restricted: {@code RESTRICTED} with the action and no roles. Its negatives
 *       are the {@code @PermitAll}/{@code @RequiresAction} conflict, {@code @Authorized} reaching
 *       the tool through <em>any</em> policy source tier, and an action value outside the frozen
 *       {@code ActionRef} grammar.</li>
 *   <li>{@link #shouldResolveMethodJsonProfileOverTypeAndRejectBlankValues()} — effective JSON
 *       profile resolution: a method-level {@code @JsonProfile} overrides the declaring type's, a
 *       blank id is rejected, and a nonblank id is normalized through {@code JsonProfileId} before
 *       it is emitted.</li>
 *   <li>{@link #shouldRejectDuplicateNamesAndUnsupportedSignatures()} — the rejection diagnostics:
 *       duplicate tool names, raw and wildcard and unresolved types, an unsupported input-schema
 *       member, a platform type carried as an application generic's type argument, an unsupported
 *       result type, and a {@code void} return.</li>
 * </ol>
 *
 * <p>Each invalid source asserts an error <em>count</em> of exactly one for its targeted fragments,
 * not merely presence: that is what lets the sensitivity proof swap a single mutated declaration for
 * its valid control and observe the count move 1 → 0. A failed compilation exposes no generated
 * files at all through {@code compile-testing}, which is the "emits no usable invoker" half of the
 * contract for every rejection row.
 *
 * <p>Diagnostic matching is fragment-based and case-insensitive: the contract fixes which boundary
 * and which offending symbol the message must name, not its exact wording.
 */
@DisplayName("MCP tool processor — T002 contract matrix")
class McpToolProcessorCompileTest {

    // --- Frozen fixture identities ---

    /** Package every fixture declares, and therefore the generated module's resolved package. */
    private static final String TOOLS_PACKAGE = "com.example.tools";

    /** The single explicit Dagger module the processor emits for a source set. */
    private static final String GENERATED_MODULE_FQN = TOOLS_PACKAGE + ".GeneratedMcpToolsModule";

    /** The generated invoker for the valid {@code WeatherTools.lookup} baseline. */
    private static final String WEATHER_INVOKER_FQN = TOOLS_PACKAGE + ".WeatherTools_lookup_McpToolInvoker";

    // --- Matrix ---

    /**
     * The four named rows of the T002 contract matrix.
     *
     * @return one row per boundary, named exactly as the proof contract lists it
     */
    static Stream<MatrixRow> t002ContractMatrix() {
        return Stream.of(
                new MatrixRow(
                        "shouldGenerateDirectInvokerTypedInputCarrierAndExplicitDaggerModule",
                        McpToolProcessorCompileTest
                                ::shouldGenerateDirectInvokerTypedInputCarrierAndExplicitDaggerModule),
                new MatrixRow(
                        "shouldCompileUnannotatedAsNoneAndActionOnlyAsRestricted",
                        McpToolProcessorCompileTest::shouldCompileUnannotatedAsNoneAndActionOnlyAsRestricted),
                new MatrixRow(
                        "shouldResolveMethodJsonProfileOverTypeAndRejectBlankValues",
                        McpToolProcessorCompileTest::shouldResolveMethodJsonProfileOverTypeAndRejectBlankValues),
                new MatrixRow(
                        "shouldRejectDuplicateNamesAndUnsupportedSignatures",
                        McpToolProcessorCompileTest::shouldRejectDuplicateNamesAndUnsupportedSignatures));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("t002ContractMatrix")
    @DisplayName("enforces the T002 contract matrix")
    void shouldEnforceT002ContractMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    // --- Row 1: generation shape ---

    /**
     * The valid {@code WeatherTools} baseline compiles and emits the frozen generation shape: an
     * explicit Dagger module multibinding a package-private invoker that owns a typed {@code Input}
     * carrier and calls the application method directly.
     *
     * <p>The two negatives share this boundary because they are its controls: a package-private tool
     * method must be a compile error rather than a reflective fallback binding, and a tool type with
     * no injectable constructor must be a compile error rather than an unwired module entry.
     */
    private static void shouldGenerateDirectInvokerTypedInputCarrierAndExplicitDaggerModule() {
        try (McpToolCompilation valid = McpToolCompilation.of(weatherReport(), validWeatherTools())) {
            valid.result()
                    .assertSuccess()
                    // Explicit Dagger multibinding module — not a scanned registry.
                    .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@Module")
                    .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@Provides")
                    .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "@IntoSet")
                    .assertGeneratedSourceContains(GENERATED_MODULE_FQN, "WeatherTools_lookup_McpToolInvoker")
                    // Package-private invoker carrying the descriptor and the typed input carrier.
                    .assertGeneratedSourceContains(WEATHER_INVOKER_FQN, "McpToolInvoker")
                    .assertGeneratedSourceContains(WEATHER_INVOKER_FQN, "weather.lookup")
                    .assertGeneratedSourceContains(WEATHER_INVOKER_FQN, "record Input")
                    .assertGeneratedSourceDoesNotContain(
                            WEATHER_INVOKER_FQN, "public class WeatherTools_lookup_McpToolInvoker")
                    // Direct invocation: the generated body calls lookup(...) and never reflects.
                    .assertGeneratedSourceContains(WEATHER_INVOKER_FQN, ".lookup(")
                    .assertGeneratedSourceDoesNotContain(WEATHER_INVOKER_FQN, "java.lang.reflect");
        }

        // Mutation — reflection fallback: a package-private tool method is rejected, never bound
        // reflectively. Valid control: declare the method `public`.
        JavaFileObject packagePrivateToolMethod = SourceFiles.inline(TOOLS_PACKAGE + ".ReflectiveWeatherTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class ReflectiveWeatherTools {

                    @Inject
                    public ReflectiveWeatherTools() {}

                    @McpTool(name = "weather.reflective", description = "Look up the current weather.")
                    Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation reflective = McpToolCompilation.of(weatherReport(), packagePrivateToolMethod)) {
            reflective.result().assertFailed();
            assertEquals(
                    1,
                    reflective.errorsNaming("lookup", "public"),
                    "a package-private tool method must be rejected as not directly invocable");
        }

        // Mutation — missing wiring: a tool type with no injectable constructor cannot be bound in
        // the generated module. Valid control: add an `@Inject` no-arg constructor.
        JavaFileObject unwiredToolType = SourceFiles.inline(TOOLS_PACKAGE + ".UnwiredWeatherTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;

                public class UnwiredWeatherTools {

                    private final String apiKey;

                    public UnwiredWeatherTools(String apiKey) {
                        this.apiKey = apiKey;
                    }

                    @McpTool(name = "weather.unwired", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation unwired = McpToolCompilation.of(weatherReport(), unwiredToolType)) {
            unwired.result().assertFailed();
            assertEquals(
                    1,
                    unwired.errorsNaming("UnwiredWeatherTools", "@Inject"),
                    "a tool type the generated module cannot wire must be rejected");
        }

        // Mutation — package-private declaring type. The contract requires the declaring
        // Dagger-managed type to be public, not only the tool method. Valid control: `public class`.
        JavaFileObject packagePrivateToolType = SourceFiles.inline(TOOLS_PACKAGE + ".PackagePrivateWeatherTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                class PackagePrivateWeatherTools {

                    @Inject
                    PackagePrivateWeatherTools() {}

                    @McpTool(name = "weather.packagePrivateType", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation packagePrivateType = McpToolCompilation.of(weatherReport(), packagePrivateToolType)) {
            packagePrivateType.result().assertFailed();
            assertEquals(
                    1,
                    packagePrivateType.errorsNaming("PackagePrivateWeatherTools", "public"),
                    "a package-private declaring type must be rejected: the contract requires the declaring"
                            + " Dagger-managed type to be public");
        }

        // Mutation — an overload of the tool method that is not itself annotated. The contract rejects
        // overloaded tool methods unqualified, so an unannotated sibling counts. Valid control: rename
        // the sibling to lookupRange(...).
        JavaFileObject overloadedToolMethod = SourceFiles.inline(TOOLS_PACKAGE + ".OverloadedWeatherTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class OverloadedWeatherTools {

                    @Inject
                    public OverloadedWeatherTools() {}

                    @McpTool(name = "weather.overloaded", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }

                    public Future<WeatherReport> lookup(String city, int days) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation overloaded = McpToolCompilation.of(weatherReport(), overloadedToolMethod)) {
            overloaded.result().assertFailed();
            assertEquals(
                    1,
                    overloaded.errorsNaming("overload", "lookup"),
                    "an overload of a tool method must be rejected even when the sibling is not annotated");
        }
    }

    // --- Row 2: access-mode derivation ---

    /**
     * An unannotated tool resolves no base policy — REST's {@code SecurityPolicy.None} — which
     * publishes as {@code PERMIT_ALL} with no roles and no action; a tool carrying only
     * {@code @RequiresAction} publishes as {@code RESTRICTED} naming that action.
     *
     * <p>The mutation is the annotation conflict the contract names: {@code @RequiresAction}
     * composes with {@code @RolesAllowed} but conflicts with {@code @PermitAll}.
     */
    private static void shouldCompileUnannotatedAsNoneAndActionOnlyAsRestricted() {
        JavaFileObject unannotatedTool = SourceFiles.inline(TOOLS_PACKAGE + ".PublicWeatherTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class PublicWeatherTools {

                    @Inject
                    public PublicWeatherTools() {}

                    @McpTool(name = "weather.public", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        JavaFileObject actionOnlyTool = SourceFiles.inline(TOOLS_PACKAGE + ".ActionOnlyWeatherTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import dev.vertique.security.authz.RequiresAction;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class ActionOnlyWeatherTools {

                    @Inject
                    public ActionOnlyWeatherTools() {}

                    @RequiresAction("weather.city.read")
                    @McpTool(name = "weather.action", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation access = McpToolCompilation.of(weatherReport(), unannotatedTool, actionOnlyTool)) {
            access.result()
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            TOOLS_PACKAGE + ".PublicWeatherTools_lookup_McpToolInvoker", "McpAccessMode.PERMIT_ALL")
                    .assertGeneratedSourceDoesNotContain(
                            TOOLS_PACKAGE + ".PublicWeatherTools_lookup_McpToolInvoker", "McpAccessMode.RESTRICTED")
                    .assertGeneratedSourceContains(
                            TOOLS_PACKAGE + ".ActionOnlyWeatherTools_lookup_McpToolInvoker", "McpAccessMode.RESTRICTED")
                    .assertGeneratedSourceContains(
                            TOOLS_PACKAGE + ".ActionOnlyWeatherTools_lookup_McpToolInvoker", "weather.city.read");
        }

        // Mutation — annotation conflict: @PermitAll and @RequiresAction on the same tool method.
        // Valid control: drop @PermitAll and keep the action-only declaration.
        JavaFileObject conflictingPolicies = SourceFiles.inline(TOOLS_PACKAGE + ".ConflictingWeatherTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import dev.vertique.security.authz.RequiresAction;
                import io.vertx.core.Future;
                import jakarta.annotation.security.PermitAll;
                import jakarta.inject.Inject;

                public class ConflictingWeatherTools {

                    @Inject
                    public ConflictingWeatherTools() {}

                    @PermitAll
                    @RequiresAction("weather.city.read")
                    @McpTool(name = "weather.conflict", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation conflict = McpToolCompilation.of(weatherReport(), conflictingPolicies)) {
            conflict.result().assertFailed();
            assertEquals(
                    1,
                    conflict.errorsNaming("@PermitAll", "@RequiresAction"),
                    "@RequiresAction must conflict with @PermitAll, exactly as it does for REST");
        }

        // Mutation — @Authorized inherited from an overridden interface method. The rejection must run
        // over every policy source tier, exactly as the base-policy and action families do; a tier-blind
        // check accepts this tool and generates an invoker for a REST-annotated declaration. Valid
        // control: drop @Authorized from the interface method.
        JavaFileObject interfaceAuthorized = SourceFiles.inline(TOOLS_PACKAGE + ".InterfaceAuthorizedTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import dev.vertique.rest.core.security.Authorized;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                interface AuthorizedLookup {

                    @Authorized
                    Future<WeatherReport> lookup(String city);
                }

                public class InterfaceAuthorizedTools implements AuthorizedLookup {

                    @Inject
                    public InterfaceAuthorizedTools() {}

                    @Override
                    @McpTool(name = "weather.interfaceAuthorized", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation inherited =
                McpToolCompilation.of(authorizedAnnotation(), weatherReport(), interfaceAuthorized)) {
            inherited.result().assertFailed();
            assertEquals(
                    1,
                    inherited.errorsNaming("@Authorized", "AuthorizedLookup"),
                    "@Authorized inherited from an overridden interface method must be rejected, naming the"
                            + " source element it was declared on");
        }

        // Mutation — TYPE-level @Authorized on a superclass of the declaring type. Same tier blindness,
        // the type half. Valid control: drop @Authorized from the base class.
        JavaFileObject superclassAuthorized = SourceFiles.inline(TOOLS_PACKAGE + ".SuperclassAuthorizedTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import dev.vertique.rest.core.security.Authorized;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                @Authorized
                abstract class AuthorizedBaseTools {}

                public class SuperclassAuthorizedTools extends AuthorizedBaseTools {

                    @Inject
                    public SuperclassAuthorizedTools() {}

                    @McpTool(name = "weather.superclassAuthorized", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation inheritedType =
                McpToolCompilation.of(authorizedAnnotation(), weatherReport(), superclassAuthorized)) {
            inheritedType.result().assertFailed();
            assertEquals(
                    1,
                    inheritedType.errorsNaming("@Authorized", "AuthorizedBaseTools"),
                    "TYPE-level @Authorized on a superclass must be rejected, naming the source element it was"
                            + " declared on");
        }

        // Mutation — @Authorized inherited from an overridden superclass method. This is the method
        // half of the superclass tier, which neither the interface-method nor the superclass-type
        // fixture reaches: dropping the superclass-method tier from the resolver leaves both of those
        // green. Valid control: drop @Authorized from the base class method.
        JavaFileObject superclassMethodAuthorized =
                SourceFiles.inline(TOOLS_PACKAGE + ".SuperclassMethodAuthorizedTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import dev.vertique.rest.core.security.Authorized;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                abstract class AuthorizedMethodBaseTools {

                    @Authorized
                    public Future<WeatherReport> lookup(String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }

                public class SuperclassMethodAuthorizedTools extends AuthorizedMethodBaseTools {

                    @Inject
                    public SuperclassMethodAuthorizedTools() {}

                    @Override
                    @McpTool(name = "weather.superclassMethodAuthorized",
                            description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation inheritedSuperclassMethod =
                McpToolCompilation.of(authorizedAnnotation(), weatherReport(), superclassMethodAuthorized)) {
            inheritedSuperclassMethod.result().assertFailed();
            assertEquals(
                    1,
                    inheritedSuperclassMethod.errorsNaming("@Authorized", "AuthorizedMethodBaseTools.lookup()"),
                    "@Authorized inherited from an overridden superclass method must be rejected, naming the"
                            + " source element it was declared on");
        }

        // Mutation — TYPE-level @Authorized on an implemented interface. This is the type half of the
        // interface tier, the last tier the resolver considers; dropping it leaves every other
        // @Authorized fixture green. Valid control: drop @Authorized from the interface.
        JavaFileObject interfaceTypeAuthorized =
                SourceFiles.inline(TOOLS_PACKAGE + ".InterfaceTypeAuthorizedTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import dev.vertique.rest.core.security.Authorized;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                @Authorized
                interface AuthorizedToolMarker {}

                public class InterfaceTypeAuthorizedTools implements AuthorizedToolMarker {

                    @Inject
                    public InterfaceTypeAuthorizedTools() {}

                    @McpTool(name = "weather.interfaceTypeAuthorized", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation inheritedInterfaceType =
                McpToolCompilation.of(authorizedAnnotation(), weatherReport(), interfaceTypeAuthorized)) {
            inheritedInterfaceType.result().assertFailed();
            assertEquals(
                    1,
                    inheritedInterfaceType.errorsNaming("@Authorized", "AuthorizedToolMarker"),
                    "TYPE-level @Authorized on an implemented interface must be rejected, naming the source"
                            + " element it was declared on");
        }

        // Mutation — an action value outside the frozen ActionRef grammar. The generated invoker emits
        // ActionRef.parse(<value>), so a non-canonical value compiles into source that always throws at
        // composition. Valid control: "weather.city.read".
        JavaFileObject invalidActionGrammar = SourceFiles.inline(TOOLS_PACKAGE + ".InvalidActionWeatherTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import dev.vertique.security.authz.RequiresAction;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class InvalidActionWeatherTools {

                    @Inject
                    public InvalidActionWeatherTools() {}

                    @RequiresAction("weather:read")
                    @McpTool(name = "weather.invalidAction", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation invalidAction = McpToolCompilation.of(weatherReport(), invalidActionGrammar)) {
            invalidAction.result().assertFailed();
            assertEquals(
                    1,
                    invalidAction.errorsNaming("@RequiresAction", "weather:read"),
                    "an action value ActionRef.parse can never accept must be rejected at compile time, naming"
                            + " the offending value");
        }
    }

    // --- Row 3: effective JSON profile ---

    /**
     * A method-level {@code @JsonProfile} wins over the declaring type's, and a blank profile id is
     * rejected at compile time rather than surfacing as an unresolvable mapper at composition.
     */
    private static void shouldResolveMethodJsonProfileOverTypeAndRejectBlankValues() {
        JavaFileObject methodOverTypeProfile = SourceFiles.inline(TOOLS_PACKAGE + ".ProfiledWeatherTools", """
                package com.example.tools;

                import dev.vertique.core.json.JsonProfile;
                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                @JsonProfile("type-profile")
                public class ProfiledWeatherTools {

                    @Inject
                    public ProfiledWeatherTools() {}

                    @JsonProfile("method-profile")
                    @McpTool(name = "weather.profiled", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation profiles = McpToolCompilation.of(weatherReport(), methodOverTypeProfile)) {
            profiles.result()
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            TOOLS_PACKAGE + ".ProfiledWeatherTools_lookup_McpToolInvoker", "method-profile")
                    .assertGeneratedSourceDoesNotContain(
                            TOOLS_PACKAGE + ".ProfiledWeatherTools_lookup_McpToolInvoker", "type-profile");
        }

        // Mutation — blank profile id on the tool method. Valid control: "method-profile".
        JavaFileObject blankMethodProfile = SourceFiles.inline(TOOLS_PACKAGE + ".BlankProfileWeatherTools", """
                package com.example.tools;

                import dev.vertique.core.json.JsonProfile;
                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                @JsonProfile("type-profile")
                public class BlankProfileWeatherTools {

                    @Inject
                    public BlankProfileWeatherTools() {}

                    @JsonProfile("")
                    @McpTool(name = "weather.blankProfile", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation blank = McpToolCompilation.of(weatherReport(), blankMethodProfile)) {
            blank.result().assertFailed();
            assertEquals(
                    1,
                    blank.errorsNaming("@JsonProfile", "blank"),
                    "a blank @JsonProfile id must be rejected at compile time");
        }

        // Mutation — surrounding whitespace on the profile id. The contract routes the nonblank value
        // through JsonProfileId, which normalizes by trimming; emitting the raw string would select a
        // profile no registry entry can match. Valid control: "strict".
        JavaFileObject untrimmedMethodProfile =
                SourceFiles.inline(TOOLS_PACKAGE + ".UntrimmedProfileWeatherTools", """
                package com.example.tools;

                import dev.vertique.core.json.JsonProfile;
                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class UntrimmedProfileWeatherTools {

                    @Inject
                    public UntrimmedProfileWeatherTools() {}

                    @JsonProfile("  strict  ")
                    @McpTool(name = "weather.untrimmedProfile", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation untrimmed = McpToolCompilation.of(weatherReport(), untrimmedMethodProfile)) {
            untrimmed
                    .result()
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            TOOLS_PACKAGE + ".UntrimmedProfileWeatherTools_lookup_McpToolInvoker", "\"strict\"")
                    .assertGeneratedSourceDoesNotContain(
                            TOOLS_PACKAGE + ".UntrimmedProfileWeatherTools_lookup_McpToolInvoker", "\"  strict  \"");
        }
    }

    // --- Row 4: rejection diagnostics ---

    /**
     * Duplicate tool names and every unsupported signature shape fail with exactly one targeted
     * diagnostic each, and — because {@code compile-testing} exposes no generated files for a failed
     * compilation — emit no usable invoker.
     */
    private static void shouldRejectDuplicateNamesAndUnsupportedSignatures() {
        // Mutation — duplicate names. Valid control: rename the second tool to "weather.forecast".
        JavaFileObject duplicateNames = SourceFiles.inline(TOOLS_PACKAGE + ".DuplicateNameTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class DuplicateNameTools {

                    @Inject
                    public DuplicateNameTools() {}

                    @McpTool(name = "weather.lookup", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }

                    @McpTool(name = "weather.lookup", description = "Look up the weather again.")
                    public Future<WeatherReport> lookupAgain(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
        try (McpToolCompilation duplicates = McpToolCompilation.of(weatherReport(), duplicateNames)) {
            duplicates.result().assertFailed();
            assertEquals(
                    1,
                    duplicates.errorsNaming("duplicate", "weather.lookup"),
                    "a duplicate tool name must be reported once, naming the colliding name");
        }

        // Mutation — raw parameter type. Valid control: List<String> cities.
        JavaFileObject rawParameterType = SourceFiles.inline(TOOLS_PACKAGE + ".RawTypeTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import java.util.List;

                public class RawTypeTools {

                    @Inject
                    public RawTypeTools() {}

                    @McpTool(name = "weather.raw", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "cities", description = "The cities to look up.") List cities) {
                        return Future.succeededFuture(new WeatherReport("nowhere", 21));
                    }
                }
                """);
        try (McpToolCompilation raw = McpToolCompilation.of(weatherReport(), rawParameterType)) {
            raw.result().assertFailed();
            assertEquals(
                    1,
                    raw.errorsNaming("raw", "List"),
                    "a raw parameter type has no honest schema and must be rejected");
        }

        // Mutation — wildcard result type. Valid control: Future<List<WeatherReport>>.
        JavaFileObject wildcardReturnType = SourceFiles.inline(TOOLS_PACKAGE + ".WildcardTypeTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import java.util.List;

                public class WildcardTypeTools {

                    @Inject
                    public WildcardTypeTools() {}

                    @McpTool(name = "weather.wildcard", description = "Look up the current weather.")
                    public Future<List<?>> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(List.of());
                    }
                }
                """);
        try (McpToolCompilation wildcard = McpToolCompilation.of(weatherReport(), wildcardReturnType)) {
            wildcard.result().assertFailed();
            assertEquals(
                    1,
                    wildcard.errorsNaming("wildcard", "lookup"),
                    "a wildcard result type has no honest output schema and must be rejected");
        }

        // Mutation — unresolved parameter type. Valid control: WeatherReport report.
        JavaFileObject unresolvedParameterType = SourceFiles.inline(TOOLS_PACKAGE + ".UnresolvedTypeTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class UnresolvedTypeTools {

                    @Inject
                    public UnresolvedTypeTools() {}

                    @McpTool(name = "weather.unresolved", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "report", description = "The report to reuse.") MissingType report) {
                        return Future.succeededFuture(new WeatherReport("nowhere", 21));
                    }
                }
                """);
        try (McpToolCompilation unresolved = McpToolCompilation.of(weatherReport(), unresolvedParameterType)) {
            unresolved.result().assertFailed();
            assertEquals(
                    1,
                    unresolved.errorsNaming("unknown type", "MissingType"),
                    "the processor must name the type it could not resolve, not leave it to javac alone"
                            + " (javac's own 'cannot find symbol' diagnostic matches neither fragment pair,"
                            + " so a silent processor scores 0 here)");
        }

        // Mutation — unsupported input-schema member. Valid control: String city.
        JavaFileObject unsupportedSchemaMember = SourceFiles.inline(TOOLS_PACKAGE + ".UnsupportedSchemaTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;
                import java.io.InputStream;

                public class UnsupportedSchemaTools {

                    @Inject
                    public UnsupportedSchemaTools() {}

                    @McpTool(name = "weather.unsupported", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "source", description = "The source to read.") InputStream source) {
                        return Future.succeededFuture(new WeatherReport("nowhere", 21));
                    }
                }
                """);
        try (McpToolCompilation unsupported = McpToolCompilation.of(weatherReport(), unsupportedSchemaMember)) {
            unsupported.result().assertFailed();
            assertEquals(
                    1,
                    unsupported.errorsNaming("InputStream", "schema"),
                    "a parameter with no JSON schema representation must be rejected");
        }

        // Mutation — a platform type carried as the argument of an application generic. The
        // representability walk must descend into every declared type argument, not only the
        // hard-coded container and map families: an application envelope is neither, so a check that
        // stops at the envelope accepts io.vertx types inside it. Valid control: Envelope<String>.
        JavaFileObject platformInsideApplicationGeneric =
                SourceFiles.inline(TOOLS_PACKAGE + ".GenericEnvelopeTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import io.vertx.core.json.JsonObject;
                import jakarta.inject.Inject;

                public class GenericEnvelopeTools {

                    @Inject
                    public GenericEnvelopeTools() {}

                    @McpTool(name = "weather.genericEnvelope", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "envelope", description = "The envelope to read.")
                            Envelope<JsonObject> envelope) {
                        return Future.succeededFuture(new WeatherReport("nowhere", 21));
                    }
                }
                """);
        try (McpToolCompilation genericEnvelope =
                McpToolCompilation.of(weatherReport(), envelope(), platformInsideApplicationGeneric)) {
            genericEnvelope.result().assertFailed();
            assertEquals(
                    1,
                    genericEnvelope.errorsNaming("JsonObject", "schema"),
                    "a platform type reached through an application generic's type argument has no JSON schema"
                            + " representation and must be rejected — the walk must descend into every declared"
                            + " type argument, not only the container and map families");
        }

        // Control — the same application generic carrying a representable argument still compiles, so
        // the descent rejects the platform argument rather than the generic itself.
        JavaFileObject representableApplicationGeneric =
                SourceFiles.inline(TOOLS_PACKAGE + ".RepresentableEnvelopeTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class RepresentableEnvelopeTools {

                    @Inject
                    public RepresentableEnvelopeTools() {}

                    @McpTool(name = "weather.representableEnvelope", description = "Look up the current weather.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "envelope", description = "The envelope to read.")
                            Envelope<String> envelope) {
                        return Future.succeededFuture(new WeatherReport("nowhere", 21));
                    }
                }
                """);
        try (McpToolCompilation representableEnvelope =
                McpToolCompilation.of(weatherReport(), envelope(), representableApplicationGeneric)) {
            representableEnvelope.result().assertSuccess();
        }

        // Mutation — void return. Valid control: Future<WeatherReport>.
        JavaFileObject voidReturnType = SourceFiles.inline(TOOLS_PACKAGE + ".VoidReturnTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import jakarta.inject.Inject;

                public class VoidReturnTools {

                    @Inject
                    public VoidReturnTools() {}

                    @McpTool(name = "weather.void", description = "Look up the current weather.")
                    public void lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {}
                }
                """);
        try (McpToolCompilation voidReturn = McpToolCompilation.of(voidReturnType)) {
            voidReturn.result().assertFailed();
            assertEquals(
                    1,
                    voidReturn.errorsNaming("void", "lookup"),
                    "a void tool method produces no result content and must be rejected");
        }

        // Mutation — a platform result type. The contract makes RoutingContext and the rest of the
        // platform/SDK families compile errors; io.vertx.core.json.JsonObject exercises the same
        // io.vertx. prefix branch that rejects io.vertx.ext.web.RoutingContext, which is not on this
        // module's test classpath. Valid control: Future<WeatherReport>.
        JavaFileObject platformResultType = SourceFiles.inline(TOOLS_PACKAGE + ".PlatformResultTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import io.vertx.core.json.JsonObject;
                import jakarta.inject.Inject;

                public class PlatformResultTools {

                    @Inject
                    public PlatformResultTools() {}

                    @McpTool(name = "weather.platformResult", description = "Look up the current weather.")
                    public Future<JsonObject> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new JsonObject());
                    }
                }
                """);
        try (McpToolCompilation platformResult = McpToolCompilation.of(weatherReport(), platformResultType)) {
            platformResult.result().assertFailed();
            assertEquals(
                    1,
                    platformResult.errorsNaming("JsonObject", "schema"),
                    "a platform result type has no output schema representation and must be rejected — the"
                            + " representability check must run over results, not only parameters");
        }

        // Mutation — an unsupported plain result type. Valid control: WeatherReport.
        JavaFileObject unsupportedResultType = SourceFiles.inline(TOOLS_PACKAGE + ".UnsupportedResultTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import jakarta.inject.Inject;
                import java.io.InputStream;

                public class UnsupportedResultTools {

                    @Inject
                    public UnsupportedResultTools() {}

                    @McpTool(name = "weather.unsupportedResult", description = "Look up the current weather.")
                    public InputStream lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return InputStream.nullInputStream();
                    }
                }
                """);
        try (McpToolCompilation unsupportedResult = McpToolCompilation.of(unsupportedResultType)) {
            unsupportedResult.result().assertFailed();
            assertEquals(
                    1,
                    unsupportedResult.errorsNaming("InputStream", "schema"),
                    "a result with no JSON schema representation must be rejected");
        }
    }

    // --- Shared fixture sources ---

    /**
     * The structured result type every weather fixture returns.
     *
     * @return the {@code com.example.tools.WeatherReport} source
     */
    private static JavaFileObject weatherReport() {
        return SourceFiles.inline(TOOLS_PACKAGE + ".WeatherReport", """
                package com.example.tools;

                public record WeatherReport(String city, int temperatureCelsius) {}
                """);
    }

    /**
     * An application generic: neither a supported container nor a map, so it is the shape that proves
     * the representability walk descends into every declared type argument.
     *
     * @return the {@code com.example.tools.Envelope} source
     */
    private static JavaFileObject envelope() {
        return SourceFiles.inline(TOOLS_PACKAGE + ".Envelope", """
                package com.example.tools;

                public record Envelope<T>(T payload) {}
                """);
    }

    /**
     * The REST-specific {@code @Authorized} annotation, declared as a fixture source under its real
     * fully-qualified name.
     *
     * <p>The processor matches this annotation by FQN ({@code JaxRsAnnotations.AUTHORIZED}), and
     * {@code vertique-rest-core} is deliberately not on this module's classpath — MCP codegen must not
     * depend on REST. Declaring the annotation in the compiled source set is therefore the only way to
     * author a fixture that carries it, and it exercises exactly the FQN the processor looks for.
     *
     * @return the {@code dev.vertique.rest.core.security.Authorized} source
     */
    private static JavaFileObject authorizedAnnotation() {
        return SourceFiles.inline("dev.vertique.rest.core.security.Authorized", """
                package dev.vertique.rest.core.security;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.METHOD, ElementType.TYPE})
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Authorized {

                    String[] scopes() default {};
                }
                """);
    }

    /**
     * The valid {@code WeatherTools} baseline: a Dagger-managed type with one public, uniquely named
     * tool method returning a structured result. This is also the control declaration the
     * sensitivity proof restores.
     *
     * @return the {@code com.example.tools.WeatherTools} source
     */
    private static JavaFileObject validWeatherTools() {
        return SourceFiles.inline(TOOLS_PACKAGE + ".WeatherTools", """
                package com.example.tools;

                import dev.vertique.mcp.annotation.McpTool;
                import dev.vertique.mcp.annotation.McpToolParam;
                import io.vertx.core.Future;
                import jakarta.inject.Inject;

                public class WeatherTools {

                    @Inject
                    public WeatherTools() {}

                    @McpTool(name = "weather.lookup", description = "Look up the current weather for a city.")
                    public Future<WeatherReport> lookup(
                            @McpToolParam(name = "city", description = "The city to look up.") String city) {
                        return Future.succeededFuture(new WeatherReport(city, 21));
                    }
                }
                """);
    }

    // --- Framework construction ---

    /**
     * One named row of the matrix. {@link #toString()} is the row name so the parameterized display
     * name is exactly the identifier the proof contract lists.
     *
     * @param rowName the frozen row identifier
     * @param proof   the row's inline input delta and decisive assertions
     */
    private record MatrixRow(String rowName, Executable proof) {

        @Override
        public String toString() {
            return rowName;
        }
    }

    /**
     * A single {@link McpToolProcessor} compilation over an in-memory source set.
     *
     * <p>{@code compile-testing} holds both the fixture sources and every generated file in an
     * in-memory file manager owned by the {@code Compilation}. Releasing that compilation and its
     * source list is therefore the complete deletion of the fixture's temporary sources and
     * generated output — and {@link #close()} does it from try-with-resources, so it runs on every
     * exit path, including an assertion failure inside the block.
     */
    private static final class McpToolCompilation implements AutoCloseable {

        private final List<JavaFileObject> sources;
        private ProcessorTestHarness.Result result;

        private McpToolCompilation(List<JavaFileObject> sources, ProcessorTestHarness.Result result) {
            this.sources = sources;
            this.result = result;
        }

        /**
         * Compiles the given sources once with the real processor.
         *
         * @param sources the fixture sources; must not be empty
         * @return the open compilation, to be used in try-with-resources
         */
        static McpToolCompilation of(JavaFileObject... sources) {
            List<JavaFileObject> retained = new ArrayList<>(List.of(sources));
            return new McpToolCompilation(
                    retained,
                    ProcessorTestHarness.run(new McpToolProcessor(), retained.toArray(JavaFileObject[]::new)));
        }

        /**
         * Returns the compilation outcome.
         *
         * @return the harness result
         */
        ProcessorTestHarness.Result result() {
            if (result == null) {
                throw new IllegalStateException("compilation output was already released");
            }
            return result;
        }

        /**
         * Counts the {@code ERROR} diagnostics whose message names every given fragment.
         *
         * <p>Matching is case-insensitive substring matching: the contract fixes which boundary and
         * which offending symbol a diagnostic must name, not its exact wording.
         *
         * @param fragments the fragments a matching message must all contain
         * @return the number of matching error diagnostics
         */
        int errorsNaming(String... fragments) {
            return (int) result().compilation().diagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(Locale.ROOT))
                    .filter(Objects::nonNull)
                    .map(message -> message.toLowerCase(Locale.ROOT))
                    .filter(message -> Stream.of(fragments)
                            .allMatch(fragment -> message.contains(fragment.toLowerCase(Locale.ROOT))))
                    .count();
        }

        @Override
        public void close() {
            sources.clear();
            result = null;
        }
    }
}
