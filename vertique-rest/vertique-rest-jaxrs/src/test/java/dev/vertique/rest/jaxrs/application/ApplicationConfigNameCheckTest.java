// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.application.CompositionComponents.StandardComponent;
import dev.vertique.rest.jaxrs.application.CompositionComponents.ZeroDeclarationComponent;
import dev.vertique.rest.jaxrs.application.manual.BlobLikeResource;
import dev.vertique.rest.jaxrs.application.unita.CatalogResource;
import dev.vertique.rest.jaxrs.application.unita.DisabledResource;
import dev.vertique.rest.jaxrs.application.unita.ExtraResource;
import dev.vertique.rest.jaxrs.application.unita.scoped.ScopedResource;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TP-011 (AC-029.2): a {@code jaxrs.applications.<name>} entry whose name matches no declared
 * registration, active or not, fails startup naming {@code jaxrs.applications.<name>} — never its
 * value — before any resource resolves, on both the explicit and the zero-declaration branch of
 * {@code RestModule.jaxRsRouterMount} (the view is requested on both, so a configured name also
 * fails with zero registrations, where it matches none).
 */
class ApplicationConfigNameCheckTest {

    @BeforeEach
    void resetCounters() {
        CatalogResource.reset();
        ExtraResource.reset();
        DisabledResource.reset();
        BlobLikeResource.reset();
        ScopedResource.reset();
    }

    @Test
    @DisplayName("An unknown jaxrs.applications name fails startup naming the configuration path, never the"
            + " configured value, and never before checking known names first")
    void unknownApplicationNameFailsNamingThePath() {
        // Known names only: every configured name matches a declared registration (public active,
        // mgmt inactive) — composes.
        JsonObject knownNames = config(
                "unitb.publicApplication.active", true,
                "jaxrs.applications.public.openapiPath", "zq7-a.yaml",
                "jaxrs.applications.mgmt.openapiPath", "zq7-a.yaml");
        StandardComponent knownNamesComponent = standardComponent(knownNames);
        assertDoesNotThrow(knownNamesComponent::routerMounts, "every configured name matches a registration");

        // The known-names composition legitimately constructs public's resources; reset before the
        // unknown-name compositions assert that nothing is constructed before their failure.
        resetCounters();

        // One unknown name: adds an unknown "ghost" entry beside the known ones.
        JsonObject oneUnknownName = config(
                "unitb.publicApplication.active", true,
                "jaxrs.applications.public.openapiPath", "zq7-a.yaml",
                "jaxrs.applications.mgmt.openapiPath", "zq7-a.yaml",
                "jaxrs.applications.ghost.openapiPath", "zq7-secret.yaml");
        StandardComponent oneUnknownNameComponent = standardComponent(oneUnknownName);
        RestConfigurationException oneUnknownFailure = assertThrows(
                RestConfigurationException.class,
                oneUnknownNameComponent::routerMounts,
                "'ghost' matches no registration");
        assertTrue(oneUnknownFailure.getMessage().contains("jaxrs.applications.ghost"), oneUnknownFailure.getMessage());
        assertFalse(
                oneUnknownFailure.getMessage().contains("zq7"),
                "must never echo a configured value: " + oneUnknownFailure.getMessage());
        assertNoResourceConstructed();

        // Two unknown names: two unknown entries, "ghost" and "other", both named in one message.
        JsonObject twoUnknownNames = config(
                "unitb.publicApplication.active", true,
                "jaxrs.applications.public.openapiPath", "zq7-a.yaml",
                "jaxrs.applications.mgmt.openapiPath", "zq7-a.yaml",
                "jaxrs.applications.ghost.openapiPath", "zq7-secret.yaml",
                "jaxrs.applications.other.openapiPath", "zq7-other.yaml");
        StandardComponent twoUnknownNamesComponent = standardComponent(twoUnknownNames);
        RestConfigurationException twoUnknownFailure = assertThrows(
                RestConfigurationException.class,
                twoUnknownNamesComponent::routerMounts,
                "both 'ghost' and 'other' match nothing");
        assertTrue(twoUnknownFailure.getMessage().contains("jaxrs.applications.ghost"), twoUnknownFailure.getMessage());
        assertTrue(twoUnknownFailure.getMessage().contains("jaxrs.applications.other"), twoUnknownFailure.getMessage());
        assertFalse(
                twoUnknownFailure.getMessage().contains("zq7"),
                "must never echo a configured value: " + twoUnknownFailure.getMessage());
        assertNoResourceConstructed();

        // Zero registrations: a zero-registration component; "ghost" still matches nothing.
        JsonObject zeroRegistrations = config("jaxrs.applications.ghost.openapiPath", "zq7-secret.yaml");
        ZeroDeclarationComponent zeroRegistrationsComponent = zeroDeclarationComponent(zeroRegistrations);
        RestConfigurationException zeroRegistrationsFailure = assertThrows(
                RestConfigurationException.class,
                zeroRegistrationsComponent::routerMounts,
                "a configured name fails even with zero registrations");
        assertTrue(
                zeroRegistrationsFailure.getMessage().contains("jaxrs.applications.ghost"),
                zeroRegistrationsFailure.getMessage());
        assertFalse(
                zeroRegistrationsFailure.getMessage().contains("zq7"),
                "must never echo a configured value: " + zeroRegistrationsFailure.getMessage());
        assertNoResourceConstructed();
    }

    private static void assertNoResourceConstructed() {
        assertEquals(0, CatalogResource.CONSTRUCTIONS.get(), "no resource constructed before the failure");
        assertEquals(0, ExtraResource.CONSTRUCTIONS.get(), "no resource constructed before the failure");
        assertEquals(0, BlobLikeResource.CONSTRUCTIONS.get(), "no resource constructed before the failure");
    }

    // --- Component-building helpers ---

    private static StandardComponent standardComponent(JsonObject config) {
        return DaggerCompositionComponents_StandardComponent.factory().create(config);
    }

    private static ZeroDeclarationComponent zeroDeclarationComponent(JsonObject config) {
        return DaggerCompositionComponents_ZeroDeclarationComponent.factory().create(config);
    }

    // --- Configuration-literal helper ---

    private static JsonObject config(Object... dottedKeyValuePairs) {
        JsonObject root = new JsonObject();
        for (int i = 0; i < dottedKeyValuePairs.length; i += 2) {
            putDotted(root, (String) dottedKeyValuePairs[i], dottedKeyValuePairs[i + 1]);
        }
        return root;
    }

    private static void putDotted(JsonObject root, String dottedKey, Object value) {
        String[] segments = dottedKey.split("\\.");
        JsonObject current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            JsonObject next = current.getJsonObject(segments[i]);
            if (next == null) {
                next = new JsonObject();
                current.put(segments[i], next);
            }
            current = next;
        }
        current.put(segments[segments.length - 1], value);
    }
}
