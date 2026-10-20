// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.validation.BeanValidator;
import dev.vertique.input.processing.EffectiveInputPolicies;
import dev.vertique.input.processing.InputObjectProcessor;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import dev.vertique.rest.jaxrs.pointer.DaggerPointerValidationComponent;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Address;
import dev.vertique.rest.jaxrs.pointer.PointerResources;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof that a request body's bean-validation failures are located in the response by the
 * wire names the request was bound with.
 *
 * <p>Each test mounts real JAX-RS resources through {@link TestFactories} with the real Hibernate
 * Validator-backed {@link BeanValidator} (built by a Dagger component installing the validation
 * module), posts a body over a loopback socket, and asserts on the raw members of the entries in the
 * 400 response: {@code pointer} for a JSON body the framework bound with the route's mapper,
 * {@code parameter} with location {@code form} for a form-urlencoded body, and neither member for a
 * body the framework did not bind or whose location it cannot verify. The retired {@code path}
 * member must be absent from every entry.
 *
 * <p>The client is a {@link WebClient} (it aggregates the response body, so a status can never be
 * paired with an empty body), shared by the class and closed in {@link #tearDownClass}. Requests block
 * on the test thread under the class-level timeout; no test sleeps.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class BodyViolationPointerIT {

    private static final String HOST = "127.0.0.1";
    private static final long AWAIT_SECONDS = 10;

    // --- Class-scoped resources ---

    private static Vertx vertx;
    private static WebClient client;
    private static BeanValidator validator;
    private static DefaultJsonMapperProfileRegistry profiles;

    // --- Per-test resources ---

    private final List<HttpServer> servers = new ArrayList<>();

    /**
     * Creates the shared client, the real bean validator and the profile registry once.
     *
     * @param v   the class-scoped Vert.x instance
     * @param ctx the test context used to signal setup completion
     */
    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        client = WebClient.create(v, new WebClientOptions().setFollowRedirects(false));
        validator = DaggerPointerValidationComponent.create().beanValidator();
        profiles = new DefaultJsonMapperProfileRegistry(
                Set.of(snakeCaseProfile(), emptyNameProfile(), caseInsensitiveProfile()));
        ctx.completeNow();
    }

    /**
     * Closes the servers a test started.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        List<Future<Void>> closes = servers.stream().map(HttpServer::close).toList();
        servers.clear();
        Future.join(closes).onComplete(ar -> ctx.completeNow());
    }

    /**
     * Closes the shared client before the extension-owned Vert.x instance goes away.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        ctx.completeNow();
    }

    // --- Profile fixtures ---

    /**
     * The {@code snake-test} profile: {@code SNAKE_CASE} wire names, tolerant of unknown properties so
     * a Java-name key can be posted without failing the bind.
     *
     * @return the profile
     */
    private static JsonMapperProfile snakeCaseProfile() {
        return JsonMapperProfiles.of(
                JsonProfileId.of(PointerResources.SNAKE_PROFILE),
                JsonMapper.builder()
                        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .addModule(VertxJsonSupport.module())
                        .build());
    }

    /**
     * The {@code case-insensitive-test} profile: property names match without regard to case.
     *
     * @return the profile
     */
    private static JsonMapperProfile caseInsensitiveProfile() {
        return JsonMapperProfiles.of(
                JsonProfileId.of(PointerResources.CASE_INSENSITIVE_PROFILE),
                JsonMapper.builder()
                        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
                        .addModule(VertxJsonSupport.module())
                        .build());
    }

    /**
     * The {@code empty-name-test} profile: a naming strategy that gives the Java property
     * {@code blank} the empty string as its wire name.
     *
     * @return the profile
     */
    private static JsonMapperProfile emptyNameProfile() {
        return JsonMapperProfiles.of(
                JsonProfileId.of(PointerResources.EMPTY_NAME_PROFILE),
                JsonMapper.builder()
                        .propertyNamingStrategy(new EmptyNameStrategy())
                        .addModule(VertxJsonSupport.module())
                        .build());
    }

    /** Names the Java property {@code blank} with the empty string and leaves every other name alone. */
    private static final class EmptyNameStrategy extends PropertyNamingStrategy {

        private static String rename(String defaultName) {
            return "blank".equals(defaultName) ? "" : defaultName;
        }

        @Override
        public String nameForField(MapperConfig<?> config, AnnotatedField field, String defaultName) {
            return rename(defaultName);
        }

        @Override
        public String nameForGetterMethod(MapperConfig<?> config, AnnotatedMethod method, String defaultName) {
            return rename(defaultName);
        }

        @Override
        public String nameForSetterMethod(MapperConfig<?> config, AnnotatedMethod method, String defaultName) {
            return rename(defaultName);
        }

        @Override
        public String nameForConstructorParameter(
                MapperConfig<?> config, AnnotatedParameter ctorParam, String defaultName) {
            return rename(defaultName);
        }
    }

    // --- Decoder and processor fixtures ---

    /**
     * An application-owned decoder at priority 0, ahead of the framework's decoders, that binds
     * {@link Address} itself (so the framework never records how the body was bound).
     */
    static final class ApplicationJsonDecoder implements RequestBodyDecoder {

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public boolean canDecode(Class<?> targetType, String contentType) {
            return targetType == Address.class
                    && contentType != null
                    && contentType.toLowerCase(java.util.Locale.ROOT).contains("json");
        }

        @Override
        public Object decode(RoutingContext ctx, RequestValue body, Class<?> targetType, Type genericType) {
            return new Address(body.getJsonObject().getString("zip_code"));
        }
    }

    /**
     * An input-processing engine that changes nothing and records each body target it was asked to
     * process, so a test can tell that a body went through the object-processor branch rather than a
     * decoder.
     */
    static final class RecordingProcessor implements InputObjectProcessor {

        private final List<Type> targets = new CopyOnWriteArrayList<>();

        @Override
        public Object processInput(
                Object input,
                Type targetType,
                EffectiveInputPolicies policies,
                InputLocation location,
                InputFieldNameResolver nameResolver) {
            targets.add(targetType);
            return input;
        }

        /**
         * Counts the processed bodies whose target is exactly {@code type}.
         *
         * @param type the target class
         * @return the number of matching calls so far
         */
        long callsFor(Class<?> type) {
            return targets.stream().filter(type::equals).count();
        }

        /**
         * Counts the processed bodies whose target is a parameterized {@code raw<...>} type.
         *
         * @param raw the raw type
         * @return the number of matching calls so far
         */
        long callsForParameterized(Class<?> raw) {
            return targets.stream()
                    .filter(t -> t instanceof ParameterizedType p && p.getRawType() == raw)
                    .count();
        }
    }

    // --- TP: renamed property ---

    @Test
    @DisplayName("A renamed body property is reported by its wire-name pointer")
    void renamedPropertyReportsItsWireNamePointer() {
        int port = deploy(b -> {}, new PointerResources.AddressResource());

        Reply reply = postJson(port, "/addresses", "{\"zip_code\":\"\"}");

        assertEquals(400, reply.status());
        JsonObject entry = reply.only();
        assertPointer("/zip_code", entry);
        assertEquals("required", entry.getString("type"));
    }

    // --- TP: list items ---

    @Test
    @DisplayName("List items are reported with distinct indexed pointers")
    void listItemsReportDistinctIndexedPointers() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply reply = postJson(port, "/bodies/cart", "{\"items\":[{\"name\":\"\"},{\"name\":\"\"}]}");

        assertEquals(400, reply.status());
        assertPointers(Set.of("/items/0/name", "/items/1/name"), reply);
    }

    // --- TP: the binding profile decides the wire name ---

    @Test
    @DisplayName("The same DTO is reported by the wire name of the profile it was bound under")
    void sameDtoReportsTheBindingProfilesWireName() {
        int port = deploy(
                b -> {}, new PointerResources.SnakeShippingResource(), new PointerResources.PlainShippingResource());

        Reply snake = postJson(port, "/shipping/snake", "{\"zip_code\":\"\"}");
        Reply plain = postJson(port, "/shipping/plain", "{\"zipCode\":\"\"}");

        assertEquals(400, snake.status());
        assertPointer("/zip_code", snake.only());
        assertEquals(400, plain.status());
        assertPointer("/zipCode", plain.only());
    }

    // --- TP: root class-level, map entries, escaping, missing leaves ---

    @Test
    @DisplayName("Root class-level, map entries, escaping and missing leaves are pointed at without client text")
    void rootClassLevelMapEntriesEscapingAndMissingLeaves() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        assertClassLevelConstraintOnTheRootReportsTheEmptyPointer(port);
        assertMapEntryReportsTheMapWithoutTheClientKey(port);
        assertEscapedWireNames(port);
        assertOmittedRootMemberIsNamed(port);
        assertOmittedMemberOfAListElementIsNamed(port);
        assertOmittedMemberOfANestedObjectIsNamed(port);
    }

    private void assertClassLevelConstraintOnTheRootReportsTheEmptyPointer(int port) {
        Reply reply = postJson(port, "/bodies/basket", validBasket().put("name", "conflict"));

        assertEquals(400, reply.status());
        assertPointer("", reply.only());
    }

    private void assertMapEntryReportsTheMapWithoutTheClientKey(int port) {
        Reply reply = postJson(
                port,
                "/bodies/basket",
                validBasket().put("byKey", new JsonObject().put("ZZCLIENTKEY", new JsonObject().put("name", ""))));

        assertEquals(400, reply.status());
        assertPointer("/byKey", reply.only());
        assertFalse(reply.raw().contains("ZZCLIENTKEY"), "the client's map key must not reach the response");
    }

    private void assertEscapedWireNames(int port) {
        Reply reply =
                postJson(port, "/bodies/basket", validBasket().put("a/b", "").put("t~x", ""));

        assertEquals(400, reply.status());
        assertPointers(Set.of("/a~1b", "/t~0x"), reply);
    }

    private void assertOmittedRootMemberIsNamed(int port) {
        JsonObject basket = validBasket();
        basket.remove("name");

        Reply reply = postJson(port, "/bodies/basket", basket);

        assertEquals(400, reply.status());
        assertPointer("/name", reply.only());
    }

    private void assertOmittedMemberOfAListElementIsNamed(int port) {
        Reply reply = postJson(
                port,
                "/bodies/basket",
                validBasket()
                        .put(
                                "items",
                                new JsonArray()
                                        .add(new JsonObject().put("name", "a"))
                                        .add(new JsonObject())));

        assertEquals(400, reply.status());
        assertPointer("/items/1/name", reply.only());
    }

    private void assertOmittedMemberOfANestedObjectIsNamed(int port) {
        Reply reply = postJson(port, "/bodies/basket", validBasket().put("owner", new JsonObject()));

        assertEquals(400, reply.status());
        assertPointer("/owner/name", reply.only());
    }

    // --- TP: form-urlencoded body ---

    @Test
    @DisplayName("A form-urlencoded POJO body is reported as a form parameter, never as a pointer")
    void formBodyReportsParameters() {
        // Route one is bound by the framework's form decoder; route two, under a snake_case profile
        // with an object processor bound, is bound by the extractor's form branch with the profile
        // mapper. Both name the form field by the wire name of the mapper that bound it.
        int decoderPort = deploy(
                b -> b.sortedDecoders(List.of(new FormUrlencodedRequestBodyDecoder(), new JsonRequestBodyDecoder())),
                new PointerResources.AddressResource());
        int processorPort = deploy(
                b -> b.objectProcessor(Optional.of(new RecordingProcessor())),
                new PointerResources.SnakeShippingResource());

        Reply decoded = postForm(decoderPort, "/addresses/form", "zip_code=");
        Reply processed = postForm(processorPort, "/shipping/snake/form", "zip_code=");

        assertEquals(400, decoded.status());
        assertFormParameter("zip_code", decoded.only());
        assertEquals(400, processed.status());
        assertFormParameter("zip_code", processed.only());
    }

    // --- TP: application decoder ---

    @Test
    @DisplayName("A body bound by an application decoder is reported without a location member")
    void applicationDecodedBodyOmitsThePointer() {
        int port = deploy(
                b -> b.sortedDecoders(List.of(new ApplicationJsonDecoder(), new JsonRequestBodyDecoder())),
                new PointerResources.AddressResource());

        Reply reply = postJson(port, "/addresses", "{\"zip_code\":\"\"}");

        assertEquals(400, reply.status());
        JsonObject entry = reply.only();
        assertWithoutLocationMembers(entry);
        assertEquals("body", entry.getString("location"));
        assertEquals("required", entry.getString("type"));
    }

    // --- TP: a raw collection no framework binder materializes ---

    @Test
    @DisplayName("A raw collection body no framework binder materialized is reported without a location member")
    void rawCollectionBodyOmitsThePointer() {
        int port = deploy(b -> {}, new PointerResources.RawCollectionResource());

        Reply reply = postJson(port, "/raw/list", "[]");

        assertEquals(400, reply.status());
        JsonObject entry = reply.only();
        assertWithoutLocationMembers(entry);
        assertEquals("body", entry.getString("location"));
        assertEquals("must not be empty", entry.getString("detail"), entry.encode());
    }

    // --- TP: the cap ---

    @Test
    @DisplayName("At most one hundred body violations are projected, the same hundred on every run")
    void atMostOneHundredBodyViolationsAreProjected() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());
        // Violations are ordered by the text of their Java path, so item 10 sorts before item 2.
        List<Integer> sortedPositions = IntStream.range(0, 150)
                .boxed()
                .sorted(Comparator.comparing(i -> "items[" + i + "].name"))
                .toList();
        List<String> expectedPointers = sortedPositions.stream()
                .limit(100)
                .map(i -> "/items/" + i + "/name")
                .toList();

        Reply first = postJson(port, "/bodies/cart", blankItems(150));
        Reply second = postJson(port, "/bodies/cart", blankItems(150));

        for (Reply reply : List.of(first, second)) {
            assertEquals(400, reply.status());
            List<JsonObject> errors = reply.errors();
            assertEquals(150, errors.size());
            for (int i = 0; i < 100; i++) {
                assertPointer(expectedPointers.get(i), errors.get(i));
            }
            for (int i = 100; i < 150; i++) {
                assertWithoutLocationMembers(errors.get(i));
                assertEquals("body", errors.get(i).getString("location"));
            }
        }
        assertEquals(first.json().getJsonArray("errors"), second.json().getJsonArray("errors"));
    }

    // --- TP: polymorphic root list ---

    @Test
    @DisplayName("A polymorphic root list body resolves through the element's runtime class")
    void polymorphicRootListBodyResolvesThroughTheRuntimeClass() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply reply = postJson(port, "/bodies/animals", "[{\"type\":\"dog\",\"woof\":\"\"}]");

        assertEquals(400, reply.status());
        assertPointer("/0/woof", reply.only());
    }

    // --- TP: field-bound properties ---

    @Test
    @DisplayName("Field-bound properties resolve when present and truncate to the parent when omitted")
    void fieldBoundPropertiesResolveWhenPresentAndTruncateWhenOmitted() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply present = postJson(port, "/bodies/public-address", "{\"zip_code\":\"\"}");
        Reply omittedPublicField = postJson(port, "/bodies/public-address", "{}");
        Reply omittedBehindSetter = postJson(port, "/bodies/renamed-setter", "{}");

        assertEquals(400, present.status());
        assertPointer("/zip_code", present.only());
        assertEquals(400, omittedPublicField.status());
        assertPointer("", omittedPublicField.only());
        assertEquals(400, omittedBehindSetter.status());
        assertPointer("", omittedBehindSetter.only());
    }

    // --- TP: every object-processor branch records the mark ---

    @Test
    @DisplayName("Every object-processor body branch records how it bound the body")
    void objectProcessorBranchesRecordTheBindingMark() {
        RecordingProcessor processor = new RecordingProcessor();
        int port = deploy(
                b -> b.objectProcessor(Optional.of(processor)),
                new PointerResources.AddressResource(),
                new PointerResources.ShapeResource());

        Reply jsonObject = postJson(port, "/addresses", "{\"zip_code\":\"\"}");
        assertEquals(1, processor.callsFor(Address.class), "the JSON object body must take the processor branch");
        Reply jsonArray = postJson(port, "/bodies/items", "[{\"name\":\"\"}]");
        assertEquals(1, processor.callsForParameterized(List.class), "the JSON array body must take the processor");
        Reply form = postForm(port, "/addresses/form", "zip_code=");
        assertEquals(2, processor.callsFor(Address.class), "the form body must take the processor branch");

        assertEquals(400, jsonObject.status());
        assertPointer("/zip_code", jsonObject.only());
        assertEquals(400, jsonArray.status());
        assertPointer("/0/name", jsonArray.only());
        assertEquals(400, form.status());
        assertFormParameter("zip_code", form.only());
    }

    @Test
    @DisplayName("An object-processor array body on a route whose profile is the process codec records the mark")
    void objectProcessorArrayBodyWithoutAProfileRecordsTheBindingMark() {
        // Given the process codec runs the vertique profile's mapper, so a route on that profile has
        // no profile override stashed and the array body is converted by the process mapper itself
        RecordingProcessor processor = new RecordingProcessor();
        VertiqueJson.resetForTests();
        try {
            VertiqueJson.install(JsonProfileId.of("vertique"), profiles.mapper(JsonProfileId.of("vertique")));
            int port = deploy(b -> b.objectProcessor(Optional.of(processor)), new PointerResources.ShapeResource());

            // When a JSON array body with an invalid element is posted to the @Valid List<Item> route
            Reply reply = postJson(port, "/bodies/items", "[{\"name\":\"\"}]");

            // Then the array took the object-processor branch and the violation is located in the body
            assertEquals(1, processor.callsForParameterized(List.class), "the JSON array body must take the processor");
            assertEquals(400, reply.status());
            assertPointer("/0/name", reply.only());
        } finally {
            VertiqueJson.resetForTests();
        }
    }

    // --- TP: container query parameter ---

    @Test
    @DisplayName("A container query parameter reports its element position through the real validator")
    void containerQueryParameterReportsItsIndex() {
        int port = deploy(b -> {}, new PointerResources.QueryResource());

        Reply reply = get(port, "/query?ids=5&ids=0");

        assertEquals(400, reply.status());
        JsonObject entry = reply.only();
        assertEquals("ids[1]", entry.getString("parameter"));
        assertEquals("query", entry.getString("location"));
        assertFalse(entry.containsKey("pointer"));
        assertFalse(entry.containsKey("path"));
    }

    // --- Wire examples ---

    @Test
    @DisplayName("A class-level constraint on a list element is reported at the element")
    void classLevelConstraintOnAListElementReportsTheElement() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply reply = postJson(port, "/bodies/ledger", "{\"entries\":[{\"label\":\"ok\"},{\"label\":\"conflict\"}]}");

        assertEquals(400, reply.status());
        assertPointer("/entries/1", reply.only());
    }

    @Test
    @DisplayName("A violated set member is reported at the set property")
    void setMemberReportsTheSetProperty() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply reply = postJson(port, "/bodies/tagged", "{\"tags\":[\"a\",\"\"]}");

        assertEquals(400, reply.status());
        assertPointer("/tags", reply.only());
    }

    @Test
    @DisplayName("Nested list positions are both reported")
    void nestedListsReportBothPositions() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply reply = postJson(port, "/bodies/grid", "{\"lists\":[[\"a\",\"\"]]}");

        assertEquals(400, reply.status());
        assertPointer("/lists/0/1", reply.only());
    }

    @Test
    @DisplayName("A root list body reports the element position and member")
    void rootListBodyElementReportsItsPositionAndMember() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply reply = postJson(port, "/bodies/items", "[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"\"}]");

        assertEquals(400, reply.status());
        assertPointer("/2/name", reply.only());
    }

    @Test
    @DisplayName("An alias the client sent is reported as the client spelled it")
    void aliasTheClientSentIsReported() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply reply = postJson(port, "/bodies/aliased", "{\"zip\":\"\"}");

        assertEquals(400, reply.status());
        assertPointer("/zip", reply.only());
    }

    @Test
    @DisplayName("A case variant the client sent is reported as the client spelled it")
    void caseVariantTheClientSentIsReported() {
        int port = deploy(b -> {}, new PointerResources.CaseInsensitiveShippingResource());

        Reply bound = postJson(port, "/shipping/insensitive", "{\"ZIPCODE\":\"90210\"}");
        Reply reply = postJson(port, "/shipping/insensitive", "{\"ZIPCODE\":\"\"}");

        assertEquals(200, bound.status(), "the upper-case key must bind for the fixture to mean anything");
        assertEquals(400, reply.status());
        assertPointer("/ZIPCODE", reply.only());
    }

    @Test
    @DisplayName("Two document keys for one property are reported at the parent")
    void twoDocumentKeysForOnePropertyReportTheParent() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply reply = postJson(port, "/bodies/aliased-bean", "{\"zipCode\":\"\",\"zip\":\"\"}");

        assertEquals(400, reply.status());
        assertPointer("", reply.only());
    }

    @Test
    @DisplayName("A list that skips null elements is reported at the list, not at a shifted position")
    void nullSkippingListReportsTheCollection() {
        int port = deploy(b -> {}, new PointerResources.ShapeResource());

        Reply reply = postJson(port, "/bodies/skipping", "{\"items\":[null,{\"name\":\"\"}]}");

        assertEquals(400, reply.status());
        assertPointer("/items", reply.only());
    }

    @Test
    @DisplayName("A member with the empty wire name is reported as the single slash")
    void emptyWireNameIsReportedAsSlash() {
        int port = deploy(b -> {}, new PointerResources.EmptyNameResource());

        Reply reply = postJson(port, "/blank-named", "{\"\":\"\"}");

        assertEquals(400, reply.status());
        assertPointer("/", reply.only());
    }

    @Test
    @DisplayName("A Java-name key on a renaming route is not a declared spelling: the omitted member is named")
    void javaNameKeyOnRenamingRouteNamesTheMissingLeaf() {
        int port = deploy(b -> {}, new PointerResources.SnakeShippingResource());

        Reply reply = postJson(port, "/shipping/snake", "{\"zipCode\":\"\"}");

        assertEquals(400, reply.status());
        assertPointer("/zip_code", reply.only());
    }

    @Test
    @DisplayName("The declared key is reported when a stray Java-name key travels beside it")
    void declaredKeyBesideStrayJavaNameKeyIsReported() {
        int port = deploy(b -> {}, new PointerResources.SnakeShippingResource());

        Reply reply = postJson(port, "/shipping/snake", "{\"zip_code\":\"\",\"zipCode\":\"x\"}");

        assertEquals(400, reply.status());
        assertPointer("/zip_code", reply.only());
    }

    // --- Assertions on raw entry members ---

    /** Asserts a body entry located by {@code pointer} and by nothing else. */
    private static void assertPointer(String expected, JsonObject entry) {
        assertTrue(entry.containsKey("pointer"), "expected a pointer member in " + entry.encode());
        assertEquals(expected, entry.getString("pointer"), entry.encode());
        assertFalse(entry.containsKey("parameter"), "pointer and parameter are never both set: " + entry.encode());
        assertFalse(entry.containsKey("path"), "the path member must be absent: " + entry.encode());
        assertEquals("body", entry.getString("location"), entry.encode());
    }

    /** Asserts that every entry is a pointer-located body entry and that the pointers are exactly these. */
    private static void assertPointers(Set<String> expected, Reply reply) {
        List<JsonObject> errors = reply.errors();
        assertEquals(expected.size(), errors.size(), reply.raw());
        Set<String> actual = new java.util.HashSet<>();
        for (JsonObject entry : errors) {
            assertTrue(entry.containsKey("pointer"), "expected a pointer member in " + entry.encode());
            assertFalse(entry.containsKey("parameter"), entry.encode());
            assertFalse(entry.containsKey("path"), "the path member must be absent: " + entry.encode());
            assertEquals("body", entry.getString("location"), entry.encode());
            actual.add(entry.getString("pointer"));
        }
        assertEquals(expected, actual, reply.raw());
    }

    /** Asserts a form body entry located by {@code parameter} and by nothing else. */
    private static void assertFormParameter(String expected, JsonObject entry) {
        assertTrue(entry.containsKey("parameter"), "expected a parameter member in " + entry.encode());
        assertEquals(expected, entry.getString("parameter"), entry.encode());
        assertFalse(entry.containsKey("pointer"), "pointer and parameter are never both set: " + entry.encode());
        assertFalse(entry.containsKey("path"), "the path member must be absent: " + entry.encode());
        assertEquals("form", entry.getString("location"), entry.encode());
    }

    /** Asserts an entry that carries none of {@code pointer}, {@code parameter} and {@code path}. */
    private static void assertWithoutLocationMembers(JsonObject entry) {
        assertFalse(entry.containsKey("pointer"), "no pointer expected in " + entry.encode());
        assertFalse(entry.containsKey("parameter"), "no parameter expected in " + entry.encode());
        assertFalse(entry.containsKey("path"), "the path member must be absent: " + entry.encode());
    }

    // --- Request fixtures ---

    /** A basket every constraint of which holds; a test breaks exactly the member it targets. */
    private static JsonObject validBasket() {
        return new JsonObject().put("a/b", "x").put("t~x", "y").put("name", "n");
    }

    /** A cart body holding {@code n} items whose required name is blank. */
    private static JsonObject blankItems(int n) {
        JsonArray items = new JsonArray();
        for (int i = 0; i < n; i++) {
            items.add(new JsonObject().put("name", ""));
        }
        return new JsonObject().put("items", items);
    }

    // --- HTTP helpers ---

    /** A response: status and the raw body text. */
    private record Reply(int status, String raw) {

        JsonObject json() {
            return new JsonObject(raw);
        }

        List<JsonObject> errors() {
            JsonArray errors = json().getJsonArray("errors");
            assertTrue(errors != null, "expected an errors array in " + raw);
            return errors.stream().map(e -> (JsonObject) e).toList();
        }

        JsonObject only() {
            List<JsonObject> errors = errors();
            assertEquals(1, errors.size(), raw);
            return errors.get(0);
        }
    }

    private static Reply postJson(int port, String path, Object body) {
        Buffer buffer = Buffer.buffer(body instanceof JsonObject json ? json.encode() : String.valueOf(body));
        return send(client.post(port, HOST, path).putHeader("Content-Type", "application/json"), buffer);
    }

    private static Reply postForm(int port, String path, String form) {
        return send(
                client.post(port, HOST, path).putHeader("Content-Type", "application/x-www-form-urlencoded"),
                Buffer.buffer(form));
    }

    private static Reply get(int port, String pathAndQuery) {
        Future<Reply> reply = client.get(port, HOST, pathAndQuery)
                .send()
                .map(response -> new Reply(response.statusCode(), String.valueOf(response.bodyAsString())));
        return await(reply);
    }

    private static Reply send(io.vertx.ext.web.client.HttpRequest<Buffer> request, Buffer body) {
        Future<Reply> reply = request.sendBuffer(body)
                .map(response -> new Reply(response.statusCode(), String.valueOf(response.bodyAsString())));
        return await(reply);
    }

    /**
     * Mounts the resources under the real bean validator and the shared profile registry, starts a
     * server bound to the loopback address and returns its port. The server is closed after the test.
     *
     * @param customizer adjusts the factory builder (decoders, object processor)
     * @param resources  the JAX-RS resources to mount
     * @return the bound port
     */
    private int deploy(Consumer<TestFactories.Builder> customizer, Object... resources) {
        TestFactories.Builder builder =
                TestFactories.builder().beanValidator(Optional.of(validator)).jsonMapperProfileRegistry(profiles);
        customizer.accept(builder);
        JaxRsRouterMount mount = builder.build().create("/*", "openapi.json", Set.of(resources));
        HttpServer server = await(mount.createRouter(vertx).compose(apiRouter -> {
            Router root = Router.router(vertx);
            root.route("/*").subRouter(apiRouter);
            return vertx.createHttpServer().requestHandler(root).listen(0, HOST);
        }));
        servers.add(server);
        return server.actualPort();
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.toCompletionStage().toCompletableFuture().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("request did not complete: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
