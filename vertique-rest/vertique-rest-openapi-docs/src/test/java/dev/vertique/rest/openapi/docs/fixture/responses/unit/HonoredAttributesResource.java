// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.responses.unit;

import dev.vertique.rest.openapi.docs.fixture.responses.dto.Part;
import dev.vertique.rest.openapi.docs.fixture.responses.dto.Thing;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.links.Link;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.vertx.core.Future;
import jakarta.ws.rs.core.Response;

/**
 * Variants of one operation's declared responses, each standing for operation {@code getThing} with
 * produces {@code application/json}: one method per case, assembled one at a time.
 *
 * <p>{@link #honored()} declares every honored attribute. Each {@code omitted*} method declares a
 * copy of {@code honored()}'s {@code 200} alone (no {@code 201}, {@code 202}, or {@code 206}), with
 * exactly one attribute added that a document does not publish; {@link #omittedAll()} adds all
 * seven to one copy. Every other member of the copy is unchanged, so the published {@code 200}
 * equals {@code honored()}'s, plus, for {@link #omittedArraySchemaMinItems()}, the second media
 * type it adds. Each {@code failing*} method declares a case that fails publication: a copy of the
 * {@code 200} with a blank example name or an example reference, or {@code useReturnTypeSchema =
 * true} on status {@code 201} of a method returning {@code Response}.
 *
 * <p>Every sentinel the omitted and failing attributes carry contains {@code ZX}, so a message or
 * warning that quotes an attribute value shows it. Every method takes no parameter and returns
 * {@code null}; the class is never deployed.
 */
public class HonoredAttributesResource {

    /** Creates the resource. */
    public HonoredAttributesResource() {}

