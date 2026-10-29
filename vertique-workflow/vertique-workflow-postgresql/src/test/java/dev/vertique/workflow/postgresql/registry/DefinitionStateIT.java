// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.registry;

import static dev.vertique.workflow.postgresql.registry.DefinitionScenario.await;
import static dev.vertique.workflow.postgresql.registry.PlanCacheFixture.ACME;
import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.engine.registry.PlanCache;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies that a document plan loaded on demand leaves no state outside the plan cache: once the cache has evicted a
 * version, nothing else in the node refers to its plan, and the definition module keeps no entry per definition key.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 120, unit = TimeUnit.SECONDS)
public class DefinitionStateIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_definition_state_it")
            .withMigration("classpath:db/migration/workflow");

    private static final int VERSIONS = 150;
    private static final int MAX_ENTRIES = 100;

    /** The period of the node's background definition work, where the node still has any, in seconds. */
    private static final int BACKGROUND_PERIOD_SECONDS = 1;

    private static final Duration CLEARED_WITHIN = Duration.ofSeconds(10);

    @Test
    @DisplayName("evicted plans are retained nowhere outside the plan cache")
    void evictedPlansAreRetainedNowhereOutsideThePlanCache(Vertx vertx) {
        try (PlanCacheFixture fixture = PlanCacheFixture.open(vertx, db, MAX_ENTRIES, 10, 0)) {
            // Given a plan cache of 100 plans and 150 versions of one acme document stored after the node started,
            // with the node left alone for several periods of any background definition work it may have
            List<DefinitionKey> versions = new ArrayList<>();
            for (int version = 1; version <= VERSIONS; version++) {
                versions.add(TenantRowSeed.seed(
                        fixture.app(),
                        ACME,
                        PlanCacheFixture.document(version),
                        DefinitionScenario.source("definition-state-" + version, Instant.now()),
                        DefinitionStatus.AVAILABLE));
            }
            pause(Duration.ofSeconds(5L * BACKGROUND_PERIOD_SECONDS));

            // When every version is loaded in turn through the registry, keeping only weak references to what it
            // returns, and the cache has run its maintenance and the collector has run
            List<Retention.Tracked> tracked = new ArrayList<>();
            for (DefinitionKey key : versions) {
                RuntimeWorkflow plan = await(fixture.app().workflowRegistry().load(key));
                tracked.add(Retention.track(key, plan));
            }
            PlanCache cache = fixture.planCache();
            long resident = cache.size();

            List<Retention.Tracked> evicted = tracked.stream()
                    .filter(entry -> cache.resident(entry.key()).isEmpty())
                    .toList();
            List<Retention.Tracked> kept = tracked.stream()
                    .filter(entry -> cache.resident(entry.key()).isPresent())
                    .toList();
            List<DefinitionKey> stillReferenced = Retention.awaitCleared(evicted, CLEARED_WITHIN);

            // Then the references of the evicted versions are cleared
            assertThat(stillReferenced)
                    .as("evicted versions whose plan, graph or callbacks are still referenced after %s", CLEARED_WITHIN)
                    .isEmpty();

            // And exactly 50 were evicted, while the 100 resident ones are still referenced by the cache
            assertThat(resident).isEqualTo(MAX_ENTRIES);
            assertThat(evicted).hasSize(VERSIONS - MAX_ENTRIES);
            assertThat(kept).hasSize(MAX_ENTRIES).allSatisfy(entry -> assertThat(entry.anyCleared())
                    .as("resident version %s", entry.key())
                    .isFalse());

            // And the definition module holds no entry per definition key
            Object[] roots = new Object[fixture.app().persistedLoaders().size() + 1];
            roots[0] = fixture.app().definitionService();
            int next = 1;
            for (Object loader : fixture.app().persistedLoaders()) {
                roots[next++] = loader;
            }
            assertThat(DefinitionModuleState.perKeyEntries(roots))
                    .as("maps keyed by definition key in the definition module")
                    .isEmpty();
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while the node was left alone", interrupted);
        }
    }

    /** Weak references to what a load returned, and the wait for the collector to clear them. */
    static final class Retention {

        private Retention() {}

        /** The weak references to the plan, its graph and its callbacks of one version. */
        record Tracked(DefinitionKey key, List<WeakReference<Object>> references) {

            boolean anyCleared() {
                return references.stream().anyMatch(reference -> reference.get() == null);
            }

            boolean allCleared() {
                return references.stream().allMatch(reference -> reference.get() == null);
            }
        }

        static Tracked track(DefinitionKey key, RuntimeWorkflow plan) {
            return new Tracked(
                    key,
                    List.of(
                            new WeakReference<>(plan),
                            new WeakReference<>(plan.plan()),
                            new WeakReference<>(plan.callbacks())));
        }

        /**
         * Requests garbage collection until every reference of the versions is cleared or the bound passes.
         *
         * @return the keys of the versions that still have a reference, empty when all are cleared
         */
        static List<DefinitionKey> awaitCleared(List<Tracked> versions, Duration bound) {
            long deadline = System.nanoTime() + bound.toNanos();
            List<DefinitionKey> remaining = remaining(versions);
            while (!remaining.isEmpty() && System.nanoTime() < deadline) {
                System.gc();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for the collector", interrupted);
                }
                remaining = remaining(versions);
            }
            return remaining;
        }

        private static List<DefinitionKey> remaining(List<Tracked> versions) {
            return versions.stream()
                    .filter(version -> !version.allCleared())
                    .map(Tracked::key)
                    .toList();
        }
    }

    /**
     * Finds what the definition module keeps per definition key: the map-valued fields, declared with a definition key
     * or holding one, of the objects it reaches from the roots within its own packages.
     */
    static final class DefinitionModuleState {

        private static final String MODULE_PACKAGE = "dev.vertique.workflow.definition.";
        private static final String TEST_PACKAGE = "dev.vertique.workflow.definition.it.";

        private DefinitionModuleState() {}

        /** The "Class.field" of every such non-empty map with its size, empty when the module keeps none. */
        static Map<String, Integer> perKeyEntries(Object... roots) {
            Map<String, Integer> found = new LinkedHashMap<>();
            Map<Object, Boolean> visited = new IdentityHashMap<>();
            List<Object> pending = new ArrayList<>();
            for (Object root : roots) {
                if (root != null) {
                    pending.add(root);
                }
            }
            while (!pending.isEmpty()) {
                Object current = pending.remove(pending.size() - 1);
                if (visited.put(current, Boolean.TRUE) != null) {
                    continue;
                }
                for (Class<?> type = current.getClass(); type != null && inModule(type); type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        Object value = read(field, current);
                        if (value == null) {
                            continue;
                        }
                        if (value instanceof Map<?, ?> map) {
                            if (!map.isEmpty() && keyedByDefinitionKey(field, map)) {
                                found.put(type.getSimpleName() + "." + field.getName(), map.size());
                            }
                        } else if (value instanceof Optional<?> optional) {
                            optional.filter(inner -> inModule(inner.getClass())).ifPresent(pending::add);
                        } else if (value instanceof Collection<?> collection) {
                            collection.stream()
                                    .filter(inner -> inner != null && inModule(inner.getClass()))
                                    .forEach(pending::add);
                        } else if (inModule(value.getClass())) {
                            pending.add(value);
                        }
                    }
                }
            }
            return found;
        }

        private static boolean inModule(Class<?> type) {
            String name = type.getName();
            return name.startsWith(MODULE_PACKAGE) && !name.startsWith(TEST_PACKAGE);
        }

        private static boolean keyedByDefinitionKey(Field field, Map<?, ?> map) {
            return field.getGenericType().getTypeName().contains("DefinitionKey")
                    || map.keySet().stream().anyMatch(DefinitionKey.class::isInstance);
        }

        private static Object read(Field field, Object owner) {
            try {
                field.setAccessible(true);
                return field.get(Modifier.isStatic(field.getModifiers()) ? null : owner);
            } catch (ReflectiveOperationException | RuntimeException inaccessible) {
                throw new AssertionError("Cannot inspect " + field, inaccessible);
            }
        }
    }
}
