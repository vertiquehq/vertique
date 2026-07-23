// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.deploy.VerticleDeploymentManager;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VertiqueComponentFactoryLoader#selectFactory} — the pure exactly-one
 * selection rule (FR-APP-032 / AC-5) behind ServiceLoader discovery, plus the produced-type
 * validation performed by the wrapper {@code selectFactory} returns.
 *
 * <p>The discovery boundaries are exercised here with synthetic lists rather than real
 * {@code META-INF/services} entries, isolating the rule from the classpath:
 * <ul>
 *   <li><b>zero</b> → fail-fast naming the {@code META-INF/services} resource;</li>
 *   <li><b>more than one</b> → fail-fast listing every discovered FQN;</li>
 *   <li><b>one</b> → returns a build-time validating wrapper that delegates to it;</li>
 *   <li><b>wrong produced type</b> → the wrapper's {@code build} throws a guided
 *       {@link IllegalStateException} naming the provider FQN and the expected type.</li>
 * </ul>
 */
class VertiqueComponentFactoryLoaderTest {

    @Test
    @DisplayName("selectFactory: empty list throws naming the SPI resource and a generate-or-register hint")
    void selectFactory_empty_throwsNoFactory() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> VertiqueComponentFactoryLoader.selectFactory(List.of()));

        String message = ex.getMessage();
        assertTrue(message.contains("No application"), "message announces no factory: " + message);
        assertTrue(
                message.contains(VertiqueComponentFactoryLoader.SERVICE_RESOURCE),
                "message names the META-INF/services resource: " + message);
        assertTrue(
                message.contains("META-INF/services/dev.vertique.core.VertiqueComponentFactory"),
                "message names the concrete SPI resource path: " + message);
    }

    @Test
    @DisplayName("selectFactory: two entries throw listing both discovered FQNs")
    void selectFactory_two_throwsListingFqns() {
        VertiqueComponentFactory<?> first = new FirstFactory();
        VertiqueComponentFactory<?> second = new SecondFactory();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> VertiqueComponentFactoryLoader.selectFactory(List.of(first, second)));

        String message = ex.getMessage();
        assertTrue(message.contains(FirstFactory.class.getName()), "message lists the first factory FQN: " + message);
        assertTrue(message.contains(SecondFactory.class.getName()), "message lists the second factory FQN: " + message);
        assertTrue(message.contains("exactly one"), "message states exactly-one is required: " + message);
    }

    @Test
    @DisplayName("selectFactory: a single valid factory yields a wrapper whose build returns the produced component")
    void selectFactory_one_wrapperDelegatesToValidFactory() {
        VertiqueApplicationComponent component = new StubApplicationComponent();
        VertiqueComponentFactory<?> only = new ValidFactory(component);

        VertiqueComponentFactory<VertiqueApplicationComponent> wrapper =
                VertiqueComponentFactoryLoader.selectFactory(List.of(only));

        assertNotNull(wrapper, "selectFactory returns a non-null wrapper for the sole factory");
        VertiqueApplicationComponent built = wrapper.build(STUB_RUNTIME);
        assertSame(component, built, "the wrapper delegates to the raw factory and returns its component unchanged");
    }

    @Test
    @DisplayName(
            "selectFactory wrapper: a factory whose build returns a non-VertiqueApplicationComponent throws naming the FQN and expected type")
    void selectFactory_wrapper_wrongProducedType_throwsGuided() {
        // FirstFactory.build() returns a plain Object — not a VertiqueApplicationComponent.
        VertiqueComponentFactory<?> wrongType = new FirstFactory();

        VertiqueComponentFactory<VertiqueApplicationComponent> wrapper =
                VertiqueComponentFactoryLoader.selectFactory(List.of(wrongType));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> wrapper.build(STUB_RUNTIME),
                "the wrapper validates the produced component type at build time");

        String message = ex.getMessage();
        assertTrue(
                message.contains(FirstFactory.class.getName()), "message names the offending provider FQN: " + message);
        assertTrue(
                message.contains(VertiqueApplicationComponent.class.getName()),
                "message names the expected VertiqueApplicationComponent type: " + message);
    }

    @Test
    @DisplayName("selectFactory: failure message names the supplied discovery classloader")
    void selectFactory_includesClassLoaderInDiagnostics() {
        ClassLoader marker = new ClassLoader() {
            @Override
            public String toString() {
                return "marker-classloader";
            }
        };

        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> VertiqueComponentFactoryLoader.selectFactory(List.of(), marker));

        assertTrue(
                ex.getMessage().contains("marker-classloader"),
                "diagnostics name the discovery classloader: " + ex.getMessage());
    }

    // --- Test doubles ---

    /**
     * A neutral runtime passed to the wrapper's {@code build} in the delegation/validation tests. The
     * {@link Vertx} is a no-op mock — the wrapper never touches it; it only forwards the runtime to
     * the delegate factory, whose stubbed {@code build} ignores it.
     */
    private static final VertiqueRuntime STUB_RUNTIME = VertiqueRuntime.of(mock(Vertx.class), new JsonObject());

    /**
     * A factory whose {@code build} returns a plain {@link Object} (not a
     * {@link VertiqueApplicationComponent}). Used both to occupy a discovery slot in the
     * exactly-one tests and to drive the wrapper's produced-type validation in
     * {@link #selectFactory_wrapper_wrongProducedType_throwsGuided()}.
     */
    private static final class FirstFactory implements VertiqueComponentFactory<Object> {
        @Override
        public Object build(VertiqueRuntime runtime) {
            return new Object();
        }
    }

    /** A second distinct factory class so the multiple-factory diagnostics list two FQNs. */
    private static final class SecondFactory implements VertiqueComponentFactory<Object> {
        @Override
        public Object build(VertiqueRuntime runtime) {
            return new Object();
        }
    }

    /** A factory that builds a valid {@link VertiqueApplicationComponent}, used for the happy-path wrapper test. */
    private static final class ValidFactory implements VertiqueComponentFactory<VertiqueApplicationComponent> {
        private final VertiqueApplicationComponent component;

        ValidFactory(VertiqueApplicationComponent component) {
            this.component = component;
        }

        @Override
        public VertiqueApplicationComponent build(VertiqueRuntime runtime) {
            return component;
        }
    }

    /** A minimal {@link VertiqueApplicationComponent} returned by {@link ValidFactory} in the happy-path test. */
    private static final class StubApplicationComponent implements VertiqueApplicationComponent {
        @Override
        public Set<ApplicationStartupStep> startupSteps() {
            return Set.of();
        }

        @Override
        public Set<ApplicationShutdownStep> shutdownSteps() {
            return Set.of();
        }

        @Override
        public VerticleDeploymentManager verticleDeploymentManager() {
            return null;
        }
    }
}
