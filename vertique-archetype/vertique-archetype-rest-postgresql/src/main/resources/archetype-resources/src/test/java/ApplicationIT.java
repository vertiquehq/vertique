package ${package};

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.test.PostgresContainer;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Boots the whole application against a real PostgreSQL container and drives one ordered item CRUD
 * journey over HTTP.
 *
 * <p><strong>Framework-owned rejections.</strong> The journey opens with the three requests the
 * resource itself does not guard: an empty and a whitespace-only {@code name}, both rejected by the
 * request-validation gate from the {@code @NotBlank} and {@code @Pattern} constraints on the request
 * record, and a malformed {@code {id}}, rejected by the built-in {@code UUID} parameter converter.
 * All must answer {@code 400}, so the template's reliance on the framework gates — rather than on
 * hand-written checks — is proven rather than taken on trust.
 *
 * <p><strong>Docker is required.</strong> The container start below propagates any discovery or
 * startup failure, so a missing daemon fails this build rather than skipping the proof.
 *
 * <p><strong>Ordering.</strong> The container is declared and started in a static initializer and
 * the config is built from it, all <em>before</em> the {@code VertiqueAppExtension} field is
 * constructed. Static declaration order therefore guarantees the database is reachable — and its
 * mapped connection identity readable — by the time the extension boots the application and its
 * {@code MIGRATE}-phase Flyway step connects. The container deliberately runs no migrations of its
 * own; the application owns them through {@code flyway.mode=MIGRATE}.
 *
 * <p><strong>Timeout.</strong> The class-level bound is 120 seconds rather than the usual 20,
 * because a cold PostgreSQL image pull and container start happen inside it.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ApplicationIT {

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("app_db");

    static {
        db.start();
    }

    static final JsonObject CONFIG = buildConfig();

    @RegisterExtension
    static final VertiqueAppExtension app = VertiqueAppExtension.forFactory(new AppComponentVertiqueComponentFactory())
            .withConfig(CONFIG);

    /**
     * Builds the application config from the running container: the {@code db} section points at the
     * container's mapped connection, and {@code flyway.mode=MIGRATE} makes the application apply its
     * own migrations during startup.
     *
     * @return the root application config
     */
    private static JsonObject buildConfig() {
        DbPoolConfig pool = db.toPoolConfig();
        return new JsonObject()
                .put("http", new JsonObject().put("port", 0))
                .put("management", new JsonObject().put("enabled", true).put("port", 0))
                .put(
                        "db",
                        new JsonObject()
                                .put("host", pool.host())
                                .put("port", pool.port())
                                .put("database", pool.database())
                                .put("user", pool.user())
                                .put("password", pool.password()))
                .put("flyway", new JsonObject().put("mode", "MIGRATE"));
    }

    @BeforeAll
    static void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = app.httpPort();
    }

    @AfterAll
    static void tearDown() {
        try {
            RestAssured.reset();
        } finally {
            db.close();
        }
    }

    @Test
    void supportsItemCrud() {
        // The request-validation gate rejects an empty name before the resource method runs: the
        // @NotBlank constraint on CreateItemRequest is the template's only presence check.
        given().contentType(ContentType.JSON)
                .body(new JsonObject().put("name", "").put("description", "x").encode())
                .when()
                .post("/items")
                .then()
                .statusCode(400);

        // And a whitespace-only name is rejected too — @NotBlank alone only bounds length, so the
        // companion @Pattern is what carries this case.
        given().contentType(ContentType.JSON)
                .body(new JsonObject().put("name", "   ").put("description", "x").encode())
                .when()
                .post("/items")
                .then()
                .statusCode(400);

        // A malformed identifier is rejected by the built-in UUID parameter converter, which answers
        // 400 without echoing the submitted value.
        given().when().get("/items/not-a-uuid").then().statusCode(400);

        Response created = given().contentType(ContentType.JSON)
                .body(new JsonObject()
                        .put("name", "Widget")
                        .put("description", "A sample widget")
                        .encode())
                .when()
                .post("/items")
                .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("name", equalTo("Widget"))
                .body("description", equalTo("A sample widget"))
                .extract()
                .response();

        String id = created.path("id");
        assertEquals("/items/" + id, created.header("Location"));

        given().when()
                .get("/items/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("name", equalTo("Widget"))
                .body("description", equalTo("A sample widget"));

        given().contentType(ContentType.JSON)
                .body(new JsonObject()
                        .put("name", "Updated widget")
                        .put("description", "An updated widget")
                        .encode())
                .when()
                .put("/items/" + id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("name", equalTo("Updated widget"))
                .body("description", equalTo("An updated widget"));

        String deletedBody = given().when()
                .delete("/items/" + id)
                .then()
                .statusCode(204)
                .extract()
                .asString();
        assertTrue(deletedBody.isEmpty(), "a 204 response must carry no body");

        given().when().get("/items/" + id).then().statusCode(404);
    }
}
