package ${package};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.config.bootstrap.BootstrapConfigLoader;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
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

        String greeting = component.greetingClient().greet("Vertique")
                .toCompletionStage()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals("Hello, Vertique!", greeting);
    }

    @Test
    void keepsGreetingServiceOnEventLoopByDefault() throws Exception {
        // The sample implementation never blocks, so it stays on the event loop: the shipped
        // configuration must not carry a worker opt-in for it. Each path segment's presence is
        // asserted structurally before the final worker-absence check, so a renamed or dropped
        // services.contracts.sample.greeting section fails loudly here instead of silently
        // resolving to an empty default object that trivially satisfies containsKey("worker") == false.
        JsonObject config = workingDirectoryConfig();
        assertTrue(config.containsKey("services"), "shipped config must declare a top-level services section");

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

    @Test
    void bootstrapReadsWorkingDirectoryConfig() {
        // Startup configuration is read from the config/ directory in the working directory, through
        // the same bootstrap loader the launcher runs. Failsafe runs this test with the project
        // basedir as its working directory, so the loader must see config/application.json exactly as
        // "mvn exec:java" does. The asserted value is deliberately one the framework does not default
        // to, so a run that never read the file cannot satisfy it.
        JsonObject config = BootstrapConfigLoader.load(new JsonObject()).config();

        JsonObject services = config.getJsonObject("services");
        assertNotNull(services, "the bootstrap loader must read the services section from config/application.json");

        JsonObject contracts = services.getJsonObject("contracts");
        assertNotNull(contracts, "the loaded services section must carry its contracts subtree");

        JsonObject sample = contracts.getJsonObject("sample");
        assertNotNull(sample, "the loaded contracts subtree must carry its sample namespace");

        JsonObject greeting = sample.getJsonObject("greeting");
        assertNotNull(greeting, "the loaded sample namespace must carry its greeting service");

        assertEquals(
                2,
                greeting.getInteger("instances"),
                "services.contracts.sample.greeting.instances must come from config/application.json, not from the"
                        + " framework default of 1");
    }

    private static JsonObject workingDirectoryConfig() throws Exception {
        Path config = Path.of("config", "application.json");
        assertTrue(
                Files.isRegularFile(config), "the application must ship config/application.json in its project root");
        return new JsonObject(Files.readString(config, StandardCharsets.UTF_8));
    }
}
