// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.inboxoutbox.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.security.AuthenticationState;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.IdentityReconstruction;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityContexts;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.SnapshotDegradationMarker;
import dev.vertique.security.authz.AuthorizationClaims;
import dev.vertique.security.runtime.IdentitySnapshotCapture;
import dev.vertique.security.runtime.IdentitySnapshotReconstructionModule;
import dev.vertique.security.runtime.events.SecurityEventsModule;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Carries a signed identity snapshot through a real outbox row and back.
 *
 * <p>The unit tests in {@code vertique-security-runtime} prove the capture, encode, decode and
 * tamper-rejection logic against an in-memory merge. This test closes the remaining gap named by
 * ADR-0232: the snapshot is written into the `metadata` JSONB column of a real `outbox` row against
 * the row's own `carrier_id`, read back through PostgreSQL, and only then decoded — so a column
 * round trip that mangled the signed document, or a carrier binding that did not survive
 * persistence, would fail here and nowhere else.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("Identity snapshot through a real outbox row")
class IdentitySnapshotOutboxCarriageIT {

    @RegisterExtension
    static final DatabaseExtension DB = new DatabaseExtension();

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("identity_snapshot_outbox_test")
            .withMigration("classpath:db/migration/inbox-outbox");

    private static final String IDENTITY_SNAPSHOT_NAMESPACE = "identity-snapshot";

    private static final PrincipalRef ACTOR =
            new PrincipalRef(PrincipalType.SERVICE, "svc-relay", Map.of("tenant", "acme"));
    private static final PrincipalRef USER =
            new PrincipalRef(PrincipalType.USER, "user-77", Map.of("realm", "acme-realm"));
    /** Capture projects free-form attributes out; only type and id survive the durable round trip. */
    private static final PrincipalRef PROJECTED_USER = new PrincipalRef(PrincipalType.USER, "user-77", Map.of());

    private static Pool pool;
    private static PgInboxOutboxRepository repository;
    private static CarriageComponent component;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        repository = new PgInboxOutboxRepository(pool, new PgDbExceptionMapper());
        component = DaggerIdentitySnapshotOutboxCarriageIT_CarriageComponent.builder()
                .testConfigModule(new TestConfigModule(signingConfig()))
                .build();
        ctx.completeNow();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("a snapshot written into an outbox row reconstructs the subject after a real database round trip")
    void snapshotSurvivesTheOutboxRow(Vertx vertx, VertxTestContext ctx) {
        UUID carrierId = UUID.randomUUID();
        DurableCarrierDescriptor rowCarrier = rowCarrier(carrierId);

        persistCapturedSnapshot(vertx, rowCarrier, carrierId)
                .compose(IdentitySnapshotOutboxCarriageIT::readPersistedContext)
                .onComplete(ctx.succeeding(persisted -> {
                    ctx.verify(() -> assertTrue(
                            persisted.has(IDENTITY_SNAPSHOT_NAMESPACE),
                            "the identity-snapshot namespace must survive the JSONB column"));
                    decodeOn(
                            vertx,
                            persisted,
                            rowCarrier,
                            reconstructed -> ctx.verify(() -> {
                                assertEquals(
                                        PROJECTED_USER,
                                        reconstructed.identity().subject().orElseThrow(),
                                        "the subject of record must survive the outbox row");
                                assertTrue(
                                        IdentityReconstruction.isReconstructed(reconstructed),
                                        "the decoded context must carry the reconstruction marker");
                                assertTrue(
                                        component
                                                .contextHolder()
                                                .current(SnapshotDegradationMarker.class)
                                                .isEmpty(),
                                        "a clean persisted round trip must bind no degradation marker");
                                ctx.completeNow();
                            }));
                }));
    }

