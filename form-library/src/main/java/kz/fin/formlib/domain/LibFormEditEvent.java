package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(schema="fin_lib",name="lib_form_edit_event")
public class LibFormEditEvent {
 @Id private UUID id;
 @Column(name="edit_session_id",nullable=false) private UUID editSessionId;
 @Column(name="edit_revision",nullable=false) private Integer editRevision;
 @Column(name="event_type",nullable=false) private String eventType;
 @Column(name="actor",nullable=false) private String actor;
 @Column(name="occurred_at",nullable=false) private Instant occurredAt;
 @Column(name="content_sha256",nullable=false) private String contentSha256;
 public UUID getId(){return id;} public void setId(UUID x){id=x;}
 public UUID getEditSessionId(){return editSessionId;} public void setEditSessionId(UUID x){editSessionId=x;}
 public Integer getEditRevision(){return editRevision;} public void setEditRevision(Integer x){editRevision=x;}
 public String getEventType(){return eventType;} public void setEventType(String x){eventType=x;}
 public String getActor(){return actor;} public void setActor(String x){actor=x;}
 public Instant getOccurredAt(){return occurredAt;} public void setOccurredAt(Instant x){occurredAt=x;}
 public String getContentSha256(){return contentSha256;} public void setContentSha256(String x){contentSha256=x;}
}
