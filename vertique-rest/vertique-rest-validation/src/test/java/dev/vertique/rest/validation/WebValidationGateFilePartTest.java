// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamDescriptor;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies synchronous file-part validation performed by the web-validation gate. */
class WebValidationGateFilePartTest {

    private static final List<String> PNG_ONLY = List.of("image/png");

    private final WebValidationStrategy strategy = strategyWith("aggregate");

    @Test
    @DisplayName("A descriptor carrying only file-part constraints installs a validation gate")
    void gateInstalledForFileOnlyConstraints() {
        JaxRsOperationDescriptor descriptor =
                descriptor(List.of(), List.of(new FilePartDescriptor("avatar", PNG_ONLY, -1)));

        assertTrue(
                strategy.gateFor(descriptor, OperationSchemas.empty()).isPresent(),
                "file-part constraints must install a gate even without body or parameter schemas");
    }

    @Test
    @DisplayName("A file exceeding maxSizeBytes fails with the complete fileMaxSize detail")
    void oversizedPartFails() {
        Handler<RoutingContext> gate = gate(
                strategy,
                List.of(),
                List.of(new FilePartDescriptor("avatar", List.of(), 4096)),
                OperationSchemas.empty());
        RoutingContext ctx = context(List.of(upload("avatar", "image/png", 4097)), null, null);

        gate.handle(ctx);

        assertEquals(
                List.of(new ValidationErrorDetail(
                        "avatar",
                        "file part exceeds the maximum allowed size",
                        "file",
                        "fileMaxSize",
                        Map.of("maxSizeBytes", 4096L))),
                captureFailure(ctx).errors());
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("A wildcard declared content type is rejected as malformed")
    void wildcardDeclaredTypeRejected() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor("avatar", PNG_ONLY, -1));
        RoutingContext ctx = context(List.of(upload("avatar", "image/*", 8)), null, null);

        gate.handle(ctx);

