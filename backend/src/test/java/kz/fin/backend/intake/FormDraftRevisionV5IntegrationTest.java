package kz.fin.backend.intake;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL evidence: revisions and import events are not simulated. */
@SpringBootTest
@Testcontainers
class FormDraftRevisionV5IntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres=
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fin").withUsername("fin").withPassword("fin");
    @DynamicPropertySource static void db(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
    }

    @Autowired FormDraftRevisionV5Service ledger;
    @Autowired JdbcTemplate jdbc;

    private record Scope(UUID org,UUID entity,UUID scope) {
        static Scope fresh(){return new Scope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());}
    }
    private FormDraftRevisionV5Service.Project project(Scope scope,String code) {
        return ledger.createProject(scope.org(),scope.entity(),scope.scope(),code,"KZT","author");
    }
    private static List<FormSchemaV5.Input> f02(String net,String cogs,String expenses){
        return List.of(
           new FormSchemaV5.Input("pnl","netRevenue",net),
           new FormSchemaV5.Input("pnl","cogs",cogs),
           new FormSchemaV5.Input("pnl","operatingExpenses",expenses));
    }
    private FormDraftRevisionV5Service.Preview preview(Scope s,UUID project,String lang,
                                                    String revenue){
        return ledger.previewManual(s.org,project,"2026.10",lang,1,
                f02(revenue,"260","90"),"author");
    }
    private FormDraftRevisionV5Service.Confirmation confirm(Scope s,
                                           FormDraftRevisionV5Service.Preview p,String reason){
        return ledger.confirm(s.org,p.id(),p.confirmationDigest(),p.expectedRevision(),
                              "author",reason);
    }

    @Test void versionHistoryStoresUploadAndSaveTimesAndNoChangeIsNotNewRevision(){
        var scope=Scope.fresh();var p=project(scope,"FIN_UAT");
        var first=preview(scope,p.id(),"ru","400.00");
        assertEquals("NEW",first.verdict());
        assertEquals(0,first.expectedRevision());
        assertNotNull(first.uploadedAt());
        assertEquals(3,first.cells().size());
        var r1=confirm(scope,first,"");
        assertEquals("CREATED",r1.result());
        assertEquals(1,r1.revisionNumber());
        assertNotNull(r1.savedAt());
        assertFalse(r1.savedAt().isBefore(first.uploadedAt()));
        assertEquals("ALREADY_APPLIED",confirm(scope,first,"").result());
        assertEquals(1,ledger.history(scope.org,p.id(),"2026.10").size());

        // Same money values are normalized to one fingerprint across input languages.
        var duplicate=preview(scope,p.id(),"en","00400.000");
        assertEquals("DUPLICATE",duplicate.verdict());
        assertEquals(first.fingerprint(),duplicate.fingerprint());
        var unchanged=confirm(scope,duplicate,"");
        assertEquals("DUPLICATE",unchanged.result());
        assertEquals(r1.revisionId(),unchanged.revisionId());
        assertEquals(1,unchanged.revisionNumber());
        assertEquals(1,ledger.history(scope.org,p.id(),"2026.10").size());
        assertEquals(2,ledger.uploads(scope.org,p.id(),"2026.10").size());
        assertEquals(1,ledger.uploads(scope.org,p.id(),"2026.10").stream()
                 .filter(x->x.resultingRevision()!=null).count());
    }

    @Test void differentValuesShowTypedDiffRequireReasonAndAppendImmutableChain(){
        var scope=Scope.fresh();var p=project(scope,"FIN_CORRECTED");
        var first=preview(scope,p.id(),"ru","400");
        var r1=confirm(scope,first,"");
        var revised=preview(scope,p.id(),"kk","420,00");
        assertEquals("CHANGED",revised.verdict());
        assertEquals(1,revised.expectedRevision());
        var change=revised.changes().stream().filter(x->x.fieldCode().equals("netRevenue"))
                .findFirst().orElseThrow();
        assertEquals("400",change.before());
        assertEquals("420",change.after());
        assertEquals("CHANGED",change.kind());
        assertEquals(2,revised.changes().stream().filter(x->x.kind().equals("UNCHANGED")).count());
        assertThrows(IllegalArgumentException.class,()->confirm(scope,revised," "));
        assertEquals(1,ledger.history(scope.org,p.id(),"2026.10").size());
        var r2=confirm(scope,revised,"Corrected source revenue");
        assertEquals(2,r2.revisionNumber());
        assertEquals("CREATED",r2.result());
        assertNotEquals(r1.revisionId(),r2.revisionId());
        assertEquals("ALREADY_APPLIED",confirm(scope,revised,"irrelevant").result());
        var history=ledger.history(scope.org,p.id(),"2026.10");
        assertEquals(List.of(2,1),history.stream().map(x->x.revisionNumber()).toList());
        assertEquals("Corrected source revenue",history.getFirst().reason());
        assertEquals("kk",history.getFirst().inputLocale());
        assertFalse(history.getFirst().savedAt().isBefore(history.getFirst().uploadedAt()));
        assertEquals(r1.revisionId(),jdbc.queryForObject("""
          SELECT parent_revision_id FROM fin_intake.draft_revision_v5 WHERE id=?
          """,UUID.class,r2.revisionId()));

        assertThrows(DataAccessException.class,()->jdbc.update("""
          UPDATE fin_intake.draft_revision_v5 SET change_reason='tamper' WHERE id=?
          """,r1.revisionId()));
        assertThrows(DataAccessException.class,()->jdbc.update("""
          DELETE FROM fin_intake.draft_revision_v5 WHERE id=?
          """,r1.revisionId()));
    }

    @Test void staleRevisionCannotBeConfirmedAndProjectTenantMustMatch(){
        var x=Scope.fresh();var p=project(x,"FIN_LOCK");
        var original=preview(x,p.id(),"en","400");
        confirm(x,original,"");
        var stale=preview(x,p.id(),"en","401");
        var current=preview(x,p.id(),"en","402");
        assertEquals(1,current.expectedRevision());
        var applied=confirm(x,current,"Corrected sales");
        assertEquals(2,applied.revisionNumber());
        var error=assertThrows(IllegalArgumentException.class,
            ()->confirm(x,stale,"Stale overwrite"));
        assertEquals("FIN_V5_REVISION_CONFLICT",error.getMessage());
        assertEquals(2,ledger.history(x.org,p.id(),"2026.10").size());
        var foreign=Scope.fresh();
        assertThrows(IllegalArgumentException.class,
            ()->ledger.history(foreign.org,p.id(),"2026.10"));
        assertThrows(IllegalArgumentException.class,
            ()->ledger.confirm(foreign.org,current.id(),current.confirmationDigest(),1,"author","x"));
        assertThrows(IllegalArgumentException.class,
            ()->ledger.confirm(x.org,current.id(),"0".repeat(64),1,"author","x"));
    }

    @Test void periodScaleAndSourceIdentityArePinnedAcrossRepeats(){
        var scope=Scope.fresh();var p=project(scope,"FIN_CONTEXT");
        preview(scope,p.id(),"ru","400");
        assertThrows(IllegalArgumentException.class,
            ()->ledger.previewManual(scope.org,p.id(),"2026.10","ru",1000,
                f02("400","260","90"),"author"));
        assertThrows(IllegalArgumentException.class,
            ()->ledger.previewManual(scope.org,p.id(),"2026.13","ru",1,
                f02("400","260","90"),"author"));
        assertThrows(IllegalArgumentException.class,
            ()->ledger.previewManual(scope.org,p.id(),"2026.10","zh-Hans",1,
                f02("400","260","90"),"author"));
        assertThrows(IllegalArgumentException.class,
            ()->ledger.previewManual(scope.org,p.id(),"2026.10","ru",1,
                f02("400","","90"),"author"));
        assertThrows(DataAccessException.class,()->jdbc.update("""
          UPDATE fin_intake.form_slot_v5 SET latest_revision=1
            WHERE project_id=? AND period_code='2026.10'
          """,p.id()));
        assertThrows(DataAccessException.class,
            ()->jdbc.update("UPDATE fin_intake.project_v5 SET project_code='changed' WHERE id=?",p.id()));
        assertEquals(0,ledger.history(scope.org,p.id(),"2026.10").size());
        assertThrows(DataAccessException.class,
            ()->jdbc.update("INSERT INTO fin_intake.project_v5 (id,organization_id,economic_entity_id,business_scope_id,project_code,accounting_currency,created_by) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(),scope.org,scope.entity,scope.scope,p.projectCode(),"KZT","author"));
    }
}
