package ${package};

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.config.bootstrap.BootstrapConfigLoader;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ApplicationIT {

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(new JsonObject()
                    .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                    .put(
                            "management",
                            new JsonObject()
                                    .put("enabled", true)
                                    .put("port", 0)
                                    .put("host", "127.0.0.1")));

    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = app.httpPort();
    }

    @AfterAll
    static void tearDown() {
        RestAssured.reset();
    }

    @Test
    void servesHello() {
        given().when()
                .get("/hello")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", equalTo("Hello, Vertique!"));
    }

    @Test
    void servesLiveness() {
        int managementPort = (int) app.vertx().sharedData().getLocalMap("vertique").get("management.port");

        given().port(managementPort)
                .when()
                .get("/health/live")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("status", equalTo("UP"));
    }

    @Test
    void bootstrapReadsWorkingDirectoryConfig() {
        // Startup configuration is read from the config/ directory in the working directory, through
        // the same bootstrap loader the launcher runs. Failsafe runs this test with the project
        // basedir as its working directory, so the loader must see config/application.json exactly as
        // "mvn exec:java" does. The asserted value is deliberately one the framework does not default
        // to, so a run that never read the file cannot satisfy it.
        JsonObject config = BootstrapConfigLoader.load(new JsonObject()).config();

        JsonObject http = config.getJsonObject("http");
        assertNotNull(http, "the bootstrap loader must read the http section from config/application.json");
        assertEquals(
                60,
                http.getInteger("idleTimeoutSeconds"),
                "http.idleTimeoutSeconds must come from config/application.json, not from the framework default of 0");
    }
}
