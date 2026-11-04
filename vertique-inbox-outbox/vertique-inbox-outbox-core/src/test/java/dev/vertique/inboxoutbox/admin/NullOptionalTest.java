// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ValidationException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** A null {@code Optional} is a caller error, not an absent value, in every administration type. */
class NullOptionalTest {

    @Test
    void helpersRejectNullAndKeepPresentAndEmptyValues() {
        assertThrows(ValidationException.class, () -> AdminChecks.optional(null, "next"));
        assertThrows(ValidationException.class, () -> AdminChecks.optionalLong(null, "resultRevision"));
        assertEquals(Optional.empty(), AdminChecks.optional(Optional.empty(), "next"));
        assertEquals(Optional.of(1), AdminChecks.optional(Optional.of(1), "next"));
        assertEquals(OptionalLong.empty(), AdminChecks.optionalLong(OptionalLong.empty(), "resultRevision"));
        assertEquals(OptionalLong.of(7), AdminChecks.optionalLong(OptionalLong.of(7), "resultRevision"));
    }

    @Test
    void pagesAndHistoryQueryRejectANullOptional() {
        assertThrows(ValidationException.class, () -> new OutboxDeadLetterPage(List.of(), null));
        assertThrows(ValidationException.class, () -> new OutboxAdminHistoryPage(List.of(), null));
        assertThrows(ValidationException.class, () -> new OutboxAdminHistoryQuery(1L, 50, null));
    }
}
