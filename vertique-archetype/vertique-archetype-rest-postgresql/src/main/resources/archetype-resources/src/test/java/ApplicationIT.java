package ${package};

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.application.test.VertiqueAppExtension;
import dev.vertique.config.bootstrap.BootstrapConfigLoader;
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
 * <p><strong>Framework-owned rejections.</strong> The journey opens with the four requests the
 * resource itself does not guard: an empty, a whitespace-only, and an over-length {@code name}, all
 * rejected by the request-validation gate from the {@code @NotBlank}, {@code @Pattern}, and
 * {@code @Size} constraints on the request record, and a malformed {@code {id}}, rejected by the
 * built-in {@code UUID} parameter converter without echoing the submitted value into its failure
 * detail. All must answer {@code 400}, so the template's reliance on the framework gates — rather
 * than on hand-written checks — is proven rather than taken on trust.
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
 * <p><strong>What each bound covers.</strong> Three spans are bounded by three different
 * mechanisms. The class-level {@code @Timeout} — 120 seconds rather than the usual 20 — bounds this
 * class's test method, that is the HTTP journey below (JUnit applies a class-level timeout to test
 * methods only, never to {@code @BeforeAll}/{@code @AfterAll}). The container's startup runs in the
 * static initializer above, outside {@code @Timeout}, and is bounded instead by
 * {@code vertique-db-test}'s own 120-second startup timeout. The application boot and its
 * {@code MIGRATE}-phase Flyway step run in the extension's {@code beforeAll}, bounded by
 * {@code VertiqueAppExtension}'s own 30-second start timeout.
 *
 * <p><strong>Static-initializer failure path.</strong> A failure there surfaces as a
 * class-initialization error, which skips {@code @AfterAll} — and nothing leaks: a failed
 * {@code start()} drops the database it had provisioned, and the shared PostgreSQL server is reaped
 * by Testcontainers' Ryuk sidecar when the JVM exits.
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
                .put("http", new JsonObject().put("port", 0).put("host", "127.0.0.1"))
                .put(
                        "management",
                        new JsonObject().put("enabled", true).put("port", 0).put("host", "127.0.0.1"))
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
        RestAssured.baseURI = "http://127.0.0.1";
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

        // And a name past the column width is rejected by @Size before any insert is attempted, so
        // the database never sees a value it would have to truncate or reject itself.
        given().contentType(ContentType.JSON)
                .body(new JsonObject()
                        .put("name", "x".repeat(256))
                        .put("description", "x")
                        .encode())
                .when()
                .post("/items")
                .then()
                .statusCode(400);

        // A malformed identifier is rejected by the built-in UUID parameter converter, which answers
        // 400 with a detail naming the parameter and its target type rather than echoing the
        // submitted value. (The RFC 9457 "instance" member still carries the request URI, as that
        // specification intends — only the failure detail is asserted here.)
        String malformedIdDetail = given().when()
                .get("/items/not-a-uuid")
                .then()
                .statusCode(400)
                .extract()
                .path("detail");
        assertFalse(
                malformedIdDetail.contains("not-a-uuid"),
                "the 400 detail for a malformed id must not echo the submitted value, found: " + malformedIdDetail);

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

    /**
     * Proves the shipped database configuration actually reaches the application.
     *
     * <p>Startup configuration is read from the {@code config/} directory in the working directory,
     * through the same bootstrap loader the launcher runs — not from the packaged classpath. Failsafe
     * runs this test with the project basedir as its working directory, so the loader sees
     * {@code config/application.json} exactly as {@code mvn exec:java} does. The asserted value is
     * deliberately one the framework does not default to ({@code DbPoolConfig.database} has no
     * default), so a run that never read the file cannot satisfy it.
     *
     * <p>Only the loader is exercised here: the container-backed journey above already proves the
     * application boots, and asserting the shipped local-PostgreSQL connection would otherwise
     * require a database on the developer's own {@code localhost:5432}.
     */
    @Test
    void bootstrapReadsWorkingDirectoryConfig() {
        JsonObject config = BootstrapConfigLoader.load(new JsonObject()).config();

        JsonObject db = config.getJsonObject("db");
        assertNotNull(db, "the bootstrap loader must read the db section from config/application.json");
        assertEquals(
                "vertique",
                db.getString("database"),
                "db.database must come from config/application.json, which the framework does not default");
    }
}
