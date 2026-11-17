package kz.fin.formlib.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** Append-only local draft lifecycle transition; not an S2 approval event. */
@Entity @Table(schema="fin_intake",name="lib_form_status_event")
public class FormStatusEvent {
 @Id private UUID id;
 @Column(name="instance_id",nullable=false) private UUID instanceId;
 @Column(name="from_status",nullable=false) private String fromStatus;
 @Column(name="to_status",nullable=false) private String toStatus;
 @Column(name="status_version",nullable=false) private Long statusVersion;
 @Column(name="revision_id",nullable=false) private UUID revisionId;
 @Column(name="actor_label",nullable=false) private String actorLabel;
 @Column(name="reason",nullable=false) private String reason;
 @Column(name="occurred_at",nullable=false) private Instant occurredAt;
 public UUID getId(){return id;} public void setId(UUID v){id=v;}
 public UUID getInstanceId(){return instanceId;} public void setInstanceId(UUID v){instanceId=v;}
 public String getFromStatus(){return fromStatus;} public void setFromStatus(String v){fromStatus=v;}
 public String getToStatus(){return toStatus;} public void setToStatus(String v){toStatus=v;}
 public Long getStatusVersion(){return statusVersion;} public void setStatusVersion(Long v){statusVersion=v;}
 public UUID getRevisionId(){return revisionId;} public void setRevisionId(UUID v){revisionId=v;}
 public String getActorLabel(){return actorLabel;} public void setActorLabel(String v){actorLabel=v;}
 public String getReason(){return reason;} public void setReason(String v){reason=v;}
 public Instant getOccurredAt(){return occurredAt;} public void setOccurredAt(Instant v){occurredAt=v;}
}
