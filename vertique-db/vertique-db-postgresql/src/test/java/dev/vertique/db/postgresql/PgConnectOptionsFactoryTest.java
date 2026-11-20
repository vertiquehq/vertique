// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.db.DbPoolConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Checks the connect options {@link PgConnectOptionsFactory} derives from the pool config. Needs no database. */
class PgConnectOptionsFactoryTest {

    private static DbPoolConfig.DbPoolConfigBuilder config() {
        return DbPoolConfig.builder().host("localhost").database("db").user("u").password("p");
    }

    @Test
    @DisplayName("an omitted port connects on the PostgreSQL default port 5432")
    void omittedPortUsesPostgresqlDefault() {
        assertEquals(5432, PgConnectOptionsFactory.create(config().build()).getPort());
    }

    @Test
    @DisplayName("a configured port is used as given")
    void configuredPortIsUsed() {
        assertEquals(
                6543,
                PgConnectOptionsFactory.create(config().port(6543).build()).getPort());
    }

    @Test
    @DisplayName("a negative or out-of-range port fails with a ConfigurationException naming db.port")
    void invalidPortFailsWithConfigurationException() {
        ConfigurationException negative = assertThrows(
                ConfigurationException.class,
                () -> PgConnectOptionsFactory.create(config().port(-1).build()));
        assertTrue(negative.getMessage().contains("db.port"), negative.getMessage());
        assertThrows(
                ConfigurationException.class,
                () -> PgConnectOptionsFactory.create(config().port(70_000).build()));
    }
}
