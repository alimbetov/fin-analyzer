package kz.fin.backend.intake;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Strict offline original-document format preflight. This is not an antivirus,
 * issuer-verification, authorization service or an authenticity certificate.
 */
@Component
public final class CatalogDocumentPreflight {
    private static final int MAX_BYTES = 2_000_000;
    private static final long MAX_EXPANDED = 16_000_000L;
    private static final int MAX_ENTRIES = 256;
    private static final long MAX_PIXELS = 5_000_000L;
    private static final Set<String> MEDIA = Set.of("application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/xml", "image/png", "image/jpeg");

    public void requireRecognizedOriginal(CatalogXlsxRoundTripService.OriginalDocument file) {
        if (file == null || file.fileName() == null || file.mediaType() == null ||
                !file.fileName().matches("[A-Za-z0-9_-]{1,80}\\.(pdf|docx|xlsx|xml|png|jpg|jpeg)") ||
                !MEDIA.contains(file.mediaType())) {
            throw invalid();
        }
        byte[] bytes = file.bytes();
        if (bytes.length < 16 || bytes.length > MAX_BYTES) throw invalid();
        String extension = file.fileName().substring(file.fileName().lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);
        String expectedType = switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xml" -> "application/xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> throw invalid();
        };
        if (!expectedType.equals(file.mediaType())) throw invalid();
        try {
            switch (extension) {
                case "pdf" -> verifyPdf(bytes);
                case "docx" -> verifyDocx(bytes);
                case "xlsx" -> verifyXlsx(bytes);
                case "xml" -> verifyXml(bytes);
                case "png", "jpg", "jpeg" -> verifyImage(bytes, extension);
                default -> throw invalid();
            }
        } catch (IOException | SAXException | ParserConfigurationException |
                 RuntimeException e) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CATALOG_DOCUMENT_INVALID_OR_UNSUPPORTED");
    }

    private static void verifyPdf(byte[] bytes) throws IOException {
        String header = new String(bytes, 0, Math.min(bytes.length, 8),
                StandardCharsets.US_ASCII);
        String tail = new String(bytes, Math.max(0, bytes.length - 128),
                Math.min(128, bytes.length), StandardCharsets.US_ASCII);
        if (!header.matches("%PDF-1\\.[0-7].*") || !tail.contains("%%EOF")) {
            throw invalid();
        }
        // A PDF header/EOF pair is NOT proof of a valid document. Parse
        // actual page trees and reject actionable/interactive PDF structures.
        // No scripts, external URLs or document actions may be executed by
        // this offline preflight. This is still not malware/issuer verification.
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || document.getDocumentCatalog() == null ||
                    document.getNumberOfPages() < 1 ||
                    document.getNumberOfPages() > 200) {
                throw invalid();
            }
            var catalog = document.getDocumentCatalog().getCOSObject();
            for (COSName disallowed : new COSName[] {
                    COSName.OPEN_ACTION, COSName.AA, COSName.NAMES,
                    COSName.ACRO_FORM, COSName.COLLECTION }) {
                if (catalog.containsKey(disallowed)) throw invalid();
            }
            for (var page : document.getPages()) {
                if (!page.getAnnotations().isEmpty() ||
                        page.getCOSObject().containsKey(COSName.AA)) {
                    throw invalid();
                }
            }
        }
    }

    private static void verifyDocx(byte[] bytes) throws IOException {
        Set<String> entries = verifyZip(bytes);
        if (!entries.contains("[Content_Types].xml") ||
                !entries.contains("word/document.xml")) throw invalid();
        try (XWPFDocument ignored = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // POI must parse the actual package, not only its first ZIP bytes.
        }
    }

    private static void verifyXlsx(byte[] bytes) throws IOException {
        Set<String> entries = verifyZip(bytes);
        if (!entries.contains("[Content_Types].xml") ||
                !entries.contains("xl/workbook.xml")) throw invalid();
        try (XSSFWorkbook ignored = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            // Encrypted/unsupported structures and malformed workbook parts fail.
        }
    }

    private static Set<String> verifyZip(byte[] bytes) throws IOException {
        Set<String> names = new HashSet<>();
        long expanded = 0;
        int count = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (++count > MAX_ENTRIES || name.isBlank() || name.startsWith("/") ||
                        name.contains("..") || name.contains("\\") ||
                        !names.add(name) || name.contains("vbaProject") ||
                        name.contains("externalLinks")) throw invalid();
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    expanded += n;
                    if (expanded > MAX_EXPANDED) throw invalid();
                }
            }
        }
        if (count < 3) throw invalid();
        return names;
    }

    private static void verifyXml(byte[] bytes)
            throws ParserConfigurationException, SAXException, IOException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Document root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        if (root.getDocumentElement() == null) throw invalid();
    }

    private static void verifyImage(byte[] bytes, String extension) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            if (input == null) throw invalid();
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String expected = extension.equals("png") ? "png" : "JPEG";
                if (!reader.getFormatName().equalsIgnoreCase(expected)) throw invalid();
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_PIXELS || reader.read(0) == null)
                    throw invalid();
            } finally {
                reader.dispose();
            }
        }
    }
}
