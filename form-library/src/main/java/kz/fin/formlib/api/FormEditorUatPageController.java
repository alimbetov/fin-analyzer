package kz.fin.formlib.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Local UAT view only: browser calls the existing production REST controllers.
 * NOT an alternate financial writer, authentication boundary or production UI.
 * application.yml binds HTTP to 127.0.0.1; keep UAT disabled on public hosts.
 */
@Controller
@ConditionalOnProperty(prefix="fin.uat.editor",name="enabled",havingValue="true")
public class FormEditorUatPageController {
  @GetMapping("/uat/form-editor")
  public String editor(Model model) {
    model.addAttribute("pageTitle","FIN Form Library — Editor UAT");
    model.addAttribute("apiRoot","/api/v1");
    return "uat/form-editor";
  }
}
