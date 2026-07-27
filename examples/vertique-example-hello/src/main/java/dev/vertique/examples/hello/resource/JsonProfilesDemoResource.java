// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.hello.resource;

import dev.vertique.core.json.JsonProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.vertx.core.Future;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.extern.slf4j.Slf4j;

/**
 * Demonstrates the framework's built-in {@code vertique} and {@code vertique-strict} JSON mapper
 * profiles on live JAX-RS endpoints.
 *
 * <p>The {@code vertique} profile ({@code /json-demo/optional}) shows JDK8 {@link Optional} /
 * {@link OptionalInt} unwrapping: a present value serializes as the plain contained value and an
 * empty value is omitted from the response body entirely, and a missing or {@code null} inbound
 * property binds to {@link Optional#empty()}.
 *
 * <p>The {@code vertique-strict} profile ({@code /json-demo/price}) shows the strict decimal wire
 * form: a {@link BigDecimal} property is written as a quoted plain-decimal JSON string (never a
 * JSON number), and only that string form is accepted on the way in.
 */
@Slf4j
@Path("/json-demo")
@Tag(name = "JsonProfilesDemo", description = "Demonstrates the vertique and vertique-strict JSON mapper profiles")
@PermitAll
public class JsonProfilesDemoResource {

    /** Constructs the resource. No collaborators are required; all endpoints return canned data. */
    @Inject
    public JsonProfilesDemoResource() {}

    // --- vertique profile: Optional / OptionalInt unwrapping ---

