package kz.fin.backend.intake;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Independent negative PDF parser preflight, not just magic byte checks. */
class CatalogDocumentPreflightTest {
    private final CatalogDocumentPreflight preflight = new CatalogDocumentPreflight();

    private static byte[] pdf(int pages, Consumer<PDDocument> change) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            change.accept(document);
            document.save(out);
            return out.toByteArray();
        }
    }

    private static CatalogXlsxRoundTripService.OriginalDocument original(byte[] bytes) {
        return new CatalogXlsxRoundTripService.OriginalDocument(
                "source.pdf", "application/pdf", bytes);
    }

    @Test void acceptsARealOfflinePdfWithOnePage() throws IOException {
        assertDoesNotThrow(() -> preflight.requireRecognizedOriginal(
                original(pdf(1, doc -> {}))));
    }

    @Test void acceptsARealDocumentWithMultiplePages() throws IOException {
        assertDoesNotThrow(() -> preflight.requireRecognizedOriginal(
                original(pdf(2, doc -> {}))));
    }

    @Test void rejectsHeaderEofForgeryWithoutValidPageTree() {
        byte[] forged = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\n"
                + "endobj\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
        assertThrows(IllegalArgumentException.class,
                () -> preflight.requireRecognizedOriginal(original(forged)));
    }

    @Test void rejectsTruncatedRealPdfEvenWhenHeaderIsIntact() throws IOException {
        var valid = pdf(1, doc -> {});
        var bytes = java.util.Arrays.copyOf(valid, valid.length - 10);
        assertThrows(IllegalArgumentException.class,
                () -> preflight.requireRecognizedOriginal(original(bytes)));
    }

    @Test void rejectsCatalogOpenActionsAndInteractiveMetadata() throws IOException {
        var openAction = pdf(1, doc ->
                doc.getDocumentCatalog().getCOSObject()
                        .setName(COSName.OPEN_ACTION, "JavaScript"));
        assertThrows(IllegalArgumentException.class,
                () -> preflight.requireRecognizedOriginal(original(openAction)));
        var names = pdf(1, doc ->
                doc.getDocumentCatalog().getCOSObject()
                        .setItem(COSName.NAMES, new COSDictionary()));
        assertThrows(IllegalArgumentException.class,
                () -> preflight.requireRecognizedOriginal(original(names)));
    }

    @Test void rejectsPageActionsAndExcessivePageCount() throws IOException {
        var pageAction = pdf(1, doc ->
                doc.getPage(0).getCOSObject().setItem(COSName.AA,
                        new COSDictionary()));
        assertThrows(IllegalArgumentException.class,
                () -> preflight.requireRecognizedOriginal(original(pageAction)));
        var excessive = pdf(201, doc -> {});
        assertThrows(IllegalArgumentException.class,
                () -> preflight.requireRecognizedOriginal(original(excessive)));
    }

    @Test void rejectsContentTypeConfusionEvenForValidPdf() throws IOException {
        var bytes = pdf(1, doc -> {});
        assertThrows(IllegalArgumentException.class,
                () -> preflight.requireRecognizedOriginal(
                        new CatalogXlsxRoundTripService.OriginalDocument(
                                "source.pdf", "application/xml", bytes)));
        assertThrows(IllegalArgumentException.class,
                () -> preflight.requireRecognizedOriginal(
                        new CatalogXlsxRoundTripService.OriginalDocument(
                                "source.xml", "application/pdf", bytes)));
    }
}
