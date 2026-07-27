package ${package}.model;

/**
 * Request body for creating a new item.
 *
 * @param name        the item name
 * @param description optional description
 */
public record CreateItemRequest(String name, String description) {}
