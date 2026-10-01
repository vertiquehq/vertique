// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import dev.vertique.rest.core.RestConfigurationException;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import java.util.ArrayList;
import java.util.List;

/**
 * Separates the inputs a document leaves out from the ones it describes, by the binding inventory's
 * {@link InputBinding#hidden() hidden} flag alone; hiding is never re-derived from annotations.
 *
 * <p>A hidden input publishes nothing, and nothing after the omission reads it: no check,
 * verification, or schema of a hidden input is ever looked at. The one exception is {@link
 * #refusePath}, which runs on the full inventory first, because a document must describe every
 * variable of a path template, so a path parameter cannot be left out.
 */
final class HiddenInputs {

    private HiddenInputs() {}

    /**
     * Fails when an operation hides one of its path parameters, whatever the binding's origin.
     *
     * @param subject the failure-message subject naming the application and its mount
     * @param operationId the runtime id of the operation
     * @param inputs the operation's full binding inventory
     * @throws RestConfigurationException naming the first hidden path parameter in inventory order
     */
    static void refusePath(String subject, String operationId, List<InputBinding> inputs) {
        for (InputBinding binding : inputs) {
            if (binding.hidden() && binding.location() == ParamLocation.PATH) {
                throw new RestConfigurationException(subject + ": operation '" + operationId
                        + "' hides its path parameter '" + binding.name()
                        + "'; a document must describe every path variable, so a path parameter cannot be hidden");
            }
        }
    }

    /**
     * Returns the inputs a document describes.
     *
     * @param inputs the operation's full binding inventory
     * @return the bindings not flagged hidden, in inventory order
     */
    static List<InputBinding> visible(List<InputBinding> inputs) {
        List<InputBinding> visible = new ArrayList<>(inputs.size());
        for (InputBinding binding : inputs) {
            if (!binding.hidden()) {
                visible.add(binding);
            }
        }
        return List.copyOf(visible);
    }
}
