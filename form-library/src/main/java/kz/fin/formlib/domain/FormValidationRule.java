package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_lib", name="lib_form_validation_rule")
public class FormValidationRule {
@Id
@Column(name="id") private UUID id;
@Column(name="form_version_id",nullable=false) private UUID formVersionId;
@Column(name="rule_code",nullable=false) private String ruleCode;
@Column(name="rule_version",nullable=false) private String ruleVersion;
@Column(name="rule_kind",nullable=false) private String ruleKind;
@Column(name="typed_params",columnDefinition="text") private String typedParams;
@Column(name="severity",nullable=false) private String severity;
public FormValidationRule(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getFormVersionId(){return formVersionId;}
public void setFormVersionId(UUID value){this.formVersionId=value;}
public String getRuleCode(){return ruleCode;}
public void setRuleCode(String value){this.ruleCode=value;}
public String getRuleVersion(){return ruleVersion;}
public void setRuleVersion(String value){this.ruleVersion=value;}
public String getRuleKind(){return ruleKind;}
public void setRuleKind(String value){this.ruleKind=value;}
public String getTypedParams(){return typedParams;}
public void setTypedParams(String value){this.typedParams=value;}
public String getSeverity(){return severity;}
public void setSeverity(String value){this.severity=value;}
}
