// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static dev.vertique.rest.openapi.docs.AnnotationValues.setOrNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import jakarta.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The complete {@code info} of a document taken from the {@code @OpenAPIDefinition(info)} on the
 * application's declaring interface: every member of {@link Info}, {@link Contact}, and {@link
 * License} as declared, a blank string counting as unset.
 *
 * <p>An {@link Extension} of the annotation, of its contact, or of its license whose name starts
 * with {@code x-} (case-sensitive) becomes a member of that name whose value is an object of its
 * {@link ExtensionProperty} names and values; a property with a blank name is skipped, a property
 * with {@code parseValue = true} holds its value parsed as JSON when it parses strictly and the
 * value as a string otherwise, and extensions of one name merge their properties in declaration
 * order. An extension with any other name is not published; its name is kept in {@link
 * #unpublishedKeys()}, a blank name as {@value #UNNAMED}.
 *
 * @param title the document title, never blank
 * @param version the document version, never blank
 * @param description the description, or {@code null} when unset
 * @param summary the summary, or {@code null} when unset
 * @param termsOfService the terms of service URL, or {@code null} when unset
 * @param contact the contact, or {@code null} when no member and no published extension is set
 * @param license the license, or {@code null} when no member and no published extension is set
 * @param extensions the published extensions of the annotation, in declaration order
 * @param unpublishedKeys the names of every extension that is not published, of the annotation,
 *     its contact, and its license, sorted and without duplicates
 */
record AnnotatedInfo(
        String title,
        String version,
        @Nullable String description,
        @Nullable String summary,
        @Nullable String termsOfService,
        @Nullable ContactMembers contact,
        @Nullable LicenseMembers license,
        Map<String, JsonNode> extensions,
        SortedSet<String> unpublishedKeys) {

    /** The name an extension with a blank name is listed under among the unpublished keys. */
    static final String UNNAMED = "<unnamed>";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * Copies the extensions and the unpublished keys unmodifiably, keeping their order.
     *
     * @param title the document title
     * @param version the document version
     * @param description the description, or {@code null}
     * @param summary the summary, or {@code null}
     * @param termsOfService the terms of service URL, or {@code null}
     * @param contact the contact, or {@code null}
     * @param license the license, or {@code null}
     * @param extensions the published extensions
     * @param unpublishedKeys the names of the extensions that are not published
     */
    AnnotatedInfo {
        extensions = Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
        unpublishedKeys = Collections.unmodifiableSortedSet(new TreeSet<>(unpublishedKeys));
    }

    /**
     * Reads the members of an {@link Info} whose {@code title} and {@code version} are not blank.
     *
     * @param info the annotation
     * @return its members
     */
    static AnnotatedInfo of(Info info) {
        SortedSet<String> unpublished = new TreeSet<>();
        Map<String, JsonNode> extensions = readExtensions(info.extensions(), unpublished);
        return new AnnotatedInfo(
                info.title(),
                info.version(),
                setOrNull(info.description()),
                setOrNull(info.summary()),
                setOrNull(info.termsOfService()),
                ContactMembers.of(info.contact(), unpublished),
                LicenseMembers.of(info.license(), unpublished),
                extensions,
                unpublished);
    }

    /**
     * Reports whether a license sets both {@code identifier} and {@code url}, which the OpenAPI 3.1
     * License Object makes mutually exclusive.
     *
     * @param license the annotation
     * @return {@code true} when both are non-blank
     */
    static boolean hasIdentifierAndUrl(License license) {
        return !license.identifier().isBlank() && !license.url().isBlank();
    }

    /**
     * The members of the {@link Contact} of the annotation.
     *
     * @param name the contact name, or {@code null} when unset
     * @param url the contact URL, or {@code null} when unset
     * @param email the contact email, or {@code null} when unset
     * @param extensions the published extensions of the contact, in declaration order
     */
    record ContactMembers(
            @Nullable String name,
            @Nullable String url,
            @Nullable String email,
            Map<String, JsonNode> extensions) {

        /**
         * Copies the extensions unmodifiably, keeping their order.
         *
         * @param name the contact name, or {@code null}
         * @param url the contact URL, or {@code null}
         * @param email the contact email, or {@code null}
         * @param extensions the published extensions
         */
        ContactMembers {
            extensions = Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
        }

        @Nullable
        private static ContactMembers of(Contact contact, SortedSet<String> unpublished) {
            ContactMembers members = new ContactMembers(
                    setOrNull(contact.name()),
                    setOrNull(contact.url()),
                    setOrNull(contact.email()),
                    readExtensions(contact.extensions(), unpublished));
            boolean set = members.name() != null
                    || members.url() != null
                    || members.email() != null
                    || !members.extensions().isEmpty();
            return set ? members : null;
        }
    }

    /**
     * The members of the {@link License} of the annotation.
     *
     * @param name the license name, or {@code null} when unset
     * @param identifier the SPDX license identifier, or {@code null} when unset
     * @param url the license URL, or {@code null} when unset
     * @param extensions the published extensions of the license, in declaration order
     */
    record LicenseMembers(
            @Nullable String name,
            @Nullable String identifier,
            @Nullable String url,
            Map<String, JsonNode> extensions) {

        /**
         * Copies the extensions unmodifiably, keeping their order.
         *
         * @param name the license name, or {@code null}
         * @param identifier the SPDX license identifier, or {@code null}
         * @param url the license URL, or {@code null}
         * @param extensions the published extensions
         */
        LicenseMembers {
            extensions = Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
        }

        @Nullable
        private static LicenseMembers of(License license, SortedSet<String> unpublished) {
            LicenseMembers members = new LicenseMembers(
                    setOrNull(license.name()),
                    setOrNull(license.identifier()),
                    setOrNull(license.url()),
                    readExtensions(license.extensions(), unpublished));
            boolean set = members.name() != null
                    || members.identifier() != null
                    || members.url() != null
                    || !members.extensions().isEmpty();
            return set ? members : null;
        }
    }

    /**
     * Reads extensions in declaration order: the {@code x-} ones become members, merged by name, and
     * the names of the others are added to {@code unpublished}.
     *
     * @param declared the extensions, in declaration order
     * @param unpublished receives the name of every extension that is not published, a blank name as
     *     {@value #UNNAMED}
     * @return the published extensions by name, in declaration order
     */
    static Map<String, JsonNode> readExtensions(Extension[] declared, SortedSet<String> unpublished) {
        Map<String, JsonNode> published = new LinkedHashMap<>();
        for (Extension extension : declared) {
            String name = extension.name();
            if (!name.startsWith("x-")) {
                unpublished.add(name.isBlank() ? UNNAMED : name);
                continue;
            }
            ObjectNode properties = (ObjectNode) published.computeIfAbsent(name, key -> NODES.objectNode());
            for (ExtensionProperty property : extension.properties()) {
                if (property.name().isBlank()) {
                    continue;
                }
                properties.set(property.name(), value(property));
            }
        }
        return published;
    }

    /**
     * Returns a property's value: parsed as JSON when {@code parseValue} is set and the value parses
     * strictly, else the value as a string.
     */
    private static JsonNode value(ExtensionProperty property) {
        JsonNode parsed = property.parseValue() ? Examples.parseStrictly(property.value()) : null;
        return parsed != null ? parsed : NODES.textNode(property.value());
    }
}
