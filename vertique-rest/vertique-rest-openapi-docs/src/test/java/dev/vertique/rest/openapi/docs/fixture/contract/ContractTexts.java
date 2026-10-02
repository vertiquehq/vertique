// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.contract;

/**
 * The texts of the {@code partner} contracts a test writes at run time instead of reading from the
 * test classpath: the working-directory file that shadows the classpath resource {@value
 * ContractFiles#SHADOWED}, the file written to an absolute location, and the file written after a
 * first, failed deployment at {@value ContractFiles#LATE}.
 *
 * <p>Each is a valid OpenAPI {@code 3.1.0} JSON contract for {@code partner}: mount-relative keys,
 * {@code listOrders} and {@code createOrder} described and nothing else, every operation with {@code
 * responses}. They differ in {@code info.title}; for {@link #WORKING_DIRECTORY_SHADOWED}, in the
 * request schema of {@code createOrder}, which also requires {@code quantity}; and in {@code servers}.
 * {@link #WORKING_DIRECTORY_SHADOWED} and {@link #ABSOLUTE} carry no {@code servers} member, because
 * a test also deploys them under the contract-validation strategy, which accepts only absolute server
 * URLs or none and answers every validated request with 500 for a relative one. {@link #LATE} keeps
 * {@code servers[0].url} {@code /api/partner}.
 */
public final class ContractTexts {

    /** The {@code info.title} of {@link #WORKING_DIRECTORY_SHADOWED}. */
    public static final String WORKING_DIRECTORY_TITLE = "working-directory";

    /** The {@code info.title} of the classpath resource {@value ContractFiles#SHADOWED}. */
    public static final String CLASSPATH_TITLE = "classpath";

    /** The {@code info.title} of {@link #ABSOLUTE}. */
    public static final String ABSOLUTE_TITLE = "absolute";

    /** The {@code info.title} of {@link #LATE}. */
    public static final String LATE_TITLE = "late";

    /**
     * The working-directory variant of {@value ContractFiles#SHADOWED}: {@code info.title} {@value
     * #WORKING_DIRECTORY_TITLE}, no {@code servers} member, and {@code createOrder}'s request schema
     * requires both {@code sku} and {@code quantity}.
     */
    public static final String WORKING_DIRECTORY_SHADOWED =
            partner(WORKING_DIRECTORY_TITLE, "\"sku\", \"quantity\"", false);

    /**
     * The contract a test writes to an absolute location: {@code info.title} {@value
     * #ABSOLUTE_TITLE}, no {@code servers} member; {@code createOrder}'s request schema requires
     * {@code sku}.
     */
    public static final String ABSOLUTE = partner(ABSOLUTE_TITLE, "\"sku\"", false);

    /**
     * The contract a test writes at {@value ContractFiles#LATE} after a first deployment failed:
     * {@code info.title} {@value #LATE_TITLE}, {@code servers[0].url} {@code /api/partner}; {@code
     * createOrder}'s request schema requires {@code sku}.
     */
    public static final String LATE = partner(LATE_TITLE, "\"sku\"", true);

    private ContractTexts() {}

    /**
     * Returns a {@code partner} contract.
     *
     * @param title    the {@code info.title}
     * @param required the members of {@code OrderRequest}'s {@code required} array, as JSON
     * @param servers  whether the contract has the {@code servers} member naming {@code /api/partner}
     * @return the contract text
     */
    private static String partner(String title, String required, boolean servers) {
        String serversMember = servers ? """
                  "servers": [
                    {
                      "url": "/api/partner"
                    }
                  ],
                """ : "";
        return """
                {
                  "openapi": "3.1.0",
                  "info": {
                    "title": "%s",
                    "version": "1.0.0"
                  },
                %s  "paths": {
                    "/orders": {
                      "get": {
                        "operationId": "listOrders",
                        "parameters": [
                          {
                            "name": "page",
                            "in": "query",
                            "required": false,
                            "schema": {
                              "type": "integer",
                              "minimum": 1
                            }
                          }
                        ],
                        "responses": {
                          "200": {
                            "description": "The partner orders"
                          }
                        }
                      },
                      "post": {
                        "operationId": "createOrder",
                        "requestBody": {
                          "required": true,
                          "content": {
                            "application/json": {
                              "schema": {
                                "$ref": "#/components/schemas/OrderRequest"
                              }
                            }
                          }
                        },
                        "responses": {
                          "204": {
                            "description": "Created"
                          }
                        }
                      }
                    }
                  },
                  "components": {
                    "schemas": {
                      "OrderRequest": {
                        "type": "object",
                        "required": [%s],
                        "properties": {
                          "sku": {
                            "type": "string",
                            "pattern": "^[A-Z]{3}-[0-9]{4}$"
                          },
                          "quantity": {
                            "type": "integer",
                            "minimum": 1
                          }
                        }
                      }
                    }
                  }
                }
                """.formatted(title, serversMember, required);
    }
}
