package kz.fin.formlib.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.sql.SQLException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String,Object>> validation(ResponseStatusException ex) {
    var status=ex.getStatusCode();
    String code=ex.getReason()==null?"VALIDATION_FAILED":ex.getReason();
    return ResponseEntity.status(status).body(Map.of(
      "status",status.value(),"code",code,"message",code));
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<Map<String,Object>> optimisticConflict(Exception error) {
    return conflict("STALE_EXPECTED_REVISION");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String,Object>> databaseConflict(DataIntegrityViolationException error) {
    // A preflight SELECT cannot prevent concurrent INSERT of the same slot.
    // Translate only known constraints; never leak raw SQL/PII to callers.
    String constraint=findConstraint(error);
    return conflict(switch(constraint) {
      case "uq_lib_instance_scope" -> "REPORT_SLOT_ALREADY_EXISTS";
      case "uq_lib_revision_no" -> "STALE_EXPECTED_REVISION";
      case "uq_lib_idempotency" -> "IDEMPOTENCY_WRITE_CONFLICT";
      default -> "CONCURRENT_OR_DUPLICATE_WRITE";
    });
  }

  private static ResponseEntity<Map<String,Object>> conflict(String code) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
      "status",409,"code",code,"message",code));
  }

  private static String findConstraint(Throwable error) {
    for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
      if(cause instanceof SQLException sql) {
        String constraint=uniqueConstraint(sql);
        if(!constraint.isEmpty())return constraint;
        for(SQLException next=sql.getNextException();next!=null;
            next=next.getNextException()) {
          constraint=uniqueConstraint(next);
          if(!constraint.isEmpty())return constraint;
        }
      }
      if(cause instanceof org.hibernate.exception.ConstraintViolationException hibernate) {
        String constraint=hibernate.getConstraintName();
        if(constraint!=null)return constraint;
      }
    }
    return "";
  }

  private static String uniqueConstraint(SQLException error) {
    if(!"23505".equals(error.getSQLState())||error.getMessage()==null)return "";
    // PostgreSQL includes the constraint in an escaped-quoted identifier.
    // Only the exact allowlisted name is later exposed to REST.
    var matcher=java.util.regex.Pattern.compile(
      "(?i)(?:constraint|ограничение)\\s+[\"«]([a-z_0-9]+)[\"»]")
      .matcher(error.getMessage());
    return matcher.find()?matcher.group(1):"";
  }
}
