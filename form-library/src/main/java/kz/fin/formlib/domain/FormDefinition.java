package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_lib", name="lib_form_definition")
public class FormDefinition {
@Id
@Column(name="id") private UUID id;
@Column(name="form_code",nullable=false) private String formCode;
@Column(name="catalog",nullable=false) private String catalog;
@Column(name="legacy_capture_mode",nullable=false) private String legacyCaptureMode;
@Column(name="current_version_id") private UUID currentVersionId;
public FormDefinition(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public String getFormCode(){return formCode;}
public void setFormCode(String value){this.formCode=value;}
public String getCatalog(){return catalog;}
public void setCatalog(String value){this.catalog=value;}
public String getLegacyCaptureMode(){return legacyCaptureMode;}
public void setLegacyCaptureMode(String value){this.legacyCaptureMode=value;}
public UUID getCurrentVersionId(){return currentVersionId;}
public void setCurrentVersionId(UUID value){this.currentVersionId=value;}
}
