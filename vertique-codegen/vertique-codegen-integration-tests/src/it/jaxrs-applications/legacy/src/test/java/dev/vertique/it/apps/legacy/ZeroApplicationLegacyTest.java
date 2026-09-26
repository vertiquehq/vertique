// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it.apps.legacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.it.apps.resources.CatalogResource;
import dev.vertique.it.apps.resources.DisabledResource;
import dev.vertique.it.apps.resources.ExtraResource;
import dev.vertique.it.apps.resources.StatusResource;
import dev.vertique.rest.core.router.RouterMount;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T003 TP-006: a zero-declaration consumer of the real, processor-generated {@code resources} unit.
 * Declares no {@code jakarta.ws.rs.core.Application}, so the generated application registration set
 * stays empty and the default {@code @JaxRsResources}-driven mount must keep serving every enabled
 * generated resource, exactly as it did before T003's application-registration emission existed
 * (I-1, AC-001.1).
 */
class ZeroApplicationLegacyTest {

    /** The classes the enabled default-mount resource set must hold, and no others (PP-001). */
    private static final Set<Class<?>> EXPECTED_ENABLED_RESOURCE_TYPES =
            Set.of(CatalogResource.class, StatusResource.class, ExtraResource.class);

    @BeforeEach
    void resetCounters() {
        CatalogResource.reset();
        StatusResource.reset();
        ExtraResource.reset();
        DisabledResource.reset();
    }

    @Test
    @DisplayName(
            "A zero-declaration consumer's @JaxRsResources holds every enabled generated resource, "
                    + "and exactly one default mount exists at /api/*")
    void generatedResourcesKeepDefaultMount() {
        JsonObject config = new JsonObject().put("jaxrs", new JsonObject().put("basePath", "/api/*"));
        LegacyComponent component = DaggerLegacyComponent.factory().create(config);

        Set<Class<?>> actualTypes =
                component.jaxRsResources().stream().map(Object::getClass).collect(Collectors.toUnmodifiableSet());
        assertEquals(
                EXPECTED_ENABLED_RESOURCE_TYPES,
                actualTypes,
                "the default mount's resource set must hold exactly Catalog, Status, and Extra, not Disabled");

        assertEquals(
                Set.of("/api/*"),
                component.routerMounts().stream().map(RouterMount::mountPath).collect(Collectors.toUnmodifiableSet()),
                "exactly one default mount must exist, at the configured base path");
    }
}
