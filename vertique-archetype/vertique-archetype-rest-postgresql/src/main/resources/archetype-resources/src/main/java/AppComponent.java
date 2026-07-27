package ${package};

import dagger.Component;
import dev.vertique.application.VertiqueApp;
import dev.vertique.application.VertiqueApplicationComponent;
import dev.vertique.starter.postgresql.PostgresqlPersistenceModule;
import dev.vertique.starter.rest.RestApplicationModule;
import ${package}.resource.GeneratedJaxRsResourcesModule;
import jakarta.inject.Singleton;

@VertiqueApp
@Singleton
@Component(
        modules = {
            RestApplicationModule.class,
            PostgresqlPersistenceModule.class,
            AppModule.class,
            GeneratedJaxRsResourcesModule.class
        })
interface AppComponent extends VertiqueApplicationComponent {}
