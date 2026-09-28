// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.application.ConflictCompositionComponents.ConflictPathsComponent;
import dev.vertique.rest.jaxrs.application.conflict.paths.AlphaResource;
import dev.vertique.rest.jaxrs.application.conflict.paths.BetaResource;
import dev.vertique.rest.jaxrs.application.conflict.paths.DeltaResource;
import dev.vertique.rest.jaxrs.application.conflict.paths.GammaResource;
import dev.vertique.rest.jaxrs.application.conflict.paths.PathConflictApis;
import dev.vertique.rest.jaxrs.application.conflict.paths.PublicProbeResource;
import dev.vertique.rest.jaxrs.application.conflict.paths.PublicityProbeResource;
import dev.vertique.rest.jaxrs.application.conflict.paths.RootResource;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TP-016 (ported): proves composer step 1b — conflicting active application paths fail before any
 * resource is resolved. Every case pairs two applications from the self-contained
 * {@code conflict.paths} compilation unit, so the only possible violation is the deliberate path
 * conflict, never an unrelated membership violation. The only changed expectations from the
 * pre-port {@code Application}-based suite are that failure messages name the application by its
 * registered name and declaring interface where they named the {@code Application} class.
 */
class JaxRsApplicationMountConflictTest {

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsApplicationMountConflictTest.class);

    /** Every conflict fixture's resource construction counter, for the "zero resolutions" assertion. */
    private static final List<AtomicInteger> ALL_RESOURCE_COUNTERS = List.of(
            AlphaResource.CONSTRUCTIONS,
            BetaResource.CONSTRUCTIONS,
            GammaResource.CONSTRUCTIONS,
            DeltaResource.CONSTRUCTIONS,
            RootResource.CONSTRUCTIONS,
            PublicProbeResource.CONSTRUCTIONS,
            PublicityProbeResource.CONSTRUCTIONS);

    @BeforeEach
    void resetCounters() {
        AlphaResource.reset();
        BetaResource.reset();
        GammaResource.reset();
        DeltaResource.reset();
        RootResource.reset();
        PublicProbeResource.reset();
        PublicityProbeResource.reset();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conflictCases")
    @DisplayName("Conflicting application paths fail before any resource is resolved; the control composes")
    void conflictingApplicationPathsFailBeforeResolution(ConflictCase testCase) {
        if (testCase.expectConflict()) {
            RestConfigurationException ex = assertThrows(
                    RestConfigurationException.class, testCase.action()::get, testCase.name() + " must fail");
            LOG.info("TP-016 {} failure: {}", testCase.name(), ex.getMessage());

            for (String fragment : testCase.expectedMessageFragments()) {
                assertTrue(
                        ex.getMessage() != null && ex.getMessage().contains(fragment),
                        () -> testCase.name() + ": expected the failure to name '" + fragment + "': "
                                + ex.getMessage());
            }

            assertEquals(
                    0,
                    sum(ALL_RESOURCE_COUNTERS),
                    testCase.name() + ": no resource is resolved before a step 1b violation fails");
        } else {
            Set<RouterMount> mounts = assertDoesNotThrow(testCase.action()::get, testCase.name() + " must compose");
            LOG.info("TP-016 {} composed {} mount(s)", testCase.name(), mounts.size());
            assertEquals(2, mounts.size(), testCase.name() + ": both non-conflicting applications must mount");
        }
    }

    private static int sum(List<AtomicInteger> counters) {
        int total = 0;
        for (AtomicInteger counter : counters) {
            total += counter.get();
        }
        return total;
    }

    /**
     * TP-016's four cases: three conflicting pairs (same normalized path; {@code /api} beside
     * {@code /api/mgmt}; root {@code /} beside {@code /api/mgmt}) and the control ({@code
     * /api/public} beside {@code /api/publicity}). Each failure names both applications' registered
     * name and declaring interface.
     *
     * @return the four cases, in contract order
     */
    private static Stream<ConflictCase> conflictCases() {
        return Stream.of(
                new ConflictCase(
                        "case 1: two applications with the same normalized path",
                        () -> conflictPathsComponent(activate("alpha", "beta")).routerMounts(),
                        true,
                        List.of(
                                "paths-alpha",
                                PathConflictApis.AlphaApi.class.getName(),
                                "paths-beta",
                                PathConflictApis.BetaApi.class.getName())),
                new ConflictCase(
                        "case 2: /api beside /api/mgmt",
                        () -> conflictPathsComponent(activate("gamma", "delta")).routerMounts(),
                        true,
                        List.of(
                                "paths-gamma",
                                PathConflictApis.GammaApi.class.getName(),
                                "paths-delta",
                                PathConflictApis.DeltaApi.class.getName())),
                new ConflictCase(
                        "case 3: root / beside /api/mgmt",
                        () -> conflictPathsComponent(activate("root", "delta")).routerMounts(),
                        true,
                        List.of(
                                "paths-root",
                                PathConflictApis.RootApi.class.getName(),
                                "paths-delta",
                                PathConflictApis.DeltaApi.class.getName())),
                new ConflictCase(
                        "case 4 (control): /api/public beside /api/publicity",
                        () -> conflictPathsComponent(activate("publicProbe", "publicityProbe"))
                                .routerMounts(),
                        false,
                        List.of()));
    }

    // --- Component and configuration helpers ---

    private static ConflictPathsComponent conflictPathsComponent(JsonObject config) {
        return DaggerConflictCompositionComponents_ConflictPathsComponent.factory()
                .create(config);
    }

    /**
     * Builds the nested {@code conflict.paths.<name>.active=true} configuration tree activating
     * exactly the named applications; every other application's condition stays unmatched
     * (missing, {@code matchIfMissing=false}), so it is inactive.
     *
     * @param names the fixture names (as used in each condition's dotted property name) to activate
     * @return the nested configuration object
     */
    private static JsonObject activate(String... names) {
        JsonObject paths = new JsonObject();
        for (String name : names) {
            paths.put(name, new JsonObject().put("active", true));
        }
        return new JsonObject().put("conflict", new JsonObject().put("paths", paths));
    }

    /**
     * One TP-016 case: its name, the {@code Set<RouterMount>} resolution to run, whether it must
     * fail with a conflict, and (for a conflicting case) the message fragments the thrown
     * {@link RestConfigurationException} must contain.
     *
     * @param name                     the case's display name
     * @param action                   resolves {@code Set<RouterMount>} for this case's activated pair
     * @param expectConflict           {@code true} when this case must fail with a path conflict
     * @param expectedMessageFragments the message fragments the failure must contain (empty for the control)
     */
    private record ConflictCase(
            String name,
            Supplier<Set<RouterMount>> action,
            boolean expectConflict,
            List<String> expectedMessageFragments) {

        @Override
        public String toString() {
            return name;
        }
    }
}
