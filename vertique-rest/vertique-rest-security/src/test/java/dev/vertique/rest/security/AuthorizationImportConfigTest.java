// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.exception.UnavailableException;
import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AuthorizationImportConfig}, {@link AuthorizationImportConfigModule} and the
 * way {@link VertxAuthorizationImportModule} hands the configured value to the importer.
 *
 * <p>Mirrors {@link AuthorizationGateConfigTest}: the default, the Jackson factory's omitted-value
 * default and the positive-only validation, plus the parse through the real {@link ConfigParser} and
 * the proof that the value reaches the importer.
 */
class AuthorizationImportConfigTest {

    private static final long AWAIT_MS = 3_000L;

    @Nested
    @DisplayName("defaults()")
    class Defaults {

        @Test
        @DisplayName("importTimeoutMs is DEFAULT_IMPORT_TIMEOUT_MS (5000)")
        void defaultImportTimeoutMsIs5000() {
            assertEquals(
                    AuthorizationImportConfig.DEFAULT_IMPORT_TIMEOUT_MS,
                    AuthorizationImportConfig.defaults().importTimeoutMs());
            assertEquals(5_000L, AuthorizationImportConfig.DEFAULT_IMPORT_TIMEOUT_MS);
        }
    }

    @Nested
    @DisplayName("fromJson(...)")
    class FromJson {

        @Test
        @DisplayName("null importTimeoutMs defaults to DEFAULT_IMPORT_TIMEOUT_MS")
        void nullImportTimeoutMsDefaults() {
            assertEquals(
                    AuthorizationImportConfig.DEFAULT_IMPORT_TIMEOUT_MS,
                    AuthorizationImportConfig.fromJson(null).importTimeoutMs());
        }

        @Test
        @DisplayName("an explicit importTimeoutMs is honored")
        void explicitImportTimeoutMsIsHonored() {
            assertEquals(250L, AuthorizationImportConfig.fromJson(250L).importTimeoutMs());
        }
    }

    @Nested
    @DisplayName("constructor validation")
    class Validation {

        @Test
        @DisplayName("zero and negative importTimeoutMs throw ConfigurationException")
        void nonPositiveThrows() {
            assertThrows(ConfigurationException.class, () -> new AuthorizationImportConfig(0L));
            assertThrows(ConfigurationException.class, () -> new AuthorizationImportConfig(-1L));
        }

        @Test
        @DisplayName("a positive importTimeoutMs is allowed")
        void positiveIsAllowed() {
            assertDoesNotThrow(() -> new AuthorizationImportConfig(1L));
        }
    }

    @Nested
    @DisplayName("AuthorizationImportConfigModule")
    class ConfigModule {

        private final ConfigParser parser = new DefaultConfigParser(DefaultConfigMapper.lenient());

        @Test
        @DisplayName("reads security.authz.importTimeoutMs through the real parser")
        void readsTheConfiguredKey() {
            JsonObject config = new JsonObject()
                    .put("security", new JsonObject().put("authz", new JsonObject().put("importTimeoutMs", 750L)));

            assertEquals(
                    750L,
                    AuthorizationImportConfigModule.authorizationImportConfig(config, parser)
                            .importTimeoutMs());
        }

        @Test
        @DisplayName("defaults when the key is absent and shares the section with gateDeadlineMs")
        void defaultsWhenAbsentAndCoexistsWithTheGateKey() {
            JsonObject config = new JsonObject()
                    .put("security", new JsonObject().put("authz", new JsonObject().put("gateDeadlineMs", 1_000L)));

            assertEquals(
                    AuthorizationImportConfig.DEFAULT_IMPORT_TIMEOUT_MS,
                    AuthorizationImportConfigModule.authorizationImportConfig(config, parser)
                            .importTimeoutMs());
            assertEquals(
                    1_000L,
                    AuthorizationGateConfigModule.authorizationGateConfig(config, parser)
                            .gateDeadlineMs());
        }

        @Test
        @DisplayName("rejects a non-positive configured value")
        void rejectsNonPositive() {
            JsonObject config = new JsonObject()
                    .put("security", new JsonObject().put("authz", new JsonObject().put("importTimeoutMs", 0L)));

            assertThrows(
                    ConfigurationException.class,
                    () -> AuthorizationImportConfigModule.authorizationImportConfig(config, parser));
        }
    }

    @Nested
    @DisplayName("VertxAuthorizationImportModule")
    class ImportModule {

        private AuthorizationProvider hungProvider() {
            return new AuthorizationProvider() {
                @Override
                public String getId() {
                    return "hung";
                }

                @Override
                public Future<Void> getAuthorizations(User user) {
                    return Promise.<Void>promise().future();
                }
            };
        }

        private Future<AuthorizationClaims> importThrough(Optional<AuthorizationImportConfig> config) {
            VertxAuthorizationImporter importer = VertxAuthorizationImportModule.vertxAuthorizationImporter(
                    Set.of(hungProvider()), config, TestResilience.shared());
            return importer.importInto(User.create(new JsonObject().put("sub", "alice")), AuthorizationClaims.empty());
        }

        @Test
        @DisplayName("hands the configured deadline to the importer: a hung provider fails within it")
        void configuredDeadlineReachesTheImporter() {
            Future<AuthorizationClaims> future = importThrough(Optional.of(new AuthorizationImportConfig(100L)));

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> future.toCompletionStage().toCompletableFuture().get(AWAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(failure.getCause() instanceof UnavailableException);
        }

        @Test
        @DisplayName("without a configuration the framework default applies: the import is still pending well "
                + "after a short configured deadline would have fired")
        void defaultDeadlineAppliesWithoutConfiguration() throws Exception {
            Future<AuthorizationClaims> future = importThrough(Optional.empty());

            Thread.sleep(600L);

            assertFalse(future.isComplete(), "the 5000 ms default must not have elapsed after 600 ms");
        }
    }
}
