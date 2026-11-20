// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.deploy;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.lifecycle.LifecycleOrdered;
import dev.vertique.core.lifecycle.LifecyclePhase;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Proves lifecycle ordering at pending startup and teardown boundaries without scheduler timing. */
class SequentialDeploymentTest {

    @Test
    @DisplayName("Deployment exposes its phase, priority and name through LifecycleOrdered")
    void deploymentImplementsLifecycleOrdered() {
        VerticleDeployment deployment = deployment("named", LifecyclePhase.INFRA, 7);

        LifecycleOrdered ordered = assertInstanceOf(LifecycleOrdered.class, deployment);

        assertEquals(LifecyclePhase.INFRA, ordered.phase());
        assertEquals(7, ordered.priority());
        assertEquals("named", ordered.orderKey());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Both manager entry points await each start in priority and name order")
    void startupWaitsForEachPredecessor(boolean allPhases) {
        // Given reverse insertion order, name ties, and names that disagree with priority order.
        ControlledDeployer deployer = new ControlledDeployer();
        List<VerticleDeployment> entries = List.of(
                deployment("a-high", LifecyclePhase.INFRA, 10),
                deployment("c", LifecyclePhase.INFRA, 0),
                deployment("b", LifecyclePhase.INFRA, 0),
                deployment("z-low", LifecyclePhase.INFRA, -10),
                deployment("edge", LifecyclePhase.EDGE, -100),
                deployment("bootstrap", LifecyclePhase.BOOTSTRAP, 100));
        VerticleDeploymentManager manager = new VerticleDeploymentManager(deployer, new LinkedHashSet<>(entries));
        List<String> expected = allPhases
                ? List.of("bootstrap", "z-low", "b", "c", "a-high", "edge")
                : List.of("z-low", "b", "c", "a-high");

        // When the selected entry point begins, each promise remains pending until explicitly released.
        Future<Void> result = allPhases ? manager.deployAll() : manager.deployPhase(LifecyclePhase.INFRA);

        // Then no successor starts while its predecessor is pending, including across phase boundaries.
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.subList(0, i + 1), deployer.starts);
            assertFalse(result.isComplete());
            deployer.startResults.get(expected.get(i)).complete("id-" + expected.get(i));
        }
        assertTrue(result.succeeded());
        assertTrue(deployer.stops.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Startup failure skips successors and awaits reverse best-effort invocation rollback")
    void failureRollsBackSequentiallyAndPreservesCause(boolean allPhases) {
        // Given a healthy earlier invocation plus two successful starts in this invocation before a failure.
        ControlledDeployer deployer = new ControlledDeployer();
        VerticleDeploymentManager previous = new VerticleDeploymentManager(
                deployer, new LinkedHashSet<>(List.of(deployment("prior", LifecyclePhase.INFRA, 0))));
        Future<Void> prior = previous.deployAll();
        deployer.startResults.get("prior").complete("id-prior");
        assertTrue(prior.succeeded());
        deployer.starts.clear();
        VerticleDeploymentManager manager = new VerticleDeploymentManager(
                deployer,
                new LinkedHashSet<>(List.of(
                        deployment("d", LifecyclePhase.INFRA, 0),
                        deployment("c", LifecyclePhase.INFRA, 0),
                        deployment("b", LifecyclePhase.INFRA, 0),
                        deployment("a", LifecyclePhase.INFRA, 0),
                        deployment("later-priority", LifecyclePhase.INFRA, 1),
                        deployment("later-phase", LifecyclePhase.EDGE, -100))));
        RuntimeException startupFailure = new RuntimeException("startup failure");

        // When a and b start and c fails, d and all subsequent entries must never start.
        Future<Void> result = allPhases ? manager.deployAll() : manager.deployPhase(LifecyclePhase.INFRA);
        deployer.startResults.get("a").complete("id-a");
        deployer.startResults.get("b").complete("id-b");
        deployer.startResults.get("c").fail(startupFailure);

        // Then rollback waits for b, continues after its failed stop, and waits for a.
        assertEquals(List.of("a", "b", "c"), deployer.starts);
        assertEquals(List.of("b"), deployer.stops);
        assertFalse(result.isComplete());
        deployer.stopResults.get("b").fail(new RuntimeException("stop failure"));
        assertEquals(List.of("b", "a"), deployer.stops);
        assertFalse(result.isComplete());
        deployer.stopResults.get("a").complete();
        assertTrue(result.failed());
        assertSame(startupFailure, result.cause());
        assertEquals(List.of("b", "a"), deployer.stops, "Prior invocation must remain untouched");
    }

    @Test
    @DisplayName("Shutdown awaits reverse phase, priority and name order and retains failed stops")
    void shutdownWaitsForReverseOrderAndContinuesAfterFailure() {
        // Given the real deployer tracking entries, with only the Vert.x transport controlled.
        ControlledVertx transport = new ControlledVertx();
        VerticleDeployer deployer = new VerticleDeployer(transport.vertx());
        List<VerticleDeployment> entries = List.of(
                deployment("z-bootstrap", LifecyclePhase.BOOTSTRAP, 100),
                deployment("z-low", LifecyclePhase.INFRA, -10),
                deployment("b", LifecyclePhase.INFRA, 0),
                deployment("c", LifecyclePhase.INFRA, 0),
                deployment("a-high", LifecyclePhase.INFRA, 10),
                deployment("a-edge", LifecyclePhase.EDGE, -100));
        for (VerticleDeployment entry : entries) {
            assertTrue(deployer.deploy(entry).succeeded());
        }
        transport.pendingStart = Promise.promise();
        Future<String> pending = deployer.deploy(deployment("pending", LifecyclePhase.EDGE, 100));
        assertFalse(pending.isComplete());

        // When shutdown starts, even an equal-priority successor must wait for the previous stop.
        Future<Void> result = deployer.undeployAll();
        List<String> expected = List.of("a-edge", "a-high", "c", "b", "z-low", "z-bootstrap");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.subList(0, i + 1), transport.stops);
            assertFalse(result.isComplete());
            String name = expected.get(i);
            if (name.equals("c")) {
                transport.stopResults.get(name).fail(new RuntimeException("stop failed"));
            } else {
                transport.stopResults.get(name).complete();
            }
        }

