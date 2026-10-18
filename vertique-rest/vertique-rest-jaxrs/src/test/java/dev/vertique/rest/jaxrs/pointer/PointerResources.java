// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.pointer;

import dev.vertique.core.json.JsonProfile;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Address;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Aliased;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.AliasedBean;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Animal;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Basket;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Blank;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Cart;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Grid;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Item;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Ledger;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.PublicAddress;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.RenamedSetter;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Shipping;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Skipping;
import dev.vertique.rest.jaxrs.pointer.PointerDtos.Tagged;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Resource fixtures for the body violation pointer integration tests. Every method accepts a valid
 * body with {@code "ok"}; a violated constraint is answered by the framework's validation gate before
 * the method runs.
 */
public final class PointerResources {

    /** The profile id of the snake_case profile the snake resource selects. */
    public static final String SNAKE_PROFILE = "snake-test";

    /** The profile id of the profile whose property names match without regard to case. */
    public static final String CASE_INSENSITIVE_PROFILE = "case-insensitive-test";

    /** The profile id of the profile that names one member with the empty string. */
    public static final String EMPTY_NAME_PROFILE = "empty-name-test";

    private PointerResources() {}

    /** Address routes under the default profile; JSON and form-urlencoded bodies of the same DTO. */
    @Path("/addresses")
    public static class AddressResource {

        /**
         * Accepts a JSON address.
         *
         * @param address the validated body
         * @return {@code "ok"}
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createAddress")
        public String create(@Valid Address address) {
            return "ok";
        }

        /**
         * Accepts a form-urlencoded address.
         *
         * @param address the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/form")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createAddressFromForm")
        public String createFromForm(@Valid Address address) {
            return "ok";
        }
    }

    /** Shipping routes under the snake_case profile. */
    @Path("/shipping/snake")
    @JsonProfile(SNAKE_PROFILE)
    public static class SnakeShippingResource {

        /**
         * Accepts a JSON shipping body.
         *
         * @param shipping the validated body
         * @return {@code "ok"}
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createSnakeShipping")
        public String create(@Valid Shipping shipping) {
            return "ok";
        }

        /**
         * Accepts a form-urlencoded shipping body.
         *
         * @param shipping the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/form")
        @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createSnakeShippingFromForm")
        public String createFromForm(@Valid Shipping shipping) {
            return "ok";
        }
    }

    /** Shipping route under no profile. */
    @Path("/shipping/plain")
    public static class PlainShippingResource {

        /**
         * Accepts a JSON shipping body.
         *
         * @param shipping the validated body
         * @return {@code "ok"}
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createPlainShipping")
        public String create(@Valid Shipping shipping) {
            return "ok";
        }
    }

    /** Shipping route under the case-insensitive profile. */
    @Path("/shipping/insensitive")
    @JsonProfile(CASE_INSENSITIVE_PROFILE)
    public static class CaseInsensitiveShippingResource {

        /**
         * Accepts a JSON shipping body.
         *
         * @param shipping the validated body
         * @return {@code "ok"}
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createInsensitiveShipping")
        public String create(@Valid Shipping shipping) {
            return "ok";
        }
    }

    /** Route whose body member is named by the empty string through the profile's naming strategy. */
    @Path("/blank-named")
    @JsonProfile(EMPTY_NAME_PROFILE)
    public static class EmptyNameResource {

        /**
         * Accepts the body.
         *
         * @param blank the validated body
         * @return {@code "ok"}
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createBlankNamed")
        public String create(@Valid Blank blank) {
            return "ok";
        }
    }

    /** Body shapes under the default profile, one route per shape. */
    @Path("/bodies")
    public static class ShapeResource {

        /**
         * Accepts a cart.
         *
         * @param cart the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/cart")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createCart")
        public String cart(@Valid Cart cart) {
            return "ok";
        }

        /**
         * Accepts a basket.
         *
         * @param basket the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/basket")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createBasket")
        public String basket(@Valid Basket basket) {
            return "ok";
        }

        /**
         * Accepts a ledger.
         *
         * @param ledger the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/ledger")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createLedger")
        public String ledger(@Valid Ledger ledger) {
            return "ok";
        }

        /**
         * Accepts a body of tags.
         *
         * @param tagged the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/tagged")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createTagged")
        public String tagged(@Valid Tagged tagged) {
            return "ok";
        }

        /**
         * Accepts a grid of strings.
         *
         * @param grid the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/grid")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createGrid")
        public String grid(@Valid Grid grid) {
            return "ok";
        }

        /**
         * Accepts a body whose member has an alias.
         *
         * @param aliased the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/aliased")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createAliased")
        public String aliased(@Valid Aliased aliased) {
            return "ok";
        }

        /**
         * Accepts a setter-bound body whose member has an alias.
         *
         * @param aliased the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/aliased-bean")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createAliasedBean")
        public String aliasedBean(@Valid AliasedBean aliased) {
            return "ok";
        }

        /**
         * Accepts a body whose list skips null elements.
         *
         * @param skipping the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/skipping")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createSkipping")
        public String skipping(@Valid Skipping skipping) {
            return "ok";
        }

        /**
         * Accepts a public-field body.
         *
         * @param address the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/public-address")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createPublicAddress")
        public String publicAddress(@Valid PublicAddress address) {
            return "ok";
        }

        /**
         * Accepts a body written through a renamed setter.
         *
         * @param renamed the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/renamed-setter")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createRenamedSetter")
        public String renamedSetter(@Valid RenamedSetter renamed) {
            return "ok";
        }

        /**
         * Accepts a root list of items.
         *
         * @param items the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/items")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createItems")
        public String items(@Valid List<Item> items) {
            return "ok";
        }

        /**
         * Accepts a root list of polymorphic animals.
         *
         * @param animals the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/animals")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createAnimals")
        public String animals(@Valid List<Animal> animals) {
            return "ok";
        }
    }

    /** A body route whose declared type is a raw collection, which no framework binder materializes. */
    @Path("/raw")
    public static class RawCollectionResource {

        /**
         * Accepts a raw list that must not be empty.
         *
         * @param raw the validated body
         * @return {@code "ok"}
         */
        @POST
        @Path("/list")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "createRawList")
        @SuppressWarnings("rawtypes")
        public String list(@NotEmpty List raw) {
            return "ok";
        }
    }

    /** A query route validated through the real bean validator. */
    @Path("/query")
    public static class QueryResource {

        /**
         * Accepts a list of ids, each of which must be at least one.
         *
         * @param ids the query values
         * @return {@code "ok"}
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "listByIds")
        public String byIds(@QueryParam("ids") List<@Min(1) Integer> ids) {
            return "ok";
        }
    }
}
