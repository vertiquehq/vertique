// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.vertique.rest.jaxrs.validation.FileVerificationResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.ext.web.FileUpload;
import io.vertx.junit5.VertxExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Contract tests for the opt-in, dependency-free magic-bytes file-content verifier. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class MagicBytesFileContentVerifierTest {

    private static final byte[] PNG = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF);
    private static final byte[] GIF_87A = bytes(0x47, 0x49, 0x46, 0x38, 0x37, 0x61);
    private static final byte[] GIF_89A = bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61);
    private static final byte[] PDF = bytes(0x25, 0x50, 0x44, 0x46, 0x2D);
    private static final byte[] ZIP_LOCAL_FILE = bytes(0x50, 0x4B, 0x03, 0x04);
    private static final byte[] ZIP_EMPTY_EOCD = bytes(0x50, 0x4B, 0x05, 0x06);
    private static final byte[] ZIP_SPANNED = bytes(0x50, 0x4B, 0x07, 0x08);
    private static final byte[] GZIP = bytes(0x1F, 0x8B);
    private static final byte[] WEBP = bytes(0x52, 0x49, 0x46, 0x46, 0x04, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50);
    private static final byte[] WAV = bytes(0x52, 0x49, 0x46, 0x46, 0x04, 0x00, 0x00, 0x00, 0x57, 0x41, 0x56, 0x45);
    private static final byte[] AVI = bytes(0x52, 0x49, 0x46, 0x46, 0x04, 0x00, 0x00, 0x00, 0x41, 0x56, 0x49, 0x20);
    private static final byte[] OLE = bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1);
    private static final byte[] MISMATCH = bytes(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07);

    private static final String MISMATCH_DETAIL = "file content does not match the declared content type";
    private static final String MISMATCH_TYPE = "fileSignatureMismatch";

    private static final List<String> ZIP_FAMILY_TYPES = List.of(
            "application/zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/java-archive");

    private static final Map<String, byte[]> EXPANDED_SIGNATURE_TYPES = Map.ofEntries(
            Map.entry("image/webp", WEBP),
            Map.entry("image/bmp", bytes(0x42, 0x4D)),
            Map.entry("image/tiff", bytes(0x49, 0x49, 0x2A, 0x00)),
            Map.entry("image/x-icon", bytes(0x00, 0x00, 0x01, 0x00)),
            Map.entry("application/msword", OLE),
            Map.entry("application/vnd.ms-excel", OLE),
            Map.entry("application/vnd.ms-powerpoint", OLE),
            Map.entry("application/x-7z-compressed", bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)),
            Map.entry("application/vnd.rar", bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)),
            Map.entry("application/x-bzip2", bytes(0x42, 0x5A, 0x68)),
            Map.entry("application/x-xz", bytes(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00)),
            Map.entry("audio/flac", bytes(0x66, 0x4C, 0x61, 0x43)),
            Map.entry("audio/ogg", bytes(0x4F, 0x67, 0x67, 0x53)),
            Map.entry("audio/wav", WAV),
            Map.entry("video/x-msvideo", AVI),
            Map.entry("application/wasm", bytes(0x00, 0x61, 0x73, 0x6D)),
            Map.entry("font/woff", bytes(0x77, 0x4F, 0x46, 0x46)),
            Map.entry("font/woff2", bytes(0x77, 0x4F, 0x46, 0x32)));

    @TempDir
    Path tempDir;

    private Vertx vertx;

    @BeforeEach
    void captureVertx(Vertx vertx) {
        this.vertx = vertx;
    }

    @Nested
    @DisplayName("PNG")
    class Png {

        @Test
        void validSignaturePasses() throws Exception {
            assertAccepted(verifyReal("image/png", PNG));
        }

        @Test
        void mismatchRejects() throws Exception {
            assertSignatureMismatch(verifyReal("image/png", MISMATCH));
        }
    }

    @Nested
    @DisplayName("JPEG")
    class Jpeg {

        @Test
        void validSignaturePasses() throws Exception {
            assertAccepted(verifyReal("image/jpeg", JPEG));
        }

        @Test
        void mismatchRejects() throws Exception {
            assertSignatureMismatch(verifyReal("image/jpeg", MISMATCH));
        }
    }

    @Nested
    @DisplayName("GIF87a and GIF89a")
    class Gif {

        @Test
        void validSignaturePasses() throws Exception {
            assertAccepted(verifyReal("image/gif", GIF_87A));
            assertAccepted(verifyReal("image/gif", GIF_89A));
        }

        @Test
        void mismatchRejects() throws Exception {
            assertSignatureMismatch(verifyReal("image/gif", MISMATCH));
        }
    }

    @Nested
    @DisplayName("PDF")
    class Pdf {

        @Test
        void validSignaturePasses() throws Exception {
            assertAccepted(verifyReal("application/pdf", PDF));
        }

        @Test
        void mismatchRejects() throws Exception {
            byte[] junkPrefixedPdf = concat(bytes(0x00), PDF);

            assertSignatureMismatch(verifyReal("application/pdf", junkPrefixedPdf));
        }
    }

    @Nested
    @DisplayName("ZIP family")
    class ZipFamily {

        @Test
        void validSignaturePasses() throws Exception {
            for (String declaredType : ZIP_FAMILY_TYPES) {
                assertAccepted(verifyReal(declaredType, ZIP_LOCAL_FILE));
                assertAccepted(verifyReal(declaredType, ZIP_SPANNED));
            }
        }

        @Test
        void mismatchRejects() throws Exception {
            for (String declaredType : ZIP_FAMILY_TYPES) {
                assertSignatureMismatch(verifyReal(declaredType, MISMATCH));
            }
        }

        @Test
        void emptyZipEocdSignaturePasses() throws Exception {
            for (String declaredType : ZIP_FAMILY_TYPES) {
                assertAccepted(verifyReal(declaredType, ZIP_EMPTY_EOCD));
            }
        }
    }

    @Nested
    @DisplayName("gzip")
    class Gzip {

        @Test
        void validSignaturePasses() throws Exception {
            assertAccepted(verifyReal("application/gzip", GZIP));
        }

        @Test
        void mismatchRejects() throws Exception {
            assertSignatureMismatch(verifyReal("application/gzip", MISMATCH));
        }
    }

    @Test
    @DisplayName("The expanded common-format catalog accepts valid signatures and rejects mismatches")
    void expandedCommonFormatCatalog() throws Exception {
        for (Map.Entry<String, byte[]> entry : EXPANDED_SIGNATURE_TYPES.entrySet()) {
            assertAccepted(verifyReal(entry.getKey(), entry.getValue()));
            assertSignatureMismatch(verifyReal(entry.getKey(), MISMATCH));
        }
    }

    @Test
    void zeroByteKnownTypeRejects() throws Exception {
        assertSignatureMismatch(verifyReal("image/png", new byte[0]));
    }

    @Test
    void fileShorterThanSignatureRejects() throws Exception {
        assertSignatureMismatch(verifyReal("image/png", bytes(0x89, 0x50, 0x4E, 0x47)));
    }

    @Test
    void unmappedDeclaredTypeAcceptedImmediately() throws Exception {
        Vertx mockVertx = mock(Vertx.class);
        FileSystem fileSystem = mock(FileSystem.class);
        when(mockVertx.fileSystem()).thenReturn(fileSystem);
        MagicBytesFileContentVerifier verifier = new MagicBytesFileContentVerifier(mockVertx);

        Future<FileVerificationResult> result = verifier.verify(upload("/must-not-be-opened", "text/plain"));

        assertTrue(result.succeeded(), "unmapped types must be accepted on an already-completed future");
        assertAccepted(await(result));
        verifyNoInteractions(fileSystem);
    }

    @Test
    void mediaTypeParametersAndCaseNormalized() throws Exception {
        assertAccepted(verifyReal("IMAGE/PNG; charset=binary", PNG));
    }

    @Test
    void readsAtMostHeadWindowOnce() throws Exception {
        MockIo io = mockIo(Future.succeededFuture(Buffer.buffer(PNG)), Future.succeededFuture());

        assertAccepted(await(io.verifier().verify(io.upload("image/png"))));

        verify(io.asyncFile(), times(1))
                .read(any(Buffer.class), eq(0), eq(0L), eq(MagicBytesFileContentVerifier.HEAD_WINDOW_BYTES));
        verify(io.asyncFile(), times(1)).close();
        verifyNoMoreInteractions(io.asyncFile());
    }

    @Test
    void opensReadOnlyNeverCreates() throws Exception {
        MockIo io = mockIo(Future.succeededFuture(Buffer.buffer(PNG)), Future.succeededFuture());

        assertAccepted(await(io.verifier().verify(io.upload("image/png"))));

        ArgumentCaptor<OpenOptions> optionsCaptor = ArgumentCaptor.forClass(OpenOptions.class);
        verify(io.fileSystem()).open(eq(io.path()), optionsCaptor.capture());
        OpenOptions options = optionsCaptor.getValue();
        assertTrue(options.isRead());
        assertFalse(options.isWrite());
        assertFalse(options.isCreate());
        verify(io.fileSystem(), never()).createFile(any(String.class));
    }

    @Test
    void resultCompletesAfterAsyncCloseCompletes() throws Exception {
        Promise<Void> closeGate = Promise.promise();
        MockIo io = mockIo(Future.succeededFuture(Buffer.buffer(PNG)), closeGate.future());

        Future<FileVerificationResult> result = io.verifier().verify(io.upload("image/png"));

        verify(io.asyncFile()).close();
        assertFalse(result.isComplete(), "verification must await asynchronous close completion");
        closeGate.complete();
        assertAccepted(await(result));
    }

    @Test
    void asyncFileClosedOnAllPaths() throws Exception {
        assertClosedAfterSuccessfulRead(Future.succeededFuture(Buffer.buffer(PNG)));
        assertClosedAfterSuccessfulRead(Future.succeededFuture(Buffer.buffer(MISMATCH)));

        RuntimeException readFailure = new RuntimeException("read failed");
        MockIo failedRead = mockIo(Future.failedFuture(readFailure), Future.succeededFuture());
        ExecutionException failure = assertThrows(
                ExecutionException.class, () -> await(failedRead.verifier().verify(failedRead.upload("image/png"))));
        assertSame(readFailure, failure.getCause());
        verify(failedRead.asyncFile()).close();
    }

    private void assertClosedAfterSuccessfulRead(Future<Buffer> readResult) throws Exception {
        MockIo io = mockIo(readResult, Future.succeededFuture());

        await(io.verifier().verify(io.upload("image/png")));
        verify(io.asyncFile()).close();
    }

    private FileVerificationResult verifyReal(String declaredType, byte[] content) throws Exception {
        Path file = Files.createTempFile(tempDir, "magic-bytes-", ".upload");
        Files.write(file, content);
        MagicBytesFileContentVerifier verifier = new MagicBytesFileContentVerifier(vertx);
        return await(verifier.verify(upload(file.toString(), declaredType)));
    }

    private static MockIo mockIo(Future<Buffer> readResult, Future<Void> closeResult) {
        Vertx vertx = mock(Vertx.class);
        FileSystem fileSystem = mock(FileSystem.class);
        AsyncFile asyncFile = mock(AsyncFile.class);
        String path = "/virtual/upload";
        when(vertx.fileSystem()).thenReturn(fileSystem);
        when(fileSystem.open(eq(path), any(OpenOptions.class))).thenReturn(Future.succeededFuture(asyncFile));
        when(asyncFile.read(any(Buffer.class), eq(0), eq(0L), eq(MagicBytesFileContentVerifier.HEAD_WINDOW_BYTES)))
                .thenReturn(readResult);
        when(asyncFile.close()).thenReturn(closeResult);
        return new MockIo(new MagicBytesFileContentVerifier(vertx), fileSystem, asyncFile, path);
    }

    private static FileUpload upload(String path, String declaredType) {
        FileUpload upload = mock(FileUpload.class);
        when(upload.uploadedFileName()).thenReturn(path);
        when(upload.contentType()).thenReturn(declaredType);
        return upload;
    }

    private static FileVerificationResult await(Future<FileVerificationResult> result) throws Exception {
        return result.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void assertAccepted(FileVerificationResult result) {
        assertInstanceOf(FileVerificationResult.Accepted.class, result);
    }

    private static void assertSignatureMismatch(FileVerificationResult result) {
        FileVerificationResult.Rejected rejected = assertInstanceOf(FileVerificationResult.Rejected.class, result);
        assertEquals(MISMATCH_DETAIL, rejected.detail());
        assertEquals(MISMATCH_TYPE, rejected.type());
        assertNull(rejected.args());
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private record MockIo(
            MagicBytesFileContentVerifier verifier, FileSystem fileSystem, AsyncFile asyncFile, String path) {

        FileUpload upload(String declaredType) {
            return MagicBytesFileContentVerifierTest.upload(path, declaredType);
        }
    }
}