        // Then shutdown succeeds best-effort, retains only failed/in-flight entries, and permits retry.
        assertTrue(result.succeeded());
        for (VerticleDeployment entry : entries) {
            assertEquals(entry.name().equals("c") ? "c" : null, deployer.deploymentId(entry.name()));
        }
        assertNull(deployer.deploymentId("pending"));
        assertFalse(pending.isComplete());
        transport.pendingStart.complete("pending");
        assertEquals("pending", deployer.deploymentId("pending"));
        Future<Void> retry = deployer.undeployAll();
        assertEquals(List.of("pending"), transport.stops.subList(expected.size(), transport.stops.size()));
        transport.stopResults.get("pending").complete();
        assertEquals("c", transport.stops.getLast());
        transport.stopResults.get("c").complete();
        assertTrue(retry.succeeded());
        assertNull(deployer.deploymentId("c"));
        assertNull(deployer.deploymentId("pending"));
    }

    private static VerticleDeployment deployment(String name, LifecyclePhase phase, int priority) {
        return VerticleDeployment.of(name, () -> new NamedVerticle(name), phase, priority);
    }

    /** Context-free promises dispatch synchronously, so call lists prove overlap without sleeps. */
    private static final class ControlledDeployer extends VerticleDeployer {
        final List<String> starts = new ArrayList<>();
        final List<String> stops = new ArrayList<>();
        final Map<String, Promise<String>> startResults = new LinkedHashMap<>();
        final Map<String, Promise<Void>> stopResults = new LinkedHashMap<>();

        ControlledDeployer() {
            super(null);
        }

        @Override
        public Future<String> deploy(VerticleDeployment deployment) {
            starts.add(deployment.name());
            Promise<String> result = Promise.promise();
            startResults.put(deployment.name(), result);
            return result.future();
        }

        @Override
        public Future<Void> undeploy(String name) {
            stops.add(name);
            Promise<Void> result = Promise.promise();
            stopResults.put(name, result);
            return result.future();
        }
    }

    /** A transport stub; all deployment tracking and shutdown orchestration remains real. */
    private static final class ControlledVertx {
        final List<String> stops = new ArrayList<>();
        final Map<String, Promise<Void>> stopResults = new LinkedHashMap<>();
        Promise<String> pendingStart;

        Vertx vertx() {
            return (Vertx) Proxy.newProxyInstance(
                    Vertx.class.getClassLoader(), new Class<?>[] {Vertx.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("deployVerticle")) {
                            NamedVerticle verticle = (NamedVerticle) ((Supplier<?>) arguments[0]).get();
                            return pendingStart == null ? Future.succeededFuture(verticle.name) : pendingStart.future();
                        }
                        if (method.getName().equals("undeploy")) {
                            String name = (String) arguments[0];
                            stops.add(name);
                            Promise<Void> result = Promise.promise();
                            stopResults.put(name, result);
                            return result.future();
                        }
                        throw new UnsupportedOperationException(method.toString());
                    });
        }
    }

    private static final class NamedVerticle extends AbstractVerticle {
        final String name;

        NamedVerticle(String name) {
            this.name = name;
        }
    }
}
