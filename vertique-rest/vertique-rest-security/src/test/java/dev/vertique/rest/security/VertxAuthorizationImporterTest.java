// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.exception.UnavailableException;
import dev.vertique.security.authz.AuthorityClaim;
import dev.vertique.security.authz.AuthorityKind;
import dev.vertique.security.authz.AuthorizationClaims;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authorization.AndAuthorization;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.AuthorizationProvider;
import io.vertx.ext.auth.authorization.NotAuthorization;
import io.vertx.ext.auth.authorization.OrAuthorization;
import io.vertx.ext.auth.authorization.PermissionBasedAuthorization;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import io.vertx.ext.auth.authorization.WildcardPermissionBasedAuthorization;
import io.vertx.ext.auth.impl.UserImpl;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link VertxAuthorizationImporter}.
 *
 * <p>Verifies the frozen import contract: constructor id validation, sequential provider
 * invocation in ascending id order against a request-local deep-copied {@link User}, the
 * role/permission-to-{@link AuthorityClaim} mapping with {@code "vertx-provider:<id>"} provenance,
 * fail-closed dropping of unmappable authorizations, plain set-union merging with the base claims,
 * atomic failure via {@link UnavailableException}, and the {@code jwt-claims} default exclusion.
 *
 * <p>All test providers complete synchronously (or via an in-test {@link Promise}), so futures are
 * inspected directly without vertx-junit5.
 */
class VertxAuthorizationImporterTest {

    // --- Test doubles ---

    /** A provider whose id is configurable (possibly blank or null) for constructor validation tests. */
    private static final class BlankIdProvider implements AuthorizationProvider {
        private final String id;

        BlankIdProvider(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Future<Void> getAuthorizations(User user) {
            return Future.succeededFuture();
        }
    }

    /** First of two distinct provider classes sharing the duplicate id {@code "dup"}. */
    private static final class DupProviderOne implements AuthorizationProvider {
        @Override
        public String getId() {
            return "dup";
        }

        @Override
        public Future<Void> getAuthorizations(User user) {
            return Future.succeededFuture();
        }
    }

    /** Second of two distinct provider classes sharing the duplicate id {@code "dup"}. */
    private static final class DupProviderTwo implements AuthorizationProvider {
        @Override
        public String getId() {
            return "dup";
        }

        @Override
        public Future<Void> getAuthorizations(User user) {
            return Future.succeededFuture();
        }
    }

    /** A provider that records invocation and the observed {@link User}, then grants fixed authorizations. */
    private static final class RecordingProvider implements AuthorizationProvider {
        private final String id;
        private final Set<Authorization> grants;
        private final AtomicBoolean invoked = new AtomicBoolean();
        private final AtomicReference<User> observedUser = new AtomicReference<>();

        RecordingProvider(String id, Authorization... grants) {
            this.id = id;
            this.grants = Set.of(grants);
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Future<Void> getAuthorizations(User user) {
            invoked.set(true);
            observedUser.set(user);
            user.authorizations().put(id, grants);
            return Future.succeededFuture();
        }
    }

    /** A provider that always fails with a plain {@link RuntimeException}. */
    private static final class FailingProvider implements AuthorizationProvider {
        private final String id;

        FailingProvider(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Future<Void> getAuthorizations(User user) {
            return Future.failedFuture(new RuntimeException("provider " + id + " blew up"));
        }
    }

    private static AuthorityClaim role(String value, String source) {
        return new AuthorityClaim(AuthorityKind.ROLE, value, "", "", source, Map.of());
    }

    private static AuthorityClaim permission(String value, String source) {
        return new AuthorityClaim(AuthorityKind.PERMISSION, value, "", "", source, Map.of());
    }

    private static User alice() {
        return User.create(new JsonObject().put("sub", "alice"));
    }

    // --- Constructor validation ---