    /**
     * Returns an {@link OptionalGreeting} under the {@code vertique} profile, demonstrating that a
     * present {@link Optional}/{@link OptionalInt} property serializes as its plain contained value
     * and an empty one is omitted from the response body.
     *
     * @param empty when {@code true}, all optional properties are returned empty; otherwise they are
     *     returned present
     * @return the demo greeting, with optional properties present or empty depending on {@code empty}
     */
    @GET
    @Path("/optional")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonProfile("vertique")
    @Operation(
            operationId = "getOptionalGreeting",
            summary = "Get a greeting with optional fields (vertique profile)",
            description = "Demonstrates Optional/OptionalInt unwrapping under the vertique JSON profile")
    @ApiResponse(
            responseCode = "200",
            description = "The demo greeting",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = OptionalGreeting.class)))
    public Future<OptionalGreeting> getOptionalGreeting(
            @Parameter(description = "Return all optional fields empty when true")
                    @QueryParam("empty")
                    @DefaultValue("false")
                    boolean empty) {
        if (empty) {
            return Future.succeededFuture(
                    new OptionalGreeting("Ada", Optional.empty(), Optional.empty(), OptionalInt.empty()));
        }
        return Future.succeededFuture(
                new OptionalGreeting("Ada", Optional.of("zed"), Optional.of(List.of("a", "b")), OptionalInt.of(7)));
    }

    /**
     * Accepts an {@link OptionalGreeting} under the {@code vertique} profile and echoes back whether
     * {@code nickname} was bound present, proving a missing or {@code null} inbound property binds to
     * {@link Optional#empty()}.
     *
     * @param greeting the request body, first-parsed and materialized by the {@code vertique} profile
     * @return the echo showing the bound {@code nickname} value and its presence
     */
    @POST
    @Path("/optional")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @JsonProfile("vertique")
    @Operation(
            operationId = "postOptionalGreeting",
            summary = "Echo Optional binding (vertique profile)",
            description = "Demonstrates that a missing or null nickname binds to Optional.empty()")
    @ApiResponse(
            responseCode = "200",
            description = "Echo of the bound nickname",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EchoResult.class)))
    public Future<EchoResult> postOptionalGreeting(OptionalGreeting greeting) {
        return Future.succeededFuture(new EchoResult(
                greeting.nickname().orElse(null), greeting.nickname().isPresent()));
    }

    // --- vertique-strict profile: BigDecimal string wire form ---

    /**
     * Returns a {@link PriceQuote} under the {@code vertique-strict} profile, demonstrating that a
     * {@link BigDecimal} property serializes as a quoted plain-decimal JSON string.
     *
     * @param nodiscount when {@code true}, {@code discount} is returned empty; otherwise present
     * @return the demo price quote
     */
    @GET
    @Path("/price")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonProfile("vertique-strict")
    @Operation(
            operationId = "getPriceQuote",
            summary = "Get a price quote (vertique-strict profile)",
            description = "Demonstrates BigDecimal serialized as a plain-decimal JSON string")
    @ApiResponse(
            responseCode = "200",
            description = "The demo price quote",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceQuote.class)))
    public Future<PriceQuote> getPriceQuote(
            @Parameter(description = "Return discount empty when true") @QueryParam("nodiscount") @DefaultValue("false")
                    boolean nodiscount) {
        if (nodiscount) {
            return Future.succeededFuture(new PriceQuote("SKU-1", new BigDecimal("1.50"), Optional.empty()));
        }
        return Future.succeededFuture(
                new PriceQuote("SKU-1", new BigDecimal("1.50"), Optional.of(new BigDecimal("0.25"))));
    }

    /**
     * Accepts a {@link PriceQuote} under the {@code vertique-strict} profile and echoes the bound
     * {@code amount}'s scale and plain string form, proving the inbound decimal string was bound to a
     * full-precision {@link BigDecimal} rather than a lossy {@code double}.
     *
     * @param quote the request body, first-parsed and materialized by the {@code vertique-strict}
     *     profile; {@code amount} must be a JSON string matching the plain decimal grammar
     * @return the echo of {@code amount}'s scale and plain string form
     */
    @POST
    @Path("/price")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @JsonProfile("vertique-strict")
    @Operation(
            operationId = "postPriceQuote",
            summary = "Echo BigDecimal binding (vertique-strict profile)",
            description = "Demonstrates that only the plain-decimal JSON string form binds to BigDecimal")
    @ApiResponse(
            responseCode = "200",
            description = "Echo of the bound amount's scale and plain string form",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PriceEcho.class)))
    public Future<PriceEcho> postPriceQuote(PriceQuote quote) {
        BigDecimal amount = quote.amount();
        return Future.succeededFuture(new PriceEcho(amount.scale(), amount.toPlainString()));
    }

    // --- DTOs ---

    /**
     * Demo DTO with a required {@code name} and three optional properties, used to demonstrate JDK8
     * {@link Optional} / {@link OptionalInt} unwrapping under the {@code vertique} JSON profile.
     *
     * @param name the greeted name (always present)
     * @param nickname an optional nickname; omitted from the response body when empty
     * @param tags an optional list of tags; omitted from the response body when empty
     * @param rank an optional numeric rank; omitted from the response body when empty
     */
    public record OptionalGreeting(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            // Deliberately left unmarked: nickname/tags/rank are the demo's point — they show up as
            // genuinely optional properties in the generated spec.
            Optional<String> nickname,
            Optional<List<String>> tags,
            OptionalInt rank) {}

    /**
     * Echo DTO reporting whether an inbound {@code nickname} was bound present.
     *
     * @param nickname the bound nickname value, or {@code null} when absent
     * @param nicknamePresent whether {@code nickname} was bound present
     */
    public record EchoResult(String nickname, boolean nicknamePresent) {}

    /**
     * Demo DTO with a {@link BigDecimal} {@code amount} and an optional {@link BigDecimal}
     * {@code discount}, used to demonstrate the {@code vertique-strict} decimal string wire form.
     *
     * @param sku the product SKU
     * @param amount the price amount, serialized as a plain-decimal JSON string
     * @param discount an optional discount amount; omitted from the response body when empty
     */
    public record PriceQuote(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            String sku,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            BigDecimal amount,
            // Deliberately left unmarked: discount is the demo's optional property.
            Optional<BigDecimal> discount) {}

    /**
     * Echo DTO reporting the scale and plain string form of a bound {@link BigDecimal} amount.
     *
     * @param scale the bound amount's {@link BigDecimal#scale()}
     * @param plain the bound amount's {@link BigDecimal#toPlainString()}
     */
    public record PriceEcho(int scale, String plain) {}
}
