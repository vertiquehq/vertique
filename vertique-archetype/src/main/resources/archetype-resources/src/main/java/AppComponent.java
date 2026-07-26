package ${package};

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxModule;
import dev.vertique.core.lifecycle.CoreLifecycleStepsModule;
import dev.vertique.deploy.DeployerModule;
import dev.vertique.management.ManagementModule;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.validation.RestValidationModule;
import ${package}.resource.GeneratedJaxRsResourcesModule;
import jakarta.inject.Singleton;

@VertiqueApp
@Singleton
@Component(
        modules = {
            VertxModule.class,
            ConfigParsingModule.class,
            RestModule.class,
            RestValidationModule.class,
            ManagementModule.class,
            DeployerModule.class,
            CoreLifecycleStepsModule.class,
            AppModule.class,
            GeneratedJaxRsResourcesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
