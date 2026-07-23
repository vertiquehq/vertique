// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.launcher;

import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.core.VertiqueComponentFactory;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.deploy.VerticleDeployer;
import dev.vertique.deploy.VerticleDeploymentManager;
import io.vertx.core.json.JsonObject;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test {@link VertiqueComponentFactory} registered via the test
 * {@code META-INF/services/dev.vertique.core.VertiqueComponentFactory} entry so that
 * {@link VertiqueComponentFactoryLoader#discover()} resolves it during {@link VertiqueBootstrapVerticleIT}.
 *
 * <p>It builds a minimal {@link TestApplicationComponent} with one CONFIGURE startup step, one
 * CONFIGURE shutdown step, and a real {@link VerticleDeploymentManager} over an empty deployment set
 * (so the verticle phases deploy nothing — keeping the IT deterministic and network-free). It must be
 * {@code public} with a {@code public} no-arg constructor for {@link java.util.ServiceLoader}.
 */
public final class TestApplicationFactory implements VertiqueComponentFactory<VertiqueApplicationComponent> {

    /** Captures the config the factory was built with, so the IT can assert the canonical tree flowed through. */
    static final AtomicReference<JsonObject> LAST_CONFIG = new AtomicReference<>(new JsonObject());

    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public TestApplicationFactory() {}

    /**
     * Builds the minimal test application component from the neutral runtime.
     *
     * @param runtime the runtime (Vert.x + config) the bootstrap verticle assembled; never {@code null}
     * @return the test application component
     */
    @Override
    public VertiqueApplicationComponent build(VertiqueRuntime runtime) {
        LAST_CONFIG.set(runtime.config());
        VerticleDeploymentManager manager =
                new VerticleDeploymentManager(new VerticleDeployer(runtime.vertx()), Set.of());
        return new TestApplicationComponent(Set.of(new TestStartupStep()), Set.of(new TestShutdownStep()), manager);
    }
}
