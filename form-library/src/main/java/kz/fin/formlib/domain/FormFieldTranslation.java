package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
import java.time.Instant;
@Entity
@Table(schema="fin_lib", name="lib_form_field_translation")
public class FormFieldTranslation {
@Id
@Column(name="id") private UUID id;
@Column(name="form_version_id",nullable=false) private UUID formVersionId;
@Column(name="subject_type",nullable=false) private String subjectType;
@Column(name="subject_code",nullable=false) private String subjectCode;
@Column(name="locale",nullable=false) private String locale;
@Column(name="title",nullable=false) private String title;
@Column(name="description") private String description;
@Column(name="review_status",nullable=false) private String reviewStatus="LEGACY_UNREVIEWED";
public FormFieldTranslation(){}
public UUID getId(){return id;}
public void setId(UUID value){this.id=value;}
public UUID getFormVersionId(){return formVersionId;}
public void setFormVersionId(UUID value){this.formVersionId=value;}
public String getSubjectType(){return subjectType;}
public void setSubjectType(String value){this.subjectType=value;}
public String getSubjectCode(){return subjectCode;}
public void setSubjectCode(String value){this.subjectCode=value;}
public String getLocale(){return locale;}
public void setLocale(String value){this.locale=value;}
public String getTitle(){return title;}
public void setTitle(String value){this.title=value;}
public String getDescription(){return description;}
public void setDescription(String value){this.description=value;}
public String getReviewStatus(){return reviewStatus;}
public void setReviewStatus(String value){reviewStatus=value;}
}
