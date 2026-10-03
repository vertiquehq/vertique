// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.ApplicationMountTestAccess;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.application.RestApplications.ContractOrigin;
import dev.vertique.rest.jaxrs.application.RestApplications.Entry;
import dev.vertique.rest.jaxrs.application.strategy.OpenApiContractPassThroughStrategy;
import dev.vertique.rest.jaxrs.application.strategy.WebValidationPassThroughStrategy;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * TP-010: {@link JaxRsApplicationMountValidator}'s global cross-mount operationId refusal holds
 * under every strategy — the same-owner exemption and rest-024's INV-1 gate unchanged — while
 * {@code resolvesOperationsFromMountContract()} alone gates the flag-only location parse, which
 * never falls back to a raw string and never throws on an unparseable location (PK5-001).
 *
 * <p>Every row's mounts are built directly through {@link ApplicationMountTestAccess}, standing in
 * for mounts a real composition or a merge of several would produce; the sentinel value {@code
 * "zq7"} must never appear in a violation, checked without ever writing it into an assertion
 * message.
 */
class ApplicationOperationIdScopeTest {

    /**
     * The value the unparseable-location rows configure, also used directly on a hand-built mount,
     * and that must never be echoed by any violation.
     */
    private static final String SENTINEL = "zq7";

    /** A contract location containing a NUL character, which {@code Path.of(...)} cannot parse. */
    private static final String UNPARSEABLE_LOCATION = SENTINEL + '\0' + ".yaml";

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    @DisplayName("The global cross-mount operationId refusal holds under every strategy and the same-owner "
            + "exemption; only a contract-driven strategy under the flag gates the location parse, which never "
            + "falls back to a raw string or throws on an unparseable location")
    void globalRefusalHoldsAndTheFlagGatesTheLocationParse(Row row) {
        MountCompositionValidator validator =
                ApplicationMountTestAccess.newValidator(row.view(), row.config(), row.strategies());

        List<String> violations = validator.validate(row.mounts());

        if (row.expectedViolationSubstrings().isEmpty()) {
            assertTrue(violations.isEmpty(), () -> row.label() + ": expected no violations, got: " + violations);
        } else {
            assertEquals(
                    1, violations.size(), () -> row.label() + ": expected exactly one violation, got: " + violations);
            String violation = violations.get(0);
            for (String required : row.expectedViolationSubstrings()) {
                assertTrue(
                        violation.contains(required),
                        () -> row.label() + ": expected the violation to contain '" + required + "': " + violation);
            }
        }
        assertTrue(
                violations.stream().noneMatch(v -> v.contains(SENTINEL)),
                () -> row.label() + ": a violation must never echo the configured location value");

        boolean expectValidated = violations.isEmpty();
        for (RouterMount mount : row.mounts()) {
            JaxRsRouterMount jaxRsMount = (JaxRsRouterMount) mount;
            if (ApplicationMountTestAccess.applicationName(jaxRsMount) != null) {
                assertEquals(
                        expectValidated,
                        ApplicationMountTestAccess.isValidated(jaxRsMount),
                        () -> row.label() + ": the validated mark must match whether a violation was reported");
            }
        }
    }

    static Stream<Row> rows() {
        return Stream.of(
                sameOwnerUnderTheFlag(),
                contractIdWithFlagFalse(),
                customContractIdWithFlagTrue(),
                applicationMountBesideHandBuiltMount(),
                onlyHandBuiltMountsKeepTheGateClosed(),
                twoApplicationMountsWithEmptyView(),
                flagFalseOverTwoApplicationMounts(),
                unregisteredStrategyIdOverTwoApplicationMounts(),
                unparseableLocationUnderTheFlag(),
                flagFalseSkipsLocationParsing(),
                nullLocationSkippedAndHandBuiltLocationNamedByPath(),
                configuredIdWithOnlyAnotherStrategyRegistered());
    }

    // --- Rows ---

    private static Row sameOwnerUnderTheFlag() {
        JaxRsRouterMount first =
                appMount("/api/one/*", "shared.yaml", "appOne", AppOne.class, new SharedOwnerResource());
        JaxRsRouterMount second =
                appMount("/api/two/*", "shared.yaml", "appTwo", AppTwo.class, new SharedOwnerResource());
        return new Row(
                "same owner under the flag",
                configFor("openapi-contract"),
                Set.of(new OpenApiContractPassThroughStrategy()),
                emptyView(),
                List.of(first, second),
                List.of());
    }

