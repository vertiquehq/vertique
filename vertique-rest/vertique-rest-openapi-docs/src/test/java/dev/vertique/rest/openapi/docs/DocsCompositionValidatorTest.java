// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.router.MountCompositionValidator;
import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.openapi.docs.fixture.CatalogResource;
import dev.vertique.rest.openapi.docs.fixture.DocsConfigs;
import dev.vertique.rest.openapi.docs.fixture.startup.validator.ForgedMetaMount;
import dev.vertique.rest.openapi.docs.fixture.startup.validator.PathOnlyMount;
import dev.vertique.rest.openapi.docs.fixture.startup.validator.ValidatorResources;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit proofs of the documentation module's composition validator, driven with mount lists built
 * the way {@code HttpVerticle} hands them over: the mounts of one component resolution, plus or
 * minus one mount, sorted by phase, priority, mount path, and order key.
 *
 * <p>The component is the shared fixture ({@code public} documented and active, {@code mgmt}
 * undocumented, the last marker mount) plus {@code dormant}, documented but inactive, so it has no
 * enabled document and no mount. The validator is taken from the component's validator set; every
 * row resolves its own mounts, so the documentation mount it inspects starts unmarked.
 *
 * <p>Every row reads the documentation validator's result alone; no other validator runs.
 */
class DocsCompositionValidatorTest {

    /**
     * A marker planted in a configuration value; no violation may echo it, because a message names
     * configuration paths, never configuration values.
     */
    private static final String ECHO_MARKER = "zq7";

    /** The configured {@code info.title} of the {@code public} document, carrying the marker. */
    private static final String PLANTED_TITLE = "Catalog " + ECHO_MARKER;

    /** The documented, active application's name. */
    private static final String PUBLIC = "public";

    /** The binary name of the documented, active application's declaring interface. */
    private static final String PUBLIC_API_BINARY_NAME = "dev.vertique.rest.openapi.docs.fixture.PublicApi";

    /** The configuration path of the {@code public} document's entry. */
    private static final String PUBLIC_DOCUMENT_PATH = "apidocs.documents.public";

    /** The fragment stating that no mount of the composition serves the document's application. */
    private static final String HOLDS_NO_MOUNT = "holds no mount";

    /** The inactive documented application's name, which no violation may name. */
    private static final String DORMANT = "dormant";

    /** The configuration setting the prefix and pattern violations name. */
    private static final String APIDOCS_PATH = "apidocs.path";

    /** The fragment stating that collision checks cover literal mount paths only. */
    private static final String LITERAL_PATHS_ONLY = "literal JAX-RS mount paths only";

    /** The documentation prefix when {@code apidocs.path} is not configured, quoted as a violation names it. */
    private static final String QUOTED_DEFAULT_PREFIX = "'/apidocs'";

    /** The mount order {@code HttpVerticle} validates and mounts in. */
    private static final Comparator<RouterMount> HTTP_VERTICLE_ORDER = Comparator.comparing(RouterMount::phase)
            .thenComparingInt(RouterMount::priority)
            .thenComparing(RouterMount::mountPath)
            .thenComparing(RouterMount::orderKey);

    /** What the validator must answer for one document-matching list. */
    enum DocumentMatch {
        /** No violation, and the list's documentation mount is marked validated. */
        MATCHED_AND_MARKED,
        /** Exactly one violation stating that no mount serves {@code public}, and nothing is marked. */
        NO_MOUNT_AND_NOTHING_MARKED
    }

    /** What the validator must answer for one prefix row. */
    enum PrefixReach {
        /** No violation. */
        NO_VIOLATION,
        /** Exactly one violation naming the mount, {@code apidocs.path}, and the prefix. */
        UNDER_PREFIX,
        /** Exactly one violation naming the mount and {@code apidocs.path}, and literal paths only. */
        PATTERN_REACHES_PREFIX
    }

    /** Builds one row's mount list from that row's resolved mounts and the JAX-RS mount factory. */
    @FunctionalInterface
    interface MountList {

