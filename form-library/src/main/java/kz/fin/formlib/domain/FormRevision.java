package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_intake", name="lib_form_revision")
public class FormRevision {
@Id
@Column(name="id") private UUID id;
@Column(name="instance_id",nullable=false) private UUID instanceId;
@Column(name="revision_no",nullable=false) private Integer revisionNo;
@Column(name="form_version_id",nullable=false) private UUID formVersionId;
@Column(name="content_mode",nullable=false) private String contentMode;
@Lob
@Column(name="values_json") private String valuesJson;
@Column(name="canonical_content_sha256",nullable=false) private String canonicalContentSha256;
@Column(name="row_order_sha256",nullable=false) private String rowOrderSha256;
@Column(name="source_kind",nullable=false) private String sourceKind;
@Column(name="created_at",nullable=false) private Instant createdAt;
@Column(name="created_by",nullable=false) private String createdBy;
@Column(name="change_reason",nullable=false) private String changeReason;
public FormRevision(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getInstanceId(){return instanceId;}
public void setInstanceId(UUID value){this.instanceId=value;}
public Integer getRevisionNo(){return revisionNo;}
public void setRevisionNo(Integer value){this.revisionNo=value;}
public UUID getFormVersionId(){return formVersionId;}
public void setFormVersionId(UUID value){this.formVersionId=value;}
public String getContentMode(){return contentMode;}
public void setContentMode(String value){this.contentMode=value;}
public String getValuesJson(){return valuesJson;}
public void setValuesJson(String value){this.valuesJson=value;}
public String getCanonicalContentSha256(){return canonicalContentSha256;}
public void setCanonicalContentSha256(String value){this.canonicalContentSha256=value;}
public String getRowOrderSha256(){return rowOrderSha256;}
public void setRowOrderSha256(String value){this.rowOrderSha256=value;}
public String getSourceKind(){return sourceKind;}
public void setSourceKind(String value){this.sourceKind=value;}
public Instant getCreatedAt(){return createdAt;}
public void setCreatedAt(Instant value){this.createdAt=value;}
public String getCreatedBy(){return createdBy;}
public void setCreatedBy(String value){this.createdBy=value;}
public String getChangeReason(){return changeReason;}
public void setChangeReason(String value){this.changeReason=value;}
}