    private static Row contractIdWithFlagFalse() {
        JaxRsRouterMount first = appMount("/api/one/*", "shared.yaml", "appOne", AppOne.class, new OwnerAResource());
        JaxRsRouterMount second = appMount("/api/two/*", "shared.yaml", "appTwo", AppTwo.class, new OwnerBResource());
        return new Row(
                "id 'openapi-contract' but flag false",
                configFor("openapi-contract"),
                Set.of(new OpenApiContractIdFalseFlagStrategy()),
                emptyView(),
                List.of(first, second),
                List.of("'list'", "/api/one/*", "/api/two/*"));
    }

    private static Row customContractIdWithFlagTrue() {
        JaxRsRouterMount first = appMount("/api/one/*", "public.yaml", "appOne", AppOne.class, new OwnerAResource());
        JaxRsRouterMount second = appMount("/api/two/*", "partner.yaml", "appTwo", AppTwo.class, new OwnerBResource());
        return new Row(
                "custom id 'contract-v2' with flag true",
                configFor("contract-v2"),
                Set.of(new ContractV2Strategy()),
                emptyView(),
                List.of(first, second),
                List.of("'list'", "/api/one/*", "/api/two/*"));
    }

    private static Row applicationMountBesideHandBuiltMount() {
        JaxRsRouterMount applicationMount =
                appMount("/api/one/*", "shared.yaml", "appOne", AppOne.class, new OwnerAResource());
        JaxRsRouterMount handBuilt = handBuiltMount("/api/two/*", "shared.yaml", new OwnerBResource());
        return new Row(
                "an application mount and a hand-built mount",
                configFor("openapi-contract"),
                Set.of(new OpenApiContractPassThroughStrategy()),
                emptyView(),
                List.of(applicationMount, handBuilt),
                List.of("'list'", "/api/one/*", "/api/two/*"));
    }

    private static Row onlyHandBuiltMountsKeepTheGateClosed() {
        // R-009: second's location is UNPARSEABLE_LOCATION, under a strategy whose flag is true, so
        // this row proves E6 — the parse runs only when the gate is open, not merely whenever the
        // flag is reported: with no application mount and an empty view, the gate stays closed, and
        // an ungated parse would turn this otherwise-clean row red.
        JaxRsRouterMount first = handBuiltMount("/api/one/*", "shared.yaml", new OwnerAResource());
        JaxRsRouterMount second = handBuiltMount("/api/two/*", UNPARSEABLE_LOCATION, new OwnerBResource());
        return new Row(
                "empty view, only hand-built mounts: the scan's gate stays closed",
                configFor("openapi-contract"),
                Set.of(new OpenApiContractPassThroughStrategy()),
                emptyView(),
                List.of(first, second),
                List.of());
    }

    private static Row twoApplicationMountsWithEmptyView() {
        JaxRsRouterMount first = appMount("/api/one/*", "shared.yaml", "appOne", AppOne.class, new OwnerAResource());
        JaxRsRouterMount second = appMount("/api/two/*", "shared.yaml", "appTwo", AppTwo.class, new OwnerBResource());
        return new Row(
                "empty view, two application mounts (merged compositions)",
                configFor("openapi-contract"),
                Set.of(new OpenApiContractPassThroughStrategy()),
                emptyView(),
                List.of(first, second),
                List.of("'list'", "/api/one/*", "/api/two/*"));
    }

    private static Row flagFalseOverTwoApplicationMounts() {
        JaxRsRouterMount first = appMount("/api/one/*", "shared.yaml", "appOne", AppOne.class, new OwnerAResource());
        JaxRsRouterMount second = appMount("/api/two/*", "shared.yaml", "appTwo", AppTwo.class, new OwnerBResource());
        return new Row(
                "flag false, two application mounts with an empty view",
                configFor("web-validation"),
                Set.of(new WebValidationPassThroughStrategy()),
                emptyView(),
                List.of(first, second),
                List.of("'list'", "/api/one/*", "/api/two/*"));
    }

