package ${package}.model;

/**
 * Request body for updating an existing item.
 *
 * @param name        the item name
 * @param description optional description
 */
public record UpdateItemRequest(String name, String description) {}