    /**
     * Returns {@code Future<Thing>} with every honored attribute: {@code 200} with a documented
     * header, a documented content schema, an example, and an {@code x-} extension; {@code 201}
     * with {@code useReturnTypeSchema}; {@code 202} with a blank description; {@code 206} with
     * array content.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            schema = @Schema(implementation = Integer.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = Thing.class,
                                            description = "The thing body",
                                            title = "Thing"),
                            examples = @ExampleObject(name = "one", summary = "One", value = "{\"id\": 1}")),
            extensions =
                    @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")))
    @ApiResponse(responseCode = "201", description = "Created", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "202")
    @ApiResponse(
            responseCode = "206",
            description = "Some parts",
            content =
                    @Content(
                            mediaType = "application/json",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = Part.class, description = "One part"))))
    public Future<Thing> honored() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} plus {@code ref}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            ref = "#/components/responses/RESPZX",
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            schema = @Schema(implementation = Integer.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = Thing.class,
                                            description = "The thing body",
                                            title = "Thing"),
                            examples = @ExampleObject(name = "one", summary = "One", value = "{\"id\": 1}")),
            extensions =
                    @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")))
    public Future<Thing> omittedRef() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} plus {@code links}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            links = @Link(name = "selfZX", operationId = "getThing"),
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            schema = @Schema(implementation = Integer.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = Thing.class,
                                            description = "The thing body",
                                            title = "Thing"),
                            examples = @ExampleObject(name = "one", summary = "One", value = "{\"id\": 1}")),
            extensions =
                    @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")))
    public Future<Thing> omittedLinks() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} whose content adds {@code
     * encoding}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            schema = @Schema(implementation = Integer.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = Thing.class,
                                            description = "The thing body",
                                            title = "Thing"),
                            encoding = @Encoding(name = "a"),
                            examples = @ExampleObject(name = "one", summary = "One", value = "{\"id\": 1}")),
            extensions =
                    @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")))
    public Future<Thing> omittedEncoding() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} whose content schema adds {@code
     * maxProperties}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            schema = @Schema(implementation = Integer.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = Thing.class,
                                            description = "The thing body",
                                            title = "Thing",
                                            maxProperties = 3),
                            examples = @ExampleObject(name = "one", summary = "One", value = "{\"id\": 1}")),
            extensions =
                    @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")))
    public Future<Thing> omittedSchemaMaxProperties() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} plus a second content, for
     * {@code application/vnd.a+json}, whose {@code @ArraySchema} declares {@code minItems}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            schema = @Schema(implementation = Integer.class)),
            content = {
                @Content(
                        mediaType = "application/json",
                        schema = @Schema(implementation = Thing.class, description = "The thing body", title = "Thing"),
                        examples = @ExampleObject(name = "one", summary = "One", value = "{\"id\": 1}")),
                @Content(
                        mediaType = "application/vnd.a+json",
                        array = @ArraySchema(minItems = 1, schema = @Schema(implementation = Thing.class)))
            },
            extensions =
                    @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")))
    public Future<Thing> omittedArraySchemaMinItems() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} whose header adds {@code ref}.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            ref = "#/components/headers/HDRZX",
                            schema = @Schema(implementation = Integer.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = Thing.class,
                                            description = "The thing body",
                                            title = "Thing"),
                            examples = @ExampleObject(name = "one", summary = "One", value = "{\"id\": 1}")),
            extensions =
                    @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")))
    public Future<Thing> omittedHeaderRef() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} plus an extension named {@code
     * rate}, beside the {@code x-} extension.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            schema = @Schema(implementation = Integer.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = Thing.class,
                                            description = "The thing body",
                                            title = "Thing"),
                            examples = @ExampleObject(name = "one", summary = "One", value = "{\"id\": 1}")),
            extensions = {
                @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")),
                @Extension(name = "rate", properties = @ExtensionProperty(name = "limit", value = "10"))
            })
    public Future<Thing> omittedExtensionName() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} carrying all seven omitted
     * attributes at once.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            ref = "#/components/responses/RESPZX",
            links = @Link(name = "selfZX", operationId = "getThing"),
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            ref = "#/components/headers/HDRZX",
                            schema = @Schema(implementation = Integer.class)),
            content = {
                @Content(
                        mediaType = "application/json",
                        schema =
                                @Schema(
                                        implementation = Thing.class,
                                        description = "The thing body",
                                        title = "Thing",
                                        maxProperties = 3),
                        encoding = @Encoding(name = "a"),
                        examples = @ExampleObject(name = "one", summary = "One", value = "{\"id\": 1}")),
                @Content(
                        mediaType = "application/vnd.a+json",
                        array = @ArraySchema(minItems = 1, schema = @Schema(implementation = Thing.class)))
            },
            extensions = {
                @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")),
                @Extension(name = "rate", properties = @ExtensionProperty(name = "limit", value = "10"))
            })
    public Future<Thing> omittedAll() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} whose example has a blank name.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            schema = @Schema(implementation = Integer.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = Thing.class,
                                            description = "The thing body",
                                            title = "Thing"),
                            examples = @ExampleObject(name = "", summary = "One", value = "{\"id\": 1}")),
            extensions =
                    @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")))
    public Future<Thing> failingBlankExampleName() {
        return null;
    }

    /**
     * Returns {@code Response}, which is not inferable, with {@code useReturnTypeSchema = true} on
     * status {@code 201}.
     *
     * @return {@code null}
     */
    @ApiResponse(responseCode = "201", description = "Created", useReturnTypeSchema = true)
    public Response failingUseReturnTypeSchemaOnResponse() {
        return null;
    }

    /**
     * Returns {@code Future<Thing>} with the honored {@code 200} whose example is a reference.
     *
     * @return {@code null}
     */
    @ApiResponse(
            responseCode = "200",
            description = "The thing",
            headers =
                    @Header(
                            name = "X-Rate",
                            description = "Remaining",
                            required = true,
                            deprecated = true,
                            schema = @Schema(implementation = Integer.class)),
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = Thing.class,
                                            description = "The thing body",
                                            title = "Thing"),
                            examples = @ExampleObject(name = "e", ref = "#/components/examples/EXZX")),
            extensions =
                    @Extension(name = "x-rate-limited", properties = @ExtensionProperty(name = "limit", value = "10")))
    public Future<Thing> failingExampleRef() {
        return null;
    }
}
