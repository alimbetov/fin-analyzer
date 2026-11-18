package kz.fin.formlib;

import jakarta.persistence.Lob;
import kz.fin.formlib.domain.FormRevision;
import kz.fin.formlib.domain.FormRevisionEvidence;
import kz.fin.formlib.domain.FormRevisionRow;
import kz.fin.formlib.domain.FormValidationRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL 16 does not recognize H2's CLOB SQL type.
 * A JDBC TEXT column must not be mapped as a PostgreSQL OID @Lob.
 */
class PostgresqlMigrationTextCompatibilityUnitTest {
  private static final Pattern PORTABILITY_HAZARD =
      Pattern.compile("(?i)\\b(CLOB|BLOB|LONGVARCHAR)\\b");

  @Test
  void migrationsDoNotUseH2OnlyLargeObjectTypes() throws IOException {
    for (int i = 1; i <= 18; i++) {
      String file = "db/changelog/%03d".formatted(i) + switch (i) {
        case 1 -> "-lib-schema.sql";
        case 2 -> "-lib-reference-catalogs.sql";
        case 3 -> "-lib-report-grid.sql";
        case 4 -> "-lib-report-format.sql";
        case 5 -> "-lib-report-validation.sql";
        case 6 -> "-lib-report-derived-totals.sql";
        case 7 -> "-lib-catalog-management.sql";
        case 8 -> "-lib-intake-report-status.sql";
        case 9 -> "-lib-form-purpose.sql";
        case 10 -> "-lib-catalog-category.sql";
        case 11 -> "-lib-editorial-drafts.sql";
        case 12 -> "-lib-field-metric-contract.sql";
        case 13 -> "-lib-field-metric-display-drafts.sql";
        case 14 -> "-lib-versioned-field-contracts.sql";
        case 15 -> "-lib-contract-append-only-postgresql.sql";
        case 16 -> "-lib-form-style-preferences.sql";
        case 17 -> "-lib-versioned-import-profile.sql";
        case 18 -> "-lib-import-profile-postgresql-immutability.sql";
        default -> throw new IllegalStateException();
      };
      try (var in = getClass().getClassLoader().getResourceAsStream(file)) {
        assertNotNull(in, "Missing migration: " + file);
        String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(PORTABILITY_HAZARD.matcher(sql).find(),
            "Unsupported large-object type in PostgreSQL migration: " + file);
      }
    }
  }

  @Test
  void textColumnsAreNotMappedToPostgresqlOidLargeObjects() throws Exception {
    for (var entityAndField : List.of(
        new EntityField(FormValidationRule.class, "typedParams"),
        new EntityField(FormRevision.class, "valuesJson"),
        new EntityField(FormRevisionRow.class, "valuesJson"),
        new EntityField(FormRevisionEvidence.class, "metadataJson"))) {
      var field = entityAndField.type().getDeclaredField(entityAndField.field());
      assertFalse(field.isAnnotationPresent(Lob.class),
          "@Lob with a PostgreSQL TEXT column may cause OID read/write failures: "
              + entityAndField.type().getSimpleName() + "." + entityAndField.field());
      var column = field.getAnnotation(jakarta.persistence.Column.class);
      assertNotNull(column, "Explicit column mapping required");
      assertEquals("text", column.columnDefinition(),
          "Financial JSON must use TEXT mapping instead of a PostgreSQL OID");
    }
  }

  private record EntityField(Class<?> type, String field) {}
}
