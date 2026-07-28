// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.config.parser.DefaultConfigMapper;
import dev.vertique.config.parser.DefaultConfigParser;
import dev.vertique.core.config.ConfigParser;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests for {@link ServiceClientFactory}'s companion-selection seam (CG-015 §4.2 steps 3-4).
 *
 * <p>This module cannot run its own downstream annotation processor against its own test sources,
 * so the generated companions this factory would select are hand-written stand-ins
 * ({@link SelectableContract_ServiceClientProxy}, {@link BrokenContract_ServiceClientProxy},
 * {@link DriftContract_ServiceClientProxy}) that mimic the §4.1 companion contract exactly: a
 * {@code public final} class implementing the contract, a 3-arg constructor
 * {@code (ServiceRequestSender, DispatchEnvelopeBuilder, ContractEntry<?>)}, and the §4.2 mismatch
 * prefix baked into any constructor-time completeness failure.
 *
 * <p>All entries here are hand-built via {@link ServiceContractEntries#deployable()} and
 * registered through the {@link ServiceContractContributor} SPI (or, where a real scan suffices,
 * {@link ServiceContractRegistry#build(Set, JsonObject, ConfigParser)} directly) — the same
 * approach used by {@link ServiceClientFactoryTest.CreateTimeCompletenessCheck}.
 *
 * <p>Until the companion-selection seam is implemented, {@code create()} unconditionally returns a
 * JDK dynamic proxy: {@code shouldSelectGeneratedCompanionWhenPresent},
 * {@code shouldFailLoudlyOnBrokenCompanion}, and {@code shouldUnwrapBakedIdDriftToFailFast} are
 * therefore red. {@code shouldFallBackToDynamicProxyWhenCompanionAbsent} and
 * {@code shouldFailCreateWhenEntryMissingOperation_generatedPath} are green on arrival — the
 * former because the current unconditional behavior already is the dynamic-proxy fallback, the
 * latter because the §4.2 step-2 completeness check (already implemented) fires before any
 * companion-selection attempt would occur.
 */
@ExtendWith(VertxExtension.class)
@DisplayName("ServiceClientFactory - Companion Selection")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceClientFactoryCompanionTest {

    // --- Fixtures ---

    /** Test-fixture contract with no generated companion anywhere on the classpath. */
    @ServiceContract("no-companion")
    interface NoCompanionContract {
        /**
         * A single trivial operation; never actually dispatched in these tests.
         *
         * @param x the payload string
         * @return a future of the dispatch result
         */
        @ServiceOperation("ping")
        Future<String> ping(String x);
    }

    /** Minimal implementation satisfying {@link NoCompanionContract} for registry scanning. */
    static class NoCompanionContractImpl implements NoCompanionContract {
        @Override
        public Future<String> ping(String x) {
            return Future.succeededFuture(x);
        }
    }

    // --- Setup Helpers ---

    /**
     * Creates a lenient {@link ConfigParser} instance for test-side config parsing.
     *
     * @return a {@link DefaultConfigParser} backed by a lenient {@link DefaultConfigMapper}
     */
    private static ConfigParser configParser() {
        return new DefaultConfigParser(DefaultConfigMapper.lenient());
    }

    // --- Tests ---

    /**
     * Selection: a complete registry entry for {@link SelectableContract} plus the hand-written
     * companion on the classpath must make {@code create()} return an instance of that companion
     * class, not a dynamic proxy.
     *
     * <p>Red now: {@code ServiceClientFactory} has no companion-selection seam yet and
     * unconditionally returns a JDK dynamic proxy.
     *
     * @param vertx the Vert.x instance
     * @throws NoSuchMethodException never — {@code ping} is a real declared method
     */
    @Test
    @DisplayName("Should select the generated companion class when present on the classpath")
    void shouldSelectGeneratedCompanionWhenPresent(Vertx vertx) throws NoSuchMethodException {
        Method pingMethod = SelectableContract.class.getMethod("ping", String.class);
        SelectableContract impl = x -> Future.succeededFuture(x);
        ServiceContractRegistry.ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(SelectableContract.class)
                .serviceInstance(impl)
                .name("selectable")
                .operation("ping")
                .method(pingMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("x", ParamSource.PAYLOAD, String.class)
                .done()
                .build();
        ServiceContractRegistry registry = ServiceClientFactoryTest.registryOf(entry);
        ServiceClientFactory factory =
                new ServiceClientFactory(ServiceClientFactoryTest.availableSender(vertx), registry);

        SelectableContract client = factory.create(SelectableContract.class);

        assertEquals(
                SelectableContract_ServiceClientProxy.class,
                client.getClass(),
                "create() must return the generated companion instance, not a dynamic proxy, when the "
                        + "companion class is present on the classpath");
    }

    /**
     * Fallback: a contract with no generated companion anywhere on the classpath must still be
     * served by a JDK dynamic proxy.
     *
     * <p>Green now and later — the current unconditional behavior already is this fallback; once
     * the seam lands, this same assertion guards that the fallback path still works when the
     * companion is genuinely absent.
     *
     * @param vertx the Vert.x instance
     */
    @Test
    @DisplayName("Should fall back to a JDK dynamic proxy when no companion class is present")
    void shouldFallBackToDynamicProxyWhenCompanionAbsent(Vertx vertx) {
        ServiceContractRegistry registry =
                ServiceContractRegistry.build(Set.of(new NoCompanionContractImpl()), new JsonObject(), configParser());
        ServiceClientFactory factory =
                new ServiceClientFactory(ServiceClientFactoryTest.availableSender(vertx), registry);

        NoCompanionContract client = factory.create(NoCompanionContract.class);

        assertTrue(
                Proxy.isProxyClass(client.getClass()),
                "create() must fall back to a JDK dynamic proxy when no generated companion exists");
    }

    /**
     * Present-but-broken companion: a complete registry entry for {@link BrokenContract} whose
     * companion constructor throws an unrelated {@link RuntimeException} must make
     * {@code create()} fail loudly with an {@link IllegalStateException} reporting the companion
     * as present-but-broken, wrapping the fixture's cause — never a silent fallback to the dynamic
     * proxy.
     *
     * <p>Red now: no seam exists yet, so {@code create()} currently returns a working dynamic
     * proxy instead of throwing.
     *
     * @param vertx the Vert.x instance
     * @throws NoSuchMethodException never — {@code ping} is a real declared method
     */
    @Test
    @DisplayName("Should fail loudly when a present companion cannot be instantiated")
    void shouldFailLoudlyOnBrokenCompanion(Vertx vertx) throws NoSuchMethodException {
        Method pingMethod = BrokenContract.class.getMethod("ping", String.class);
        BrokenContract impl = x -> Future.succeededFuture(x);
        ServiceContractRegistry.ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(BrokenContract.class)
                .serviceInstance(impl)
                .name("broken")
                .operation("ping")
                .method(pingMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("x", ParamSource.PAYLOAD, String.class)
                .done()
                .build();
        ServiceContractRegistry registry = ServiceClientFactoryTest.registryOf(entry);
        ServiceClientFactory factory =
                new ServiceClientFactory(ServiceClientFactoryTest.availableSender(vertx), registry);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> factory.create(BrokenContract.class));
        assertTrue(
                ex.getMessage().contains("present but could not be instantiated"),
                "Message must report the present-but-broken companion, was: " + ex.getMessage());

        Throwable cause = ex.getCause();
        boolean foundFixtureCause = false;
        while (cause != null) {
            if ("fixture ctor boom".equals(cause.getMessage())) {
                foundFixtureCause = true;
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(foundFixtureCause, "Cause chain must contain the fixture's constructor RuntimeException");
    }

    /**
     * Baked-id drift (§4.2 narrow unwrap): a runtime-complete registry entry for
     * {@link DriftContract} (its sole current operation, {@code "opA"}) whose companion was
     * "compiled" against an older interface version baking in a now-nonexistent {@code "opGhost"}
     * operation must make {@code create()} fail with the exact generated-constructor
     * {@link IllegalStateException} — narrowly unwrapped, not re-wrapped in the generic
     * "present but could not be instantiated" message.
     *
     * <p>Red now: no seam exists yet, so {@code create()} currently returns a working dynamic
     * proxy instead of throwing.
     *
     * @param vertx the Vert.x instance
     * @throws NoSuchMethodException never — {@code opA} is a real declared method
     */
    @Test
    @DisplayName("Should unwrap baked-id drift to a fail-fast exception matching the §4.2 prefix")
    void shouldUnwrapBakedIdDriftToFailFast(Vertx vertx) throws NoSuchMethodException {
        Method opAMethod = DriftContract.class.getMethod("opA", String.class);
        DriftContract impl = x -> Future.succeededFuture(x);
        ServiceContractRegistry.ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(DriftContract.class)
                .serviceInstance(impl)
                .name("drift")
                .operation("opA")
                .method(opAMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("x", ParamSource.PAYLOAD, String.class)
                .done()
                .build();
        ServiceContractRegistry registry = ServiceClientFactoryTest.registryOf(entry);
        ServiceClientFactory factory =
                new ServiceClientFactory(ServiceClientFactoryTest.availableSender(vertx), registry);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> factory.create(DriftContract.class));
        assertTrue(
                ex.getMessage().startsWith(ServiceClientFactory.CONTRACT_MISMATCH_PREFIX),
                "Message must start with the §4.2-pinned mismatch prefix (narrow unwrap), was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("opGhost"), "Message must name the stale baked operation id 'opGhost'");
        assertFalse(
                ex.getMessage().contains("present but could not be instantiated"),
                "Baked-id drift must use the narrow unwrap, not the generic present-but-broken wrapper");
    }

    /**
     * Cross-path guard: a partial registry entry for {@link SelectableContract} (missing its only
     * operation, {@code "ping"}) with the real companion present must fail {@code create()} with
     * the identical top-level mismatch exception as the S2 reflective-path test — the §4.2 step-2
     * completeness check runs immediately after {@code registry.resolve()} and before any
     * companion-selection attempt, on both the reflective and generated paths alike.
     *
     * <p>Green on arrival — the §4.2 step-2 completeness check already exists in
     * {@code ServiceClientFactory} (CG-015 S2) and already fires before any companion selection
     * would be attempted, regardless of whether the seam itself exists yet.
     *
     * @param vertx the Vert.x instance
     * @throws NoSuchMethodException never — {@code ping} is a real declared method
     */
    @Test
    @DisplayName("Should fail create() fast on a partial entry via the same check as the reflective path, "
            + "even with a companion present")
    void shouldFailCreateWhenEntryMissingOperation_generatedPath(Vertx vertx) throws NoSuchMethodException {
        Method pingMethod = SelectableContract.class.getMethod("ping", String.class);
        SelectableContract impl = x -> Future.succeededFuture(x);
        ServiceContractRegistry.ContractEntry<?> entry = ServiceContractEntries.deployable()
                .contract(SelectableContract.class)
                .serviceInstance(impl)
                .name("selectable-partial")
                .operation("decoy")
                .method(pingMethod)
                .payloadType(String.class)
                .returnType(String.class)
                .param("x", ParamSource.PAYLOAD, String.class)
                .done()
                .build();
        ServiceContractRegistry registry = ServiceClientFactoryTest.registryOf(entry);
        ServiceClientFactory factory =
                new ServiceClientFactory(ServiceClientFactoryTest.availableSender(vertx), registry);

        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> factory.create(SelectableContract.class));
        assertTrue(
                ex.getMessage().startsWith(ServiceClientFactory.CONTRACT_MISMATCH_PREFIX),
                "Message must start with the §4.2-pinned mismatch prefix, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(SelectableContract.class.getName()), "Message must name the contract FQCN");
        assertTrue(ex.getMessage().contains("ping"), "Message must name the missing operation id 'ping'");
    }
}
