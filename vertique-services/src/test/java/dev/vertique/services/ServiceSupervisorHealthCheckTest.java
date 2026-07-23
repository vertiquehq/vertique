// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.core.health.HealthCheckResult;
import dev.vertique.core.health.HealthStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies {@link ServiceSupervisorHealthCheck} correctly aggregates supervisor
 * state into health check results.
 *
 * <p>Uses a mock {@link ServiceSupervisor} to drive {@link ServiceSupervisor#serviceAvailability()}
 * return values, keeping these tests focused solely on the health check aggregation logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceSupervisorHealthCheck")
class ServiceSupervisorHealthCheckTest {

    @Mock
    ServiceSupervisor supervisor;

    ServiceSupervisorHealthCheck check;

    @BeforeEach
    void setUp() {
        check = new ServiceSupervisorHealthCheck(supervisor);
    }

    /**
     * When no services are supervised, the health check must report UP with an empty data map.
     */
    @Test
    @DisplayName("returns UP when no services are supervised")
    void upWhenEmpty() {
        when(supervisor.serviceAvailability()).thenReturn(Map.of());

        HealthCheckResult result = check.check().result();

        assertEquals(HealthStatus.UP, result.status());
        assertTrue(result.data().isEmpty());
    }

    /**
     * When all supervised services are available, the health check must report UP and include
     * per-service status entries with value {@code "UP"}.
     */
    @Test
    @DisplayName("returns UP with data when all services are available")
    void upWhenAllAvailable() {
        when(supervisor.serviceAvailability()).thenReturn(Map.of("runner", true, "sorter", true));

        HealthCheckResult result = check.check().result();

        assertEquals(HealthStatus.UP, result.status());
        assertEquals("UP", result.data().get("runner"));
        assertEquals("UP", result.data().get("sorter"));
    }

    /**
     * When any supervised service is unavailable, the health check must report DOWN and include
     * the service entry with value {@code "DOWN"}.
     */
    @Test
    @DisplayName("returns DOWN when a service is unavailable")
    void downWhenUnavailable() {
        when(supervisor.serviceAvailability()).thenReturn(Map.of("runner", false));

        HealthCheckResult result = check.check().result();

        assertEquals(HealthStatus.DOWN, result.status());
        assertEquals("DOWN", result.data().get("runner"));
    }

    /**
     * When some services are available and others are not, the health check must report DOWN
     * and include correct per-service status entries.
     */
    @Test
    @DisplayName("returns DOWN with mixed statuses when some services are unavailable")
    void downWhenMixed() {
        // Use a LinkedHashMap-backed map to ensure deterministic ordering
        java.util.Map<String, Boolean> statuses = new java.util.LinkedHashMap<>();
        statuses.put("runner", true);
        statuses.put("sorter", false);
        when(supervisor.serviceAvailability()).thenReturn(statuses);

        HealthCheckResult result = check.check().result();

        assertEquals(HealthStatus.DOWN, result.status());
        assertEquals("UP", result.data().get("runner"));
        assertEquals("DOWN", result.data().get("sorter"));
    }

    /**
     * The health check name must be {@code "services"}.
     */
    @Test
    @DisplayName("name returns 'services'")
    void name() {
        assertEquals("services", check.name());
    }
}
