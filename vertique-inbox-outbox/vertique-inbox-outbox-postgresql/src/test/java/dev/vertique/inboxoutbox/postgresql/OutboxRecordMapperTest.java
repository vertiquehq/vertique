// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.inboxoutbox.OutboxRecord;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests for {@link OutboxRecordMapper} — conversion of the stored JSONB {@code headers} column to
 * the record's header map.
 */
class OutboxRecordMapperTest {

    private final Logger mapperLogger = (Logger) LoggerFactory.getLogger(OutboxRecordMapper.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        mapperLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        mapperLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("a stored JSON null header value is dropped, not turned into the text \"null\"")
    void jsonNullHeaderValueIsDropped() {
        Row row = rowWithHeaders(
                41L,
                new JsonObject().put("x-tenant", "acme").putNull("x-broken").put("x-count", 3));

        OutboxRecord record = OutboxRecordMapper.fromRow(row);

        assertEquals(Map.of("x-tenant", "acme", "x-count", "3"), record.headers());
    }

    @Test
    @DisplayName("dropping a JSON null header value logs one WARN naming the entry id and the header key")
    void jsonNullHeaderValueIsLoggedOnce() {
        Row row = rowWithHeaders(41L, new JsonObject().put("x-tenant", "acme").putNull("x-broken"));

        OutboxRecordMapper.fromRow(row);

        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
        assertEquals(1, warnings.size(), "exactly one WARN per dropped header");
        String message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("41"), "WARN must name the entry id: " + message);
        assertTrue(message.contains("x-broken"), "WARN must name the header key: " + message);
    }

    @Test
    @DisplayName("the WARN for a dropped header shows its key without control characters and cut to the maximum length")
    void droppedHeaderKeyIsSanitisedInTheWarning() {
        String key = "x-broken\r\nforged log line\t" + "k".repeat(500);
        Row row = rowWithHeaders(41L, new JsonObject().putNull(key));

        OutboxRecordMapper.fromRow(row);

        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
        assertEquals(1, warnings.size(), "exactly one WARN per dropped header");
        String message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("x-broken__forged log line_k"), "control characters become '_': " + message);
        assertFalse(message.chars().anyMatch(Character::isISOControl), "no control character is left: " + message);
        assertFalse(message.contains("k".repeat(OutboxHeaderKeys.MAX_SHOWN_LENGTH)), "the key is cut: " + message);
    }

    @Test
    @DisplayName("a headers column holding only JSON null values maps to an empty map")
    void onlyJsonNullValuesGiveAnEmptyMap() {
        Row row = rowWithHeaders(7L, new JsonObject().putNull("x-broken"));

        assertEquals(Map.of(), OutboxRecordMapper.fromRow(row).headers());
    }

    @Test
    @DisplayName("non-null header values are kept and no WARN is logged")
    void nonNullHeaderValuesAreKept() {
        Row row = rowWithHeaders(7L, new JsonObject().put("x-tenant", "acme"));

        assertEquals(Map.of("x-tenant", "acme"), OutboxRecordMapper.fromRow(row).headers());
        assertTrue(appender.list.isEmpty(), "no log event expected for deliverable headers");
    }

    // --- Helpers ---

    /** Builds a row mock carrying the columns {@link OutboxRecordMapper#fromRow} cannot map from null. */
    private static Row rowWithHeaders(long id, JsonObject headers) {
        Row row = mock(Row.class);
        when(row.getLong(OutboxRecordMapper.COL_ID)).thenReturn(id);
        when(row.getUUID(OutboxRecordMapper.COL_CARRIER_ID)).thenReturn(UUID.randomUUID());
        when(row.getString(OutboxRecordMapper.COL_DESTINATION_TYPE)).thenReturn("SERVICE");
        when(row.getString(OutboxRecordMapper.COL_STATE)).thenReturn("PROCESSING");
        when(row.getInteger(OutboxRecordMapper.COL_ATTEMPT)).thenReturn(0);
        when(row.getInteger(OutboxRecordMapper.COL_MAX_ATTEMPTS)).thenReturn(20);
        when(row.getJsonObject(OutboxRecordMapper.COL_HEADERS)).thenReturn(headers);
        return row;
    }
}
