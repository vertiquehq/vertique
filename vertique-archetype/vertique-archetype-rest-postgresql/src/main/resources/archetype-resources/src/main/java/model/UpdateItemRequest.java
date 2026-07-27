package ${package}.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for updating an existing item.
 *
 * <p>Both constraints are enforced by the framework's request-validation gate before the resource
 * method runs, so the resource needs no hand-written presence check: a missing, empty, or
 * whitespace-only name is rejected with {@code 400}.
 *
 * @param name        the item name; required and non-blank
 * @param description optional description
 */
public record UpdateItemRequest(
        // @Pattern restores whitespace-only rejection on top of @NotBlank's minLength: the synthesized
        // JSON Schema pattern is unanchored, so "\S" matches only a value carrying a non-whitespace
        // character — the same rule as String.isBlank().
        @NotBlank @Pattern(regexp = "\\S") String name, String description) {}
