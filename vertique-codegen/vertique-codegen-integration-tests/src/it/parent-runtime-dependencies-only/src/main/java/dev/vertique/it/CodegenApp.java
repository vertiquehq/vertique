// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.it;

import dagger.Component;
import dagger.Module;
import dagger.multibindings.Multibinds;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.deploy.VerticleDeployment;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.Set;

/** External application source compiled using runtime dependencies and the public parent only. */
@Path("/codegen")
public final class CodegenApp {

    @Inject
    public CodegenApp() {}

    @GET
    public String get() {
        return "generated";
    }
}

@Module
abstract class EmptyLifecycleModule {

    @Multibinds
    abstract Set<ApplicationStartupStep> startupSteps();

    @Multibinds
    abstract Set<ApplicationShutdownStep> shutdownSteps();

    @Multibinds
    abstract Set<VerticleDeployment> verticleDeployments();
}

@VertiqueApp
@Singleton
@Component(modules = {VertxModule.class, EmptyLifecycleModule.class})
interface AppComponent extends VertiqueApplicationComponent {}
