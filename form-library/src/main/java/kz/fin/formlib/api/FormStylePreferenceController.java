package kz.fin.formlib.api;

import kz.fin.formlib.service.FormStylePreferenceService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Local prototype only: userId is NOT authenticated.
 * Production deployment requires server-resolved principal and tenant checks.
 */
@RestController
@RequestMapping("/api/v1/form-preferences")
public class FormStylePreferenceController {
  private final FormStylePreferenceService service;

  public FormStylePreferenceController(FormStylePreferenceService service) {
    this.service=service;
  }

  @GetMapping("/default")
  public FormStylePreferenceService.StyleSelection defaultStyle(@RequestParam UUID userId) {
    return service.getDefault(userId);
  }

  @PutMapping("/default")
  public FormStylePreferenceService.StyleSelection setDefault(
      @RequestBody FormStylePreferenceService.StyleUpdate request) {
    return service.setDefault(request);
  }

  @GetMapping("/{formCode}")
  public FormStylePreferenceService.StyleSelection formStyle(
      @PathVariable String formCode,@RequestParam UUID userId) {
    return service.getForForm(formCode,userId);
  }

  @PutMapping("/{formCode}/style")
  public FormStylePreferenceService.StyleSelection setFormStyle(
      @PathVariable String formCode,
      @RequestBody FormStylePreferenceService.StyleUpdate request) {
    return service.setForForm(formCode,request);
  }

  @DeleteMapping("/{formCode}/style")
  public FormStylePreferenceService.StyleSelection resetFormStyle(
      @PathVariable String formCode,@RequestParam UUID userId) {
    return service.resetForForm(formCode,userId);
  }
}
