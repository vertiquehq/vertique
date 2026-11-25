// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.router.RouterMount;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.application.legacy.CatalogResource;
import dev.vertique.rest.jaxrs.application.legacy.DaggerLegacyComponents_EmptyComponent;
import dev.vertique.rest.jaxrs.application.legacy.DaggerLegacyComponents_ResourceAndManualComponent;
import dev.vertique.rest.jaxrs.application.legacy.DisabledResource;
import dev.vertique.rest.jaxrs.application.legacy.ExtraResource;
import dev.vertique.rest.jaxrs.application.legacy.LegacyComponents;
import dev.vertique.rest.jaxrs.application.legacy.ManualResource;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterizes today's zero-declaration JAX-RS mount composition against the legacy-shaped
 * generated module under {@code application.legacy}: no registration-set parameter, no catalog
 * entries — exactly the shape {@code GeneratedJaxRsResourcesModuleEmitter} emits for
 * older-processor modules, which contribute unconditionally.
 *
 * <p>Pins three of the four facts the suite characterizes: the {@code @JaxRsResources}
 * content, the default mount's configured base path and OpenAPI contract location,
 * and the absent default mount for an empty resource set. The fourth —
 * per-{@code HttpVerticle}-instance construction — is pinned by
 * {@link ZeroApplicationCompositionCharacterizationIT}.
 *
 * <p>This suite pins the legacy zero-declaration behavior: a failure here is a regression in
 * that behavior, not an expectation to be flipped.
 */
class ZeroApplicationCompositionCharacterizationTest {

    @BeforeEach
    void resetCounters() {
        CatalogResource.CONSTRUCTIONS.set(0);
        ExtraResource.CONSTRUCTIONS.set(0);
        DisabledResource.CONSTRUCTIONS.set(0);
        ManualResource.CONSTRUCTIONS.set(0);
    }

    /** Builds the legacy-resource-and-manual component from the given application configuration. */
    private static LegacyComponents.ResourceAndManualComponent resourceAndManualComponent(JsonObject config) {
        return DaggerLegacyComponents_ResourceAndManualComponent.factory().create(config);
    }

    @Test
    @DisplayName("@JaxRsResources holds exactly the generated Catalog/Extra and manual contributions, "
            + "excludes the non-matching Disabled entry, and reconstructs on every resolution")
    void jaxRsResourcesHoldGeneratedAndManualContributions() {
        LegacyComponents.ResourceAndManualComponent component = resourceAndManualComponent(new JsonObject());

        Set<Object> firstResolution = component.jaxRsResources();
        assertEquals(3, firstResolution.size(), "exactly one instance each of Catalog, Extra, and Manual");
        assertEquals(
                Set.of(CatalogResource.class, ExtraResource.class, ManualResource.class),
                firstResolution.stream().map(Object::getClass).collect(Collectors.toSet()));
        assertEquals(0, DisabledResource.CONSTRUCTIONS.get(), "the non-matching condition must never construct");
        assertEquals(1, CatalogResource.CONSTRUCTIONS.get(), "one construction per resolution");
        assertEquals(1, ExtraResource.CONSTRUCTIONS.get(), "one construction per resolution");

        Set<Object> secondResolution = component.jaxRsResources();
        assertEquals(3, secondResolution.size(), "exactly one instance each of Catalog, Extra, and Manual");
        assertEquals(
                Set.of(CatalogResource.class, ExtraResource.class, ManualResource.class),
                secondResolution.stream().map(Object::getClass).collect(Collectors.toSet()));
        assertEquals(0, DisabledResource.CONSTRUCTIONS.get(), "the non-matching condition must never construct");
        assertEquals(2, CatalogResource.CONSTRUCTIONS.get(), "the counter rises by exactly 1 per resolution");
        assertEquals(2, ExtraResource.CONSTRUCTIONS.get(), "the counter rises by exactly 1 per resolution");
    }

    @Test
    @DisplayName(
            "the default mount uses the configured basePath and openapiPath and routes every " + "contributed resource")
    void defaultMountUsesConfiguredBasePath() {
        JsonObject config = new JsonObject()
                .put("jaxrs", new JsonObject().put("basePath", "/api/*").put("openapiPath", "custom.json"));
        LegacyComponents.ResourceAndManualComponent component = resourceAndManualComponent(config);

        Set<RouterMount> mounts = component.routerMounts();

        assertEquals(1, mounts.size(), "exactly one default mount");
        JaxRsRouterMount mount =
                assertInstanceOf(JaxRsRouterMount.class, mounts.iterator().next());
        assertEquals("/api/*", mount.mountPath());
        assertEquals("custom.json", mount.meta().openapiPath());
        assertEquals(
                Set.of(CatalogResource.class, ExtraResource.class, ManualResource.class),
                mount.meta().resourceTypes());
    }

    @Test
    @DisplayName("an empty resource set contributes no default mount")
    void emptyResourceSetContributesNoMount() {
        LegacyComponents.EmptyComponent component =
                DaggerLegacyComponents_EmptyComponent.factory().create(new JsonObject());

        Set<RouterMount> mounts = component.routerMounts();

        assertTrue(mounts.isEmpty(), "an empty @JaxRsResources set must contribute no mount");
    }
}
