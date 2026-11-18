package kz.fin.formlib;

import kz.fin.formlib.api.ApiErrorHandler;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class ApiErrorHandlerUnitTest {
  private final ApiErrorHandler errors=new ApiErrorHandler();

  private String code(DataIntegrityViolationException e) {
    var response=errors.databaseConflict(e);
    assertEquals(HttpStatus.CONFLICT,response.getStatusCode());
    assertNotNull(response.getBody());
    assertEquals(409,response.getBody().get("status"));
    return String.valueOf(response.getBody().get("code"));
  }

  @Test
  void mapsKnownPostgresqlConstraintWithoutExposingSqlOrTenantIds() {
    var violated=new SQLException(
      "ERROR: duplicate key value violates unique constraint \"uq_lib_instance_scope\"",
      "23505");
    var batch=new BatchUpdateException("batch failed","23505",new int[0]);
    batch.setNextException(violated);
    assertEquals("REPORT_SLOT_ALREADY_EXISTS",
      code(new DataIntegrityViolationException("batch failed",batch)));
    assertEquals("STALE_EXPECTED_REVISION",code(new DataIntegrityViolationException(
      "duplicate",new SQLException("constraint \"uq_lib_revision_no\"","23505"))));
    assertEquals("IDEMPOTENCY_WRITE_CONFLICT",code(new DataIntegrityViolationException(
      "duplicate",new SQLException("constraint \"uq_lib_idempotency\"","23505"))));
  }

  @Test
  void unknownConstraintKeepsGenericConflictAndOptimisticLockIsSpecific() {
    assertEquals("CONCURRENT_OR_DUPLICATE_WRITE",code(new DataIntegrityViolationException(
      "secret SQL",new SQLException("constraint \"secret_customer_unique\"","23505"))));
    var response=errors.optimisticConflict(new ObjectOptimisticLockingFailureException(
      "FormInstance",java.util.UUID.randomUUID()));
    assertEquals("STALE_EXPECTED_REVISION",response.getBody().get("code"));
  }
}
