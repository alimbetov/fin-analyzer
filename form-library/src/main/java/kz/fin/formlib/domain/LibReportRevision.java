package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
import java.time.LocalDate;
@Entity
@Table(schema="fin_intake",name="lib_report_revision")
public class LibReportRevision {
@Id
@Column(name="id") private UUID id;
@Column(name="report_id",nullable=false) private UUID reportId;
@Column(name="revision_no",nullable=false) private Integer revisionNo;
@Column(name="canonical_content_sha256",nullable=false) private String canonicalContentSha256;
@Column(name="created_at",nullable=false) private Instant createdAt;
@Column(name="created_by",nullable=false) private String createdBy;
@Column(name="change_reason",nullable=false) private String changeReason;
@Column(name="source_kind",nullable=false) private String sourceKind;
@Column(name="qualification_state",nullable=false) private String qualificationState;
@Column(name="validation_version",nullable=false) private Integer validationVersion;
@Column(name="validation_sha256") private String validationSha256;
public LibReportRevision(){}
public UUID getId(){return id;}
public void setId(UUID v){id=v;}
public UUID getReportId(){return reportId;}
public void setReportId(UUID v){reportId=v;}
public Integer getRevisionNo(){return revisionNo;}
public void setRevisionNo(Integer v){revisionNo=v;}
public String getCanonicalContentSha256(){return canonicalContentSha256;}
public void setCanonicalContentSha256(String v){canonicalContentSha256=v;}
public Instant getCreatedAt(){return createdAt;}
public void setCreatedAt(Instant v){createdAt=v;}
public String getCreatedBy(){return createdBy;}
public void setCreatedBy(String v){createdBy=v;}
public String getChangeReason(){return changeReason;}
public void setChangeReason(String v){changeReason=v;}
public String getSourceKind(){return sourceKind;}
public void setSourceKind(String v){sourceKind=v;}
public Integer getValidationVersion(){return validationVersion;}
public void setValidationVersion(Integer v){validationVersion=v;}
public String getValidationSha256(){return validationSha256;}
public void setValidationSha256(String v){validationSha256=v;}
public String getQualificationState(){return qualificationState;}
public void setQualificationState(String v){qualificationState=v;}
}
