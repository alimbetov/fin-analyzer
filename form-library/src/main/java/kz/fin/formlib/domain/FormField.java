package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_lib", name="lib_form_field")
public class FormField {
@Id
@Column(name="id") private UUID id;
@Column(name="form_version_id",nullable=false) private UUID formVersionId;
@Column(name="section_id",nullable=false) private UUID sectionId;
@Column(name="field_code",nullable=false) private String fieldCode;
@Column(name="display_order",nullable=false) private Integer displayOrder;
@Column(name="field_type",nullable=false) private String fieldType;
@Column(name="input_mode",nullable=false) private String inputMode;
@Column(name="required",nullable=false) private Boolean required;
@Column(name="formula_code") private String formulaCode;
@Column(name="unit_code") private String unitCode;
public FormField(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getFormVersionId(){return formVersionId;}
public void setFormVersionId(UUID value){this.formVersionId=value;}
public UUID getSectionId(){return sectionId;}
public void setSectionId(UUID value){this.sectionId=value;}
public String getFieldCode(){return fieldCode;}
public void setFieldCode(String value){this.fieldCode=value;}
public Integer getDisplayOrder(){return displayOrder;}
public void setDisplayOrder(Integer value){this.displayOrder=value;}
public String getFieldType(){return fieldType;}
public void setFieldType(String value){this.fieldType=value;}
public String getInputMode(){return inputMode;}
public void setInputMode(String value){this.inputMode=value;}
public Boolean getRequired(){return required;}
public void setRequired(Boolean value){this.required=value;}
public String getFormulaCode(){return formulaCode;}
public void setFormulaCode(String value){this.formulaCode=value;}
public String getUnitCode(){return unitCode;}
public void setUnitCode(String value){this.unitCode=value;}
}
