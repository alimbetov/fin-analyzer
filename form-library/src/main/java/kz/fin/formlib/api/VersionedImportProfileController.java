package kz.fin.formlib.api;

import kz.fin.formlib.service.FormDraftService;
import kz.fin.formlib.service.VersionedImportProfileService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.UUID;

/**
 * R19 LOCAL-TEST-ONLY. No authentication is wired: never deploy this
 * controller publicly. Production requires server-resolved identity and S1/S2
 * moderation before external import profiles can be enabled.
 */
@RestController
@RequestMapping("/api/v1/import-profiles")
public class VersionedImportProfileController {
  private final VersionedImportProfileService profiles;
  public VersionedImportProfileController(VersionedImportProfileService profiles){
    this.profiles=profiles;
  }

  private static byte[] bytes(MultipartFile file){
    if(file==null||file.isEmpty()||
       file.getSize()>kz.fin.formlib.service.FormXlsxCodec.MAX_BYTES)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "XLSX_FILE_SIZE_INVALID");
    try{return file.getBytes();}
    catch(IOException e){throw new ResponseStatusException(
      HttpStatus.UNPROCESSABLE_ENTITY,"XLSX_READ_FAILED",e);}
  }

  public record Registration(UUID formVersionId) {}

  @PostMapping
  public VersionedImportProfileService.Profile register(@RequestBody Registration request){
    if(request==null)throw new ResponseStatusException(
      HttpStatus.UNPROCESSABLE_ENTITY,"REQUEST_REQUIRED");
    return profiles.register(request.formVersionId());
  }

  @GetMapping("/{profileId}")
  public VersionedImportProfileService.Profile get(@PathVariable UUID profileId){
    return profiles.get(profileId);
  }

  @PostMapping(value="/{profileId}/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public VersionedImportProfileService.PreviewTicket preview(
      @PathVariable UUID profileId,
      @RequestParam UUID instanceId,@RequestParam UUID organizationId,
      @RequestParam("file") MultipartFile file,
      @RequestParam String actor,@RequestParam String reason){
    return profiles.preview(profileId,instanceId,organizationId,bytes(file),actor,reason);
  }

  @PostMapping(value="/previews/{previewId}/commit",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public FormDraftService.DraftReceipt commit(@PathVariable UUID previewId,
      @RequestParam UUID instanceId,@RequestParam UUID organizationId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestParam("file") MultipartFile file,
      @RequestParam String actor,@RequestParam String reason){
    return profiles.commit(previewId,instanceId,organizationId,bytes(file),
      idempotencyKey,actor,reason);
  }
}
