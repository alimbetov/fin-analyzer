package kz.fin.formlib.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * Temporary local-only UAT harness. No separate business write service.
 * Disabled by default; remove after acceptance.
 */
@Controller
@ConditionalOnProperty(prefix="fin.uat.editor",name="enabled",havingValue="true")
public class FormEditorUatPageController {
  public FormEditorUatPageController(){}
  @GetMapping("/uat/form-editor")
  public String editor(Model model){
    model.addAttribute("pageTitle","FIN Form Library — Editor UAT");
    return "uat/form-editor";
  }
}