    @Test
    @DisplayName("Constructor rejects providers with a null or blank id, naming the provider class")
    void constructorRejectsBlankProviderId() {
        for (String badId : new String[] {"", "  ", null}) {
            IllegalStateException ex = assertThrows(
                    IllegalStateException.class,
                    () -> new VertxAuthorizationImporter(Set.of(new BlankIdProvider(badId))),
                    "id [" + badId + "] must be rejected at construction");
            assertTrue(
                    ex.getMessage().contains("BlankIdProvider"),
                    "message must name the offending provider class for id [" + badId + "]: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("Constructor rejects duplicate provider ids, naming both provider classes")
    void constructorRejectsDuplicateProviderIds() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> new VertxAuthorizationImporter(Set.of(new DupProviderOne(), new DupProviderTwo())));
        assertTrue(
                ex.getMessage().contains("DupProviderOne"),
                "message must name the first provider class: " + ex.getMessage());
        assertTrue(
                ex.getMessage().contains("DupProviderTwo"),
                "message must name the second provider class: " + ex.getMessage());
    }

    // --- Mapping & merging ---

    @Test
    @DisplayName("Resource-free roles and permissions map to AuthorityClaims with vertx-provider provenance")
    void importsRolesAndPermissionsWithProvenance() {
        RecordingProvider p1 = new RecordingProvider(
                "p1", RoleBasedAuthorization.create("admin"), PermissionBasedAuthorization.create("orders:read"));
        VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(p1));

        Future<AuthorizationClaims> future = importer.importInto(alice(), AuthorizationClaims.empty());

        assertTrue(future.succeeded(), () -> "import must succeed: " + future.cause());
        AuthorizationClaims result = future.result();
        assertTrue(
                result.claims().contains(role("admin", "vertx-provider:p1")),
                "role claim with provenance missing: " + result.claims());
        assertTrue(
                result.claims().contains(permission("orders:read", "vertx-provider:p1")),
                "permission claim with provenance missing: " + result.claims());
    }

    @Test
    @DisplayName("Imported claims union with base claims by full record equality; base attributes are kept")
    void mergesWithBaseByFullEquality() {
        AuthorityClaim baseClaim = role("admin", "");
        AuthorizationClaims base = new AuthorizationClaims(Set.of(baseClaim), Map.of("k", "v"));
        RecordingProvider p1 = new RecordingProvider("p1", RoleBasedAuthorization.create("admin"));
        VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(p1));

        Future<AuthorizationClaims> future = importer.importInto(alice(), base);

        assertTrue(future.succeeded(), () -> "import must succeed: " + future.cause());
        AuthorizationClaims result = future.result();
        assertTrue(result.claims().contains(baseClaim), "base claim must survive the merge: " + result.claims());
        assertTrue(
                result.claims().contains(role("admin", "vertx-provider:p1")),
                "imported claim (differing source) must be present: " + result.claims());
        assertEquals(Map.of("k", "v"), result.attributes(), "base attributes must be kept unchanged");
    }

    @Test
    @DisplayName("Unmappable authorizations (wildcard, composite, resource-scoped) are dropped fail-closed")
    void dropsUnmappableAuthorizationsFailClosed() {
        RecordingProvider provider = new RecordingProvider(
                "p1",
                WildcardPermissionBasedAuthorization.create("orders:*"),
                AndAuthorization.create().addAuthorization(RoleBasedAuthorization.create("x")),
                OrAuthorization.create().addAuthorization(RoleBasedAuthorization.create("y")),
                NotAuthorization.create(RoleBasedAuthorization.create("z")),
                RoleBasedAuthorization.create("r").setResource("res1"),
                PermissionBasedAuthorization.create("p").setResource("res2"));
        AuthorityClaim baseClaim = role("base-role", "");
        AuthorizationClaims base = new AuthorizationClaims(Set.of(baseClaim), Map.of());
        VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(provider));

        Future<AuthorizationClaims> future = importer.importInto(alice(), base);

        assertTrue(future.succeeded(), () -> "import must succeed: " + future.cause());
        assertTrue(provider.invoked.get(), "the non-excluded provider must have been invoked");
        // None of the unmappable grants may surface as claims: only the base claim passes through.
        assertEquals(
                Set.of(baseClaim), future.result().claims(), "unmappable authorizations must be dropped fail-closed");
        // WARN throttling deliberately left unasserted (logging is not part of this contract test).
    }

    // --- Caller-user isolation ---

    @Test
    @DisplayName("The caller's User is never mutated; providers observe a request-local deep copy")
    void neverMutatesTheCallersUser() {
        User caller = alice();
        RecordingProvider p1 = new RecordingProvider("p1", RoleBasedAuthorization.create("admin"));
        VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(p1));

        Future<AuthorizationClaims> future = importer.importInto(caller, AuthorizationClaims.empty());

