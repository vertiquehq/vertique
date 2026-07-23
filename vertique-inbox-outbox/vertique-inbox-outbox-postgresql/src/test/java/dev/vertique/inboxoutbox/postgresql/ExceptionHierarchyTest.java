// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.exception.TechnicalException;
import dev.vertique.inboxoutbox.exception.InboxOutboxConfigurationException;
import dev.vertique.inboxoutbox.postgresql.maintenance.OutboxMaintenanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the exception-hierarchy reparenting for Phase 5 of the inbox/outbox exception alignment.
 *
 * <p>Specifically asserts:
 * <ul>
 *   <li>{@code OutboxMaintenanceException} is an {@code InboxOutboxTechnicalException} /
 *       {@code TechnicalException} after reparenting.</li>
 *   <li>{@code ClaimScopeException} (package-private, accessible in this same-package test) is an
 *       {@code InboxOutboxConfigurationException} / {@code ConfigurationException} after
 *       reparenting, and its security contract (no cause, no raw value in message) is preserved.</li>
 * </ul>
 */
class ExceptionHierarchyTest {

    // --- OutboxMaintenanceException reparent ---

    @Nested
    class OutboxMaintenance {

        @Test
        @DisplayName("OutboxMaintenanceException is instanceof TechnicalException after reparenting")
        void outboxMaintenanceExceptionIsInstanceOfTechnicalException() {
            RuntimeException cause = new RuntimeException("db fail");
            OutboxMaintenanceException ex = new OutboxMaintenanceException("cleanup failed: [published]", cause);

            assertInstanceOf(
                    TechnicalException.class,
                    ex,
                    "OutboxMaintenanceException must be a TechnicalException after reparenting");
        }
    }

    // --- ClaimScopeException reparent (same-package access) ---

    @Nested
    class ClaimScope {

        @Test
        @DisplayName("ClaimScopeException is instanceof InboxOutboxConfigurationException after reparenting")
        void claimScopeExceptionIsInstanceOfInboxOutboxConfigurationException() {
            ClaimScopeException ex =
                    new ClaimScopeException("claim scope for destination type 'SERVICE' produced a null target set");

            assertInstanceOf(
                    InboxOutboxConfigurationException.class,
                    ex,
                    "ClaimScopeException must be an InboxOutboxConfigurationException after reparenting");
            assertInstanceOf(
                    ConfigurationException.class,
                    ex,
                    "ClaimScopeException must be a ConfigurationException (framework root) after reparenting");
        }

        @Test
        @DisplayName("ClaimScopeException has no cause — security contract preserved")
        void claimScopeExceptionHasNoCause() {
            ClaimScopeException ex =
                    new ClaimScopeException("claim scope for destination type 'SERVICE' produced a null target set");

            assertNull(ex.getCause(), "ClaimScopeException must not carry a cause (security contract)");
        }

        @Test
        @DisplayName("ClaimScopeException message does not contain an offending target value")
        void claimScopeExceptionMessageDoesNotContainOffendingTargetValue() {
            // The compliant message names only the type and reason category — never an actual target value.
            // 'acme-service' is a sample offending target value that must NOT appear in a sanitized message.
            String sanitizedMessage = "claim scope for destination type 'SERVICE' contains a null or blank element";
            ClaimScopeException ex = new ClaimScopeException(sanitizedMessage);

            assertFalse(
                    ex.getMessage().contains("acme-service"),
                    "ClaimScopeException message must not contain any offending target value");
        }
    }
}
