package kz.fin.backend.methodology.persistence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIN HTAP W0 hardening: a PlanPreview reader may not mutate an immutable
 * persisted parameter tree or fail on legitimate JSON null values.
 */
class MethodConfigVersionEntityImmutabilityTest {
    private static MethodConfigVersionEntity withParams(Map<String, Object> json)
            throws Exception {
        Constructor<MethodConfigVersionEntity> constructor =
                MethodConfigVersionEntity.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var entity = constructor.newInstance();
        Field field = MethodConfigVersionEntity.class.getDeclaredField("parameters");
        field.setAccessible(true);
        field.set(entity, json);
        return entity;
    }

    @SuppressWarnings("unchecked")
    @Test void protectsNestedObjectsArraysAndNullWithoutChangingSource() throws Exception {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("threshold", new BigDecimal("75.00"));
        nested.put("nullableOption", null);
        var row = new LinkedHashMap<String, Object>();
        row.put("member", new ArrayList<>(List.of("A", "B")));
        var rows = new ArrayList<Object>();
        rows.add(row);
        var original = new LinkedHashMap<String, Object>();
        original.put("nested", nested);
        original.put("rows", rows);
        original.put("unset", null);

        var entity = withParams(original);
        Map<String, Object> snapshot = entity.getParameters();

        assertTrue(snapshot.containsKey("unset"));
        assertNull(snapshot.get("unset"));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put("new", "forbidden"));

        var deepNested = (Map<String, Object>) snapshot.get("nested");
        assertNull(deepNested.get("nullableOption"));
        assertThrows(UnsupportedOperationException.class,
                () -> deepNested.put("threshold", new BigDecimal("90")));

        var frozenRows = (List<Object>) snapshot.get("rows");
        assertThrows(UnsupportedOperationException.class, () -> frozenRows.add("X"));
        var frozenMember = (List<Object>) ((Map<String, Object>) frozenRows.getFirst())
                .get("member");
        assertThrows(UnsupportedOperationException.class, () -> frozenMember.add("C"));

        nested.put("threshold", new BigDecimal("90"));
        rows.clear();
        assertEquals(new BigDecimal("75.00"), deepNested.get("threshold"));
        assertEquals(1, frozenRows.size());
        assertEquals(List.of("A", "B"), frozenMember);
    }

    @Test void rejectsNonJsonMutableObjectsRatherThanLeakingReferences() throws Exception {
        var invalid = new LinkedHashMap<String, Object>();
        invalid.put("object", new StringBuilder("mutable"));
        var entity = withParams(invalid);
        var exception=assertThrows(IllegalStateException.class,entity::getParameters);
        assertEquals("FIN_METHOD_CONFIG_UNSUPPORTED_JSON_VALUE",exception.getMessage());
    }
}