    @Test
    @DisplayName("a snapshot tampered in the database binds a degradation marker instead of an identity")
    void tamperedRowDegrades(Vertx vertx, VertxTestContext ctx) {
        UUID carrierId = UUID.randomUUID();
        DurableCarrierDescriptor rowCarrier = rowCarrier(carrierId);

        persistCapturedSnapshot(vertx, rowCarrier, carrierId)
                .compose(id -> tamperPersistedSnapshot(id).map(id))
                .compose(IdentitySnapshotOutboxCarriageIT::readPersistedContext)
                .onComplete(ctx.succeeding(persisted -> decodeOn(
                        vertx,
                        persisted,
                        rowCarrier,
                        reconstructed -> ctx.verify(() -> {
                            assertTrue(
                                    component
                                            .contextHolder()
                                            .current(SnapshotDegradationMarker.class)
                                            .isPresent(),
                                    "a snapshot tampered in the database must bind a degradation marker");
                            assertTrue(
                                    reconstructed == null
                                            || reconstructed
                                                    .identity()
                                                    .subject()
                                                    .isEmpty()
                                            || !IdentityReconstruction.isReconstructed(reconstructed),
                                    "a tampered snapshot must not yield a verified reconstructed subject");
                            ctx.completeNow();
                        }))));
    }

    @Test
    @DisplayName("a snapshot decoded against a different row's carrier does not verify")
    void foreignCarrierDoesNotVerify(Vertx vertx, VertxTestContext ctx) {
        UUID carrierId = UUID.randomUUID();
        DurableCarrierDescriptor rowCarrier = rowCarrier(carrierId);
        DurableCarrierDescriptor foreignCarrier = rowCarrier(UUID.randomUUID());

        persistCapturedSnapshot(vertx, rowCarrier, carrierId)
                .compose(IdentitySnapshotOutboxCarriageIT::readPersistedContext)
                .onComplete(ctx.succeeding(persisted -> decodeOn(
                        vertx,
                        persisted,
                        foreignCarrier,
                        reconstructed -> ctx.verify(() -> {
                            assertTrue(
                                    component
                                            .contextHolder()
                                            .current(SnapshotDegradationMarker.class)
                                            .isPresent(),
                                    "a snapshot transplanted onto another row's carrier must not verify");
                            ctx.completeNow();
                        }))));
    }

    // --- Fixtures ---

    private static DurableCarrierDescriptor rowCarrier(UUID carrierId) {
        return new DurableCarrierDescriptor(
                carrierId.toString(),
                new DurableTarget(DispatchBoundary.OUTBOX, "outbox.identity-snapshot-test", Optional.empty()));
    }

    /** Captures a live user context, merges it for the outbox boundary, and inserts a real row. */
    private static Future<Long> persistCapturedSnapshot(
            Vertx vertx, DurableCarrierDescriptor rowCarrier, UUID carrierId) {
        IdentitySnapshotCapture capture = component.identitySnapshotCapture();
        DurableContextPropagator propagator = component.durableContextPropagator();
        io.vertx.core.Promise<DurableMetadata> merged = io.vertx.core.Promise.promise();

        ContextInternal producerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        producerCtx.runOnContext(v -> {
            try (ContextHolder.Scope captureScope = capture.captureFrom(liveUserContext())) {
                merged.complete(propagator.mergeCaptured(DurableMetadata.empty(), DispatchBoundary.OUTBOX, rowCarrier));
            } catch (Throwable t) {
                merged.fail(t);
            }
        });

        return merged.future()
                .compose(context -> pool.withTransaction(tx -> repository.insert(
                        OutboxEntry.builder()
                                .destinationType(DestinationType.SERVICE)
                                .destination("identity-snapshot-test")
                                .eventType("test.event")
                                .payload(new JsonObject().put("k", "v"))
                                .build(),
                        new OutboxMetadata(context, dev.vertique.inboxoutbox.OutboxDeliveryMetadata.empty()),
                        carrierId,
                        tx)));
    }

    /** Reads the persisted metadata document back out of the row and returns its context section. */
    private static Future<DurableMetadata> readPersistedContext(long id) {
        return readMetadata(id)
                .map(metadata -> OutboxMetadata.fromJson(metadata).context());
    }

