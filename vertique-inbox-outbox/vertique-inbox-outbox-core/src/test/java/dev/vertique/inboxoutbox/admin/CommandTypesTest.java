// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.core.exception.ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxAdminCommand and OutboxAdminActor")
class CommandTypesTest {

    private static final UUID OP = UUID.randomUUID();
    private static final OutboxAdminActor ACTOR = new OutboxAdminActor("user", "alice");

    private static OutboxAdminCommand command(
            UUID op, long entry, long revision, String reason, OutboxAdminActor actor) {
        return new OutboxAdminCommand(op, entry, revision, reason, actor);
    }

    @Test
    @DisplayName("a valid command keeps every component")
    void valid() {
        OutboxAdminCommand command = command(OP, 1L, 0L, "r", ACTOR);
        assertEquals(OP, command.operationId());
        assertEquals(1L, command.entryId());
        assertEquals(0L, command.expectedRevision());
        assertEquals("r", command.reason());
        assertEquals(ACTOR, command.actor());
    }

    @Test
    @DisplayName("operation id and actor are required")
    void required() {
        assertThrows(ValidationException.class, () -> command(null, 1L, 0L, "r", ACTOR));
        assertThrows(ValidationException.class, () -> command(OP, 1L, 0L, "r", null));
    }

    @Test
    @DisplayName("entry id must be positive")
    void entryId() {
        assertDoesNotThrow(() -> command(OP, Long.MAX_VALUE, 0L, "r", ACTOR));
        assertThrows(ValidationException.class, () -> command(OP, 0L, 0L, "r", ACTOR));
        assertThrows(ValidationException.class, () -> command(OP, -1L, 0L, "r", ACTOR));
    }

    @Test
    @DisplayName("expected revision must not be negative")
    void revision() {
        assertDoesNotThrow(() -> command(OP, 1L, 0L, "r", ACTOR));
        assertDoesNotThrow(() -> command(OP, 1L, Long.MAX_VALUE, "r", ACTOR));
        assertThrows(ValidationException.class, () -> command(OP, 1L, -1L, "r", ACTOR));
    }

    @Test
    @DisplayName("reason is 1 to 1024 characters and not all whitespace")
    void reason() {
        assertDoesNotThrow(() -> command(OP, 1L, 0L, "x", ACTOR));
        assertDoesNotThrow(() -> command(OP, 1L, 0L, "x".repeat(1024), ACTOR));
        assertDoesNotThrow(() -> command(OP, 1L, 0L, "  padded  ", ACTOR));
        assertThrows(ValidationException.class, () -> command(OP, 1L, 0L, "x".repeat(1025), ACTOR));
        assertThrows(ValidationException.class, () -> command(OP, 1L, 0L, "", ACTOR));
        assertThrows(ValidationException.class, () -> command(OP, 1L, 0L, " \t\n ", ACTOR));
        assertThrows(ValidationException.class, () -> command(OP, 1L, 0L, null, ACTOR));
    }

    @Test
    @DisplayName("reason is stored exactly, without trimming")
    void reasonExact() {
        assertEquals("  padded  ", command(OP, 1L, 0L, "  padded  ", ACTOR).reason());
    }

    @Test
    @DisplayName("actor type is 1 to 64 and id is 1 to 255 characters, both nonblank")
    void actorBounds() {
        assertDoesNotThrow(() -> new OutboxAdminActor("t".repeat(64), "i".repeat(255)));
        assertThrows(ValidationException.class, () -> new OutboxAdminActor("t".repeat(65), "i"));
        assertThrows(ValidationException.class, () -> new OutboxAdminActor("t", "i".repeat(256)));
        assertThrows(ValidationException.class, () -> new OutboxAdminActor("", "i"));
        assertThrows(ValidationException.class, () -> new OutboxAdminActor(" ", "i"));
        assertThrows(ValidationException.class, () -> new OutboxAdminActor(null, "i"));
        assertThrows(ValidationException.class, () -> new OutboxAdminActor("t", ""));
        assertThrows(ValidationException.class, () -> new OutboxAdminActor("t", "  "));
        assertThrows(ValidationException.class, () -> new OutboxAdminActor("t", null));
    }

