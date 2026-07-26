package ${package};

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import dev.vertique.application.test.VertiqueAppExtension;
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
                    .put("http", new JsonObject().put("port", 0))
                    .put("management", new JsonObject().put("enabled", true).put("port", 0)));

    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://localhost";
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
}
