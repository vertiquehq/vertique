package ${package}.service;

import io.vertx.core.Future;
import jakarta.inject.Inject;

/** Application code receives the typed service contract directly from Dagger. */
public final class GreetingClient {

    private final GreetingService service;

    @Inject
    public GreetingClient(GreetingService service) {
        this.service = service;
    }

    public Future<String> greet(String name) {
        return service.greet(name);
    }
}