    @Test
    @DisplayName("actor accessors work and toString hides the actor")
    void actorAccessors() {
        assertEquals("user", ACTOR.type());
        assertEquals("alice", ACTOR.id());
        assertFalse(ACTOR.toString().contains("alice"));
    }

    @Test
    @DisplayName("command toString hides the reason and the actor")
    void commandToString() {
        String text = command(OP, 1L, 0L, "secret reason", ACTOR).toString();
        assertFalse(text.contains("secret reason"));
        assertFalse(text.contains("alice"));
        assertEquals(true, text.contains(OP.toString()));
    }

    @Test
    @DisplayName("validation messages never echo the reason")
    void messagesDoNotEchoReason() {
        ValidationException e =
                assertThrows(ValidationException.class, () -> command(OP, 1L, 0L, "x".repeat(1025), ACTOR));
        assertFalse(e.getMessage().contains("xxx"));
    }

    @Test
    @DisplayName("reason allows tab, line feed and carriage return but no other control character")
    void reasonControlCharacters() {
        assertDoesNotThrow(() -> command(OP, 1L, 0L, "line one\r\n\tline two", ACTOR));
        for (String bad : new String[] {"a\u0000b", "a\u0001b", "a\u001fb", "a\u007fb", "a\u0085b", "a\u009fb"}) {
            ValidationException e = assertThrows(ValidationException.class, () -> command(OP, 1L, 0L, bad, ACTOR));
            assertEquals("reason must not contain control characters", e.getMessage());
        }
    }

    @Test
    @DisplayName("reason rejects unpaired surrogates and accepts a valid pair")
    void reasonSurrogates() {
        assertDoesNotThrow(() -> command(OP, 1L, 0L, "ok \ud83d\ude00", ACTOR));
        for (String bad : new String[] {"a\ud800", "\udc00b", "a\ud83d", "\ude00\ud83d"}) {
            ValidationException e = assertThrows(ValidationException.class, () -> command(OP, 1L, 0L, bad, ACTOR));
            assertEquals("reason must not contain unpaired surrogates", e.getMessage());
        }
    }

    @Test
    @DisplayName("actor parts reject every control character, including tab and line breaks, and unpaired surrogates")
    void actorControlCharactersAndSurrogates() {
        for (String bad : new String[] {"a\u0000", "a\tb", "a\nb", "a\rb", "a\u007f", "a\u0085"}) {
            ValidationException type = assertThrows(ValidationException.class, () -> new OutboxAdminActor(bad, "id"));
            ValidationException id = assertThrows(ValidationException.class, () -> new OutboxAdminActor("type", bad));
            assertEquals("actor type must not contain control characters", type.getMessage());
            assertEquals("actor id must not contain control characters", id.getMessage());
        }
        assertEquals(
                "actor id must not contain unpaired surrogates",
                assertThrows(ValidationException.class, () -> new OutboxAdminActor("type", "x\ud800"))
                        .getMessage());
        assertEquals(
                "actor type must not contain unpaired surrogates",
                assertThrows(ValidationException.class, () -> new OutboxAdminActor("x\udc00", "id"))
                        .getMessage());
        assertDoesNotThrow(() -> new OutboxAdminActor("type", "caf\u00e9 \ud83d\ude00"));
    }

    @Test
    @DisplayName("control and surrogate failures never echo the value")
    void failuresDoNotEchoValues() {
        ValidationException e =
                assertThrows(ValidationException.class, () -> command(OP, 1L, 0L, "secret\u0000value", ACTOR));
        assertFalse(e.getMessage().contains("secret"));
        ValidationException lone =
                assertThrows(ValidationException.class, () -> new OutboxAdminActor("secret-type\ud800", "id"));
        assertFalse(lone.getMessage().contains("secret"));
    }
}
