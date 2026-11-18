package kz.fin.formlib.domain;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.util.UUID;
import java.time.Instant;
@Entity
@Getter
@Setter
@NoArgsConstructor
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
}
