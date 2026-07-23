// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.postgresql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.db.DbExceptionMapper;
import io.vertx.sqlclient.SqlClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PgQueryTest {

    @Mock
    private SqlClient client;

    @Mock
    private DbExceptionMapper exceptionMapper;

    // -- PgLockMode SQL fragments --

    @Test
    void pgLockMode_forUpdate_hasSql() {
        assertEquals("FOR UPDATE", PgLockMode.FOR_UPDATE.sql());
    }

    @Test
    void pgLockMode_forUpdateSkipLocked_hasSql() {
        assertEquals("FOR UPDATE SKIP LOCKED", PgLockMode.FOR_UPDATE_SKIP_LOCKED.sql());
    }

    @Test
    void pgLockMode_forUpdateNowait_hasSql() {
        assertEquals("FOR UPDATE NOWAIT", PgLockMode.FOR_UPDATE_NOWAIT.sql());
    }

    @Test
    void pgLockMode_forShare_hasSql() {
        assertEquals("FOR SHARE", PgLockMode.FOR_SHARE.sql());
    }

    @Test
    void pgLockMode_forKeyShare_hasSql() {
        assertEquals("FOR KEY SHARE", PgLockMode.FOR_KEY_SHARE.sql());
    }

    // -- Inline lock clause validation --

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM t FOR UPDATE",
                "SELECT * FROM t for update",
                "SELECT * FROM t FOR SHARE",
                "SELECT * FROM t FOR NO KEY UPDATE",
                "SELECT * FROM t FOR KEY SHARE"
            })
    void build_rejectsInlineForLock(String sql) {
        assertThrows(IllegalArgumentException.class, () -> PgQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .sql(sql)
                .build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT * FROM t WHERE status = $1 SKIP LOCKED", "SELECT * FROM t SKIP LOCKED"})
    void build_rejectsInlineSkipLocked(String sql) {
        assertThrows(IllegalArgumentException.class, () -> PgQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .sql(sql)
                .build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT * FROM t NOWAIT", "SELECT * FROM t WHERE id = $1 NOWAIT"})
    void build_rejectsInlineNowait(String sql) {
        assertThrows(IllegalArgumentException.class, () -> PgQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .sql(sql)
                .build());
    }

    @Test
    void build_acceptsNormalSql() {
        assertDoesNotThrow(() -> PgQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .sql("SELECT id, name FROM items WHERE id = $1")
                .build());
    }

    @Test
    void builder_appendsQueryClauseViaBuildSql() {
        var q = PgQuery.<String>builder()
                .on(client)
                .exceptionMapper(exceptionMapper)
                .sql("SELECT * FROM items WHERE id = $1")
                .queryClause(PgLockMode.FOR_UPDATE)
                .build();
        assertEquals("SELECT * FROM items WHERE id = $1 FOR UPDATE", q.buildSql());
    }
}
