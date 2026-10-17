// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.json.schema.Draft;
import io.vertx.json.schema.JsonSchemaOptions;
import io.vertx.json.schema.OutputFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the shared engine options: Draft 2020-12, the fixed base URI and the Basic output format, returned as
 * a fresh instance on every call so that no caller can change another's options.
 */
class JsonSchemaValidationOptionsTest {

    @Test
    @DisplayName("the options select Draft 2020-12, the fixed base URI and Basic output")
    void draft202012SelectsTheSharedConfiguration() {
        JsonSchemaOptions options = JsonSchemaValidationOptions.draft202012();

        assertThat(options.getDraft()).isEqualTo(Draft.DRAFT202012);
        assertThat(options.getBaseUri()).isEqualTo("https://vertique.local/");
        assertThat(options.getOutputFormat()).isEqualTo(OutputFormat.Basic);
    }

    @Test
    @DisplayName("every call returns a fresh instance, so changing one does not change another")
    void everyCallReturnsAFreshInstance() {
        JsonSchemaOptions first = JsonSchemaValidationOptions.draft202012();
        JsonSchemaOptions second = JsonSchemaValidationOptions.draft202012();

        first.setBaseUri("https://changed.example/");

        assertThat(first).isNotSameAs(second);
        assertThat(second.getBaseUri()).isEqualTo("https://vertique.local/");
    }
}
