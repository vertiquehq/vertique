// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.RestValidationException;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.jaxrs.convert.ConversionContexts;
import dev.vertique.rest.jaxrs.routing.FilePartDescriptor;
import dev.vertique.rest.jaxrs.routing.JaxRsOperationDescriptor;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.FileVerificationResult;
import dev.vertique.rest.jaxrs.validation.OperationSchemas;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies asynchronous deep verification of physical multipart uploads. */
class WebValidationGateVerifierTest {

    @Test
    @DisplayName("All bound verifiers run for every physical upload in OrderedExtension order")
    void verifiersRunInComparatorOrder() {
        List<String> calls = new ArrayList<>();
        FileContentVerifier last = new RecordingVerifier("last", ExtensionPhase.SYSTEM_LAST, -100, calls);
        FileContentVerifier application = new RecordingVerifier("application", ExtensionPhase.APPLICATION, 0, calls);
        FileContentVerifier first = new RecordingVerifier("first", ExtensionPhase.SYSTEM_FIRST, 100, calls);
        Handler<RoutingContext> gate =
                gate(Set.of(last, application, first), List.of(new FilePartDescriptor(null, List.of(), -1)));
        RoutingContext ctx = context(List.of(upload("avatar"), upload("document")));

        gate.handle(ctx);

        assertEquals(
                List.of(
                        "first:avatar",
                        "application:avatar",
                        "last:avatar",
                        "first:document",
                        "application:document",
                        "last:document"),
                calls);
        verify(ctx).next();
        verify(ctx, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("A verifier that does not apply accepts immediately and permits the request")
    void nonApplicableVerifierAcceptedResultPasses() {
        FileContentVerifier verifier = verifierReturning(Future.succeededFuture(FileVerificationResult.accepted()));
        Handler<RoutingContext> gate = gate(Set.of(verifier), List.of(new FilePartDescriptor(null, List.of(), -1)));
        FileUpload upload = upload("avatar");
        RoutingContext ctx = context(List.of(upload));

        gate.handle(ctx);

        verify(verifier).verify(upload);
        verify(ctx).next();
        verify(ctx, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("A synchronous verifier throw is an infrastructure failure")
    void syncThrowFromVerifyMapsTo500() {
        IllegalStateException boom = new IllegalStateException("scanner unavailable");
        FileContentVerifier verifier = mock(FileContentVerifier.class);
        when(verifier.verify(any(FileUpload.class))).thenThrow(boom);
        Handler<RoutingContext> gate = gate(Set.of(verifier), List.of(new FilePartDescriptor(null, List.of(), -1)));
        RoutingContext ctx = context(List.of(upload("avatar")));

        gate.handle(ctx);

        assertSame(boom, captureFailure(ctx));
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("A null verifier Future is an infrastructure failure")
    void nullFutureMapsTo500() {
        FileContentVerifier verifier = verifierReturning(null);
        Handler<RoutingContext> gate = gate(Set.of(verifier), List.of(new FilePartDescriptor(null, List.of(), -1)));
        RoutingContext ctx = context(List.of(upload("avatar")));

        gate.handle(ctx);

        assertInfrastructureFailure(captureFailure(ctx));
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("A verifier Future completing with null is an infrastructure failure")
    void nullResultMapsTo500() {
        FileContentVerifier verifier = verifierReturning(Future.succeededFuture((FileVerificationResult) null));
        Handler<RoutingContext> gate = gate(Set.of(verifier), List.of(new FilePartDescriptor(null, List.of(), -1)));
        RoutingContext ctx = context(List.of(upload("avatar")));

        gate.handle(ctx);

        assertInfrastructureFailure(captureFailure(ctx));
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("A rejected verification result maps verbatim to a pointed 400 detail")
    void rejectedResultMapsTo400WithPointer() {
        Map<String, Object> args = Map.of("rule", "no-macros", "score", 9);
        FileContentVerifier verifier = verifierReturning(Future.succeededFuture(
                FileVerificationResult.rejected("application policy rejected the upload", "macroPolicy", args)));
        Handler<RoutingContext> gate = gate(Set.of(verifier), List.of(new FilePartDescriptor("avatar", List.of(), -1)));
        RoutingContext ctx = context(List.of(upload("avatar")));

        gate.handle(ctx);

        RestValidationException failure = assertInstanceOf(RestValidationException.class, captureFailure(ctx));
        assertEquals(
                List.of(new ValidationErrorDetail(
                        "avatar", "application policy rejected the upload", "file", "macroPolicy", args)),
                failure.errors());
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("A failed verifier Future is an infrastructure failure")
    void failedFutureMapsTo500() {
        IllegalStateException boom = new IllegalStateException("scanner I/O failed");
        FileContentVerifier verifier = verifierReturning(Future.failedFuture(boom));
        Handler<RoutingContext> gate = gate(Set.of(verifier), List.of(new FilePartDescriptor(null, List.of(), -1)));
        RoutingContext ctx = context(List.of(upload("avatar")));

        gate.handle(ctx);

        assertSame(boom, captureFailure(ctx));
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("Deep verifiers do not run when synchronous file checks fail")
    void verifiersNotRunWhenSyncChecksFail() {
        FileContentVerifier verifier = verifierReturning(Future.succeededFuture(FileVerificationResult.accepted()));
        Handler<RoutingContext> gate =
                gate(Set.of(verifier), List.of(new FilePartDescriptor("avatar", List.of("image/png"), -1)));
        FileUpload upload = upload("avatar");
        when(upload.contentType()).thenReturn("application/pdf");
        RoutingContext ctx = context(List.of(upload));

        gate.handle(ctx);

        RestValidationException failure = assertInstanceOf(RestValidationException.class, captureFailure(ctx));
        assertEquals(
                List.of(new ValidationErrorDetail(
                        "avatar",
                        "file part content type is not allowed",
                        "file",
                        "fileContentTypeNotAllowed",
                        Map.of("allowedTypes", List.of("image/png")))),
                failure.errors());
        verify(verifier, never()).verify(any(FileUpload.class));
    }

    @Test
    @DisplayName("An immediately accepted non-applicable verifier preserves the synchronous fast path")
    void noApplicableVerifierPreservesSynchronousFastPath() {
        FileContentVerifier verifier = verifierReturning(Future.succeededFuture(FileVerificationResult.accepted()));
        Handler<RoutingContext> gate = gate(Set.of(verifier), List.of(new FilePartDescriptor(null, List.of(), -1)));
        RoutingContext ctx = context(List.of(upload("avatar")));
        AtomicBoolean handleReturned = new AtomicBoolean();
        doAnswer(invocation -> {
                    assertFalse(handleReturned.get(), "ctx.next() must run before handle() returns");
                    return null;
                })
                .when(ctx)
                .next();

        gate.handle(ctx);
        handleReturned.set(true);

        verify(ctx).next();
        verify(ctx, never()).fail(any(Throwable.class));
    }

    @Test
    @DisplayName("Named and aggregate exposure verifies each physical upload only once")
    void eachPhysicalUploadVerifiedOnce() {
        FileContentVerifier verifier = verifierReturning(Future.succeededFuture(FileVerificationResult.accepted()));
        Handler<RoutingContext> gate = gate(
                Set.of(verifier),
                List.of(new FilePartDescriptor("avatar", List.of(), -1), new FilePartDescriptor(null, List.of(), -1)));
        FileUpload upload = upload("avatar");
        RoutingContext ctx = context(List.of(upload));

        gate.handle(ctx);

        verify(verifier, times(1)).verify(upload);
        verify(ctx).next();
    }

    @Test
    @DisplayName("A rejection skips every later verifier and upload")
    void rejectionSkipsLaterVerifiersAndUploads() {
        List<String> calls = new ArrayList<>();
        FileContentVerifier rejecting = new FileContentVerifier() {
            @Override
            public Future<FileVerificationResult> verify(FileUpload part) {
                calls.add("reject:" + part.name());
                return Future.succeededFuture(FileVerificationResult.rejected(
                        "application policy rejected the upload", "filePolicy", Map.of()));
            }

            @Override
            public ExtensionPhase phase() {
                return ExtensionPhase.SYSTEM_FIRST;
            }

            @Override
            public String orderKey() {
                return "reject";
            }
        };
        FileContentVerifier later = new RecordingVerifier("later", ExtensionPhase.APPLICATION, 0, calls);
        Handler<RoutingContext> gate =
                gate(Set.of(later, rejecting), List.of(new FilePartDescriptor(null, List.of(), -1)));
        RoutingContext ctx = context(List.of(upload("avatar"), upload("document")));

        gate.handle(ctx);

        assertEquals(List.of("reject:avatar"), calls);
        assertInstanceOf(RestValidationException.class, captureFailure(ctx));
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("Synchronous checks and deep verification share one identity-deduplicated upload snapshot")
    void syncAndVerifierStagesShareIdentityDeduplicatedSnapshot() {
        FileUpload first = upload("avatar");
        FileUpload second = upload("avatar");
        FileContentVerifier verifier = mock(FileContentVerifier.class);
        when(verifier.verify(first)).thenReturn(Future.succeededFuture(FileVerificationResult.accepted()));
        when(verifier.verify(second))
                .thenReturn(Future.succeededFuture(FileVerificationResult.rejected(
                        "application policy rejected the upload", "filePolicy", Map.of())));
        Handler<RoutingContext> gate =
                gate(Set.of(verifier), List.of(new FilePartDescriptor("avatar", List.of("image/png"), -1)));
        RoutingContext ctx = context(List.of(first, first, second));

        gate.handle(ctx);

        RestValidationException failure = assertInstanceOf(RestValidationException.class, captureFailure(ctx));
        assertEquals(
                List.of(new ValidationErrorDetail(
                        "avatar[1]", "application policy rejected the upload", "file", "filePolicy", Map.of())),
                failure.errors());
        verify(verifier, times(1)).verify(first);
        verify(verifier, times(1)).verify(second);
        verify(ctx, never()).next();
    }

    @Test
    @DisplayName("Unannotated FileUpload and EntityPart descriptors remain verifier-eligible")
    void unannotatedAndEntityPartFilePartsStillVerified() {
        List<String> calls = new ArrayList<>();
        FileContentVerifier verifier = new RecordingVerifier("verifier", ExtensionPhase.APPLICATION, 0, calls);
        Handler<RoutingContext> gate = gate(
                Set.of(verifier),
                List.of(
                        new FilePartDescriptor("unannotated", List.of(), -1),
                        new FilePartDescriptor("entity", List.of(), -1)));
        RoutingContext ctx = context(List.of(upload("unannotated"), upload("entity")));

        gate.handle(ctx);

        assertEquals(List.of("verifier:unannotated", "verifier:entity"), calls);
        verify(ctx).next();
        verify(ctx, never()).fail(any(Throwable.class));
    }

    private static Handler<RoutingContext> gate(
            Set<FileContentVerifier> verifiers, List<FilePartDescriptor> fileParts) {
        WebValidationStrategy strategy = new WebValidationStrategy(
                JaxRsConfig.builder().build(), ConversionContexts.defaultResolver(), verifiers);
        return strategy.gateFor(descriptor(fileParts), OperationSchemas.empty()).orElseThrow();
    }

    private static FileContentVerifier verifierReturning(Future<FileVerificationResult> result) {
        FileContentVerifier verifier = mock(FileContentVerifier.class);
        when(verifier.verify(any(FileUpload.class))).thenReturn(result);
        return verifier;
    }

    private static FileUpload upload(String name) {
        FileUpload upload = mock(FileUpload.class);
        when(upload.name()).thenReturn(name);
        when(upload.fileName()).thenReturn(name + ".bin");
        when(upload.uploadedFileName()).thenReturn("target/" + name + ".upload");
        when(upload.contentType()).thenReturn("image/png");
        when(upload.size()).thenReturn(8L);
        return upload;
    }

    private static RoutingContext context(List<FileUpload> uploads) {
        RoutingContext ctx = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        Map<String, Object> data = new HashMap<>();
        when(ctx.request()).thenReturn(request);
        when(ctx.pathParams()).thenReturn(Map.of());
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(ctx.fileUploads()).thenReturn(uploads);
        when(ctx.body()).thenReturn(null);
        when(request.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(request.cookies()).thenReturn(Set.<Cookie>of());
        when(request.formAttributes()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(ctx.get(anyString())).thenAnswer(invocation -> data.get(invocation.getArgument(0, String.class)));
        when(ctx.put(anyString(), any())).thenAnswer(invocation -> {
            data.put(invocation.getArgument(0, String.class), invocation.getArgument(1));
            return ctx;
        });
        return ctx;
    }

    private static Throwable captureFailure(RoutingContext ctx) {
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(ctx).fail(failure.capture());
        return failure.getValue();
    }

    private static void assertInfrastructureFailure(Throwable failure) {
        assertTrue(failure != null, "an infrastructure failure must be reported");
        assertFalse(
                failure instanceof RestValidationException,
                "verifier infrastructure failures must map to 500, not validation 400");
    }

    private static JaxRsOperationDescriptor descriptor(List<FilePartDescriptor> fileParts) {
        return StubDescriptors.builder()
                .operationId("verifyUploads")
                .httpMethod("POST")
                .routeTemplate("/files")
                .consumes(List.of("multipart/form-data"))
                .fileParts(fileParts)
                .build();
    }

    private record RecordingVerifier(String id, ExtensionPhase phase, int priority, List<String> calls)
            implements FileContentVerifier {

        @Override
        public Future<FileVerificationResult> verify(FileUpload part) {
            calls.add(id + ":" + part.name());
            return Future.succeededFuture(FileVerificationResult.accepted());
        }

        @Override
        public String orderKey() {
            return id;
        }
    }
}