        assertEquals(
                List.of(fileError(
                        "avatar",
                        "file part declares a malformed or wildcard content type",
                        "fileContentTypeMalformed")),
                captureFailure(ctx).errors());
    }

    @Test
    @DisplayName("Malformed declared content types are rejected before compatibility matching")
    void malformedDeclaredTypesRejectedBeforeMatching() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor("avatar", PNG_ONLY, -1));

        for (String malformed : List.of(
                "image/png;garbage",
                "image / png",
                "image/png/extra",
                "image/\u0001png",
                "image/png; charset =utf-8",
                "image/png; charset= utf-8",
                "image/png; charset\t=utf-8",
                "image/png; charset=\tutf-8",
                "image/png; charset=",
                "image/png; charset=\"binary",
                "image/png; charset=\"binary\\",
                "image/png; charset=\"safe\"junk",
                "image/png; charset=\"binary" + (char) 1 + "\"",
                "image/png; charset=\"binary\\" + (char) 1 + "\"",
                "image/png; charset=\"" + (char) 0x100 + "\"",
                "image/png; charset=\"\\" + (char) 0x100 + "\"")) {
            RoutingContext ctx = context(List.of(upload("avatar", malformed, 8)), null, null);

            gate.handle(ctx);

            assertEquals(
                    List.of(fileError(
                            "avatar",
                            "file part declares a malformed or wildcard content type",
                            "fileContentTypeMalformed")),
                    captureFailure(ctx).errors());
        }
    }

    @Test
    @DisplayName("RFC-valid parameter forms remain compatible")
    void validParameterizedDeclaredTypeAccepted() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor("avatar", PNG_ONLY, -1));

        for (String valid : List.of(
                "IMAGE/PNG; charset=utf-8",
                "IMAGE/PNG; charset=\"binary;safe\"",
                "IMAGE/PNG; charset=\"binary\\\"safe\"",
                "IMAGE/PNG;;; charset=utf-8;",
                "IMAGE/PNG \t; \t; charset=utf-8 \t; \t",
                "IMAGE/PNG; charset=\"" + (char) 0xFF + "\"",
                "IMAGE/PNG; charset=\"\\" + (char) 0xFF + "\"")) {
            RoutingContext ctx = context(List.of(upload("avatar", valid, 8)), null, null);

            gate.handle(ctx);

            verify(ctx).next();
            verify(ctx, never()).fail(any(Throwable.class));
        }
    }

    @Test
    @DisplayName("An absent or blank declared content type is rejected as missing")
    void absentDeclaredTypeRejected() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor("avatar", PNG_ONLY, -1));

        for (String missing : new String[] {null, "   "}) {
            RoutingContext ctx = context(List.of(upload("avatar", missing, 8)), null, null);

            gate.handle(ctx);

            assertEquals(
                    List.of(fileError("avatar", "file part declares no content type", "fileContentTypeMissing")),
                    captureFailure(ctx).errors());
        }
    }

    @Test
    @DisplayName("Aggregate constraints apply to every uploaded file regardless of part name")
    void aggregateAppliesToAllParts() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor(null, PNG_ONLY, -1));
        RoutingContext ctx = context(
                List.of(upload("avatar", "application/pdf", 8), upload("document", "text/plain", 8)), null, null);

        gate.handle(ctx);

        assertEquals(
                List.of(
                        fileError("avatar", "file part content type is not allowed", "fileContentTypeNotAllowed"),
                        fileError("document", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                captureFailure(ctx).errors());
    }

    @Test
    @DisplayName("Every same-name physical upload is validated")
    void sameNameDuplicatesAllValidated() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor("avatar", PNG_ONLY, -1));
        RoutingContext ctx =
                context(List.of(upload("avatar", "application/pdf", 8), upload("avatar", "text/plain", 8)), null, null);

        gate.handle(ctx);

        assertEquals(
                List.of(
                        fileError("avatar", "file part content type is not allowed", "fileContentTypeNotAllowed"),
                        fileError("avatar[1]", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                captureFailure(ctx).errors());
    }

    @Test
    @DisplayName("A violation on the second same-name upload has the occurrence-indexed pointer")
    void duplicateOccurrencePointerIndexed() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor("avatar", PNG_ONLY, -1));
        RoutingContext ctx =
                context(List.of(upload("avatar", "image/png", 8), upload("avatar", "application/pdf", 8)), null, null);

        gate.handle(ctx);

        assertEquals(
                List.of(fileError("avatar[1]", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                captureFailure(ctx).errors());
    }

    @Test
    @DisplayName("Named and aggregate parameter exposure validates a physical upload once")
    void namedAndAggregateExposureValidatesOnce() {
        Handler<RoutingContext> gate = gate(
                strategy,
                List.of(),
                List.of(new FilePartDescriptor("avatar", PNG_ONLY, -1), new FilePartDescriptor(null, List.of(), -1)),
                OperationSchemas.empty());
        RoutingContext ctx = context(List.of(upload("avatar", "application/pdf", 8)), null, null);

        gate.handle(ctx);

        assertEquals(
                List.of(fileError("avatar", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                captureFailure(ctx).errors());
    }

    @Test
    @DisplayName("An unconstrained duplicate never erases the named constraint regardless of declaration order")
    void unconstrainedDuplicateDoesNotEraseNamedConstraint() {
        FilePartDescriptor constrained = new FilePartDescriptor("avatar", PNG_ONLY, -1);
        FilePartDescriptor unconstrained = new FilePartDescriptor("avatar", List.of(), -1);

        for (List<FilePartDescriptor> fileParts :
                List.of(List.of(constrained, unconstrained), List.of(unconstrained, constrained))) {
            Handler<RoutingContext> gate = gate(strategy, List.of(), fileParts, OperationSchemas.empty());
            RoutingContext ctx = context(List.of(upload("avatar", "application/pdf", 8)), null, null);

            gate.handle(ctx);

            assertEquals(
                    List.of(fileError("avatar", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                    captureFailure(ctx).errors());
        }
    }

    @Test
    @DisplayName("An unconstrained named descriptor falls through to the constrained aggregate")
    void unconstrainedNamedDescriptorFallsThroughToAggregateConstraint() {
        Handler<RoutingContext> gate = gate(
                strategy,
                List.of(),
                List.of(new FilePartDescriptor("avatar", List.of(), -1), new FilePartDescriptor(null, PNG_ONLY, -1)),
                OperationSchemas.empty());
        RoutingContext ctx = context(List.of(upload("avatar", "application/pdf", 8)), null, null);

        gate.handle(ctx);

        assertEquals(
                List.of(fileError("avatar", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                captureFailure(ctx).errors());
    }

    @Test
    @DisplayName("Content-type errors retain the descriptor's canonical allowed-type strings")
    void contentTypeErrorRetainsCanonicalAllowedTypes() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor("avatar", List.of("Image/PNG"), -1));
        RoutingContext ctx = context(List.of(upload("avatar", "application/pdf", 8)), null, null);

        gate.handle(ctx);

        assertEquals(
                List.of(fileError("avatar", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                captureFailure(ctx).errors());
    }

    @Test
    @DisplayName("Fail-fast mode stops after the first file-part violation")
    void failFastStopsAtFirstFileViolation() {
        Handler<RoutingContext> gate = fileGate(strategyWith("failFast"), new FilePartDescriptor(null, PNG_ONLY, -1));
        RoutingContext ctx = context(
                List.of(upload("avatar", "application/pdf", 8), upload("document", "text/plain", 8)), null, null);

        gate.handle(ctx);

        assertEquals(
                List.of(fileError("avatar", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                captureFailure(ctx).errors());
    }

    @Test
    @DisplayName("Aggregate mode collects query-parameter and file-part failures")
    void aggregateCollectsParamAndFileFailures() {
        ParamDescriptor limit =
                new ParamDescriptor("limit", ParamLocation.QUERY, Integer.class, null, null, null, List.of());
        OperationSchemas schemas = OperationSchemas.builder()
                .parameterSchema(
                        ParamLocation.QUERY,
                        "limit",
                        new JsonObject().put("type", "integer").put("maximum", 100))
                .build();
        Handler<RoutingContext> gate =
                gate(strategy, List.of(limit), List.of(new FilePartDescriptor("avatar", PNG_ONLY, -1)), schemas);
        MultiMap query = MultiMap.caseInsensitiveMultiMap().add("limit", "500");
        RoutingContext ctx = context(List.of(upload("avatar", "application/pdf", 8)), query, null);

        gate.handle(ctx);

        assertEquals(
                List.of(
                        new ValidationErrorDetail(
                                "limit", "must be at most 100", "query", "maximum", Map.of("maximum", 100)),
                        fileError("avatar", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                captureFailure(ctx).errors());
    }

    @Test
    @DisplayName("Aggregate file constraints never apply to text form fields")
    void textFieldExemptFromAggregateConstraints() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor(null, PNG_ONLY, 1));
        MultiMap formAttributes = MultiMap.caseInsensitiveMultiMap().add("avatar", "not-a-file");
        RoutingContext ctx = context(List.of(), null, formAttributes);

        gate.handle(ctx);

        verify(ctx).next();
        verify(ctx, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("File validation details never expose submitted or filesystem metadata")
    void detailNeverEchoesFilenameTempPathOrDeclaredType() {
        Handler<RoutingContext> gate = fileGate(strategy, new FilePartDescriptor("avatar", PNG_ONLY, -1));
        FileUpload upload = upload("avatar", "application/x-private-declared-type", 987_654_321L);
        when(upload.fileName()).thenReturn("private-client-filename.exe");
        when(upload.uploadedFileName()).thenReturn("/tmp/private-upload-path");
        RoutingContext ctx = context(List.of(upload), null, null);

        gate.handle(ctx);

        RestValidationException failure = captureFailure(ctx);
        assertEquals(
                List.of(fileError("avatar", "file part content type is not allowed", "fileContentTypeNotAllowed")),
                failure.errors());
        String serialized = Json.encode(failure.errors());
        assertFalse(serialized.contains("private-client-filename.exe"));
        assertFalse(serialized.contains("/tmp/private-upload-path"));
        assertFalse(serialized.contains("application/x-private-declared-type"));
        assertFalse(serialized.contains("987654321"));
    }

    private static Handler<RoutingContext> fileGate(WebValidationStrategy strategy, FilePartDescriptor filePart) {
        return gate(strategy, List.of(), List.of(filePart), OperationSchemas.empty());
    }

    private static Handler<RoutingContext> gate(
            WebValidationStrategy strategy,
            List<ParamDescriptor> parameters,
            List<FilePartDescriptor> fileParts,
            OperationSchemas schemas) {
        return strategy.gateFor(descriptor(parameters, fileParts), schemas).orElseThrow();
    }

    private static WebValidationStrategy strategyWith(String validationMode) {
        return new WebValidationStrategy(
                JaxRsConfig.builder().validationMode(validationMode).build());
    }

    private static FileUpload upload(String name, String contentType, long size) {
        FileUpload upload = mock(FileUpload.class);
        when(upload.name()).thenReturn(name);
        when(upload.fileName()).thenReturn(name + ".bin");
        when(upload.uploadedFileName()).thenReturn("target/" + name + ".upload");
        when(upload.contentType()).thenReturn(contentType);
        when(upload.size()).thenReturn(size);
        return upload;
    }

    private static RoutingContext context(List<FileUpload> uploads, MultiMap query, MultiMap formAttributes) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        Map<String, Object> data = new HashMap<>();
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(query != null ? query : MultiMap.caseInsensitiveMultiMap());
        when(ctx.fileUploads()).thenReturn(uploads);
        when(ctx.body()).thenReturn(null);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.<Cookie>of());
        when(request.formAttributes())
                .thenReturn(formAttributes != null ? formAttributes : MultiMap.caseInsensitiveMultiMap());
        when(ctx.get(anyString())).thenAnswer(invocation -> data.get(invocation.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(invocation -> {
            data.put(invocation.getArgument(0, String.class), invocation.getArgument(1));
            return ctx;
        });
        return ctx;
    }

    private static RestValidationException captureFailure(RoutingContext ctx) {
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).fail(failure.capture());
        return assertInstanceOf(RestValidationException.class, failure.getValue());
    }

    private static ValidationErrorDetail fileError(String path, String detail, String type) {
        return new ValidationErrorDetail(path, detail, "file", type, Map.of("allowedTypes", PNG_ONLY));
    }

    private static JaxRsOperationDescriptor descriptor(
            List<ParamDescriptor> parameters, List<FilePartDescriptor> fileParts) {
        return StubDescriptors.builder()
                .operationId("uploadAvatar")
                .httpMethod("POST")
                .routeTemplate("/files")
                .consumes(List.of("multipart/form-data"))
                .parameters(parameters)
                .fileParts(fileParts)
                .build();
    }
}
