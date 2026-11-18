package kz.fin.formlib.service;

import kz.fin.formlib.domain.LibCurrency;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit coverage for decimal cell canonicalization used by ReportGridService.save().
 * A grid must not silently round or convert non-numeric input to zero.
 */
class ReportGridCellValidationUnitTest {
  private static LibCurrency currency(int minorUnits) {
    var c = new LibCurrency();
    c.setCode("KZT");
    c.setStatus("ACTIVE");
    c.setMinorUnits(minorUnits);
    return c;
  }

  private static String canonical(String value, String type, int minorUnits) {
    return ReflectionTestUtils.invokeMethod(
        ReportGridService.class, "canonicalDecimal", value, type, currency(minorUnits));
  }

  private static void rejected(String value, String type, int minorUnits, String reason) {
    var error = assertThrows(ResponseStatusException.class,
        () -> canonical(value, type, minorUnits));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
    assertTrue(error.getReason().contains(reason), error.getReason());
  }

  @Test
  void preservesExactDecimalWithoutBinaryFloatingPoint() {
    assertEquals("12345678901234567890.12", canonical("12345678901234567890.1200", "MONEY", 2));
    assertEquals("-0.01", canonical("-0.010", "MONEY", 2));
    assertEquals("0", canonical("0.000", "MONEY", 2));
    assertEquals("10.125", canonical("10.12500", "DECIMAL", 2));
  }

  @Test
  void rejectsInvalidCurrencyPrecisionRatherThanRounding() {
    rejected("0.001", "MONEY", 2, "CURRENCY_MINOR_UNITS_EXCEEDED");
    rejected("-1.011", "MONEY", 2, "CURRENCY_MINOR_UNITS_EXCEEDED");
  }

  @Test
  void rejectsFractionalAndScientificNotationForIntegerMetric() {
    rejected("1.5", "INTEGER", 2, "INTEGER_REQUIRED");
    rejected("1e3", "INTEGER", 2, "INVALID_DECIMAL");
    assertEquals("123", canonical("00123", "INTEGER", 2));
  }

  @Test
  void rejectsNonNumbersAndEmptyCellPayloads() {
    rejected("", "DECIMAL", 2, "CELL_VALUE_REQUIRED");
    rejected(" ", "DECIMAL", 2, "CELL_VALUE_REQUIRED");
    rejected("NaN", "DECIMAL", 2, "INVALID_DECIMAL");
    rejected("1,000", "DECIMAL", 2, "INVALID_DECIMAL");
    rejected("1/0", "DECIMAL", 2, "INVALID_DECIMAL");
  }

  @Test
  void rejectsExcessPrecisionAndFractionDigits() {
    rejected("123456789012345678901234567", "DECIMAL", 2, "INVALID_DECIMAL");
    rejected("1.1234567890123", "DECIMAL", 2, "INVALID_DECIMAL");
  }
}
