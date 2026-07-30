// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import io.vertx.core.buffer.Buffer;
import java.nio.charset.StandardCharsets;

/** Deterministic raw multipart request bodies for REST validation integration tests. */
final class MultipartBodies {

    static final String BOUNDARY = "vertique-file-part-boundary";

    private MultipartBodies() {}

    /** Returns the HTTP Content-Type header value matching bodies produced by this fixture. */
    static String contentType() {
        return "multipart/form-data; boundary=" + BOUNDARY;
    }

    /**
     * Builds a raw multipart body containing exactly one file part.
     *
     * @param partName multipart form name
     * @param fileName submitted filename
     * @param declaredType declared Content-Type for the part
     * @param content raw file content
     * @return a complete multipart body with CRLF framing and a closing boundary
     */
    static Buffer singleFile(String partName, String fileName, String declaredType, byte[] content) {
        Buffer body = Buffer.buffer();
        appendFile(body, partName, fileName, declaredType, content);
        appendAscii(body, "--" + BOUNDARY + "--\r\n");
        return body;
    }

    /**
     * Builds a raw multipart body containing one file part and one text form field.
     *
     * @param partName multipart file-part form name
     * @param fileName submitted filename
     * @param declaredType declared Content-Type for the file part
     * @param content raw file content
     * @param fieldName text form-field name
     * @param fieldValue text form-field value
     * @return a complete multipart body with CRLF framing and a closing boundary
     */
    static Buffer fileAndTextField(
            String partName,
            String fileName,
            String declaredType,
            byte[] content,
            String fieldName,
            String fieldValue) {
        Buffer body = Buffer.buffer();
        appendFile(body, partName, fileName, declaredType, content);
        appendAscii(body, "--" + BOUNDARY + "\r\n");
        appendAscii(body, "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n");
        appendAscii(body, fieldValue + "\r\n");
        appendAscii(body, "--" + BOUNDARY + "--\r\n");
        return body;
    }

    /**
     * Builds a raw multipart body containing a fixed number of small file parts and text form
     * fields, used to probe part-count limits well below the configured byte cap.
     *
     * <p>File parts are named {@code file0..fileN} with {@code application/octet-stream} content;
     * text fields are named {@code field0..fieldN}. File parts precede text fields.
     *
     * @param fileParts number of small file parts to emit
     * @param textFields number of small text form fields to emit
     * @return a complete multipart body with CRLF framing and a closing boundary
     */
    static Buffer parts(int fileParts, int textFields) {
        Buffer body = Buffer.buffer();
        for (int i = 0; i < fileParts; i++) {
            appendFile(
                    body,
                    "file" + i,
                    "file" + i + ".bin",
                    "application/octet-stream",
                    ("payload-" + i).getBytes(StandardCharsets.US_ASCII));
        }
        for (int i = 0; i < textFields; i++) {
            appendAscii(body, "--" + BOUNDARY + "\r\n");
            appendAscii(body, "Content-Disposition: form-data; name=\"field" + i + "\"\r\n\r\n");
            appendAscii(body, "value-" + i + "\r\n");
        }
        appendAscii(body, "--" + BOUNDARY + "--\r\n");
        return body;
    }

    private static void appendFile(Buffer body, String partName, String fileName, String declaredType, byte[] content) {
        appendAscii(body, "--" + BOUNDARY + "\r\n");
        appendAscii(
                body, "Content-Disposition: form-data; name=\"" + partName + "\"; filename=\"" + fileName + "\"\r\n");
        appendAscii(body, "Content-Type: " + declaredType + "\r\n\r\n");
        body.appendBytes(content);
        appendAscii(body, "\r\n");
    }

    private static void appendAscii(Buffer target, String value) {
        target.appendBytes(value.getBytes(StandardCharsets.US_ASCII));
    }
}
