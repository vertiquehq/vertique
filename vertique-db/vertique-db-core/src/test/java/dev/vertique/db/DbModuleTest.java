// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.config.ConfigParser;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link DbModule#dbPoolConfig}: the provider returns the parsed config and logs
 * every {@link DbPoolConfig#validate()} warning at WARN without failing.
 */
@DisplayName("DbModule pool-config provider")
class DbModuleTest {

    private final JsonObject appConfig = new JsonObject().put("db", new JsonObject().put("host", "ignored"));
    private final ConfigParser parser = mock(ConfigParser.class);
    private final Logger moduleLogger = (Logger) LoggerFactory.getLogger(DbModule.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        moduleLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        moduleLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("logs each validation warning at WARN and still returns the config")
    void warnings_loggedAndConfigReturned() {
        // Given: no host, non-positive pool size, and a trust store under sslMode DISABLE — three warnings
        DbPoolConfig misconfigured = DbPoolConfig.builder()
                .maxPoolSize(0)
                .trustStorePath("/etc/certs/db-ca.pem")
                .build();
        when(parser.parse(any(JsonObject.class), eq(DbPoolConfig.class))).thenReturn(misconfigured);

        // When
        DbPoolConfig provided = DbModule.dbPoolConfig(appConfig, parser);

        // Then
        assertSame(misconfigured, provided);
        List<String> warnings = warnMessages();
        assertEquals(misconfigured.validate().size(), warnings.size(), "one WARN line per validation warning");
        assertTrue(warnings.stream().anyMatch(m -> m.contains("db.host is not configured")));
        assertTrue(warnings.stream().anyMatch(m -> m.contains("db.maxPoolSize must be positive")));
        assertTrue(warnings.stream().anyMatch(m -> m.contains("db.trustStorePath is set but sslMode is DISABLE")));
    }

    @Test
    @DisplayName("a valid config logs nothing")
    void validConfig_logsNothing() {
        DbPoolConfig valid = DbPoolConfig.builder().host("db.example").build();
        when(parser.parse(any(JsonObject.class), eq(DbPoolConfig.class))).thenReturn(valid);

        assertSame(valid, DbModule.dbPoolConfig(appConfig, parser));
        assertEquals(List.of(), warnMessages());
    }

    private List<String> warnMessages() {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