        assertTrue(future.succeeded(), () -> "import must succeed: " + future.cause());
        assertFalse(
                caller.authorizations().contains("p1"),
                "caller's user must not carry the provider's authorization bucket");
        User observed = p1.observedUser.get();
        assertNotNull(observed, "provider must have been invoked with a user");
        assertNotSame(caller, observed, "provider must observe a request-local copy, not the caller's User");
        assertEquals(caller.principal(), observed.principal(), "copied principal must equal the caller's");
        assertEquals(caller.attributes(), observed.attributes(), "copied attributes must equal the caller's");
    }

    @Test
    @DisplayName("A failed provider cannot poison a reused caller User across repeated imports")
    void failedProviderCannotPoisonReusedUser() {
        User caller = alice();
        RecordingProvider a = new RecordingProvider("a", RoleBasedAuthorization.create("admin"));
        FailingProvider b = new FailingProvider("b");
        VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(a, b));
        AuthorizationClaims base = AuthorizationClaims.empty();

        Future<AuthorizationClaims> first = importer.importInto(caller, base);
        Future<AuthorizationClaims> second = importer.importInto(caller, base);

        for (Future<AuthorizationClaims> future : List.of(first, second)) {
            assertTrue(future.failed(), "import must fail atomically when any provider fails");
            UnavailableException cause = assertInstanceOf(UnavailableException.class, future.cause());
            assertEquals(
                    "Authorization is temporarily unavailable",
                    cause.getMessage(),
                    "the client-visible failure detail must be the generic, provider-agnostic message");
            assertNull(future.result(), "no claims may be observable from a failed import");
        }
        assertFalse(
                caller.authorizations().contains("a"),
                "provider a's bucket must never leak onto the reused caller User");
    }

    // --- Sequencing & exclusion ---

    @Test
    @DisplayName("Providers are invoked sequentially in ascending provider-id order")
    void invokesProvidersSequentiallyInIdOrder() {
        List<String> events = new ArrayList<>();
        Promise<Void> aGate = Promise.promise();
        AuthorizationProvider a = new AuthorizationProvider() {
            @Override
            public String getId() {
                return "a";
            }

            @Override
            public Future<Void> getAuthorizations(User user) {
                events.add("a-start");
                return aGate.future().andThen(r -> events.add("a-complete"));
            }
        };
        AuthorizationProvider b = new AuthorizationProvider() {
            @Override
            public String getId() {
                return "b";
            }

            @Override
            public Future<Void> getAuthorizations(User user) {
                events.add("b-start");
                return Future.<Void>succeededFuture().andThen(r -> events.add("b-complete"));
            }
        };
        // Registered as "b" then "a": ascending id order must still run "a" first.
        Set<AuthorizationProvider> registeredBThenA = new LinkedHashSet<>(List.of(b, a));
        VertxAuthorizationImporter importer = new VertxAuthorizationImporter(registeredBThenA);

        Future<AuthorizationClaims> future = importer.importInto(alice(), AuthorizationClaims.empty());

        assertEquals(List.of("a-start"), events, "b must not start while a is still pending");
        aGate.complete();
        assertTrue(future.succeeded(), () -> "import must succeed: " + future.cause());
        assertEquals(
                List.of("a-start", "a-complete", "b-start", "b-complete"),
                events,
                "providers must run sequentially in ascending id order");
    }

    @Test
    @DisplayName("Excluded providers are neither invoked nor imported (default jwt-claims and explicit exclusions)")
    void excludedProviderNeitherInvokedNorImported() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(VertxAuthorizationImporter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            // 1-arg constructor: "jwt-claims" is always excluded.
            RecordingProvider jwtClaims = new RecordingProvider(
                    VertxAuthorizationImporter.EXCLUDED_JWT_CLAIMS_PROVIDER_ID, RoleBasedAuthorization.create("admin"));
            VertxAuthorizationImporter defaultImporter = new VertxAuthorizationImporter(Set.of(jwtClaims));

            // 2-arg constructor: an explicitly excluded arbitrary id gets the same treatment.
            RecordingProvider custom = new RecordingProvider("custom", RoleBasedAuthorization.create("admin"));
            VertxAuthorizationImporter explicitImporter =
                    new VertxAuthorizationImporter(Set.of(custom), Set.of("custom"));

            // Each excluded-and-present provider must be named once at INFO at wiring time —
            // already present after construction, before any importInto call — so an operator can
            // tell from the startup log why the provider's grants never appear.
            List<String> infoMessages = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(
                    2, infoMessages.size(), "one exclusion notice per excluded-and-present provider: " + infoMessages);
            assertTrue(
                    infoMessages.get(0).contains("[" + VertxAuthorizationImporter.EXCLUDED_JWT_CLAIMS_PROVIDER_ID + "]")
                            && infoMessages.get(0).contains(RecordingProvider.class.getName())
                            && infoMessages.get(0).contains("excluded from claims import"),
                    "notice must name the jwt-claims id and its provider class: " + infoMessages.get(0));
            assertTrue(
                    infoMessages.get(1).contains("[custom]")
                            && infoMessages.get(1).contains(RecordingProvider.class.getName())
                            && infoMessages.get(1).contains("excluded from claims import"),
                    "notice must name the explicitly excluded id and its provider class: " + infoMessages.get(1));

            Future<AuthorizationClaims> defaultResult =
                    defaultImporter.importInto(alice(), AuthorizationClaims.empty());

            assertTrue(defaultResult.succeeded(), () -> "import must succeed: " + defaultResult.cause());
            assertFalse(jwtClaims.invoked.get(), "the jwt-claims provider must never be invoked");
            assertTrue(
                    defaultResult.result().claims().isEmpty(),
                    "no claims may be imported from the excluded jwt-claims provider");

            Future<AuthorizationClaims> explicitResult =
                    explicitImporter.importInto(alice(), AuthorizationClaims.empty());

            assertTrue(explicitResult.succeeded(), () -> "import must succeed: " + explicitResult.cause());
            assertFalse(custom.invoked.get(), "an explicitly excluded provider must never be invoked");
            assertTrue(
                    explicitResult.result().claims().isEmpty(),
                    "no claims may be imported from an explicitly excluded provider");

            // The notices are wiring-time-only: the two imports above must not have re-emitted
            // them per request.
            long infoCountAfterImports = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.INFO)
                    .count();
            assertEquals(
                    2,
                    infoCountAfterImports,
                    "exclusion notices must be emitted once at wiring time, never per importInto call");
        } finally {
            logbackLogger.detachAppender(appender);
            appender.stop();
        }
    }

    // --- Atomic failure & empty set ---

    @Test
    @DisplayName("Any provider failure fails the whole import with a generic UnavailableException, logging the "
            + "provider id once at ERROR")
    void failsWholeImportOnProviderFailure() {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(VertxAuthorizationImporter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        try {
            User caller = alice();
            RecordingProvider a = new RecordingProvider("a", RoleBasedAuthorization.create("admin"));
            // A distinctive id so "the client-visible message does not name the provider" is a real
            // assertion — a one-letter id would appear by accident inside the generic wording.
            FailingProvider failing = new FailingProvider("teams-down");
            VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(a, failing));

            Future<AuthorizationClaims> future = importer.importInto(caller, AuthorizationClaims.empty());

            assertTrue(future.failed(), "import must fail when any provider fails");
            UnavailableException cause = assertInstanceOf(UnavailableException.class, future.cause());
            assertEquals(
                    "Authorization is temporarily unavailable",
                    cause.getMessage(),
                    "the client-visible failure detail must be the generic, provider-agnostic message");
            assertFalse(
                    cause.getMessage().contains("teams-down"),
                    "the client-visible failure detail must never name the failing provider id: " + cause.getMessage());
            assertNull(future.result(), "nothing imported from provider a may be observable");
            assertFalse(
                    caller.authorizations().contains("a"), "provider a's grants must not leak onto the caller's User");

            // The provider id is server-side-only: exactly one ERROR at the importer names it and
            // carries the provider's own failure, so an operator can diagnose what the 503 hides.
            List<ILoggingEvent> errors = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertEquals(1, errors.size(), "exactly one ERROR must be logged for the failed import: " + errors);
            ILoggingEvent error = errors.get(0);
            assertTrue(
                    error.getFormattedMessage().contains("teams-down"),
                    "the server-side ERROR must name the failing provider id: " + error.getFormattedMessage());
            IThrowableProxy thrown = error.getThrowableProxy();
            assertNotNull(thrown, "the ERROR event must carry the provider's failure as its throwable");
            assertSame(
                    cause.getCause(),
                    assertInstanceOf(ThrowableProxy.class, thrown).getThrowable(),
                    "the logged throwable must be the provider's own failure, i.e. the UnavailableException's cause");
        } finally {
            logbackLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("An empty effective provider set returns the same base instance with no async hop")
    void emptyProviderSetReturnsBaseSameInstance() {
        AuthorizationClaims base = new AuthorizationClaims(Set.of(role("admin", "")), Map.of("k", "v"));

        // No providers at all.
        VertxAuthorizationImporter empty = new VertxAuthorizationImporter(Set.of());
        Future<AuthorizationClaims> emptyResult = empty.importInto(alice(), base);
        assertTrue(emptyResult.succeeded(), "future must already be completed — no async hop");
        assertSame(base, emptyResult.result(), "the same base instance must be returned");

        // All providers excluded.
        RecordingProvider only = new RecordingProvider("x", RoleBasedAuthorization.create("r"));
        VertxAuthorizationImporter allExcluded = new VertxAuthorizationImporter(Set.of(only), Set.of("x"));
        Future<AuthorizationClaims> excludedResult = allExcluded.importInto(alice(), base);
        assertTrue(excludedResult.succeeded(), "future must already be completed — no async hop");
        assertSame(base, excludedResult.result(), "the same base instance must be returned");
        assertFalse(only.invoked.get(), "an excluded provider must not be invoked");
    }

    @Test
    @DisplayName("A provider that ran but mapped no claims returns the same base instance")
    void emptyImportResultReturnsBaseSameInstance() {
        // The provider is invoked and grants an authorization, but the grant is unmappable, so the
        // import contributes nothing — the merge must degenerate to returning base itself.
        RecordingProvider provider =
                new RecordingProvider("p1", WildcardPermissionBasedAuthorization.create("orders:*"));
        AuthorizationClaims base = new AuthorizationClaims(Set.of(role("base-role", "")), Map.of("k", "v"));
        VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(provider));

        Future<AuthorizationClaims> future = importer.importInto(alice(), base);

        assertTrue(future.succeeded(), () -> "import must succeed: " + future.cause());
        assertTrue(provider.invoked.get(), "the provider must have been invoked");
        assertSame(base, future.result(), "an import that maps no claims must return the very same base instance");
    }

    // --- Deep-copy isolation ---

    @Test
    @DisplayName("Provider mutation of the copied principal/attributes never reaches the caller's User")
    void providerMutationOfCopiedDataDoesNotAffectCaller() {
        AtomicBoolean invoked = new AtomicBoolean();
        AuthorizationProvider mutating = new AuthorizationProvider() {
            @Override
            public String getId() {
                return "p1";
            }

            @Override
            public Future<Void> getAuthorizations(User user) {
                invoked.set(true);
                user.principal().put("hacked", true);
                user.attributes().put("hacked", true);
                user.authorizations().put("p1", Set.of(RoleBasedAuthorization.create("r")));
                return Future.succeededFuture();
            }
        };
        VertxAuthorizationImporter importer = new VertxAuthorizationImporter(Set.of(mutating));

        User caller = User.create(new JsonObject().put("sub", "alice"), new JsonObject().put("attr", "v"));
        Future<AuthorizationClaims> future = importer.importInto(caller, AuthorizationClaims.empty());

        assertTrue(future.succeeded(), () -> "import must succeed: " + future.cause());
        assertTrue(invoked.get(), "the provider must have been invoked with a copied user");
        assertTrue(
                future.result().claims().contains(role("r", "vertx-provider:p1")),
                "the granted role must still be imported: " + future.result().claims());
        assertEquals(new JsonObject().put("sub", "alice"), caller.principal(), "caller principal must be unchanged");
        assertEquals(new JsonObject().put("attr", "v"), caller.attributes(), "caller attributes must be unchanged");

        // Null principal/attributes case: no public User factory permits nulls, but the no-arg
        // UserImpl cluster-serialization constructor leaves both null — the closest reachable
        // null-semantics the API allows. The importer must null-safely copy to empty JsonObjects.
        invoked.set(false);
        User nullUser = new UserImpl();
        Future<AuthorizationClaims> nullResult = importer.importInto(nullUser, AuthorizationClaims.empty());

        assertTrue(
                nullResult.succeeded(),
                () -> "import must succeed for null principal/attributes: " + nullResult.cause());
        assertTrue(invoked.get(), "the provider must have been invoked with a copied user");
        assertTrue(
                nullResult.result().claims().contains(role("r", "vertx-provider:p1")),
                "the granted role must still be imported: "
                        + nullResult.result().claims());
        assertNull(nullUser.principal(), "caller's null principal must stay null (never back-filled)");
        assertNull(nullUser.attributes(), "caller's null attributes must stay null (never back-filled)");
    }
}
