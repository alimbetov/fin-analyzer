package kz.fin.backend.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice(basePackages="kz.fin.backend.api")
public class FinancialApiErrors {
    @ExceptionHandler(kz.fin.backend.intake.FormDraftRevisionV5Service.RevisionConflictException.class)
    ResponseEntity<Map<String,String>> revisionConflict(
        kz.fin.backend.intake.FormDraftRevisionV5Service.RevisionConflictException e){
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code","FIN_V5_REVISION_CONFLICT"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){
        String message=e.getMessage()==null?"INVALID_FINANCIAL_REQUEST":e.getMessage();
        if(message.equals("INTAKE_NOT_FOUND_OR_FORBIDDEN") ||
           message.equals("FIN_V5_PROJECT_NOT_FOUND") ||
           message.equals("FIN_V5_PREVIEW_NOT_FOUND") ||
           message.equals("FIN_V5_REVISION_NOT_FOUND") ||
           message.equals("FIN_V5_PREVIEW_PROJECT_MISMATCH"))
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code","NOT_FOUND_OR_FORBIDDEN"));
        return ResponseEntity.badRequest().body(Map.of("code",message.matches("[A-Z_0-9]{3,100}")
                ?message:"INVALID_FINANCIAL_REQUEST"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String,String>> json(HttpMessageNotReadableException e){
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_JSON_BODY"));
    }
    @ExceptionHandler({DataIntegrityViolationException.class,OptimisticLockingFailureException.class})
    ResponseEntity<Map<String,String>> conflict(Exception e){
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code","FINANCIAL_STATE_CONFLICT"));
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String,String>> forbidden(AccessDeniedException e){
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("code","FINANCIAL_ACCESS_DENIED"));
    }
}
