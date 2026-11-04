// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql.maintenance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.inboxoutbox.InboxOutboxCleanupConfig;
import dev.vertique.inboxoutbox.InboxRepository;
import dev.vertique.inboxoutbox.OutboxRelayConfig;
import dev.vertique.inboxoutbox.OutboxRepository;
import dev.vertique.inboxoutbox.admin.InboxOutboxAdminConfig;
import jakarta.inject.Inject;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the constructor shape Dagger resolves for {@link OutboxMaintenanceService}: exactly one
 * {@code @Inject} constructor, the one that takes the receipt cleanup, while the original public
 * four-argument constructor stays available without being an injection point.
 */
@DisplayName("OutboxMaintenanceService injection wiring")
class OutboxMaintenanceInjectionWiringTest {

    @Test
    @DisplayName("exactly one @Inject constructor, and it is the six-argument one")
    void singleInjectConstructor() {
        List<Constructor<?>> injectable = Arrays.stream(OutboxMaintenanceService.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                .toList();

        assertEquals(1, injectable.size());
        assertArrayEquals(
                new Class<?>[] {
                    OutboxRepository.class,
                    InboxRepository.class,
                    OutboxRelayConfig.class,
                    InboxOutboxCleanupConfig.class,
                    OutboxAdminOperationCleanup.class,
                    InboxOutboxAdminConfig.class
                },
                injectable.get(0).getParameterTypes());
    }

    @Test
    @DisplayName("the original public four-argument constructor still exists")
    void legacyConstructorStillPublic() throws NoSuchMethodException {
        Constructor<OutboxMaintenanceService> legacy = OutboxMaintenanceService.class.getConstructor(
                OutboxRepository.class, InboxRepository.class, OutboxRelayConfig.class, InboxOutboxCleanupConfig.class);

        assertNotNull(legacy);
    }
}
