// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.inboxoutbox.InboxOutboxCleanupConfig;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the constructor shape Dagger resolves for {@link OutboxMaintenanceService}: a single public constructor,
 * annotated for injection, that takes the two repositories and the two configurations.
 */
@DisplayName("OutboxMaintenanceService injection wiring")
class OutboxMaintenanceInjectionWiringTest {

    @Test
    @DisplayName("exactly one public constructor, it is the @Inject one, and it takes four arguments")
    void singleInjectConstructor() {
        Constructor<?>[] constructors = OutboxMaintenanceService.class.getConstructors();

        assertEquals(1, constructors.length);
        assertTrue(constructors[0].isAnnotationPresent(Inject.class));
        assertArrayEquals(
                new Class<?>[] {
                    OutboxRepository.class,
                    InboxRepository.class,
                    OutboxRelayConfig.class,
                    InboxOutboxCleanupConfig.class
                },
                constructors[0].getParameterTypes());
    }

    @Test
    @DisplayName("no other constructor, however restricted, competes for injection")
    void noOtherConstructor() {
        assertEquals(1, OutboxMaintenanceService.class.getDeclaredConstructors().length);
    }
}
