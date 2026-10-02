// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.openapi.docs.OpenApiConformanceCorpusIT.PatternPair;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hand-written rows for the pattern and {@code nullable} helpers of {@link OpenApiConformanceCorpusIT}:
 * each row hands a helper a literal input and expects a literal result, so the corpus checks that
 * rely on the helpers cannot pass because a helper finds nothing or accepts everything.
 */
class ConformancePatternHelpersTest {

    /**
     * A document with patterns in four schema positions (a Parameter Object's schema, the {@code
     * items} of a response media type's schema, a {@code patternProperties} key, and a nested
     * property) and decoys that hold data, not schemas: a media type's {@code examples} holding a
     * {@code schema} member, and a property's {@code enum}, {@code default}, and {@code examples}.
     */
    private static final String PATTERN_DOCUMENT = """
            {
              "openapi": "3.1.1",
              "paths": {
                "/a": {
                  "get": {
                    "parameters": [
                      {"name": "id", "in": "path", "required": true,
                       "schema": {"type": "string", "pattern": "^p1$"}}
                    ],
                    "responses": {
                      "200": {
                        "description": "ok",
                        "content": {
                          "application/json": {
                            "schema": {"type": "array", "items": {"type": "string", "pattern": "^p2$"}},
                            "examples": {
                              "one": {"value": {"schema": {"type": "string", "pattern": "^mediaExample$"}}}
                            }
                          }
                        }
                      }
                    }
                  }
                }
              },
              "components": {
                "schemas": {
                  "Bag": {
                    "type": "object",
                    "patternProperties": {"^k[0-9]$": {"type": "string"}},
                    "properties": {
                      "code": {
                        "type": "string",
                        "pattern": "^p3$",
                        "enum": [{"pattern": "^enum$"}],
                        "default": {"pattern": "^default$"},
                        "examples": [{"pattern": "^examples$"}]
                      }
                    }
                  }
                }
              }
            }
            """;

    /** A document with one {@code nullable} member, on a property nested in a component schema. */
    private static final String NULLABLE_DOCUMENT = """
            {
              "openapi": "3.1.1",
              "components": {
                "schemas": {
                  "Order": {
                    "type": "object",
                    "properties": {
                      "lines": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {"note": {"type": "string", "nullable": true}}
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    @Test
    @DisplayName("A pattern with an unclosed group does not compile, and the failure names its pointer")
    void unclosedGroupFailsToCompile() {
        // Given: a pattern value with an unclosed group
        PatternPair pair = new PatternPair("/components/schemas/Bad/pattern", "^(a", false);

        // When: it is compiled in the toolchain
        String failure = OpenApiConformanceCorpusIT.compileFailure(pair);

        // Then: a failure is reported, starting with the pattern's pointer
        assertNotNull(failure, "an unclosed group must not compile");
        assertTrue(
                failure.startsWith("/components/schemas/Bad/pattern: "),
                () -> "the failure names the pointer first: " + failure);
    }

    @Test
    @DisplayName("A well-formed pattern compiles and evaluates with no failure")
    void wellFormedPatternCompiles() {
        // Given: a well-formed pattern value
        PatternPair pair = new PatternPair("/components/schemas/Good/pattern", "^[0-9]+$", false);

        // When: it is compiled in the toolchain
        String failure = OpenApiConformanceCorpusIT.compileFailure(pair);

        // Then: no failure is reported
        assertNull(failure, "a well-formed pattern compiles");
    }

    @Test
    @DisplayName("A nested nullable member is found at its exact pointer")
    void nestedNullableIsFound() {
        // Given: a document with one nullable member nested in a component schema
        JsonObject document = new JsonObject(NULLABLE_DOCUMENT);

        // When: the nullable members are collected
        List<String> pointers = OpenApiConformanceCorpusIT.collectNullableMembers(document);

        // Then: exactly that member's pointer is returned
        assertEquals(List.of("/components/schemas/Order/properties/lines/items/properties/note/nullable"), pointers);
    }

    @Test
    @DisplayName(
            "Patterns are collected from every schema position, items and patternProperties keys included, and never from examples, enum, or default")
    void patternsAreCollectedFromSchemaPositionsOnly() {
        // Given: a document with patterns in four schema positions and in data-holding decoys
        JsonObject document = new JsonObject(PATTERN_DOCUMENT);

        // When: the patterns are collected
        List<PatternPair> pairs = OpenApiConformanceCorpusIT.collectPatterns(document);

        // Then: exactly the four schema-position patterns are returned, in document order
        assertEquals(
                List.of(
                        new PatternPair("/paths/~1a/get/parameters/0/schema/pattern", "^p1$", false),
                        new PatternPair(
                                "/paths/~1a/get/responses/200/content/application~1json/schema/items/pattern",
                                "^p2$",
                                false),
                        new PatternPair("/components/schemas/Bag/patternProperties/^k[0-9]$", "^k[0-9]$", true),
                        new PatternPair("/components/schemas/Bag/properties/code/pattern", "^p3$", false)),
                pairs);
    }
}