    private static Row unregisteredStrategyIdOverTwoApplicationMounts() {
        JaxRsRouterMount first = appMount("/api/one/*", "shared.yaml", "appOne", AppOne.class, new OwnerAResource());
        JaxRsRouterMount second = appMount("/api/two/*", "shared.yaml", "appTwo", AppTwo.class, new OwnerBResource());
        return new Row(
                "no registered strategy carries the configured id, two application mounts with an empty view",
                configFor("unregistered-strategy-id"),
                Set.of(),
                emptyView(),
                List.of(first, second),
                List.of("'list'", "/api/one/*", "/api/two/*"));
    }

    private static Row unparseableLocationUnderTheFlag() {
        RestApplications view = viewWithAAndB();
        JaxRsRouterMount unparseable =
                appMount("/api/a/*", UNPARSEABLE_LOCATION, "a", AppA.class, new DistinctResourceA());
        JaxRsRouterMount parseable = appMount("/api/b/*", "shared.yaml", "b", AppB.class, new DistinctResourceB());
        return new Row(
                "an unparseable configured location under the flag",
                configFor("openapi-contract"),
                Set.of(new OpenApiContractPassThroughStrategy()),
                view,
                List.of(unparseable, parseable),
                List.of("'a'", "jaxrs.applications.a.openapiPath"));
    }

    private static Row flagFalseSkipsLocationParsing() {
        RestApplications view = viewWithAAndB();
        JaxRsRouterMount unparseable =
                appMount("/api/a/*", UNPARSEABLE_LOCATION, "a", AppA.class, new DistinctResourceA());
        JaxRsRouterMount parseable = appMount("/api/b/*", "shared.yaml", "b", AppB.class, new DistinctResourceB());
        return new Row(
                "flag false: locations are not parsed at all",
                configFor("web-validation"),
                Set.of(new WebValidationPassThroughStrategy()),
                view,
                List.of(unparseable, parseable),
                List.of());
    }

    private static Row nullLocationSkippedAndHandBuiltLocationNamedByPath() {
        // PIT G1: a null location is skipped without throwing, and a hand-built mount at an
        // unparseable location is named by its mount path, never by the location value — no
        // application mount is validated once a violation is reported.
        JaxRsRouterMount application = appMount("/api/one/*", null, "appOne", AppOne.class, new DistinctResourceA());
        JaxRsRouterMount handBuilt = handBuiltMount("/api/two/*", UNPARSEABLE_LOCATION, new DistinctResourceB(), 500);
        return new Row(
                "a null location is skipped; a hand-built unparseable location is named by its path",
                configFor("openapi-contract"),
                Set.of(new OpenApiContractPassThroughStrategy()),
                emptyView(),
                List.of(application, handBuilt),
                List.of("hand-built JAX-RS mount '/api/two/*'"));
    }

    private static Row configuredIdWithOnlyAnotherStrategyRegistered() {
        // PIT G1: no registered strategy carries the configured id ("web-validation"); the only
        // registered strategy ("openapi-contract", flag true) must not be consulted merely because it
        // is the sole entry in the Set — unregisteredStrategyIdOverTwoApplicationMounts cannot show this
        // clause because it registers no strategy at all and both its locations parse cleanly.
        RestApplications view = viewWithAAndB();
        JaxRsRouterMount unparseable =
                appMount("/api/a/*", UNPARSEABLE_LOCATION, "a", AppA.class, new DistinctResourceA());
        JaxRsRouterMount parseable = appMount("/api/b/*", "shared.yaml", "b", AppB.class, new DistinctResourceB());
        return new Row(
                "a configured id with only a strategy registered under a different id",
                configFor("web-validation"),
                Set.of(new OpenApiContractPassThroughStrategy()),
                view,
                List.of(unparseable, parseable),
                List.of());
    }

    // --- Row-building helpers ---

    private static JaxRsRouterMount appMount(
            String mountPath, String openapiPath, String name, Class<?> declaringType, Object resource) {
        return ApplicationMountTestAccess.createApplicationMount(
                ApplicationMountTestAccess.factory(), mountPath, openapiPath, Set.of(resource), name, declaringType);
    }

