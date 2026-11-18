package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_intake", name="lib_form_revision_evidence")
public class FormRevisionEvidence {
@Id
@Column(name="id") private UUID id;
@Column(name="revision_id",nullable=false) private UUID revisionId;
@Column(name="evidence_id",nullable=false) private UUID evidenceId;
@Column(name="document_ref",nullable=false) private String documentRef;
@Column(name="raw_sha256",nullable=false) private String rawSha256;
@Column(name="media_type",nullable=false) private String mediaType;
@Column(name="size_bytes",nullable=false) private Long sizeBytes;
@Column(name="metadata_json",columnDefinition="text") private String metadataJson;
public FormRevisionEvidence(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getRevisionId(){return revisionId;}
public void setRevisionId(UUID value){this.revisionId=value;}
public UUID getEvidenceId(){return evidenceId;}
public void setEvidenceId(UUID value){this.evidenceId=value;}
public String getDocumentRef(){return documentRef;}
public void setDocumentRef(String value){this.documentRef=value;}
public String getRawSha256(){return rawSha256;}
public void setRawSha256(String value){this.rawSha256=value;}
public String getMediaType(){return mediaType;}
public void setMediaType(String value){this.mediaType=value;}
public Long getSizeBytes(){return sizeBytes;}
public void setSizeBytes(Long value){this.sizeBytes=value;}
public String getMetadataJson(){return metadataJson;}
public void setMetadataJson(String value){this.metadataJson=value;}
}
