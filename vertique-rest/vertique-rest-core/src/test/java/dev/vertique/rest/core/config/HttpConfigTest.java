// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.RestConfigurationException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies multipart upload-directory and admission-limit defaults, overrides, and validation. */
class HttpConfigTest {

    @Test
    @DisplayName("uploadsDirectory defaults to file-uploads and supports builder and JSON overrides")
    void uploadsDirectoryDefaultAndOverride() throws Exception {
        HttpConfig defaults = HttpConfig.builder().build();
        HttpConfig builderOverride =
                HttpConfig.builder().uploadsDirectory("target/builder-uploads").build();
        HttpConfig jsonOverride =
                new ObjectMapper().readValue("{\"uploadsDirectory\":\"target/json-uploads\"}", HttpConfig.class);

        assertEquals("file-uploads", defaults.uploadsDirectory());
        assertEquals("target/builder-uploads", builderOverride.uploadsDirectory());
        assertEquals("target/json-uploads", jsonOverride.uploadsDirectory());
    }

    @Test
    @DisplayName("blank and null uploadsDirectory values fail configuration")
    void blankAndNullUploadsDirectoryRejected() {
        for (String invalid : Arrays.asList("", "   ", null)) {
            assertThrows(
                    RestConfigurationException.class,
                    () -> HttpConfig.builder().uploadsDirectory(invalid).build(),
                    () -> "Expected builder to reject uploadsDirectory=" + invalid);
        }

        for (String json : List.of(
                "{\"uploadsDirectory\":\"\"}", "{\"uploadsDirectory\":\"   \"}", "{\"uploadsDirectory\":null}")) {
            JsonMappingException failure = assertThrows(
                    JsonMappingException.class,
                    () -> new ObjectMapper().readValue(json, HttpConfig.class),
                    () -> "Expected JSON deserialization to reject " + json);
            assertInstanceOf(RestConfigurationException.class, failure.getCause());
        }
    }

    @Test
    @DisplayName("maxMultipartBodySizeBytes defaults to 2 MiB and supports builder and JSON overrides")
    void maxMultipartBodySizeBytesDefaultAndOverride() throws Exception {
        HttpConfig defaults = HttpConfig.builder().build();
        HttpConfig builderOverride =
                HttpConfig.builder().maxMultipartBodySizeBytes(1024).build();
        HttpConfig jsonOverride =
                new ObjectMapper().readValue("{\"maxMultipartBodySizeBytes\":2048}", HttpConfig.class);

        assertEquals(2_097_152, defaults.maxMultipartBodySizeBytes());
        assertEquals(1024, builderOverride.maxMultipartBodySizeBytes());
        assertEquals(2048, jsonOverride.maxMultipartBodySizeBytes());
    }

    @Test
    @DisplayName("non-positive maxMultipartBodySizeBytes values fail configuration")
    void nonPositiveMaxMultipartBodySizeBytesRejected() {
        for (long invalid : new long[] {0L, -1L, Long.MIN_VALUE}) {
            assertThrows(
                    RestConfigurationException.class,
                    () -> HttpConfig.builder()
                            .maxMultipartBodySizeBytes(invalid)
                            .build(),
                    () -> "Expected builder to reject maxMultipartBodySizeBytes=" + invalid);
        }

        for (String json : List.of(
                "{\"maxMultipartBodySizeBytes\":0}",
                "{\"maxMultipartBodySizeBytes\":-1}",
                "{\"maxMultipartBodySizeBytes\":-9223372036854775808}")) {
            JsonMappingException failure = assertThrows(
                    JsonMappingException.class,
                    () -> new ObjectMapper().readValue(json, HttpConfig.class),
                    () -> "Expected JSON deserialization to reject " + json);
            assertInstanceOf(RestConfigurationException.class, failure.getCause());
        }
    }

    @Test
    @DisplayName("bodyLimitBytes applies the tighter multipart admission ceiling only to multipart/form-data")
    void bodyLimitBytesUsesMultipartAdmissionCeiling() {
        HttpConfig config = HttpConfig.builder()
                .maxBodySize(10_000_000)
                .maxMultipartBodySizeBytes(4_096)
                .build();

        assertEquals(10_000_000, config.bodyLimitBytes(null));
        assertEquals(10_000_000, config.bodyLimitBytes("application/json"));
        assertEquals(4_096, config.bodyLimitBytes("multipart/form-data"));
        assertEquals(4_096, config.bodyLimitBytes("Multipart/Form-Data; boundary=abc"));
        assertEquals(
                4_096,
                config.bodyLimitBytes("multipart/form-data; boundary=x"),
                "parameters must not defeat the multipart admission match");
    }

    @Test
    @DisplayName("bodyLimitBytes never exceeds maxBodySize even when the multipart ceiling is higher")
    void bodyLimitBytesClampsToMaxBodySize() {
        HttpConfig config = HttpConfig.builder()
                .maxBodySize(1_024)
                .maxMultipartBodySizeBytes(10_000_000)
                .build();

        assertEquals(1_024, config.bodyLimitBytes("multipart/form-data"));
        assertEquals(1_024, config.bodyLimitBytes("application/json"));
    }

    @Test
    @DisplayName("bodyLimitBytes keeps the multipart ceiling when maxBodySize is the -1 unlimited sentinel (builder)")
    void bodyLimitBytesUnlimitedGlobalKeepsMultipartCeilingBuilder() {
        HttpConfig config = HttpConfig.builder()
                .maxBodySize(-1)
                .maxMultipartBodySizeBytes(4_096)
                .build();

        assertEquals(4_096, config.bodyLimitBytes("multipart/form-data; boundary=x"));
        assertEquals(4_096, config.bodyLimitBytes("MULTIPART/FORM-DATA"));
        assertEquals(-1, config.bodyLimitBytes("application/json"), "non-multipart stays unlimited");
        assertEquals(-1, config.bodyLimitBytes(null), "missing content type stays unlimited");
    }

    @Test
    @DisplayName("bodyLimitBytes keeps the multipart ceiling when maxBodySize is the -1 unlimited sentinel (JSON)")
    void bodyLimitBytesUnlimitedGlobalKeepsMultipartCeilingJson() throws Exception {
        HttpConfig config = new ObjectMapper()
                .readValue("{\"maxBodySize\":-1,\"maxMultipartBodySizeBytes\":4096}", HttpConfig.class);

        assertEquals(-1, config.maxBodySize());
        assertEquals(4_096, config.bodyLimitBytes("multipart/form-data; boundary=x"));
        assertEquals(-1, config.bodyLimitBytes("text/plain"));
    }

    @Test
    @DisplayName("isMultipartFormData matches Vert.x BodyHandler's multipart prefix rule")
    void isMultipartFormDataPrefixMatch() {
        assertFalse(HttpConfig.isMultipartFormData(null));
        assertFalse(HttpConfig.isMultipartFormData(""));
        assertFalse(HttpConfig.isMultipartFormData("application/json"));
        assertFalse(HttpConfig.isMultipartFormData("multipart/mixed"));
        assertTrue(HttpConfig.isMultipartFormData("multipart/form-data"));
        assertTrue(HttpConfig.isMultipartFormData("MULTIPART/FORM-DATA; boundary=z"));
    }
}
