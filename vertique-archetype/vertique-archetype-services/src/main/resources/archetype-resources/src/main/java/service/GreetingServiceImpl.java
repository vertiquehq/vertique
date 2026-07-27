package ${package}.service;

import io.vertx.core.Future;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public final class GreetingServiceImpl implements GreetingService {

    @Inject
    public GreetingServiceImpl() {}

    @Override
    public Future<String> greet(String name) {
        return Future.succeededFuture("Hello, " + name + "!");
    }
}
