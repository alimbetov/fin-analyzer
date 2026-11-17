package kz.fin.formlib.api;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;
@RestControllerAdvice
public class ApiErrorHandler {
 @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
 public ResponseEntity<Map<String,String>> conflict(Exception error) {
   return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","CONCURRENT_OR_DUPLICATE_WRITE"));
 }
}
