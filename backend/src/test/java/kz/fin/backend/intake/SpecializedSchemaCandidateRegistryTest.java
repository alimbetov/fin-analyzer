package kz.fin.backend.intake;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** All 32 specialized form candidates are validated independently in Java. */
class SpecializedSchemaCandidateRegistryTest {
    private static String sample(CatalogCaptureRegistry.FieldType type){
        return switch(type){
            case REF->"REF-001";
            case CODE->"CATEGORY_A";
            case DATE->"2026-10-31";
            case MONEY->"100.00";
            case DECIMAL->"0.15";
            case PERIOD->"2026-10";
            case ENUM->"ACTIVE";
            case INTEGER->"5";
            case TEXT->"Source note";
            case BOOLEAN->"false";
        };
    }
    private static CatalogCaptureRegistry.EntryRow row(SpecializedSchemaCandidateRegistry.Candidate form){
        Map<String,String> cells=new LinkedHashMap<>();
        for(var f:form.fields())cells.put(f.code(),sample(f.type()));
        if(Set.of("F01","P01").contains(form.code()))cells.put("assets","200");
        if(form.code().equals("F03"))cells.put("closingCash","400");
        if(form.code().equals("F04"))cells.put("closingEquity","200");
        return new CatalogCaptureRegistry.EntryRow("ROW-001",cells);
    }
    @Test void only32ExistingUnapprovedSpecializedFormsAreMapped(){
        var forms=SpecializedSchemaCandidateRegistry.all();
        assertEquals(32,forms.size());
        assertEquals(32,forms.stream().map(SpecializedSchemaCandidateRegistry.Candidate::code).distinct().count());
        assertEquals("SCHEMA_CANDIDATE_NOT_APPROVED",
            SpecializedSchemaCandidateRegistry.require("P09").qualification());
        assertTrue(SpecializedSchemaCandidateRegistry.require("F08").fields().stream()
            .noneMatch(x->x.type()==CatalogCaptureRegistry.FieldType.MONEY));
        assertThrows(IllegalArgumentException.class,()->SpecializedSchemaCandidateRegistry.require("D01"));
    }
    @TestFactory Stream<DynamicTest> each32CandidateAcceptsCanonicalDraftButNeverPublishes(){
        return SpecializedSchemaCandidateRegistry.all().stream()
            .map(form->DynamicTest.dynamicTest(form.code(),()->{
                var original=row(form);
                var preview=SpecializedSchemaCandidateRegistry.validate(
                    form.code(),List.of(original),"ru");
                assertTrue(preview.validDraft(),form.code()+": "+preview.findings());
                assertFalse(preview.methodologyApproved());
                assertFalse(preview.publishable());
                assertEquals(form.fields().size(),preview.rows().getFirst().cells().size());
                var invalid=new LinkedHashMap<>(original.cells());
                invalid.put("inventedMetric","123");
                var rejected=SpecializedSchemaCandidateRegistry.validate(form.code(),List.of(
                    new CatalogCaptureRegistry.EntryRow("ROW-001",invalid),original),"ru");
                assertFalse(rejected.validDraft());
                assertTrue(rejected.findings().stream().anyMatch(
                    f->f.code().equals("SPECIALIZED_UNKNOWN_FIELD")));
                assertTrue(rejected.findings().stream().anyMatch(
                    f->f.code().equals("SPECIALIZED_INVALID_OR_DUPLICATE_ROW")));
            }));
    }
    @Test void independentGoldenAndNegativeBalanceAndCashProof(){
        var balanced=SpecializedSchemaCandidateRegistry.validate("F01",List.of(
            new CatalogCaptureRegistry.EntryRow("ROW-001",Map.of(
              "assets","1000000000000000000.000000000001",
              "liabilities","500000000000000000.000000000001",
              "equity","500000000000000000","asOf","2026-10-31"
            )),"en");
        assertTrue(balanced.validDraft());
        assertFalse(balanced.methodologyApproved());
        var mismatch=SpecializedSchemaCandidateRegistry.validate("F01",List.of(
            new CatalogCaptureRegistry.EntryRow("ROW-001",Map.of(
              "assets","100.01","liabilities","60.01","equity","39.99"
            )),"en");
        assertFalse(mismatch.validDraft());
        assertTrue(mismatch.findings().stream().anyMatch(f->
            f.code().equals("SPECIALIZED_PROVISIONAL_INVARIANT_MISMATCH")&&
            f.expected().equals("100")&&f.actual().equals("100.01")));
        var cash=SpecializedSchemaCandidateRegistry.validate("F03",List.of(
            new CatalogCaptureRegistry.EntryRow("ROW-001",Map.of(
               "openingCash","100","cfo","30","cfi","-10","cff","5","closingCash","125"
            )),"en");
        assertTrue(cash.validDraft());
        assertFalse(cash.publishable());
        var stale=cash.rows().getFirst().cells();
        assertEquals("125",stale.get("closingCash"));
    }
    @Test void F06UnqualifiedAccountNormalSideProducesReviewWarningNotFabricatedMath(){
        var result=SpecializedSchemaCandidateRegistry.validate("F06",List.of(
            row(SpecializedSchemaCandidateRegistry.require("F06"))),"ru");
        assertTrue(result.validDraft());
        assertTrue(result.findings().stream().anyMatch(
            f->f.code().equals("SPECIALIZED_METHOD_REVIEW_REQUIRED")));
        assertFalse(result.methodologyApproved());
    }
}
