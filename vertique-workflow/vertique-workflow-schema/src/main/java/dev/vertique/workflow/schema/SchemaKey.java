// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dev.vertique.core.json.JsonProfileId;

/**
 * Identifies one cached payload schema: the logical name of the payload type (its binary name when the class carries
 * no name annotation) together with the JSON profile the schema was generated under.
 *
 * @param typeName the logical name of the payload type
 * @param profileId the profile the schema was generated under
 */
record SchemaKey(String typeName, JsonProfileId profileId) {}
