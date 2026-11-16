package kz.fin.backend.intake;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each of 166 unique catalog codes is a separately reported JUnit dynamic test.
 * TYPED_FIELD_HINTS are a structural draft contract, never financial approval.
 */
class CatalogCaptureRegistryTest {
    private static final CatalogCaptureRegistry.Context CTX=new CatalogCaptureRegistry.Context(
        "DF-AST-01","PILOT_1","ru",
        ReportingPeriodV5.Type.MONTH,"2026.10",ReportingPeriodV5.Coverage.PERIOD_ONLY,
        "KZT",1);
    private static String sample(CatalogCaptureRegistry.FieldType type){
        return switch(type){
            case REF -> "REF-001";
            case CODE -> "CATEGORY_A";
            case DATE -> "2026-10-31";
            case MONEY -> "00123.4500";
            case DECIMAL -> "1.2500";
            case PERIOD -> "2026-10";
            case ENUM -> "ACTIVE";
            case INTEGER -> "0003";
            case TEXT -> "Комментарий по финансовой форме";
            case BOOLEAN -> "false";
        };
    }
    private static CatalogCaptureRegistry.Context context(String formCode){
        return new CatalogCaptureRegistry.Context(formCode,CTX.projectCode(),CTX.locale(),
          CTX.periodType(),CTX.periodCode(),CTX.coverage(),CTX.currency(),CTX.amountScale());
    }
    private static CatalogCaptureRegistry.EntryRow sampleRow(CatalogCaptureRegistry.Definition form){
        Map<String,String> cells=new LinkedHashMap<>();
        for(var field:form.fields())cells.put(field.code(),sample(field.type()));
        return new CatalogCaptureRegistry.EntryRow("ROW-001",cells);
    }
    @Test void verifiesOneSharedRegistryAndExactlyThreeReadinessGroups(){
        assertEquals(166,CatalogCaptureRegistry.all().size());
        assertEquals(166,CatalogCaptureRegistry.all().stream()
            .map(CatalogCaptureRegistry.Definition::code).distinct().count());
        assertEquals(122,CatalogCaptureRegistry.all().stream()
            .filter(f->f.captureMode()==CatalogCaptureRegistry.Mode.TYPED_DRAFT).count());
        assertEquals(12,CatalogCaptureRegistry.all().stream()
            .filter(f->f.captureMode()==CatalogCaptureRegistry.Mode.EVIDENCE_ONLY).count());
        assertEquals(32,CatalogCaptureRegistry.all().stream()
            .filter(f->f.captureMode()==CatalogCaptureRegistry.Mode.SCHEMA_NOT_APPROVED).count());
        assertEquals(CatalogCaptureRegistry.Mode.SCHEMA_NOT_APPROVED,
            CatalogCaptureRegistry.require("F02").captureMode());
        assertEquals(CatalogCaptureRegistry.Mode.EVIDENCE_ONLY,
            CatalogCaptureRegistry.require("T04").captureMode());
    }
    @TestFactory Stream<DynamicTest> all166HaveRealPerCodeExecutableContractProof(){
        return CatalogCaptureRegistry.all().stream().map(form->DynamicTest.dynamicTest(form.code(),()->{
            assertEquals(form,CatalogCaptureRegistry.require(form.code()));
            var input=List.of(sampleRow(form));
            if(form.captureMode()!=CatalogCaptureRegistry.Mode.TYPED_DRAFT){
                assertThrows(IllegalArgumentException.class,
                    ()->CatalogCaptureRegistry.validate(context(form.code()),input));
                assertTrue(form.fields().isEmpty());
                return;
            }
            var result=CatalogCaptureRegistry.validate(context(form.code()),input);
            assertTrue(result.valid(),form.code()+": "+result.findings());
            assertFalse(result.publishable());
            assertEquals("UNQUALIFIED_DRAFT",result.status());
            assertEquals(1,result.rows().size());
            assertEquals(form.fields().size(),result.rows().getFirst().cells().size());
            for(var field:form.fields())assertEquals(
                CatalogCaptureRegistry.canonical(field.type(),sample(field.type()),"ru"),
                result.rows().getFirst().cells().get(field.code()));

            Map<String,String> wrong=new LinkedHashMap<>(input.getFirst().cells());
            wrong.put("FORGED_METRIC","100");
            var rejected=CatalogCaptureRegistry.validate(context(form.code()),List.of(
                new CatalogCaptureRegistry.EntryRow("ROW-001",wrong),input.getFirst()));
            assertFalse(rejected.valid());
            assertTrue(rejected.findings().stream()
                .anyMatch(f->f.code().equals("CATALOG_UNKNOWN_FIELD")));
            assertTrue(rejected.findings().stream()
                .anyMatch(f->f.code().equals("CATALOG_DUPLICATE_OR_INVALID_ROW_ID")));
        }));
    }
    @Test void numericDatesAndPeriodsAreExactAndFailClosed(){
        assertEquals("123.45",CatalogCaptureRegistry.canonical(
            CatalogCaptureRegistry.FieldType.MONEY,"00123.4500","ru"));
        assertEquals("1.25",CatalogCaptureRegistry.canonical(
            CatalogCaptureRegistry.FieldType.DECIMAL,"1,25","kk"));
        assertEquals("0",CatalogCaptureRegistry.canonical(
            CatalogCaptureRegistry.FieldType.INTEGER,"-0000","ru"));
        assertEquals("2026.10",CatalogCaptureRegistry.canonical(
            CatalogCaptureRegistry.FieldType.PERIOD,"2026-10","en"));
        assertEquals("2024-02-29",CatalogCaptureRegistry.canonical(
            CatalogCaptureRegistry.FieldType.DATE,"2024-02-29","en"));
        for(var type:List.of(CatalogCaptureRegistry.FieldType.MONEY,
                             CatalogCaptureRegistry.FieldType.DECIMAL))
            assertThrows(IllegalArgumentException.class,
                ()->CatalogCaptureRegistry.canonical(type,"0.1234567890123","ru"));
        for(var pair:List.of(
            new String[]{"DATE","2025-02-29"},
            new String[]{"DATE","1899-01-01"},
            new String[]{"PERIOD","2021-W53"},
            new String[]{"PERIOD","2026.13"},
            new String[]{"INTEGER","1.5"},
            new String[]{"BOOLEAN","yes"},
            new String[]{"CODE","=SUM(A1)"},
            new String[]{"REF","@malicious"},
            new String[]{"MONEY","1e12"}
        ))assertThrows(IllegalArgumentException.class,
            ()->CatalogCaptureRegistry.canonical(
                CatalogCaptureRegistry.FieldType.valueOf(pair[0]),pair[1],"en"),
            pair[0]+":"+pair[1]);
    }
    @Test void invalidMissingContextOrRowIsNeverMarkedPublishable(){
        var form=CatalogCaptureRegistry.require("DF-AST-01");
        var result=CatalogCaptureRegistry.validate(context(form.code()),List.of(
           new CatalogCaptureRegistry.EntryRow("ROW-001",Map.of("cost","0"))));
        assertTrue(result.valid());
        assertEquals("0",result.rows().getFirst().cells().get("cost"));
        var missing=CatalogCaptureRegistry.validate(context(form.code()),List.of());
        assertFalse(missing.valid());
        assertTrue(missing.findings().stream().anyMatch(f->f.code().equals("CATALOG_ROW_COUNT_INVALID")));
        var other=new CatalogCaptureRegistry.Context(form.code(),CTX.projectCode(),"zh-Hans",
          CTX.periodType(),CTX.periodCode(),CTX.coverage(),CTX.currency(),CTX.amountScale());
        assertFalse(CatalogCaptureRegistry.validate(other,List.of(sampleRow(form))).valid());
        assertFalse(CatalogCaptureRegistry.validate(context(form.code()),Collections.nCopies(201,sampleRow(form))).valid());
    }
}
