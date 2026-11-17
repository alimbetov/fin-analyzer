package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.util.UUID;
@Entity @Table(schema="fin_lib",name="lib_form_translation")
public class LibFormTranslation {
@Id @Column(name="id") private UUID id;
@Column(name="form_version_id",nullable=false) private UUID formVersionId;
@Column(name="locale",nullable=false) private String locale;
@Column(name="title") private String title;
@Column(name="description") private String description;
@Column(name="review_status",nullable=false) private String reviewStatus;
public UUID getId(){return id;}
public void setId(UUID v){id=v;}
public UUID getFormVersionId(){return formVersionId;}
public void setFormVersionId(UUID v){formVersionId=v;}
public String getLocale(){return locale;}
public void setLocale(String v){locale=v;}
public String getTitle(){return title;}
public void setTitle(String v){title=v;}
public String getDescription(){return description;}
public void setDescription(String v){description=v;}
public String getReviewStatus(){return reviewStatus;}
public void setReviewStatus(String v){reviewStatus=v;}
}
