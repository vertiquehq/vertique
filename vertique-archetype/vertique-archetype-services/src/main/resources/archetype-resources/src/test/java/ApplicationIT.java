package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        // configuration must not carry a worker opt-in for it.
        JsonObject greeting = packagedConfig()
                .getJsonObject("services", new JsonObject())
                .getJsonObject("contracts", new JsonObject())
                .getJsonObject("sample", new JsonObject())
                .getJsonObject("greeting", new JsonObject());

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
