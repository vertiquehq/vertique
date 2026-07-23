// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.RestConfigurationException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies multipart upload-directory defaults, overrides, and validation. */
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
}
