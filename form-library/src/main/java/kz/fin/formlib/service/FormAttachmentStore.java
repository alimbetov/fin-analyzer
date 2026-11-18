package kz.fin.formlib.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.fin.formlib.domain.FormRevisionEvidence;
import kz.fin.formlib.repository.FormRevisionEvidenceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class FormAttachmentStore {
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  private static final String LOCAL = "LOCAL_FILE_V1";
  private final Path directory;
  private final FormRevisionEvidenceRepository evidence;
  private final ObjectMapper mapper;

  public record Upload(String fileName, String mediaType, byte[] bytes, String sha256) {}
  public record Attachment(UUID id, String fileName, String mediaType, long sizeBytes, String sha256) {}
  public record Download(Attachment attachment, byte[] bytes) {}
  private record Metadata(String storage, String fileName) {}

  public FormAttachmentStore(@Value("${fin.attachments.directory:./var/form-attachments}") String directory,
      FormRevisionEvidenceRepository evidence, ObjectMapper mapper) {
    this.directory = Path.of(directory).toAbsolutePath().normalize();
    this.evidence = evidence;
    this.mapper = mapper;
  }

  public static Upload validate(String originalName, byte[] bytes) {
    if (bytes.length == 0) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ATTACHMENT_EMPTY");
    if (bytes.length > MAX_BYTES) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "ATTACHMENT_TOO_LARGE");
    if (originalName == null || originalName.isBlank() ||
        originalName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240 ||
        originalName.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT) ||
        originalName.contains("/") || originalName.contains("\\"))
      throw error(HttpStatus.UNPROCESSABLE_ENTITY, "ATTACHMENT_FILENAME_INVALID");
    String name = originalName.toLowerCase(Locale.ROOT);
    String type;
    if (name.endsWith(".pdf") && startsWith(bytes, 0x25, 0x50, 0x44, 0x46, 0x2d)) type = "application/pdf";
    else if (name.endsWith(".png") && startsWith(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) type = "image/png";
    else if ((name.endsWith(".jpg") || name.endsWith(".jpeg")) && startsWith(bytes, 0xff, 0xd8, 0xff)) type = "image/jpeg";
    else throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "ATTACHMENT_TYPE_UNSUPPORTED");
    return new Upload(originalName, type, bytes, sha256(bytes));
  }

  private static boolean startsWith(byte[] bytes, int... signature) {
    if (bytes.length < signature.length) return false;
    for (int i = 0; i < signature.length; i++)
      if (Byte.toUnsignedInt(bytes[i]) != signature[i]) return false;
    return true;
  }

  public Optional<Attachment> find(UUID revisionId) {
    return evidence.findByRevisionId(revisionId).stream().flatMap(link -> metadata(link).stream()
        .map(meta -> new Attachment(link.getEvidenceId(), meta.fileName(), link.getMediaType(),
            link.getSizeBytes(), link.getRawSha256()))).findFirst();
  }

  public void link(UUID revisionId, Attachment attachment, String documentRef) {
    var link = new FormRevisionEvidence();
    link.setId(UUID.randomUUID());
    link.setRevisionId(revisionId);
    link.setEvidenceId(attachment.id());
    link.setDocumentRef(documentRef != null && !documentRef.isBlank() && documentRef.length() <= 255
        ? documentRef : attachment.fileName());
    link.setRawSha256(attachment.sha256());
    link.setMediaType(attachment.mediaType());
    link.setSizeBytes(attachment.sizeBytes());
    try {
      link.setMetadataJson(mapper.writeValueAsString(new Metadata(LOCAL, attachment.fileName())));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("ATTACHMENT_METADATA_WRITE_FAILED", e);
    }
    evidence.save(link);
  }

  public Attachment store(Upload upload) {
    if (!TransactionSynchronizationManager.isActualTransactionActive() ||
        !TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("ATTACHMENT_TRANSACTION_REQUIRED");
    UUID id = UUID.randomUUID();
    Path destination = location(id);
    Path temporary = null;
    try {
      Files.createDirectories(directory);
      temporary = Files.createTempFile(directory, ".attachment-", ".tmp");
      Files.write(temporary, upload.bytes());
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw error(HttpStatus.SERVICE_UNAVAILABLE, "ATTACHMENT_STORAGE_UNAVAILABLE");
    } finally {
      if (temporary != null) deleteTemporary(temporary);
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCompletion(int status) {
        if (status == STATUS_ROLLED_BACK) deleteTemporary(destination);
      }
    });
    return new Attachment(id, upload.fileName(), upload.mediaType(), upload.bytes().length, upload.sha256());
  }

  public Download load(Attachment attachment) {
    Path file = location(attachment.id());
    try {
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw error(HttpStatus.SERVICE_UNAVAILABLE, "ATTACHMENT_FILE_UNAVAILABLE");
      byte[] bytes;
      try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
        bytes = stream.readNBytes(MAX_BYTES + 1);
      }
      if (bytes.length > MAX_BYTES || bytes.length != attachment.sizeBytes() ||
          !sha256(bytes).equals(attachment.sha256()))
        throw error(HttpStatus.CONFLICT, "ATTACHMENT_INTEGRITY_MISMATCH");
      return new Download(attachment, bytes);
    } catch (IOException e) {
      throw error(HttpStatus.SERVICE_UNAVAILABLE, "ATTACHMENT_FILE_UNAVAILABLE");
    }
  }

  private Optional<Metadata> metadata(FormRevisionEvidence link) {
    String json = link.getMetadataJson();
    if (json == null || !json.contains(LOCAL)) return Optional.empty();
    try {
      var node = mapper.readTree(json);
      if (node == null || !LOCAL.equals(node.path("storage").asText())) return Optional.empty();
      return Optional.of(mapper.treeToValue(node, Metadata.class));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("ATTACHMENT_METADATA_INVALID", e);
    }
  }

  private Path location(UUID id) {
    return directory.resolve(id + ".bin");
  }

  private static void deleteTemporary(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      org.slf4j.LoggerFactory.getLogger(FormAttachmentStore.class).warn("Could not remove attachment file {}", path, e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ResponseStatusException error(HttpStatus status, String code) {
    return new ResponseStatusException(status, code);
  }
}
