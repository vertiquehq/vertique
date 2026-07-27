package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.test.VertiqueAppExtension;
import io.vertx.core.json.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import ${package}.service.GreetingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ApplicationIT {

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject().put("management", new JsonObject().put("enabled", false)));

    @Test
    void invokesGreetingThroughTypedProxy() throws Exception {
        AppComponent component = app.component();
        GreetingService greetings = component.serviceClientFactory().create(GreetingService.class);

        String greeting = greetings.greet("Vertique")
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals("Hello, Vertique!", greeting);
    }

    @Test
    void keepsGreetingServiceOnEventLoopByDefault() throws Exception {
        // The sample implementation never blocks, so it stays on the event loop: the packaged
        // configuration must not carry a worker opt-in for it. Each path segment's presence is
        // asserted structurally before the final worker-absence check, so a renamed or dropped
        // services.contracts.sample.greeting section fails loudly here instead of silently
        // resolving to an empty default object that trivially satisfies containsKey("worker") == false.
        JsonObject config = packagedConfig();
        assertTrue(config.containsKey("services"), "packaged config must declare a top-level services section");

        JsonObject services = config.getJsonObject("services");
        assertTrue(services.containsKey("contracts"), "services config must declare a contracts section");

        JsonObject contracts = services.getJsonObject("contracts");
        assertTrue(contracts.containsKey("sample"), "services.contracts config must declare a sample section");

        JsonObject sample = contracts.getJsonObject("sample");
        assertTrue(sample.containsKey("greeting"), "services.contracts.sample config must declare a greeting section");

        JsonObject greeting = sample.getJsonObject("greeting");
        assertFalse(
                greeting.containsKey("worker"),
                "services.contracts.sample.greeting must not set worker — the sample does no blocking work");
    }

    private static JsonObject packagedConfig() throws Exception {
        try (InputStream config = ApplicationIT.class.getResourceAsStream("/config/application.json")) {
            assertNotNull(config, "the application must package config/application.json");
            return new JsonObject(new String(config.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
