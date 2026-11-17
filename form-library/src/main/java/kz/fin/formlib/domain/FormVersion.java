package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_lib", name="lib_form_version")
public class FormVersion {
@Id
@Column(name="id") private UUID id;
@Column(name="form_id",nullable=false) private UUID formId;
@Column(name="version_label",nullable=false) private String versionLabel;
@Column(name="content_mode",nullable=false) private String contentMode;
@Column(name="grain",nullable=false) private String grain;
@Column(name="qualification_state",nullable=false) private String qualificationState;
@Column(name="schema_sha256",nullable=false) private String schemaSha256;
@Column(name="presentation_sha256",nullable=false) private String presentationSha256;
@Column(name="mapping_sha256",nullable=false) private String mappingSha256;
@Column(name="created_at",nullable=false) private Instant createdAt;
public FormVersion(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getFormId(){return formId;}
public void setFormId(UUID value){this.formId=value;}
public String getVersionLabel(){return versionLabel;}
public void setVersionLabel(String value){this.versionLabel=value;}
public String getContentMode(){return contentMode;}
public void setContentMode(String value){this.contentMode=value;}
public String getGrain(){return grain;}
public void setGrain(String value){this.grain=value;}
public String getQualificationState(){return qualificationState;}
public void setQualificationState(String value){this.qualificationState=value;}
public String getSchemaSha256(){return schemaSha256;}
public void setSchemaSha256(String value){this.schemaSha256=value;}
public String getPresentationSha256(){return presentationSha256;}
public void setPresentationSha256(String value){this.presentationSha256=value;}
public String getMappingSha256(){return mappingSha256;}
public void setMappingSha256(String value){this.mappingSha256=value;}
public Instant getCreatedAt(){return createdAt;}
public void setCreatedAt(Instant value){this.createdAt=value;}
}
