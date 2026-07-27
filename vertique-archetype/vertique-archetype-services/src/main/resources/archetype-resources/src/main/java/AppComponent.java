package ${package};

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.services.ServiceClientFactory;
import dev.vertique.starter.services.ServicesApplicationModule;
import ${package}.service.GeneratedServicesModule;
import jakarta.inject.Singleton;

@VertiqueApp
@Singleton
@Component(
        modules = {
            ServicesApplicationModule.class,
            AppModule.class,
            GeneratedServicesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {

    ServiceClientFactory serviceClientFactory();
}
