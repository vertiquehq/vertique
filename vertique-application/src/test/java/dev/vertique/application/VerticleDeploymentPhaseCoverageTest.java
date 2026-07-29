// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.deploy.VerticleDeployment;
import dev.vertique.deploy.VerticleDeploymentManager;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Phase-coverage proof for the host-neutral lifecycle runner: a <em>multibound</em> {@link
 * VerticleDeployment} contributed in <strong>every</strong> {@link LifecyclePhase#isVerticlePhase()
 * verticle phase} actually reaches deployment when the application starts through {@link
 * VertiqueApplicationBootstrap#start}.
 *
 * <p><b>Why this exists.</b> The other coverage of the runner's phase loop is indirect:
 * {@link VertiqueApplicationBootstrapTest} verifies {@code deployPhase} calls on a Mockito
 * <em>mock</em> {@link VerticleDeploymentManager}, and {@code VertiqueBootstrapVerticleIT} in
 * {@code vertique-launcher} hand-constructs a manager over a single {@code EDGE} deployment. Neither
 * proves that the mechanism applications actually use — {@code @Provides @IntoSet
 * VerticleDeployment} on a real Dagger component — reaches a running verticle in every phase. A
 * contribution to a phase the orchestrator never deploys would vanish with no error, which is
 * exactly the silent loss this test rules out.
 *
 * <p><b>How the wiring is real.</b> The application under test is assembled by Dagger from the
 * framework's own modules: {@link VertxModule} (the {@code Vertx} + config bindings), the real
 * {@link DeployerModule} (which declares the {@code Set<VerticleDeployment>} /
 * {@code Set<ApplicationStartupStep>} / {@code Set<ApplicationShutdownStep>} multibindings), and
 * {@link SentinelDeploymentModule}, which contributes one sentinel deployment per verticle phase via
 * four separate {@code @Provides @IntoSet} methods. Four independent {@code @Provides} methods for
 * the same type only compile because Dagger treats them as multibinding contributions, so a green
 * compile is itself the proof that the set is a real multibinding rather than a hand-built
 * {@code Set.of(...)}. {@link VerticleDeploymentManager} and {@code VerticleDeployer} are
 * constructed by Dagger from their {@code @Inject} constructors — no mock, no hand-built manager —
 * and the verticles are deployed on a real {@link Vertx}.
 *
 * <p>Test naming note: this class is deliberately named {@code *Test} (Surefire) rather than
 * {@code *IT} — {@code vertique-application} declares no {@code maven-failsafe-plugin}, so an
 * {@code *IT} class in this module would compile but never run. It needs no external resource: the
 * sentinel verticles bind nothing and complete their {@code start()} synchronously.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class VerticleDeploymentPhaseCoverageTest {

    /** The verticle phases, in deployment order, that must each carry their contribution to a start. */
    private static final List<LifecyclePhase> VERTICLE_PHASES =
            List.of(LifecyclePhase.BOOTSTRAP, LifecyclePhase.INFRA, LifecyclePhase.SERVICES, LifecyclePhase.EDGE);

    // --- Phase coverage through the real runner ---

    /**
     * Given a real Dagger component that contributes one sentinel {@link VerticleDeployment} per
     * verticle phase through {@code @Provides @IntoSet}, when the application is started through
     * {@link VertiqueApplicationBootstrap#start}, then every sentinel verticle's {@code start()} ran.
     *
     * <p>The per-phase assertion is deliberate: a failure names the phase whose contribution was
     * dropped, which is the diagnostic the silent-loss defect needs. The trailing list equality then
     * pins deployment order and rules out extra or repeated deployments.
     */
    @Test
    @DisplayName("every verticle-phase contribution deploys: a multibound sentinel starts in BOOTSTRAP, INFRA, "
            + "SERVICES and EDGE through the real runner")
    void everyVerticlePhaseContributionDeploys(Vertx vertx, VertxTestContext ctx) {
        SentinelDeploymentModule.STARTED_PHASES.clear();

        VertiqueRuntime runtime = VertiqueRuntime.of(vertx, new JsonObject());
        VertiqueComponentFactory<SentinelAppComponent> factory =
                rt -> DaggerVerticleDeploymentPhaseCoverageTest_SentinelAppComponent.builder()
                        .vertxModule(new VertxModule(rt.vertx(), rt.config()))
                        .build();

        VertiqueApplicationBootstrap.start(runtime, factory).onComplete(ctx.succeeding(handle -> {
            // The manager is the real framework type built by Dagger — not a mock and not hand-built.
            assertSame(
                    VerticleDeploymentManager.class,
                    handle.component().verticleDeploymentManager().getClass(),
                    "the runner must have driven the real VerticleDeploymentManager, not a test double");

            // The multibound set materialized with exactly one contribution per verticle phase.
            Set<VerticleDeployment> contributed = handle.component().verticleDeployments();
            assertEquals(
                    VERTICLE_PHASES.size(),
                    contributed.size(),
                    "the @IntoSet multibinding must carry one contribution per verticle phase");

            // Per-phase proof: a failure here names the phase whose contribution was dropped.
            for (LifecyclePhase phase : VERTICLE_PHASES) {
                assertTrue(
                        SentinelDeploymentModule.STARTED_PHASES.contains(phase),
                        "no sentinel verticle started for phase " + phase
                                + " — a multibound VerticleDeployment contributed to that phase was silently dropped "
                                + "by the lifecycle orchestrator; started phases were "
                                + SentinelDeploymentModule.STARTED_PHASES);
            }

            // Exhaustiveness + order: exactly these four, in verticle-phase order, once each.
            assertEquals(
                    VERTICLE_PHASES,
                    List.copyOf(SentinelDeploymentModule.STARTED_PHASES),
                    "each verticle phase deploys exactly once, in BOOTSTRAP → INFRA → SERVICES → EDGE order");

            handle.shutdown().onComplete(ctx.succeedingThenComplete());
        }));
    }

    // --- The guard that makes the runner's unfiltered phase loop safe ---

    /**
     * Given a {@link VerticleDeployment} constructed for a non-verticle phase, then construction
     * throws. This pins locally the compact-constructor guard that lets the runner write its {@code
     * LifecyclePhase.values()} loop with no phase filter: a contribution can never name a phase the
     * runner does not deploy, so an unreachable contribution cannot be declared in the first place.
     * (The guard's message contract is covered in depth by {@code
     * dev.vertique.deploy.VerticleDeploymentConstructionTest}.)
     *
     * @param phase the non-verticle phase under test
     */
    @ParameterizedTest
    @EnumSource(
            value = LifecyclePhase.class,
            names = {"CONFIGURE", "VALIDATE", "MIGRATE", "AFTER_START"})
    @DisplayName("a VerticleDeployment for a non-verticle phase is rejected at construction")
    void nonVerticlePhaseContributionIsRejectedAtConstruction(LifecyclePhase phase) {
        Supplier<AbstractVerticle> supplier = () -> new SentinelVerticle(phase);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> VerticleDeployment.of("sentinel-" + phase, supplier, phase),
                "a contribution to non-verticle phase " + phase + " must be rejected at construction");
        assertTrue(
                ex.getMessage().contains(phase.name()),
                "the rejection must name the offending phase; got: " + ex.getMessage());
    }

    // --- The application under test ---

    /**
     * The real Dagger application component under test: the framework's {@link VertxModule} and
     * {@link DeployerModule} plus {@link SentinelDeploymentModule}'s four {@code @IntoSet}
     * contributions. It exposes the multibound deployment set so the test can assert the multibinding
     * materialized, in addition to the {@link VertiqueApplicationComponent} accessors the runner uses.
     */
    @Singleton
    @Component(modules = {VertxModule.class, DeployerModule.class, SentinelDeploymentModule.class})
    interface SentinelAppComponent extends VertiqueApplicationComponent {

        /**
         * Returns the multibound verticle deployments — the same {@code @IntoSet} set Dagger injects
         * into the {@link VerticleDeploymentManager} the runner drives.
         *
         * @return the contributed deployments; one per verticle phase
         */
        Set<VerticleDeployment> verticleDeployments();
    }

    /**
     * Contributes one sentinel {@link VerticleDeployment} per {@link
     * LifecyclePhase#isVerticlePhase() verticle phase} through four separate {@code @Provides
     * @IntoSet} methods — the exact mechanism an application uses. Each sentinel verticle appends its
     * phase to {@link #STARTED_PHASES} when Vert.x starts it.
     */
    @Module
    abstract static class SentinelDeploymentModule {

        /**
         * The phases whose sentinel verticle actually started, in start order. Appended to by {@link
         * SentinelVerticle#start(Promise)}; cleared by the test before {@code start(...)} so it
         * observes only its own run.
         */
        static final List<LifecyclePhase> STARTED_PHASES = new CopyOnWriteArrayList<>();

        /**
         * Contributes the {@link LifecyclePhase#BOOTSTRAP BOOTSTRAP} sentinel.
         *
         * @return the sentinel deployment
         */
        @Provides
        @IntoSet
        static VerticleDeployment bootstrapSentinel() {
            return sentinel(LifecyclePhase.BOOTSTRAP);
        }

        /**
         * Contributes the {@link LifecyclePhase#INFRA INFRA} sentinel.
         *
         * @return the sentinel deployment
         */
        @Provides
        @IntoSet
        static VerticleDeployment infraSentinel() {
            return sentinel(LifecyclePhase.INFRA);
        }

        /**
         * Contributes the {@link LifecyclePhase#SERVICES SERVICES} sentinel — the phase whose loss is
         * the live defect a partial deploy chain produces (cron, delayed jobs, outbox relay).
         *
         * @return the sentinel deployment
         */
        @Provides
        @IntoSet
        static VerticleDeployment servicesSentinel() {
            return sentinel(LifecyclePhase.SERVICES);
        }

        /**
         * Contributes the {@link LifecyclePhase#EDGE EDGE} sentinel.
         *
         * @return the sentinel deployment
         */
        @Provides
        @IntoSet
        static VerticleDeployment edgeSentinel() {
            return sentinel(LifecyclePhase.EDGE);
        }

        /**
         * Builds a sentinel deployment for the given verticle phase.
         *
         * @param phase the verticle phase the sentinel is contributed to
         * @return a deployment whose verticle records {@code phase} in {@link #STARTED_PHASES}
         */
        private static VerticleDeployment sentinel(LifecyclePhase phase) {
            return VerticleDeployment.of("sentinel-" + phase, () -> new SentinelVerticle(phase), phase);
        }
    }

    /**
     * A verticle that records its contributing phase in {@link
     * SentinelDeploymentModule#STARTED_PHASES} on start and completes immediately. Its
     * {@code start()} running is the observable proof that the phase's contribution was deployed.
     */
    private static final class SentinelVerticle extends AbstractVerticle {

        private final LifecyclePhase phase;

        /**
         * Creates a sentinel for the given phase.
         *
         * @param phase the verticle phase this sentinel was contributed to
         */
        SentinelVerticle(LifecyclePhase phase) {
            this.phase = phase;
        }

        @Override
        public void start(Promise<Void> startPromise) {
            SentinelDeploymentModule.STARTED_PHASES.add(phase);
            startPromise.complete();
        }
    }
}
