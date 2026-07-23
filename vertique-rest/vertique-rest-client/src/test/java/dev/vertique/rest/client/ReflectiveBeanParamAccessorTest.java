// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReflectiveBeanParamAccessor}.
 *
 * <p>Verifies that the reflective accessor extracts field values from records and regular classes
 * (including inheritance chains) with identical behaviour to the prior {@code extractFieldValue}
 * path in {@link RestClientRequestFactory}.
 */
class ReflectiveBeanParamAccessorTest {

    // --- Test fixtures ---

    /** Record bean param — annotations on record components. */
    record RecordBean(
            @QueryParam("q") String query,
            @QueryParam("page") int page,
            @HeaderParam("X-Token") String token) {}

    /** Regular class bean param — private fields with no public getters. */
    static class ClassBean {
        @QueryParam("q")
        String query;

        @QueryParam("page")
        int page;

        ClassBean(String query, int page) {
            this.query = query;
            this.page = page;
        }
    }

    /** Base class for inheritance fixture. */
    static class BaseBean {
        @QueryParam("base")
        String baseField;

        BaseBean(String baseField) {
            this.baseField = baseField;
        }
    }

    /** Subclass that adds a field; accessor must walk the superclass chain. */
    static class DerivedBean extends BaseBean {
        @QueryParam("derived")
        String derivedField;

        DerivedBean(String baseField, String derivedField) {
            super(baseField);
            this.derivedField = derivedField;
        }
    }

    // --- Fields ---

