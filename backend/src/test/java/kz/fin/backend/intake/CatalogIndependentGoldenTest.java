package kz.fin.backend.intake;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Type-level oracle independent from CatalogCaptureRegistry.canonical() and the
 * XLSX generator. The committed TSV expected strings were specified manually.
 * These cases do NOT approve financial meaning/methodology for any form.
 */
class CatalogIndependentGoldenTest {
    private record Case(String id, CatalogCaptureRegistry.FieldType type,
                        String locale, String raw, String expected, String verdict) {}

    private static List<Case> fixtures() {
        String resource = "/fin/intake/catalog-golden-value-v1.tsv";
        var stream = CatalogIndependentGoldenTest.class.getResourceAsStream(resource);
        if (stream == null) throw new IllegalStateException("FIN_BE_R1_GOLDEN_MISSING");
        var cases = new ArrayList<Case>();
        try (var reader = new BufferedReader(new InputStreamReader(stream,
                StandardCharsets.UTF_8))) {
            String note = reader.readLine();
            String header = reader.readLine();
            if (note == null || !note.startsWith("#") ||
                    !"caseId\tfieldType\tlocale\traw\texpected\tverdict".equals(header)) {
                throw new IllegalStateException("FIN_BE_R1_GOLDEN_HEADER_DRIFT");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                var cells = line.split("\t", -1);
                if (cells.length != 6) {
                    throw new IllegalStateException("FIN_BE_R1_GOLDEN_INVALID_ROW");
                }
                cases.add(new Case(cells[0],
                        CatalogCaptureRegistry.FieldType.valueOf(cells[1]),
                        cells[2], cells[3], cells[4], cells[5]));
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("FIN_BE_R1_GOLDEN_READ_FAILED", e);
        }
        if (cases.size() != 36 ||
                cases.stream().map(Case::id).distinct().count() != 36) {
            throw new IllegalStateException("FIN_BE_R1_GOLDEN_COVERAGE_DRIFT");
        }
        return List.copyOf(cases);
    }

    @TestFactory
    Stream<DynamicTest> financeCellTypesMatchIndependentGoldenExpectedValues() {
        return fixtures().stream().map(f ->
                DynamicTest.dynamicTest("BE-R1-GOLDEN-" + f.id(), () -> {
                    if ("VALID".equals(f.verdict())) {
                        assertEquals(f.expected(), CatalogCaptureRegistry.canonical(
                                f.type(), f.raw(), f.locale()), f.id());
                    } else if ("INVALID".equals(f.verdict())) {
                        assertThrows(IllegalArgumentException.class,
                                () -> CatalogCaptureRegistry.canonical(
                                        f.type(), f.raw(), f.locale()), f.id());
                    } else {
                        fail("FIN_BE_R1_GOLDEN_UNKNOWN_VERDICT " + f.id());
                    }
                }));
    }
}
