// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.exception.ConflictException;
import dev.vertique.core.exception.NotFoundException;
import dev.vertique.core.exception.ValidationException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Administration exceptions and config")
class ExceptionsAndConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("not-found exception extends NotFoundException and exposes the entry id")
    void notFound() {
        OutboxDeadLetterNotFoundException e = new OutboxDeadLetterNotFoundException(42L);
        assertInstanceOf(NotFoundException.class, e);
        assertEquals(42L, e.entryId());
        assertEquals("Outbox entry 42 was not found", e.getMessage());
    }

    @Test
    @DisplayName("conflict exception extends ConflictException and exposes its reason")
    void conflictReasons() {
        Set<String> messages = new HashSet<>();
        for (OutboxDeadLetterConflictException.Reason reason : OutboxDeadLetterConflictException.Reason.values()) {
            OutboxDeadLetterConflictException e = new OutboxDeadLetterConflictException(reason);
            assertInstanceOf(ConflictException.class, e);
            assertEquals(reason, e.reason());
            messages.add(e.getMessage());
        }
        assertEquals(5, OutboxDeadLetterConflictException.Reason.values().length);
        assertEquals(5, messages.size());
        assertEquals(
                Set.of(
                        "NOT_DEAD_LETTER",
                        "REVISION_CONFLICT",
                        "OPERATION_ID_CONFLICT",
                        "CONTENT_LIMIT_EXCEEDED",
                        "REVISION_EXHAUSTED"),
                Set.of(
                        OutboxDeadLetterConflictException.Reason.NOT_DEAD_LETTER.name(),
                        OutboxDeadLetterConflictException.Reason.REVISION_CONFLICT.name(),
                        OutboxDeadLetterConflictException.Reason.OPERATION_ID_CONFLICT.name(),
                        OutboxDeadLetterConflictException.Reason.CONTENT_LIMIT_EXCEEDED.name(),
                        OutboxDeadLetterConflictException.Reason.REVISION_EXHAUSTED.name()));
    }

    @Test
    @DisplayName("conflict messages contain no SQL-like text and a null reason is a validation failure")
    void conflictMessages() {
        for (OutboxDeadLetterConflictException.Reason reason : OutboxDeadLetterConflictException.Reason.values()) {
            String message =
                    new OutboxDeadLetterConflictException(reason).getMessage().toLowerCase();
            assertFalse(message.contains("select") || message.contains("insert") || message.contains("sql"));
        }
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterConflictException(null));
    }

    @Test
    @DisplayName("admin config defaults: 90 days and 1 MiB")
    void configDefaults() throws Exception {
        InboxOutboxAdminConfig fromBuilder = InboxOutboxAdminConfig.builder().build();
        InboxOutboxAdminConfig fromJson = mapper.readValue("{}", InboxOutboxAdminConfig.class);
        assertEquals(90, fromBuilder.operationRetentionDays());
        assertEquals(1_048_576, fromBuilder.maxContentBytes());
        assertEquals(90, fromJson.operationRetentionDays());
        assertEquals(1_048_576, fromJson.maxContentBytes());
    }

    @Test
    @DisplayName("admin config deserializes explicit values")
    void configOverrides() throws Exception {
        InboxOutboxAdminConfig config = mapper.readValue(
                "{\"operationRetentionDays\":7,\"maxContentBytes\":2048}", InboxOutboxAdminConfig.class);
        assertEquals(7, config.operationRetentionDays());
        assertEquals(2048, config.maxContentBytes());
        InboxOutboxAdminConfig minimal = InboxOutboxAdminConfig.builder()
                .operationRetentionDays(1)
                .maxContentBytes(1)
                .build();
        assertEquals(1, minimal.operationRetentionDays());
        assertEquals(1, minimal.maxContentBytes());
    }

    @Test
    @DisplayName("admin config rejects zero and negative values from the builder")
    void configValidationBuilder() {
        assertThrows(
                ValidationException.class,
                () -> InboxOutboxAdminConfig.builder().operationRetentionDays(0).build());
        assertThrows(ValidationException.class, () -> InboxOutboxAdminConfig.builder()
                .operationRetentionDays(-1)
                .build());
        assertThrows(
                ValidationException.class,
                () -> InboxOutboxAdminConfig.builder().maxContentBytes(0).build());
        assertThrows(
                ValidationException.class,
                () -> InboxOutboxAdminConfig.builder().maxContentBytes(-1).build());
    }

    @Test
    @DisplayName("admin config rejects non-positive values from JSON")
    void configValidationJson() {
        Exception e = assertThrows(
                Exception.class, () -> mapper.readValue("{\"maxContentBytes\":0}", InboxOutboxAdminConfig.class));
        Throwable cause = e;
        boolean found = false;
        while (cause != null) {
            if (cause instanceof ValidationException) {
                found = true;
            }
            cause = cause.getCause();
        }
        assertEquals(true, found);
    }
}
