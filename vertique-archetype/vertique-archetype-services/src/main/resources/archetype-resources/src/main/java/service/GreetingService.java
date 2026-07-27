package ${package}.service;

import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceOperation;
import io.vertx.core.Future;

@ServiceContract(namespace = "sample", value = "greeting")
public interface GreetingService {

    @ServiceOperation("greet")
    Future<String> greet(String name);
}