    private static JaxRsRouterMount handBuiltMount(String mountPath, String openapiPath, Object resource) {
        return ApplicationMountTestAccess.factory().create(mountPath, openapiPath, Set.of(resource));
    }

    private static JaxRsRouterMount handBuiltMount(
            String mountPath, String openapiPath, Object resource, int priority) {
        return ApplicationMountTestAccess.factory().create(mountPath, openapiPath, Set.of(resource), priority);
    }

    private static JaxRsConfig configFor(String validationStrategyId) {
        return JaxRsConfig.builder().validationStrategy(validationStrategyId).build();
    }

    private static RestApplications emptyView() {
        return new RestApplications(List.of());
    }

    private static RestApplications viewWithAAndB() {
        return new RestApplications(List.of(
                new Entry("a", AppA.class, true, "/api/a/*", UNPARSEABLE_LOCATION, ContractOrigin.CONFIGURATION),
                new Entry("b", AppB.class, true, "/api/b/*", "shared.yaml", ContractOrigin.ANNOTATION)));
    }

    /** One parameterized row: (label, config, strategies, view, mounts, expected violation substrings). */
    private record Row(
            String label,
            JaxRsConfig config,
            Set<RequestValidationStrategy> strategies,
            RestApplications view,
            List<RouterMount> mounts,
            List<String> expectedViolationSubstrings) {

        @Override
        public String toString() {
            return label;
        }
    }

    // --- Declaring-type fixtures (identity only, never constructed) ---

    private static final class AppOne {}

    private static final class AppTwo {}

    private static final class AppA {}

    private static final class AppB {}

    // --- Resource fixtures ---

    /** Instantiated twice in sameOwnerUnderTheFlag: two instances of the SAME class share an owner. */
    @Path("/scope/shared")
    public static class SharedOwnerResource {

        /**
         * Handles {@code GET .../scope/shared}.
         *
         * @return the fixed body {@code "shared"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String list() {
            return "shared";
        }
    }

    /** A distinct owner from {@link OwnerBResource}, sharing the operationId {@code list}. */
    @Path("/scope/owner-a")
    public static class OwnerAResource {

        /**
         * Handles {@code GET .../scope/owner-a}.
         *
         * @return the fixed body {@code "a"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String list() {
            return "a";
        }
    }

    /** A distinct owner from {@link OwnerAResource}, sharing the operationId {@code list}. */
    @Path("/scope/owner-b")
    public static class OwnerBResource {

        /**
         * Handles {@code GET .../scope/owner-b}.
         *
         * @return the fixed body {@code "b"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String list() {
            return "b";
        }
    }

    /** The unparseable-location rows' first resource, an operationId distinct from {@link DistinctResourceB}'s. */
    @Path("/scope/distinct-a")
    public static class DistinctResourceA {

        /**
         * Handles {@code GET .../scope/distinct-a}.
         *
         * @return the fixed body {@code "opA"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String opA() {
            return "opA";
        }
    }

    /** The unparseable-location rows' second resource, an operationId distinct from {@link DistinctResourceA}'s. */
    @Path("/scope/distinct-b")
    public static class DistinctResourceB {

        /**
         * Handles {@code GET .../scope/distinct-b}.
         *
         * @return the fixed body {@code "opB"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String opB() {
            return "opB";
        }
    }

    // --- Strategy fixtures local to this test (mismatched id/flag combinations) ---

    /** Carries the built-in {@code "openapi-contract"} id but reports the flag false. */
    private static final class OpenApiContractIdFalseFlagStrategy implements RequestValidationStrategy {

        @Override
        public String id() {
            return "openapi-contract";
        }

        @Override
        public boolean resolvesOperationsFromMountContract() {
            return false;
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            return Optional.empty();
        }
    }

    /** A custom, non-built-in id reporting the flag true. */
    private static final class ContractV2Strategy implements RequestValidationStrategy {

        @Override
        public String id() {
            return "contract-v2";
        }

        @Override
        public boolean resolvesOperationsFromMountContract() {
            return true;
        }

        @Override
        public Optional<Handler<RoutingContext>> gateFor(JaxRsOperationDescriptor op, OperationSchemas schemas) {
            return Optional.empty();
        }
    }
}