    private ReflectiveBeanParamAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = new ReflectiveBeanParamAccessor();
    }

    // --- Record tests ---

    @Nested
    @DisplayName("Record bean extraction")
    class RecordBeanTests {

        @Test
        @DisplayName("extracts String field from record component by name")
        void extractsStringFieldFromRecord() {
            RecordBean bean = new RecordBean("hello", 1, "tok");
            Object value = accessor.extract(bean, "query");
            assertThat(value).isEqualTo("hello");
        }

        @Test
        @DisplayName("extracts int field from record component by name")
        void extractsIntFieldFromRecord() {
            RecordBean bean = new RecordBean("hello", 42, "tok");
            Object value = accessor.extract(bean, "page");
            assertThat(value).isEqualTo(42);
        }

        @Test
        @DisplayName("extracts header token field from record component by name")
        void extractsHeaderFieldFromRecord() {
            RecordBean bean = new RecordBean("hello", 1, "Bearer xyz");
            Object value = accessor.extract(bean, "token");
            assertThat(value).isEqualTo("Bearer xyz");
        }

        @Test
        @DisplayName("returns null for unknown field name on record")
        void returnsNullForUnknownRecordField() {
            RecordBean bean = new RecordBean("hello", 1, "tok");
            Object value = accessor.extract(bean, "nonexistent");
            assertThat(value).isNull();
        }

        @Test
        @DisplayName("fieldNames() returns all record component names")
        void fieldNamesReturnsComponentNames() {
            assertThat(accessor.fieldNames()).isEmpty();
        }
    }

    // --- Regular class tests ---

    @Nested
    @DisplayName("Regular class bean extraction")
    class ClassBeanTests {

        @Test
        @DisplayName("extracts private String field from class by name using setAccessible")
        void extractsPrivateStringField() {
            ClassBean bean = new ClassBean("widget", 3);
            Object value = accessor.extract(bean, "query");
            assertThat(value).isEqualTo("widget");
        }

        @Test
        @DisplayName("extracts private int field from class by name using setAccessible")
        void extractsPrivateIntField() {
            ClassBean bean = new ClassBean("widget", 7);
            Object value = accessor.extract(bean, "page");
            assertThat(value).isEqualTo(7);
        }

        @Test
        @DisplayName("returns null for unknown field name on class")
        void returnsNullForUnknownClassField() {
            ClassBean bean = new ClassBean("widget", 7);
            Object value = accessor.extract(bean, "nonexistent");
            assertThat(value).isNull();
        }
    }

    // --- Inheritance tests ---

    @Nested
    @DisplayName("Inheritance chain extraction")
    class InheritanceBeanTests {

        @Test
        @DisplayName("extracts field declared in the subclass")
        void extractsSubclassField() {
            DerivedBean bean = new DerivedBean("baseVal", "derivedVal");
            Object value = accessor.extract(bean, "derivedField");
            assertThat(value).isEqualTo("derivedVal");
        }

        @Test
        @DisplayName("extracts field declared in the superclass by walking the chain")
        void extractsSuperclassField() {
            DerivedBean bean = new DerivedBean("baseVal", "derivedVal");
            Object value = accessor.extract(bean, "baseField");
            assertThat(value).isEqualTo("baseVal");
        }
    }

    // --- Cache shape tests ---

    /** Single-field record probe — used to assert positive resolution caches exactly one entry. */
    record RecordPositiveProbe(@QueryParam("a") String alpha) {}

    /** Two-field record probe — used to assert two distinct fields cache as two entries. */
    record RecordTwoFieldProbe(
            @QueryParam("a") String alpha, @QueryParam("b") String beta) {}

    /** Single-field record probe — used to assert negative resolution caches a single Miss entry. */
    record RecordNegativeProbe(@QueryParam("a") String alpha) {}

    /** Single-field class probe — used to assert positive resolution caches exactly one entry. */
    static class ClassPositiveProbe {
        @QueryParam("p")
        public String present;

        ClassPositiveProbe(String present) {
            this.present = present;
        }
    }

    /** Single-field class probe — used to assert negative resolution caches a single Miss entry. */
    static class ClassNegativeProbe {
        @QueryParam("p")
        public String present;

        ClassNegativeProbe(String present) {
            this.present = present;
        }
    }

    @Nested
    @DisplayName("Cache shape (per-(Class, fieldName) caching)")
    class CacheShapeTests {

        @Test
        @DisplayName("repeated extract for the same record field caches one entry per fieldName")
        void recordPositiveResolutionCachedOnce() {
            RecordPositiveProbe bean = new RecordPositiveProbe("a-val");
            for (int i = 0; i < 100; i++) {
                assertThat(accessor.extract(bean, "alpha")).isEqualTo("a-val");
            }
            assertThat(ReflectiveBeanParamAccessor.cachedFieldCount(RecordPositiveProbe.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("repeated extract for two different record fields caches one entry per fieldName")
        void recordTwoFieldsCachedSeparately() {
            RecordTwoFieldProbe bean = new RecordTwoFieldProbe("a-val", "b-val");
            for (int i = 0; i < 50; i++) {
                accessor.extract(bean, "alpha");
                accessor.extract(bean, "beta");
            }
            assertThat(ReflectiveBeanParamAccessor.cachedFieldCount(RecordTwoFieldProbe.class))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("repeated extract for an unknown record field caches a single Miss entry")
        void recordNegativeResolutionCachedOnce() {
            RecordNegativeProbe bean = new RecordNegativeProbe("a-val");
            for (int i = 0; i < 100; i++) {
                assertThat(accessor.extract(bean, "doesNotExist")).isNull();
            }
            assertThat(ReflectiveBeanParamAccessor.cachedFieldCount(RecordNegativeProbe.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("repeated extract for a class field caches one entry per fieldName")
        void classPositiveResolutionCachedOnce() {
            ClassPositiveProbe bean = new ClassPositiveProbe("p-val");
            for (int i = 0; i < 100; i++) {
                assertThat(accessor.extract(bean, "present")).isEqualTo("p-val");
            }
            assertThat(ReflectiveBeanParamAccessor.cachedFieldCount(ClassPositiveProbe.class))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("repeated extract for an unknown class field caches a single Miss entry")
        void classNegativeResolutionCachedOnce() {
            ClassNegativeProbe bean = new ClassNegativeProbe("p-val");
            for (int i = 0; i < 100; i++) {
                assertThat(accessor.extract(bean, "doesNotExist")).isNull();
            }
            assertThat(ReflectiveBeanParamAccessor.cachedFieldCount(ClassNegativeProbe.class))
                    .isEqualTo(1);
        }
    }
}
