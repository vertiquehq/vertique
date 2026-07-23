// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import dev.vertique.rest.core.request.MediaType;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.FileVerificationResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.ext.web.FileUpload;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dependency-free leading-byte signature verifier for common uploaded-file media types.
 *
 * <p>The verifier is deliberately package-private: applications opt in through {@link
 * MagicBytesVerifierModule}, while the public extension contract remains {@link
 * FileContentVerifier}. Unknown or malformed declared media types are accepted without file I/O;
 * baseline declared-type validation remains the validation strategy's responsibility.
 */
final class MagicBytesFileContentVerifier implements FileContentVerifier {

    static final int HEAD_WINDOW_BYTES = 12;

    private static final String MISMATCH_DETAIL = "file content does not match the declared content type";
    private static final String MISMATCH_TYPE = "fileSignatureMismatch";

    private static final List<Signature> ZIP_SIGNATURES =
            List.of(prefix(0x50, 0x4B, 0x03, 0x04), prefix(0x50, 0x4B, 0x05, 0x06), prefix(0x50, 0x4B, 0x07, 0x08));

    private static final List<Signature> OLE_SIGNATURES =
            List.of(prefix(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1));
    private static final List<Signature> OGG_SIGNATURES = List.of(prefix(0x4F, 0x67, 0x67, 0x53));
    private static final List<Signature> WAV_SIGNATURES =
            List.of(atOffsets(fragment(0, 0x52, 0x49, 0x46, 0x46), fragment(8, 0x57, 0x41, 0x56, 0x45)));
    private static final List<Signature> RAR_SIGNATURES = List.of(
            prefix(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00), prefix(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00));

    private static final Map<String, List<Signature>> SIGNATURES = Map.ofEntries(
            Map.entry("image/png", List.of(prefix(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))),
            Map.entry("image/jpeg", List.of(prefix(0xFF, 0xD8, 0xFF))),
            Map.entry(
                    "image/gif",
                    List.of(prefix(0x47, 0x49, 0x46, 0x38, 0x37, 0x61), prefix(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))),
            Map.entry(
                    "image/webp",
                    List.of(atOffsets(fragment(0, 0x52, 0x49, 0x46, 0x46), fragment(8, 0x57, 0x45, 0x42, 0x50)))),
            Map.entry("image/bmp", List.of(prefix(0x42, 0x4D))),
            Map.entry("image/tiff", List.of(prefix(0x49, 0x49, 0x2A, 0x00), prefix(0x4D, 0x4D, 0x00, 0x2A))),
            Map.entry("image/x-icon", List.of(prefix(0x00, 0x00, 0x01, 0x00))),
            Map.entry("image/vnd.microsoft.icon", List.of(prefix(0x00, 0x00, 0x01, 0x00))),
            Map.entry("application/pdf", List.of(prefix(0x25, 0x50, 0x44, 0x46, 0x2D))),
            Map.entry("application/zip", ZIP_SIGNATURES),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ZIP_SIGNATURES),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ZIP_SIGNATURES),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", ZIP_SIGNATURES),
            Map.entry("application/java-archive", ZIP_SIGNATURES),
            Map.entry("application/msword", OLE_SIGNATURES),
            Map.entry("application/vnd.ms-excel", OLE_SIGNATURES),
            Map.entry("application/vnd.ms-powerpoint", OLE_SIGNATURES),
            Map.entry("application/gzip", List.of(prefix(0x1F, 0x8B))),
            Map.entry("application/x-gzip", List.of(prefix(0x1F, 0x8B))),
            Map.entry("application/x-7z-compressed", List.of(prefix(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))),
            Map.entry("application/vnd.rar", RAR_SIGNATURES),
            Map.entry("application/x-rar-compressed", RAR_SIGNATURES),
            Map.entry("application/x-bzip2", List.of(prefix(0x42, 0x5A, 0x68))),
            Map.entry("application/x-xz", List.of(prefix(0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00))),
            Map.entry("audio/flac", List.of(prefix(0x66, 0x4C, 0x61, 0x43))),
            Map.entry("application/ogg", OGG_SIGNATURES),
            Map.entry("audio/ogg", OGG_SIGNATURES),
            Map.entry("video/ogg", OGG_SIGNATURES),
            Map.entry("audio/wav", WAV_SIGNATURES),
            Map.entry("audio/x-wav", WAV_SIGNATURES),
            Map.entry(
                    "video/x-msvideo",
                    List.of(atOffsets(fragment(0, 0x52, 0x49, 0x46, 0x46), fragment(8, 0x41, 0x56, 0x49, 0x20)))),
            Map.entry("application/wasm", List.of(prefix(0x00, 0x61, 0x73, 0x6D))),
            Map.entry("font/woff", List.of(prefix(0x77, 0x4F, 0x46, 0x46))),
            Map.entry("application/font-woff", List.of(prefix(0x77, 0x4F, 0x46, 0x46))),
            Map.entry("font/woff2", List.of(prefix(0x77, 0x4F, 0x46, 0x32))));

    private final FileSystem fileSystem;

    /**
     * Creates the verifier using Vert.x asynchronous file I/O.
     *
     * @param vertx the Vert.x runtime
     */
    MagicBytesFileContentVerifier(Vertx vertx) {
        this.fileSystem = Objects.requireNonNull(vertx, "vertx").fileSystem();
    }

    /** {@inheritDoc} */
    @Override
    public Future<FileVerificationResult> verify(FileUpload part) {
        Objects.requireNonNull(part, "part");
        List<Signature> acceptedSignatures = signaturesFor(part.contentType());
        if (acceptedSignatures == null) {
            return Future.succeededFuture(FileVerificationResult.accepted());
        }

        OpenOptions options = new OpenOptions().setRead(true).setWrite(false).setCreate(false);
        return fileSystem.open(part.uploadedFileName(), options).compose(file -> {
            Future<Buffer> read = Future.<Void>succeededFuture()
                    .compose(ignored -> file.read(Buffer.buffer(HEAD_WINDOW_BYTES), 0, 0L, HEAD_WINDOW_BYTES));
            return read.map(head -> verdict(head, acceptedSignatures)).eventually(file::close);
        });
    }

    private static List<Signature> signaturesFor(String declaredType) {
        MediaType mediaType = MediaType.parse(declaredType);
        return mediaType == null ? null : SIGNATURES.get(mediaType.withoutParameters());
    }

    private static FileVerificationResult verdict(Buffer head, List<Signature> acceptedSignatures) {
        for (Signature signature : acceptedSignatures) {
            if (signature.matches(head)) {
                return FileVerificationResult.accepted();
            }
        }
        return FileVerificationResult.rejected(MISMATCH_DETAIL, MISMATCH_TYPE);
    }

    private static Signature prefix(int... values) {
        return atOffsets(fragment(0, values));
    }

    private static Signature atOffsets(Fragment... fragments) {
        return new Signature(List.of(fragments));
    }

    private static Fragment fragment(int offset, int... values) {
        return new Fragment(offset, bytes(values));
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return bytes;
    }

    /** One signature composed from fixed byte fragments at bounded offsets in the head window. */
    private record Signature(List<Fragment> fragments) {

        private boolean matches(Buffer head) {
            return fragments.stream().allMatch(fragment -> fragment.matches(head));
        }
    }

    /** One fixed byte sequence at an absolute head-window offset. */
    private record Fragment(int offset, byte[] bytes) {

        private boolean matches(Buffer head) {
            if (head.length() < offset + bytes.length) {
                return false;
            }
            for (int i = 0; i < bytes.length; i++) {
                if (head.getByte(offset + i) != bytes[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
