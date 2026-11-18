package kz.fin.formlib.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.FormField;
import kz.fin.formlib.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Isolated editor canonicalization tests; no Spring context, H2 or PostgreSQL.
 * These assert the actual FormDraftService normalization used by save().
 * Persistence, concurrency and HTTP are covered by integration tests separately.
 */
class FormDraftValidationUnitTest {
  private final UUID version = UUID.randomUUID();
  private FormFieldRepository fields;
  private FormDraftService editor;

  @BeforeEach
  void setUp() {
    fields = mock(FormFieldRepository.class);
    editor = new FormDraftService(
        mock(FormDefinitionRepository.class), mock(FormVersionRepository.class), fields,
        mock(FormInstanceRepository.class), mock(FormRevisionRepository.class),
        mock(FormRevisionRowRepository.class), mock(FormCommandReceiptRepository.class),
        mock(LibCurrencyRepository.class), new ObjectMapper(), mock(FieldMetricContractGuard.class), mock(FormAttachmentStore.class));
    when(fields.findByFormVersionIdOrderByDisplayOrderAsc(version)).thenReturn(List.of(
        field("revenue", "MONEY", "MANUAL", true),
        field("quantity", "INTEGER", "MANUAL", false),
        field("comment", "STRING", "MANUAL", false),
        field("derived", "DECIMAL", "DERIVED", false)));
  }

  private static FormField field(String code, String type, String mode, boolean required) {
    var f = new FormField();
    f.setId(UUID.randomUUID());
    f.setFieldCode(code);
    f.setFieldType(type);
    f.setInputMode(mode);
    f.setRequired(required);
    return f;
  }

  @SuppressWarnings("unchecked")
  private Map<String,String> canonical(Map<String,String> values, boolean required, int minorUnits) {
    return (Map<String,String>) ReflectionTestUtils.invokeMethod(
        editor, "canonical", version, values, required, minorUnits);
  }

  private void invalid(Map<String,String> values, boolean required, int minorUnits, String code) {
    var ex = assertThrows(ResponseStatusException.class,
        () -> canonical(values, required, minorUnits));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
    assertTrue(ex.getReason().contains(code), ex.getReason());
  }

  @Test
  void canonicalizesFinancialValuesWithoutFloatingPointLoss() {
    var result = canonical(Map.of(
        "revenue", " 12000000.5000 ", "quantity", "00042", "comment", " text "), true, 2);
    assertEquals("12000000.5", result.get("revenue"));
    assertEquals("42", result.get("quantity"));
    assertEquals("text", result.get("comment"));
    assertEquals(3, result.size());
  }

  @Test
  void preservesZeroEmptyAndMissingAsDifferentStates() {
    var result = canonical(Map.of("revenue", "0", "comment", " "), true, 2);
    assertEquals("0", result.get("revenue"));
    assertTrue(result.containsKey("comment"));
    assertEquals("", result.get("comment"));
    assertFalse(result.containsKey("quantity"));
  }

  @Test
  void rejectsMissingRequiredValuesForSingleRecordAndRowset() {
    invalid(Map.of("quantity", "2"), true, 2, "REQUIRED_FIELD_MISSING:revenue");
    invalid(Map.of("revenue", ""), true, 2, "REQUIRED_FIELD_MISSING:revenue");
    assertEquals("2", canonical(Map.of("quantity", "2"), false, 2).get("quantity"));
  }

  @Test
  void rejectsUnknownAndDerivedFields() {
    invalid(Map.of("revenue", "1", "unapproved", "9"), true, 2, "UNKNOWN_FIELD");
    invalid(Map.of("revenue", "1", "derived", "9"), true, 2, "READ_ONLY_FIELD");
  }

  @Test
  void rejectsNullCellAndCurrencySubunitOverflow() {
    var withNull = new java.util.HashMap<String,String>();
    withNull.put("revenue", null);
    invalid(withNull, true, 2, "NULL_CELL_NOT_ALLOWED");
    invalid(Map.of("revenue", "1.001"), true, 2, "CURRENCY_MINOR_UNITS_EXCEEDED");
    assertEquals("1.01", canonical(Map.of("revenue", "1.0100"), true, 2).get("revenue"));
  }

  @Test
  void rejectsInvalidNumbersAndIntegerFractions() {
    invalid(Map.of("revenue", "1,000"), true, 2, "INVALID_DECIMAL");
    invalid(Map.of("revenue", "NaN"), true, 2, "INVALID_DECIMAL");
    invalid(Map.of("revenue", "1", "quantity", "3.5"), true, 2, "INVALID_INTEGER");
    invalid(Map.of("revenue", "1", "quantity", "2e3"), true, 2, "INVALID_INTEGER");
  }

  @Test
  void rejectsExcessivelyLongText() {
    invalid(Map.of("revenue", "1", "comment", "x".repeat(1001)), true, 2, "VALUE_TOO_LONG");
  }

  @Test
  void canonicalOutputDoesNotSilentlyAddOmittedOptionalFields() {
    var result = canonical(Map.of("revenue", "100"), true, 2);
    assertEquals(Map.of("revenue", "100"), result);
    // The editor's existing save API is a full snapshot, not a PATCH merge.
    // A test for preserving omitted values must be added when PATCH is implemented.
  }
}
