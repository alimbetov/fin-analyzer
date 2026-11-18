package kz.fin.formlib.api;

import kz.fin.formlib.service.FormDraftService;
import kz.fin.formlib.service.FormXlsxExchangeService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.UUID;

/**
 * Local-only round-trip XLSX transport. No trusted IAM yet:
 * organizationId and actor are caller-supplied and must not be accepted on public networks.
 */
@RestController
@RequestMapping("/api/v1/drafts/{instanceId}")
public class FormXlsxExchangeController {
  private static final MediaType XLSX=MediaType.parseMediaType(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  private final FormXlsxExchangeService exchange;

  public FormXlsxExchangeController(FormXlsxExchangeService exchange){
    this.exchange=exchange;
  }

  @GetMapping("/export.xlsx")
  public ResponseEntity<byte[]> export(@PathVariable UUID instanceId,
      @RequestParam UUID organizationId,
      @RequestParam(required=false) String styleCode,
      @RequestParam(required=false) UUID userId,
      @RequestParam(defaultValue="ru") String locale){
    var export=exchange.exportNamed(instanceId,organizationId,styleCode,userId,locale);
    return ResponseEntity.ok().contentType(XLSX)
      .header(HttpHeaders.CONTENT_DISPOSITION,
          "attachment; filename=\""+export.filename()+"\"")
      .header(HttpHeaders.CACHE_CONTROL,"private, no-store")
      .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,HttpHeaders.CONTENT_DISPOSITION)
      .body(export.bytes());
  }

  @PostMapping(value="/import.xlsx",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public FormDraftService.DraftReceipt upload(@PathVariable UUID instanceId,
      @RequestParam UUID organizationId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestParam("file") MultipartFile file,
      @RequestParam String actor,
      @RequestParam String reason) {
    if(file==null||file.isEmpty()||file.getSize()>2_000_000)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"XLSX_FILE_SIZE_INVALID");
    try {
      return exchange.importFile(instanceId,organizationId,file.getBytes(),
          idempotencyKey,actor,reason);
    }catch(IOException e){
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"XLSX_READ_FAILED",e);
    }
  }
}
