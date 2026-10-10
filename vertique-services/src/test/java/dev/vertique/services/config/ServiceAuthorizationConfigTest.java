// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ServiceAuthorizationConfig}: the default, the key, and the validation. */
class ServiceAuthorizationConfigTest {

    private static JsonObject config(Object value) {
        return new JsonObject()
                .put("security", new JsonObject().put("authz", new JsonObject().put("gateDeadlineMs", value)));
    }

    @Test
    @DisplayName("defaults to 5000 ms when the key is absent")
    void defaultsWhenAbsent() {
        assertEquals(
                5_000L, ServiceAuthorizationConfig.fromConfig(new JsonObject()).gateDeadlineMs());
        assertEquals(5_000L, ServiceAuthorizationConfig.defaults().gateDeadlineMs());
    }

    @Test
    @DisplayName("reads security.authz.gateDeadlineMs as a number or a numeric string")
    void readsNumberAndNumericString() {
        assertEquals(750L, ServiceAuthorizationConfig.fromConfig(config(750)).gateDeadlineMs());
        assertEquals(
                1_250L, ServiceAuthorizationConfig.fromConfig(config("1250")).gateDeadlineMs());
    }

    @Test
    @DisplayName("rejects a non-positive or non-numeric value")
    void rejectsInvalid() {
        assertThrows(ConfigurationException.class, () -> ServiceAuthorizationConfig.fromConfig(config(0)));
        assertThrows(ConfigurationException.class, () -> ServiceAuthorizationConfig.fromConfig(config(-5)));
        assertThrows(ConfigurationException.class, () -> ServiceAuthorizationConfig.fromConfig(config("soon")));
        assertThrows(ConfigurationException.class, () -> ServiceAuthorizationConfig.fromConfig(config(true)));
    }
}
