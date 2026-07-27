package ${package}.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for updating an existing item.
 *
 * <p>All three constraints are enforced by the framework's request-validation gate before the
 * resource method runs, so the resource needs no hand-written presence check: a missing, empty,
 * whitespace-only, or over-length name is rejected with {@code 400}.
 *
 * @param name        the item name; required, non-blank, and at most 255 characters
 * @param description optional description
 */
public record UpdateItemRequest(
        // @Pattern restores whitespace-only rejection on top of @NotBlank's minLength: the synthesized
        // JSON Schema pattern is unanchored, so "\S" matches only a value carrying a non-whitespace
        // character — the same rule as String.isBlank(). That relies on JSON-Schema (unanchored)
        // matching; a standard Bean Validation runtime anchors @Pattern, where "\S" would reject any
        // name longer than one character. @Size(max = 255) matches the items.name column width.
        @NotBlank @Pattern(regexp = "\\S") @Size(max = 255) String name, String description) {}