        /**
         * Builds the list.
         *
         * @param resolved the mounts of one resolution
         * @param factory the JAX-RS mount factory
         * @return the list, in {@code HttpVerticle}'s order
         */
        List<RouterMount> build(Set<RouterMount> resolved, JaxRsRouterMount.Factory factory);
    }

    static Stream<Arguments> documentMatchRows() {
        return Stream.of(
                Arguments.of(
                        "as provided: public's application mount matches",
                        (MountList) (resolved, factory) -> mountsInHttpVerticleOrder(resolved, mount -> true),
                        DocumentMatch.MATCHED_AND_MARKED),
                Arguments.of(
                        "without public's application mount",
                        (MountList) (resolved, factory) ->
                                mountsInHttpVerticleOrder(resolved, DocsCompositionValidatorTest::isNotPublicsMount),
                        DocumentMatch.NO_MOUNT_AND_NOTHING_MARKED),
                Arguments.of(
                        "a manual JAX-RS mount at public's path, with no application name",
                        (MountList) (resolved, factory) -> mountsInHttpVerticleOrder(
                                resolved,
                                DocsCompositionValidatorTest::isNotPublicsMount,
                                factory.create("/api/public/*", null, Set.of(new CatalogResource()))),
                        DocumentMatch.NO_MOUNT_AND_NOTHING_MARKED),
                Arguments.of(
                        "a non-JAX-RS mount at public's path whose metadata names public",
                        (MountList) (resolved, factory) -> mountsInHttpVerticleOrder(
                                resolved, DocsCompositionValidatorTest::isNotPublicsMount, new ForgedMetaMount()),
                        DocumentMatch.NO_MOUNT_AND_NOTHING_MARKED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentMatchRows")
    @DisplayName("A document matches a JAX-RS mount by application name only, never by path")
    void documentMatchesItsMountByApplicationName(String row, MountList mountList, DocumentMatch expected) {
        // Given: the row's list, built from one resolution of the component's mounts, holding one
        // documentation mount that is not yet marked.
        DormantBesideShared component = component(null);
        Set<RouterMount> resolved = component.routerMounts();
        if (expected == DocumentMatch.NO_MOUNT_AND_NOTHING_MARKED) {
            assertEquals(
                    1,
                    resolved.stream().filter(mount -> !isNotPublicsMount(mount)).count(),
                    "the resolution holds exactly one JAX-RS mount of public, which the row removes");
        }
        List<RouterMount> mounts = mountList.build(resolved, component.jaxRsMountFactory());
        DocsRouterMount docsMount = onlyDocsMount(mounts);
        assertFalse(docsMount.isValidated(), "the documentation mount starts unmarked");
        MountCompositionValidator validator = component.docsValidator();

        // When: the documentation validator checks the list.
        List<String> violations = validator.validate(mounts);

        // Then: the row's expectation holds, and no violation names the inactive application.
        switch (expected) {
            case MATCHED_AND_MARKED -> {
                assertEquals(List.of(), violations, "no violation");
                assertTrue(docsMount.isValidated(), "the documentation mount is marked");
            }
            case NO_MOUNT_AND_NOTHING_MARKED -> {
                assertEquals(1, violations.size(), () -> "exactly one violation: " + violations);
                assertNames(
                        violations.getFirst(),
                        PUBLIC_DOCUMENT_PATH,
                        "'" + PUBLIC + "'",
                        PUBLIC_API_BINARY_NAME,
                        HOLDS_NO_MOUNT);
                assertFalse(docsMount.isValidated(), "a violation leaves the documentation mount unmarked");
            }
        }
        assertFalse(String.join("\n", violations).contains(DORMANT), () -> "no violation names dormant: " + violations);
    }

    static Stream<Arguments> prefixRows() {
        return Stream.of(
                prefixRow(
                        "JAX-RS at /apidocs/*",
                        null,
                        jaxRs("/apidocs/*", new ValidatorResources.AtPrefix()),
                        PrefixReach.UNDER_PREFIX,
                        "jaxrs:/apidocs/*"),
                prefixRow(
                        "JAX-RS at /apidocs/admin/*",
                        null,
                        jaxRs("/apidocs/admin/*", new ValidatorResources.UnderPrefix()),
                        PrefixReach.UNDER_PREFIX,
                        "jaxrs:/apidocs/admin/*"),
                prefixRow(
                        "JAX-RS at /apidocsx/*",
                        null,
                        jaxRs("/apidocsx/*", new ValidatorResources.PrefixWithoutBoundary()),
                        PrefixReach.NO_VIOLATION,
                        null),
                prefixRow(
                        "JAX-RS at /api/apidocs/*",
                        null,
                        jaxRs("/api/apidocs/*", new ValidatorResources.PrefixNested()),
                        PrefixReach.NO_VIOLATION,
                        null),
                prefixRow(
                        "non-JAX-RS at /apidocs/ui/*",
                        null,
                        factory -> new PathOnlyMount("/apidocs/ui/*"),
                        PrefixReach.NO_VIOLATION,
                        null),
                prefixRow(
                        "JAX-RS at /apidocs/* with apidocs.path /docs",
                        "/docs",
                        jaxRs("/apidocs/*", new ValidatorResources.MovedPrefix()),
                        PrefixReach.NO_VIOLATION,
                        null),
                prefixRow(
                        "JAX-RS at /:tenant/*",
                        null,
                        jaxRs("/:tenant/*", new ValidatorResources.ColonTenant()),
                        PrefixReach.PATTERN_REACHES_PREFIX,
                        "jaxrs:/:tenant/*"),
                prefixRow(
                        "JAX-RS at /{tenant}/*",
                        null,
                        jaxRs("/{tenant}/*", new ValidatorResources.BraceTenant()),
                        PrefixReach.PATTERN_REACHES_PREFIX,
                        "jaxrs:/{tenant}/*"),
                prefixRow(
                        "non-JAX-RS at /:tenant/*",
                        null,
                        factory -> new PathOnlyMount("/:tenant/*"),
                        PrefixReach.NO_VIOLATION,
                        null),
                prefixRow(
                        "JAX-RS at /api/{v}/* with apidocs.path /api/docs",
                        "/api/docs",
                        jaxRs("/api/{v}/*", new ValidatorResources.Versioned()),
                        PrefixReach.PATTERN_REACHES_PREFIX,
                        "jaxrs:/api/{v}/*"),
                prefixRow(
                        "JAX-RS at /api/:tenant/*",
                        null,
                        jaxRs("/api/:tenant/*", new ValidatorResources.ApiTenant()),
                        PrefixReach.NO_VIOLATION,
                        null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prefixRows")
    @DisplayName("A JAX-RS mount under the documentation prefix, or a pattern mount able to reach it, is a violation")
    void jaxRsMountsUnderOrAbleToReachThePrefixAreViolations(
            String row,
            @Nullable String apidocsPath,
            Function<JaxRsRouterMount.Factory, RouterMount> extraMount,
            PrefixReach expected,
            @Nullable String mountId) {
        // Given: the component's mounts as provided, plus the row's mount, in HttpVerticle's order.
        DormantBesideShared component = component(apidocsPath);
        RouterMount added = extraMount.apply(component.jaxRsMountFactory());
        List<RouterMount> mounts = mountsInHttpVerticleOrder(component.routerMounts(), mount -> true, added);
        MountCompositionValidator validator = component.docsValidator();

        // When: the documentation validator checks the list.
        List<String> violations = validator.validate(mounts);

        // Then: the row's expectation holds.
        switch (expected) {
            case NO_VIOLATION -> assertEquals(List.of(), violations, "no violation");
            case UNDER_PREFIX -> {
                assertEquals(1, violations.size(), () -> "exactly one violation: " + violations);
                assertNames(violations.getFirst(), mountId, added.mountPath(), APIDOCS_PATH, QUOTED_DEFAULT_PREFIX);
            }
            case PATTERN_REACHES_PREFIX -> {
                assertEquals(1, violations.size(), () -> "exactly one violation: " + violations);
                assertNames(violations.getFirst(), mountId, added.mountPath(), APIDOCS_PATH, LITERAL_PATHS_ONLY);
            }
        }
    }

    /**
     * Builds a mount list the way {@code HttpVerticle} orders it: the resolved mounts that
     * {@code kept} accepts, plus {@code added}, sorted by phase, priority, mount path, and order key.
     *
     * @param resolved the mounts of one resolution
     * @param kept which resolved mounts stay in the list
     * @param added mounts added to the list
     * @return the unmodifiable list
     */
    private static List<RouterMount> mountsInHttpVerticleOrder(
            Set<RouterMount> resolved, Predicate<RouterMount> kept, RouterMount... added) {
        List<RouterMount> mounts = new ArrayList<>();
        resolved.stream().filter(kept).forEach(mounts::add);
        mounts.addAll(List.of(added));
        return mounts.stream().sorted(HTTP_VERTICLE_ORDER).toList();
    }

    private static boolean isNotPublicsMount(RouterMount mount) {
        return !(mount instanceof JaxRsRouterMount && PUBLIC.equals(mount.meta().applicationName()));
    }

    private static DocsRouterMount onlyDocsMount(List<RouterMount> mounts) {
        List<RouterMount> docsMounts = mounts.stream()
                .filter(mount -> mount instanceof DocsRouterMount)
                .toList();
        assertEquals(1, docsMounts.size(), () -> "exactly one documentation mount in " + mounts);
        return assertInstanceOf(DocsRouterMount.class, docsMounts.getFirst());
    }

    private static Function<JaxRsRouterMount.Factory, RouterMount> jaxRs(String mountPath, Object resource) {
        return factory -> factory.create(mountPath, null, Set.of(resource));
    }

    private static Arguments prefixRow(
            String row,
            @Nullable String apidocsPath,
            Function<JaxRsRouterMount.Factory, RouterMount> extraMount,
            PrefixReach expected,
            @Nullable String mountId) {
        return Arguments.of(row, apidocsPath, extraMount, expected, mountId);
    }

    private static void assertNames(String violation, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(violation.contains(fragment), () -> "the violation names '" + fragment + "': " + violation);
        }
        assertFalse(violation.contains(ECHO_MARKER), () -> "the violation echoes no configuration value: " + violation);
    }

    /**
     * Creates the component from the shared configuration, with the marker planted in the
     * {@code public} document's title and, when given, {@code apidocs.path} set.
     */
    private static DormantBesideShared component(@Nullable String apidocsPath) {
        JsonObject config = DocsConfigs.loopback();
        DocsConfigs.withDocumentInfo(config, PUBLIC, PLANTED_TITLE, DocsConfigs.PUBLIC_VERSION);
        if (apidocsPath != null) {
            DocsConfigs.withApidocsPath(config, apidocsPath);
        }
        return new DormantBesideShared(DaggerValidatorTestComponents_DormantBesideSharedComponent.factory()
                .create(config));
    }

    /** The component, with the lookup of the documentation validator in its validator set. */
    private record DormantBesideShared(ValidatorTestComponents.DormantBesideSharedComponent component) {

        Set<RouterMount> routerMounts() {
            return component.routerMounts();
        }

        JaxRsRouterMount.Factory jaxRsMountFactory() {
            return component.jaxRsMountFactory();
        }

        MountCompositionValidator docsValidator() {
            List<MountCompositionValidator> docsValidators = component.mountCompositionValidators().stream()
                    .filter(validator -> validator instanceof DocsCompositionValidator)
                    .toList();
            assertEquals(
                    1, docsValidators.size(), "the composition validators hold exactly one documentation validator");
            return docsValidators.getFirst();
        }
    }
}
