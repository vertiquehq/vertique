// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.metadata.unit.enrichment;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import jakarta.ws.rs.QueryParam;

/**
 * Fixture methods declaring parameter examples on one query parameter {@code q}, read onto synthetic
 * operations by {@code MetadataPublications.annotate}; nothing invokes them. Each method has exactly
 * the one parameter, a plain {@code String}, so its synthetic binding is not required. The class
 * itself carries no annotation.
 */
@SuppressWarnings("unused")
public final class ExampleResource {

    private ExampleResource() {}

    /**
     * (c) An example whose text is a JSON object.
     *
     * @param q the query parameter
     */
    public void jsonExample(@Parameter(example = "{\"a\": 1}") @QueryParam("q") String q) {}

    /**
     * (d) An example whose text is not JSON.
     *
     * @param q the query parameter
     */
    public void textExample(@Parameter(example = "plain text") @QueryParam("q") String q) {}

    /**
     * (e) An example beside named examples.
     *
     * @param q the query parameter
     */
    public void exampleAndExamples(
            @Parameter(example = "1", examples = @ExampleObject(name = "n", summary = "Sum", value = "[1, 2]"))
                    @QueryParam("q")
                    String q) {}

    /**
     * (f) A named example whose name is blank.
     *
     * @param q the query parameter
     */
    public void blankExampleName(
            @Parameter(examples = @ExampleObject(name = " ", value = "1")) @QueryParam("q") String q) {}

    /**
     * (g) A named example with both a value and an external value.
     *
     * @param q the query parameter
     */
    public void valueAndExternalValue(
            @Parameter(
                            examples =
                                    @ExampleObject(
                                            name = "both",
                                            value = "1",
                                            externalValue = "https://examples.example.test/1"))
                    @QueryParam("q")
                    String q) {}

    /**
     * (h) Two named examples sharing one name.
     *
     * @param q the query parameter
     */
    public void duplicateExampleNames(
            @Parameter(
                            examples = {
                                @ExampleObject(name = "dup", value = "1"),
                                @ExampleObject(name = "dup", value = "2")
                            })
                    @QueryParam("q")
                    String q) {}
}
