package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_lib", name="lib_form_section")
public class FormSection {
@Id
@Column(name="id") private UUID id;
@Column(name="form_version_id",nullable=false) private UUID formVersionId;
@Column(name="section_code",nullable=false) private String sectionCode;
@Column(name="section_order",nullable=false) private Integer sectionOrder;
@Column(name="parent_section_id") private UUID parentSectionId;
@Column(name="layout_kind",nullable=false) private String layoutKind;
public FormSection(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getFormVersionId(){return formVersionId;}
public void setFormVersionId(UUID value){this.formVersionId=value;}
public String getSectionCode(){return sectionCode;}
public void setSectionCode(String value){this.sectionCode=value;}
public Integer getSectionOrder(){return sectionOrder;}
public void setSectionOrder(Integer value){this.sectionOrder=value;}
public UUID getParentSectionId(){return parentSectionId;}
public void setParentSectionId(UUID value){this.parentSectionId=value;}
public String getLayoutKind(){return layoutKind;}
public void setLayoutKind(String value){this.layoutKind=value;}
}
