// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.rest.openapi.docs.DisclosureDocuments.Rendering;
import dev.vertique.rest.openapi.docs.MetadataDocuments.WarningCapture;
import dev.vertique.rest.openapi.docs.config.EnabledDocuments;
import dev.vertique.rest.openapi.docs.diagnostics.DocumentWarnings;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.BothLicenseApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.ChildInfoApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.FullInfoWithContactExtensionApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.InfoRegistrations;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.MixedExtensionApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.OwnInfoApi;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.SubclassInfoApplication;
import dev.vertique.rest.openapi.docs.fixture.metadata.info.UrlLicenseApi;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The complete {@code info} of a document: every member of the declaring interface's own
 * {@code @OpenAPIDefinition(info)} in the OpenAPI field order, {@code x-} extensions only with one
 * warning per document for any other key, the {@code identifier}-and-{@code url} license refusal,
 * no member read from a superinterface, and a configured {@code info} replacing the annotation.
 *
 * <p>Each case resolves one enabled document {@value InfoRegistrations#CHILD_NAME} through the
 * configuration and enabled-document providers, then assembles it over a publication without
 * operations with a fresh warning guard standing for one component.
 */
@DisplayName("A document's complete info")
class DocumentInfoTest {

    private static final String APPLICATION = InfoRegistrations.CHILD_NAME;

    private static final String DOCUMENT_PATH = "apidocs.documents." + APPLICATION;

    private final WarningCapture capture = new WarningCapture();

    @BeforeEach
    void attachCapture() {
        capture.attach();
    }

    @AfterEach
    void detachCapture() {
        capture.detach();
    }

    /** One case of the proof, run against the attached warning capture. */
    @FunctionalInterface
    interface InfoCase {
        void verify(WarningCapture capture);
    }

    static Stream<Arguments> infoCases() {
        return Stream.of(
                Arguments.of("every member is published in the OpenAPI field order and nothing is logged", (InfoCase)
                        DocumentInfoTest::fullInfoFollowsFieldOrder),
                Arguments.of("a license with a url publishes exactly its name and url", (InfoCase)
                        DocumentInfoTest::urlLicenseIsPublishedAsDeclared),
                Arguments.of(
                        "only x- extensions are published and the other keys are warned about once per document",
                        (InfoCase) DocumentInfoTest::nonExtensionKeysAreWarnedOnce),
                Arguments.of("an info only on the superinterface is not read and fails naming the setting", (InfoCase)
                        DocumentInfoTest::superinterfaceInfoIsNotRead),
                Arguments.of("an own info publishes none of the superinterface's members", (InfoCase)
                        DocumentInfoTest::ownInfoIgnoresTheSuperinterface),
                Arguments.of("a license with both identifier and url fails without echoing either", (InfoCase)
                        DocumentInfoTest::licenseWithIdentifierAndUrlFails),
                Arguments.of("a configured info replaces the annotation and its license rule", (InfoCase)
                        DocumentInfoTest::configuredInfoReplacesTheAnnotation),
                Arguments.of("an info inherited from a superclass is not read and fails naming the setting", (InfoCase)
                        DocumentInfoTest::superclassInfoIsNotRead));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("infoCases")
    @DisplayName("Follows the field order, publishes x- extensions only, and reads only the declaring interface")
    void completeInfoFollowsFieldOrderAndReadsOnlyTheDeclaringInterface(String scenario, InfoCase infoCase) {
        infoCase.verify(capture);
    }

    // ---------------------------------------------------------------------------------------------
    // Cases
    // ---------------------------------------------------------------------------------------------

    private static void fullInfoFollowsFieldOrder(WarningCapture capture) {
        // Given child declared by an interface whose own info sets every member, with an x- extension
        // on the info and on its contact, and no configured info
        EnabledDocuments.EnabledDocument document = MetadataDocuments.resolve(
                        InfoRegistrations.childApi(FullInfoWithContactExtensionApi.class), null)
                .document();

        // When the document is assembled
        Rendering rendering = MetadataDocuments.assembleResolved(document, MetadataDocuments.warnings())
                .rendering();

        // Then the info is exactly the contract text, in the OpenAPI field order, and nothing is logged
        assertEquals(
                "{\"title\":\"Catalog API\",\"summary\":\"Catalog\",\"description\":\"Items\","
                        + "\"termsOfService\":\"https://example.test/terms\","
                        + "\"contact\":{\"name\":\"Ops\",\"url\":\"https://example.test/ops\","
                        + "\"email\":\"ops@example.test\",\"x-team\":{\"channel\":\"ops\"}},"
                        + "\"license\":{\"name\":\"Apache 2.0\",\"identifier\":\"Apache-2.0\"},"
                        + "\"version\":\"2.1\",\"x-audience\":{\"tier\":\"public\"}}",
                MetadataDocuments.infoJson(rendering));
        assertEquals(List.of(), capture.warnings());
    }

    private static void urlLicenseIsPublishedAsDeclared(WarningCapture capture) {
        // Given child declared by an interface whose info carries a license with a name and a url
        EnabledDocuments.EnabledDocument document = MetadataDocuments.resolve(
                        InfoRegistrations.childApi(UrlLicenseApi.class), null)
                .document();

        // When the document is assembled
        Rendering rendering = MetadataDocuments.assembleResolved(document, MetadataDocuments.warnings())
                .rendering();

        // Then the license is exactly its name and url, without any default member
        assertEquals(
                "{\"name\":\"MIT\",\"url\":\"https://example.test/mit\"}",
                String.valueOf(MetadataDocuments.info(rendering).get("license")),
                () -> "info: " + MetadataDocuments.infoJson(rendering));
    }

    private static void nonExtensionKeysAreWarnedOnce(WarningCapture capture) {
        // Given child declared by an interface whose info carries an x- extension and a key without
        // the x- prefix, and whose contact carries another key without it
        EnabledDocuments.EnabledDocument document = MetadataDocuments.resolve(
                        InfoRegistrations.childApi(MixedExtensionApi.class), null)
                .document();
        DocumentWarnings component = MetadataDocuments.warnings();

        // When the document is assembled twice by one component
        Rendering first =
                MetadataDocuments.assembleResolved(document, component).rendering();
        Rendering second =
                MetadataDocuments.assembleResolved(document, component).rendering();

        // Then each assembly publishes the x- extension and none of the other keys or their values
        for (Rendering rendering : List.of(first, second)) {
            JsonNode audience = MetadataDocuments.info(rendering).get("x-audience");
            assertEquals(
                    "{\"tier\":\"public\"}",
                    String.valueOf(audience),
                    () -> "info: " + MetadataDocuments.infoJson(rendering));
            for (String absent : List.of("audienceZx", "contactZx", "valueQv")) {
                assertFalse(rendering.jsonText().contains(absent), () -> absent + " in " + rendering.jsonText());
                assertFalse(rendering.yamlText().contains(absent), () -> absent + " in " + rendering.yamlText());
            }
        }

        // And exactly one warning is logged, naming the document, the application, the declaring
        // interface and both keys in sorted order, and quoting no value
        List<String> warnings = capture.warnings();
        assertEquals(1, warnings.size(), () -> "warnings: " + warnings);
        String warning = warnings.get(0);
        assertTrue(warning.startsWith(DOCUMENT_PATH), warning);
        String afterPath = warning.substring(DOCUMENT_PATH.length());
        assertTrue(afterPath.contains(APPLICATION), warning);
        assertTrue(warning.contains(MixedExtensionApi.class.getName()), warning);
        int audienceKey = warning.indexOf("audienceZx");
        int contactKey = warning.indexOf("contactZx");
        assertTrue(audienceKey >= 0, warning);
        assertTrue(contactKey > audienceKey, warning);
        assertFalse(warning.contains("valueQv"), warning);
    }

    private static void superinterfaceInfoIsNotRead(WarningCapture capture) {
        // Given child declared by an interface without an info of its own whose superinterface carries
        // a complete one, and no configured info
        MetadataDocuments.Resolution resolution =
                MetadataDocuments.resolve(InfoRegistrations.childApi(ChildInfoApi.class), null);

        // When the enabled documents are resolved
        RuntimeException failure = resolution.failure();

        // Then resolution fails naming the info setting and the declaring interface, and no message
        // carries the superinterface's members
        ConfigurationException configuration = assertInstanceOf(ConfigurationException.class, failure);
        String message = configuration.getMessage();
        assertTrue(message.contains(DOCUMENT_PATH + ".info"), message);
        assertTrue(message.contains(ChildInfoApi.class.getName()), message);
        for (String text : messages(failure, capture)) {
            assertFalse(text.contains("ParentZx"), text);
        }
    }

    private static void ownInfoIgnoresTheSuperinterface(WarningCapture capture) {
        // Given child declared by an interface carrying its own info whose superinterface carries a
        // complete one, and no configured info
        EnabledDocuments.EnabledDocument document = MetadataDocuments.resolve(
                        InfoRegistrations.childApi(OwnInfoApi.class), null)
                .document();

        // When the document is assembled
        Rendering rendering = MetadataDocuments.assembleResolved(document, MetadataDocuments.warnings())
                .rendering();

        // Then the info is exactly the declaring interface's own members
        assertEquals(
                "{\"title\":\"Own\",\"contact\":{\"name\":\"OwnContact\"},\"version\":\"2\"}",
                MetadataDocuments.infoJson(rendering));
    }

    private static void licenseWithIdentifierAndUrlFails(WarningCapture capture) {
        // Given child declared by an interface whose info carries a license with both an identifier
        // and a url, and no configured info
        MetadataDocuments.Resolution resolution =
                MetadataDocuments.resolve(InfoRegistrations.childApi(BothLicenseApi.class), null);

        // When the enabled documents are resolved
        RuntimeException failure = resolution.failure();

        // Then resolution fails before any document exists, naming the application, the declaring
        // interface and the license member, and echoing neither value
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains(APPLICATION), message);
        assertTrue(message.contains(BothLicenseApi.class.getName()), message);
        assertTrue(message.contains("@OpenAPIDefinition.info.license"), message);
        for (String text : messages(failure, capture)) {
            assertFalse(text.contains("IdZx"), text);
            assertFalse(text.contains("urlzx"), text);
        }
    }

    private static void configuredInfoReplacesTheAnnotation(WarningCapture capture) {
        // Given child declared by an interface whose info carries a license with both an identifier
        // and a url, and a configured info with a title and a version
        JsonObject configured = new JsonObject().put("title", "Configured").put("version", "9");
        EnabledDocuments.EnabledDocument document = MetadataDocuments.resolve(
                        InfoRegistrations.childApi(BothLicenseApi.class), configured)
                .document();

        // When the document is assembled
        Rendering rendering = MetadataDocuments.assembleResolved(document, MetadataDocuments.warnings())
                .rendering();

        // Then the info is exactly the configured one
        assertEquals("{\"title\":\"Configured\",\"version\":\"9\"}", MetadataDocuments.infoJson(rendering));
    }

    private static void superclassInfoIsNotRead(WarningCapture capture) {
        // Given child declared by a class without an info of its own whose superclass carries one, which
        // reflection reports as inherited, and no configured info
        MetadataDocuments.Resolution resolution =
                MetadataDocuments.resolve(InfoRegistrations.childApi(SubclassInfoApplication.class), null);

        // When the enabled documents are resolved
        RuntimeException failure = resolution.failure();

        // Then resolution fails naming the info setting and the declaring class, and no message carries
        // the superclass's title
        ConfigurationException configuration = assertInstanceOf(ConfigurationException.class, failure);
        String message = configuration.getMessage();
        assertTrue(message.contains(DOCUMENT_PATH + ".info"), message);
        assertTrue(message.contains(SubclassInfoApplication.class.getName()), message);
        for (String text : messages(failure, capture)) {
            assertFalse(text.contains("SuperZx"), text);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /** Returns the messages of a failure and its causes, then every captured warning. */
    private static List<String> messages(@Nullable Throwable failure, WarningCapture capture) {
        List<String> texts = new ArrayList<>();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            texts.add(String.valueOf(current.getMessage()));
            if (current.getCause() == current) {
                break;
            }
        }
        texts.addAll(capture.warnings());
        return texts;
    }
}
