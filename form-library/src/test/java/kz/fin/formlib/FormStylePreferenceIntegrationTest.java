package kz.fin.formlib;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * H2 integration proof: UI preference edits do not touch finance intake revisions.
 * PostgreSQL clean-install/migration remains a separate release gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FormStylePreferenceIntegrationTest {
  @Autowired MockMvc http;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  private String json(UUID user,String style) throws Exception {
    return mapper.writeValueAsString(Map.of("userId",user,"styleCode",style));
  }

  private long intakeRevisionCount() {
    Long drafts=jdbc.queryForObject("SELECT COUNT(*) FROM fin_intake.lib_form_revision",Long.class);
    Long grids=jdbc.queryForObject("SELECT COUNT(*) FROM fin_intake.lib_report_revision",Long.class);
    return drafts+grids;
  }

  @Test
  void defaultStyleCanBeSavedAndRestoredWithoutCreatingFinancialRevision() throws Exception {
    UUID user=UUID.randomUUID();
    long before=intakeRevisionCount();
    http.perform(get("/api/v1/form-preferences/default").param("userId",user.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.styleCode").value("CLASSIC"))
      .andExpect(jsonPath("$.source").value("SYSTEM_DEFAULT"));
    http.perform(put("/api/v1/form-preferences/default")
        .contentType(MediaType.APPLICATION_JSON).content(json(user,"EXCEL")))
      .andExpect(status().isOk()).andExpect(jsonPath("$.styleCode").value("EXCEL"));
    http.perform(get("/api/v1/form-preferences/default").param("userId",user.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("USER_DEFAULT"))
      .andExpect(jsonPath("$.styleCode").value("EXCEL"));
    assertEquals(before,intakeRevisionCount());
  }

  @Test
  void formOverrideWinsThenResetFallsBackToUserDefault() throws Exception {
    UUID user=UUID.randomUUID();
    http.perform(put("/api/v1/form-preferences/default")
        .contentType(MediaType.APPLICATION_JSON).content(json(user,"EXCEL")))
      .andExpect(status().isOk());
    http.perform(get("/api/v1/form-preferences/F02").param("userId",user.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.styleCode").value("EXCEL"))
      .andExpect(jsonPath("$.source").value("USER_DEFAULT"));
    http.perform(put("/api/v1/form-preferences/F02/style")
        .contentType(MediaType.APPLICATION_JSON).content(json(user,"DASHBOARD")))
      .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("USER_FORM"));
    http.perform(get("/api/v1/form-preferences/F02").param("userId",user.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.styleCode").value("DASHBOARD"));
    http.perform(delete("/api/v1/form-preferences/F02/style").param("userId",user.toString()))
      .andExpect(status().isOk()).andExpect(jsonPath("$.styleCode").value("EXCEL"))
      .andExpect(jsonPath("$.source").value("USER_DEFAULT"));
  }

  @Test
  void differentUsersKeepIndependentPreferences() throws Exception {
    UUID a=UUID.randomUUID(),b=UUID.randomUUID();
    http.perform(put("/api/v1/form-preferences/F02/style")
        .contentType(MediaType.APPLICATION_JSON).content(json(a,"DASHBOARD")))
      .andExpect(status().isOk());
    http.perform(get("/api/v1/form-preferences/F02").param("userId",a.toString()))
      .andExpect(jsonPath("$.styleCode").value("DASHBOARD"));
    http.perform(get("/api/v1/form-preferences/F02").param("userId",b.toString()))
      .andExpect(jsonPath("$.styleCode").value("CLASSIC"));
  }

  @Test
  void badStyleAndUnknownFormAreRejected() throws Exception {
    UUID user=UUID.randomUUID();
    http.perform(put("/api/v1/form-preferences/F02/style")
        .contentType(MediaType.APPLICATION_JSON).content(json(user,"NOT_A_STYLE")))
      .andExpect(status().isUnprocessableEntity());
    http.perform(put("/api/v1/form-preferences/UNKNOWN-FORM/style")
        .contentType(MediaType.APPLICATION_JSON).content(json(user,"CLASSIC")))
      .andExpect(status().isNotFound());
    http.perform(get("/api/v1/form-preferences/UNKNOWN-FORM")
        .param("userId",user.toString())).andExpect(status().isNotFound());
  }

  @Test
  void changingPreferenceDoesNotCreateFinancialRevisions() throws Exception {
    UUID user=UUID.randomUUID();
    long before=intakeRevisionCount();
    http.perform(put("/api/v1/form-preferences/F02/style")
        .contentType(MediaType.APPLICATION_JSON).content(json(user,"EXCEL")))
      .andExpect(status().isOk());
    http.perform(put("/api/v1/form-preferences/F02/style")
        .contentType(MediaType.APPLICATION_JSON).content(json(user,"DASHBOARD")))
      .andExpect(status().isOk());
    assertEquals(before,intakeRevisionCount());
    Integer count=jdbc.queryForObject("""
        SELECT COUNT(*) FROM fin_ui.lib_user_form_style_preference
        WHERE user_id=? AND form_code='F02'
        """,Integer.class,user);
    assertEquals(1,count);
  }
}