    /** Reads the `metadata` JSONB column; the driver hands it back as a string after an explicit cast. */
    private static Future<JsonObject> readMetadata(long id) {
        return pool.preparedQuery("SELECT metadata FROM outbox WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rows -> {
                    Object value = rows.iterator().next().getValue("metadata");
                    return value instanceof JsonObject json ? json : new JsonObject(String.valueOf(value));
                });
    }

    /** Flips one character of the persisted snapshot payload so its integrity tag no longer matches. */
    private static Future<Void> tamperPersistedSnapshot(long id) {
        return readMetadata(id).compose(metadata -> {
            JsonObject snapshot =
                    metadata.getJsonObject(OutboxMetadata.CONTEXT_KEY).getJsonObject(IDENTITY_SNAPSHOT_NAMESPACE);
            // Flip the last bit of the signed document so its integrity tag no longer matches.
            byte[] raw = Base64.getDecoder().decode(snapshot.getString("snapshot"));
            raw[raw.length - 1] ^= 0x01;
            snapshot.put("snapshot", Base64.getEncoder().encodeToString(raw));
            return pool.preparedQuery("UPDATE outbox SET metadata = $2::jsonb WHERE id = $1")
                    .execute(Tuple.of(id, metadata.encode()))
                    .mapEmpty();
        });
    }

    /** Decodes the persisted context on a fresh consumer context and hands the bound security context back. */
    private static void decodeOn(
            Vertx vertx,
            DurableMetadata persisted,
            DurableCarrierDescriptor carrier,
            java.util.function.Consumer<SecurityContext> assertions) {
        DurableContextPropagator propagator = component.durableContextPropagator();
        InboundExecutionContextScope executionScope = component.inboundExecutionContextScope();
        ContextHolder holder = component.contextHolder();

        ContextInternal consumerCtx = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        consumerCtx.runOnContext(v -> {
            Map<String, Object> dispatchContext =
                    propagator.decodeToDispatchContext(persisted, DispatchBoundary.OUTBOX, carrier);
            try (ContextHolder.Scope installed =
                    executionScope.installDispatch(dispatchContext, DispatchBoundary.OUTBOX)) {
                assertions.accept(holder.current(SecurityContext.class).orElse(null));
            }
        });
    }

    private static SecurityContext liveUserContext() {
        SecurityIdentity identity = new SecurityIdentity(ACTOR, Optional.of(USER), Optional.empty(), Optional.empty());
        return SecurityContexts.assemble(
                identity,
                new AuthenticationState(
                        DefaultAuthMethod.jwt(), List.of(), Optional.empty(), Optional.empty(), Map.of()),
                AuthorizationClaims.empty(),
                Optional.empty());
    }

    private static JsonObject signingConfig() {
        return new JsonObject()
                .put(
                        "identity",
                        new JsonObject()
                                .put(
                                        "snapshot",
                                        new JsonObject()
                                                .put(
                                                        "hmacKeys",
                                                        new JsonObject()
                                                                .put(
                                                                        "active",
                                                                        new JsonObject()
                                                                                .put("keyId", "key-1")
                                                                                .put(
                                                                                        "secretRef",
                                                                                        "super-secret-signing-key-material")))));
    }

    // --- Dagger wiring ---

    @Singleton
    @Component(
            modules = {
                IdentitySnapshotReconstructionModule.class,
                SecurityEventsModule.class,
                ConfigParsingModule.class,
                TestConfigModule.class
            })
    interface CarriageComponent {

        ContextHolder contextHolder();

        DurableContextPropagator durableContextPropagator();

        InboundExecutionContextScope inboundExecutionContextScope();

        IdentitySnapshotCapture identitySnapshotCapture();
    }

    /** Provides the {@code @VertxConfig JsonObject} from a caller-supplied value. */
    @Module
    static final class TestConfigModule {

        private final JsonObject config;

        TestConfigModule(JsonObject config) {
            this.config = config;
        }

        @Provides
        @Singleton
        @VertxConfig
        JsonObject vertxConfig() {
            return config;
        }
    }
}
