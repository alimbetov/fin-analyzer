package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_lib", name="lib_form_metric_mapping_candidate")
public class FormMetricMappingCandidate {
@Id
@Column(name="id") private UUID id;
@Column(name="form_version_id",nullable=false) private UUID formVersionId;
@Column(name="field_code",nullable=false) private String fieldCode;
@Column(name="metric_code",nullable=false) private String metricCode;
@Column(name="metric_version",nullable=false) private String metricVersion;
@Column(name="mapping_sha256",nullable=false) private String mappingSha256;
@Column(name="review_state",nullable=false) private String reviewState;
public FormMetricMappingCandidate(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getFormVersionId(){return formVersionId;}
public void setFormVersionId(UUID value){this.formVersionId=value;}
public String getFieldCode(){return fieldCode;}
public void setFieldCode(String value){this.fieldCode=value;}
public String getMetricCode(){return metricCode;}
public void setMetricCode(String value){this.metricCode=value;}
public String getMetricVersion(){return metricVersion;}
public void setMetricVersion(String value){this.metricVersion=value;}
public String getMappingSha256(){return mappingSha256;}
public void setMappingSha256(String value){this.mappingSha256=value;}
public String getReviewState(){return reviewState;}
public void setReviewState(String value){this.reviewState=value;}
}
