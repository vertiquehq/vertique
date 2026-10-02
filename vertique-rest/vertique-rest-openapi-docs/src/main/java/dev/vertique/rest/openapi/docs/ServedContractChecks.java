// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static dev.vertique.rest.openapi.docs.ContractReferences.child;
import static dev.vertique.rest.openapi.docs.ContractReferences.display;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.openapi.docs.ContractReferences.Hop;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks the parsed contract an application serves as its document against the routes of its mount.
 *
 * <p>The root must be an object whose {@code openapi} member is a string {@code 3.0.<n>} or {@code
 * 3.1.<n>}; otherwise that one violation is reported and no other check runs. A literal walk of the whole tree then refuses every string {@code $ref} that does not
 * start with {@code #/} and every member named {@code operationRef} or {@code $id}, and collects
 * every {@code operationId} member with a string value.
 *
 * <p>Path Items are found under {@code paths} first, in document order, then under {@code webhooks},
 * in Callback Objects ({@code components.callbacks} and the {@code callbacks} of every operation
 * found), and under {@code components.pathItems}. A Path Item given by a local {@code $ref} is
 * followed with a visited set, so a cycle fails naming the reference that closes it. Each Operation
 * Object ({@code get}, {@code put}, {@code post}, {@code delete}, {@code options}, {@code head},
 * {@code patch}, {@code trace}) is counted once, at its own location, and belongs to one class:
 *
 * <ul>
 *   <li>a route operation, when its Path Item is under {@code paths} or reached from there by
 *       reference: its id must be routed, it is bound to the routed operation of that id by HTTP
 *       method (ignoring ASCII case) and by every referencing {@code paths} key with each {@code
 *       {...}} variable reduced to {@code {}}, mount-relative or prefixed by the mount path, and it
 *       counts toward completeness;
 *   <li>a webhook or callback operation, when its Path Item is otherwise under {@code webhooks} or
 *       in a Callback Object, or reached from there: its id must not be a routed id;
 *   <li>any other Operation Object, an unreferenced {@code components.pathItems} entry: its id must
 *       be routed.
 * </ul>
 *
 * <p>Every Operation Object needs a non-blank string {@code operationId}, and no two share one. Every
 * other {@code operationId} member must name a routed operation; when it belongs to a Link Object
 * (a member of a {@code links} object), the Link is checked against that operation's hidden inputs; a
 * member of a {@code links} object that is a local {@code $ref} is followed, and the Link it resolves
 * to is checked the same way at its own pointer.
 * Routed ids include hidden operations; every routed operation that is not hidden must have a route
 * operation. Route operations and the other Operation Objects naming a routed operation are checked
 * against its hidden inputs by {@link ServedContractInputs}. Ids are compared exactly.
 *
 * <p>Every violation found is collected, sorted, and reported in one {@link
 * RestConfigurationException} naming the application. A violation names operation ids, JSON
 * Pointers, and fixed wording only, never a description, example, schema, or reference value of the
 * contract. Every walk is iterative, so the depth of the document never exhausts the stack.
 */
final class ServedContractChecks {

    /** The {@code openapi} values a served contract may declare. */
    private static final Pattern VERSION = Pattern.compile("3\\.[01]\\.[0-9]+");

    private final JsonNode root;

    /** The mount path without a trailing {@code /*} or {@code /}, empty for the root mount. */
    private final String mountPrefix;

    private final Map<String, RoutedOperation> routedById = new HashMap<>();
    private final Set<String> violations;
    private final ContractReferences references;
    private final ServedContractInputs inputs;

    /** Every Path Item found, by JSON Pointer, in the order found. */
    private final Map<String, PathItem> pathItems = new LinkedHashMap<>();

    /** The Path Items still to register, in the order found. */
    private final Deque<Start> pending = new ArrayDeque<>();

    /** Every Path Item pointer a reference chain has visited. */
    private final Set<String> chained = new HashSet<>();

    /** Every Callback Object pointer whose Path Items are queued. */
    private final Set<String> callbacks = new HashSet<>();

    /** The pointers of every unrouted id, by id. */
    private final Map<String, Set<String>> unrouted = new TreeMap<>();

    /** The members of {@code links} objects that are local references, in the order found. */
    private final List<Hop> linkReferences = new ArrayList<>();

    private ServedContractChecks(
            JsonNode root, String mountPath, List<RoutedOperation> routed, Set<String> violations) {
        this.root = root;
        this.mountPrefix = mountPrefix(mountPath);
        this.violations = violations;
        this.references = new ContractReferences(root, violations);
        this.inputs = new ServedContractInputs(references, violations);
        for (RoutedOperation operation : routed) {
            routedById.putIfAbsent(operation.operationId(), operation);
        }
    }

    /**
     * Checks a served contract.
     *
     * @param application the application's name
     * @param contract the parsed contract
     * @param mountPath the application's normalized mount path
     * @param routed the operations the mount routes, hidden ones included
     * @throws RestConfigurationException naming the application and every violation found
     */
    static void check(String application, JsonNode contract, String mountPath, List<RoutedOperation> routed) {
        Set<String> violations = new TreeSet<>();
        if (contract == null || !contract.isObject()) {
            violations.add("the document root must be OpenAPI 3.0 or 3.1, and it is not an object");
        } else {
            JsonNode openapi = contract.get("openapi");
            if (openapi == null
                    || !openapi.isTextual()
                    || !VERSION.matcher(openapi.textValue()).matches()) {
                violations.add("member /openapi must be OpenAPI 3.0 or 3.1, a string of the form 3.0.<n> or 3.1.<n>");
            } else {
                new ServedContractChecks(contract, mountPath, routed, violations).run();
            }
        }
        if (!violations.isEmpty()) {
            throw new RestConfigurationException("The served contract of application '" + display(application)
                    + "' is refused: " + String.join("; ", violations));
        }
    }

    /** Runs every check after the version check. */
    private void run() {
        List<IdMember> members = literalWalk();
        findPathItems();
        Set<String> operationIdPointers = checkOperations();
        Set<String> checkedLinks = new HashSet<>();
        for (IdMember member : members) {
            if (operationIdPointers.contains(member.pointer())) {
                continue;
            }
            RoutedOperation twin = routedById.get(member.id());
            if (twin == null) {
                recordUnrouted(member.id(), member.pointer());
            } else if (member.inLinks()) {
                checkedLinks.add(member.ownerPointer());
                inputs.checkLink(member.ownerPointer(), member.owner(), twin);
            }
        }
        for (Hop reference : linkReferences) {
            ContractReferences.Chain chain = references.follow(reference.pointer(), reference.node());
            if (!chain.complete()) {
                continue;
            }
            Hop link = chain.last();
            JsonNode id = link.node().get("operationId");
            RoutedOperation twin = id != null && id.isTextual() ? routedById.get(id.textValue()) : null;
            if (twin != null && checkedLinks.add(link.pointer())) {
                inputs.checkLink(link.pointer(), link.node(), twin);
            }
        }
        unrouted.forEach((id, pointers) -> violations.add(
                "operationId '" + display(id) + "' is not routed by the mount (at " + displayAll(pointers) + ")"));
    }

    // ---------------------------------------------------------------------------------------------
    // The literal walk
    // ---------------------------------------------------------------------------------------------

    /**
     * One {@code operationId} member with a string value.
     *
     * @param pointer the member's JSON Pointer
     * @param id the member's value
     * @param ownerPointer the JSON Pointer of the object holding the member
     * @param owner the object holding the member
     * @param inLinks whether that object is a member of an object named {@code links}
     */
    private record IdMember(String pointer, String id, String ownerPointer, JsonNode owner, boolean inLinks) {}

    /**
     * One node of the literal walk.
     *
     * @param pointer the node's JSON Pointer
     * @param node the node
     * @param memberName the member name the node is the value of, or {@code null} for an element
     * @param inLinks whether the node is a member of an object named {@code links}
     */
    private record Frame(String pointer, JsonNode node, String memberName, boolean inLinks) {}

    /** Refuses non-local references, {@code operationRef}, and {@code $id}; collects string operationIds. */
    private List<IdMember> literalWalk() {
        List<IdMember> members = new ArrayList<>();
        linkReferences.clear();
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame("", root, null, false));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            JsonNode node = frame.node();
            if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    stack.push(new Frame(child(frame.pointer(), Integer.toString(i)), node.get(i), null, false));
                }
            } else if (node.isObject()) {
                JsonNode linkRef = frame.inLinks() ? node.get("$ref") : null;
                if (linkRef != null
                        && linkRef.isTextual()
                        && linkRef.textValue().startsWith("#/")) {
                    linkReferences.add(new Hop(frame.pointer(), node));
                }
                boolean linksObject = "links".equals(frame.memberName());
                for (Map.Entry<String, JsonNode> entry : node.properties()) {
                    String name = entry.getKey();
                    JsonNode value = entry.getValue();
                    String pointer = child(frame.pointer(), name);
                    if (name.equals("$ref")
                            && value.isTextual()
                            && !value.textValue().startsWith("#/")) {
                        violations.add("member " + display(pointer)
                                + " is not a local reference: a reference must start with #/");
                    } else if (name.equals("operationRef") || name.equals("$id")) {
                        violations.add("member " + display(pointer) + " is not allowed in a served contract");
                    } else if (name.equals("operationId") && value.isTextual()) {
                        members.add(new IdMember(pointer, value.textValue(), frame.pointer(), node, frame.inLinks()));
                    }
                    stack.push(new Frame(pointer, value, name, linksObject));
                }
            }
        }
        return members;
    }

    // ---------------------------------------------------------------------------------------------
    // Path Items
    // ---------------------------------------------------------------------------------------------

    /**
     * Where a Path Item is reached from.
     *
     * @param pointer the Path Item's JSON Pointer
     * @param node the Path Item, or the object referencing it
     * @param routeKey the {@code paths} key it is reached from, or {@code null}
     * @param outbound whether it is reached from {@code webhooks} or a Callback Object
     */
    private record Start(String pointer, JsonNode node, String routeKey, boolean outbound) {}

    /** A Path Item and every way it is reached. */
    private static final class PathItem {
        final String pointer;
        final JsonNode node;
        final Set<String> routeKeys = new LinkedHashSet<>();
        final Map<String, JsonNode> referrers = new LinkedHashMap<>();
        boolean outbound;

        PathItem(String pointer, JsonNode node) {
            this.pointer = pointer;
            this.node = node;
        }
    }

    /** Registers every Path Item, following references from {@code paths} first. */
    private void findPathItems() {
        JsonNode paths = root.get("paths");
        if (paths != null && paths.isObject()) {
            for (Map.Entry<String, JsonNode> entry : paths.properties()) {
                if (!entry.getKey().startsWith("x-")) {
                    pending.add(new Start(child("/paths", entry.getKey()), entry.getValue(), entry.getKey(), false));
                }
            }
        }
        JsonNode webhooks = root.get("webhooks");
        if (webhooks != null && webhooks.isObject()) {
            for (Map.Entry<String, JsonNode> entry : webhooks.properties()) {
                pending.add(new Start(child("/webhooks", entry.getKey()), entry.getValue(), null, true));
            }
        }
        JsonNode components = root.get("components");
        JsonNode componentCallbacks = components == null ? null : components.get("callbacks");
        if (componentCallbacks != null && componentCallbacks.isObject()) {
            for (Map.Entry<String, JsonNode> entry : componentCallbacks.properties()) {
                queueCallback(child("/components/callbacks", entry.getKey()), entry.getValue());
            }
        }
        JsonNode componentPathItems = components == null ? null : components.get("pathItems");
        if (componentPathItems != null && componentPathItems.isObject()) {
            for (Map.Entry<String, JsonNode> entry : componentPathItems.properties()) {
                pending.add(new Start(child("/components/pathItems", entry.getKey()), entry.getValue(), null, false));
            }
        }
        while (!pending.isEmpty()) {
            Start start = pending.poll();
            boolean componentOnly = start.routeKey() == null && !start.outbound();
            if (componentOnly && chained.contains(start.pointer())) {
                register(start.pointer(), start.node());
                continue;
            }
            List<ContractReferences.Hop> hops =
                    references.follow(start.pointer(), start.node()).hops();
            for (int i = 0; i < hops.size(); i++) {
                ContractReferences.Hop hop = hops.get(i);
                chained.add(hop.pointer());
                PathItem pathItem = register(hop.pointer(), hop.node());
                if (pathItem == null) {
                    continue;
                }
                if (start.routeKey() != null) {
                    pathItem.routeKeys.add(start.routeKey());
                }
                pathItem.outbound |= start.outbound();
                for (int j = 0; j < i; j++) {
                    pathItem.referrers.putIfAbsent(
                            hops.get(j).pointer(), hops.get(j).node());
                }
            }
        }
    }

    /** Registers a Path Item once, queueing the Path Items of its operations' callbacks. */
    private PathItem register(String pointer, JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        PathItem known = pathItems.get(pointer);
        if (known != null) {
            return known;
        }
        PathItem pathItem = new PathItem(pointer, node);
        pathItems.put(pointer, pathItem);
        for (String method : RenderedPaths.METHOD_ORDER) {
            JsonNode operation = node.get(method);
            JsonNode operationCallbacks = operation == null ? null : operation.get("callbacks");
            if (operationCallbacks != null && operationCallbacks.isObject()) {
                String base = child(child(pointer, method), "callbacks");
                for (Map.Entry<String, JsonNode> entry : operationCallbacks.properties()) {
                    queueCallback(child(base, entry.getKey()), entry.getValue());
                }
            }
        }
        return pathItem;
    }

    /** Queues the Path Items of a Callback Object, following a reference to it, once per Callback Object. */
    private void queueCallback(String pointer, JsonNode node) {
        ContractReferences.Chain chain = references.follow(pointer, node);
        ContractReferences.Hop callback = chain.last();
        if (!chain.complete() || !callback.node().isObject() || !callbacks.add(callback.pointer())) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : callback.node().properties()) {
            if (!entry.getKey().startsWith("x-")) {
                pending.add(new Start(child(callback.pointer(), entry.getKey()), entry.getValue(), null, true));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Operation Objects
    // ---------------------------------------------------------------------------------------------

    /**
     * Checks every Operation Object of every Path Item by its class.
     *
     * @return the JSON Pointers of the Operation Objects' {@code operationId} members
     */
    private Set<String> checkOperations() {
        Set<String> operationIdPointers = new HashSet<>();
        Map<String, Set<String>> pointersById = new TreeMap<>();
        Set<String> described = new HashSet<>();
        for (PathItem pathItem : pathItems.values()) {
            for (String method : RenderedPaths.METHOD_ORDER) {
                JsonNode operation = pathItem.node.get(method);
                if (operation == null) {
                    continue;
                }
                String pointer = child(pathItem.pointer, method);
                JsonNode idNode = operation.get("operationId");
                if (idNode == null || !idNode.isTextual() || idNode.textValue().isBlank()) {
                    violations.add("operation object " + display(pointer) + " has no operationId");
                    continue;
                }
                String id = idNode.textValue();
                String idPointer = child(pointer, "operationId");
                operationIdPointers.add(idPointer);
                pointersById.computeIfAbsent(id, key -> new TreeSet<>()).add(pointer);
                RoutedOperation twin = routedById.get(id);
                if (!pathItem.routeKeys.isEmpty()) {
                    if (twin == null) {
                        recordUnrouted(id, idPointer);
                        continue;
                    }
                    described.add(id);
                    if (!bound(method, pathItem.routeKeys, twin)) {
                        violations.add("operation object " + display(pointer) + " with operationId '" + display(id)
                                + "' is not bound to its routed operation: its HTTP method or path differs");
                    }
                    inputs.checkOperation(pointer, operation, pathItemsOf(pathItem), twin);
                } else if (pathItem.outbound) {
                    if (twin != null) {
                        violations.add("webhook or callback operation " + display(pointer)
                                + " reuses a routed operation id '" + display(id) + "'");
                    }
                } else if (twin == null) {
                    recordUnrouted(id, idPointer);
                } else {
                    inputs.checkOperation(pointer, operation, pathItemsOf(pathItem), twin);
                }
            }
        }
        pointersById.forEach((id, pointers) -> {
            if (pointers.size() > 1) {
                violations.add("operationId '" + display(id) + "' is repeated at " + displayAll(pointers));
            }
        });
        routedById.values().stream()
                .filter(operation -> !operation.hidden() && !described.contains(operation.operationId()))
                .forEach(operation ->
                        violations.add("routed operation '" + display(operation.operationId()) + "' is not described"));
        return operationIdPointers;
    }

    /** Records the pointer of an {@code operationId} member whose id the mount does not route. */
    private void recordUnrouted(String id, String idPointer) {
        unrouted.computeIfAbsent(id, key -> new TreeSet<>()).add(idPointer);
    }

    /** Returns a Path Item and every Path Item that references it, each with its JSON Pointer. */
    private static List<ContractReferences.Hop> pathItemsOf(PathItem pathItem) {
        List<ContractReferences.Hop> all = new ArrayList<>();
        all.add(new ContractReferences.Hop(pathItem.pointer, pathItem.node));
        pathItem.referrers.forEach((pointer, node) -> all.add(new ContractReferences.Hop(pointer, node)));
        return all;
    }

    /** Tells whether a route operation matches its routed twin by method and by every path key. */
    private boolean bound(String method, Set<String> routeKeys, RoutedOperation twin) {
        if (!method.equals(ResponseAssembler.asciiLowerCase(twin.httpMethod()))) {
            return false;
        }
        String relative = reduce(twin.renderedPath());
        Set<String> accepted = new HashSet<>();
        accepted.add(relative);
        accepted.add(mountPrefix + relative);
        if (relative.equals("/") && !mountPrefix.isEmpty()) {
            accepted.add(mountPrefix);
        }
        for (String key : routeKeys) {
            if (!accepted.contains(reduce(key))) {
                return false;
            }
        }
        return true;
    }

    /** Returns the mount path without a trailing {@code /*} or {@code /}, empty for the root mount. */
    private static String mountPrefix(String mountPath) {
        String mount = mountPath == null ? "" : mountPath;
        if (mount.endsWith("/*")) {
            mount = mount.substring(0, mount.length() - 2);
        }
        while (mount.endsWith("/")) {
            mount = mount.substring(0, mount.length() - 1);
        }
        return mount;
    }

    /** Reduces every {@code {...}} variable of a path to {@code {}}. */
    private static String reduce(String path) {
        StringBuilder out = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            int close = c == '{' ? path.indexOf('}', i) : -1;
            if (close < 0) {
                out.append(c);
                i++;
            } else {
                out.append("{}");
                i = close + 1;
            }
        }
        return out.toString();
    }

    /** Renders sorted pointers for a message, separated by commas. */
    private static String displayAll(Set<String> pointers) {
        return pointers.stream().map(ContractReferences::display).collect(Collectors.joining(", "));
    }
}
