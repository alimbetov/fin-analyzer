package kz.fin.formlib.service;

import kz.fin.formlib.domain.FormInstance;
import kz.fin.formlib.domain.FormStatusEvent;
import kz.fin.formlib.repository.FormInstanceRepository;
import kz.fin.formlib.repository.FormStatusEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * R8.C2: minimal, local-only intake status lifecycle.
 * READY_FOR_REVIEW is a user workflow marker, NEVER S2 financial approval.
 * Identity is not verified without authentication.
 */
@Service
public class FormWorkflowService {
  public record TransitionRequest(UUID organizationId,int expectedRevision,
      long expectedStatusVersion,String targetStatus,String actorLabel,String reason){}
  public record StatusView(UUID instanceId,UUID organizationId,int revisionNo,
      long statusVersion,String status,boolean editable,UUID revisionId){}
  public record EventView(long statusVersion,String fromStatus,String toStatus,
      UUID revisionId,String actorLabel,String reason,Instant occurredAt){}

  private final FormInstanceRepository instances;
  private final FormStatusEventRepository events;
  public FormWorkflowService(FormInstanceRepository instances,FormStatusEventRepository events){
    this.instances=instances;this.events=events;
  }
  private static ResponseStatusException bad(String code){
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,code);
  }
  private static ResponseStatusException conflict(String code){
    return new ResponseStatusException(HttpStatus.CONFLICT,code);
  }
  private static String actor(String label){
    if(label==null||label.isBlank()||label.length()>255)throw bad("ACTOR_LABEL_REQUIRED");
    return label.trim();
  }
  private static String reason(String label){
    if(label==null||label.isBlank()||label.length()>255)throw bad("TRANSITION_REASON_REQUIRED");
    return label.trim();
  }
  private FormInstance owned(UUID id,UUID org){
    if(id==null||org==null)throw bad("INSTANCE_CONTEXT_REQUIRED");
    return instances.findByIdAndOrganizationId(id,org).orElseThrow(
      ()->new ResponseStatusException(HttpStatus.NOT_FOUND,"INSTANCE_NOT_FOUND"));
  }
  private static StatusView view(FormInstance instance){
    return new StatusView(instance.getId(),instance.getOrganizationId(),
      instance.getLatestRevisionNo(),instance.getStatusVersion(),
      instance.getWorkflowStatus(),"DRAFT".equals(instance.getWorkflowStatus()),
      instance.getCurrentRevisionId());
  }
  @Transactional(readOnly=true)
  public StatusView current(UUID id,UUID organizationId){
    return view(owned(id,organizationId));
  }
  @Transactional(readOnly=true)
  public List<EventView> history(UUID id,UUID organizationId){
    owned(id,organizationId);
    return events.findByInstanceIdOrderByStatusVersionAsc(id).stream()
      .map(e->new EventView(e.getStatusVersion(),e.getFromStatus(),
        e.getToStatus(),e.getRevisionId(),e.getActorLabel(),
        e.getReason(),e.getOccurredAt())).toList();
  }
  @Transactional(readOnly=true)
  public CatalogPaging.Page<EventView> historyPage(UUID id,UUID organizationId,
      int page,int size){
    CatalogPaging.validateBounds(page,size);
    owned(id,organizationId); // authorization boundary must precede any query
    var result=events.findByInstanceIdOrderByStatusVersionAsc(id,
      org.springframework.data.domain.PageRequest.of(page,size));
    return CatalogPaging.fromDatabase(result.map(e->new EventView(
      e.getStatusVersion(),e.getFromStatus(),e.getToStatus(),
      e.getRevisionId(),e.getActorLabel(),e.getReason(),e.getOccurredAt())));
  }
  @Transactional
  public StatusView transition(UUID id,TransitionRequest req){
    if(req==null||req.organizationId()==null)throw bad("TRANSITION_CONTEXT_REQUIRED");
    String who=actor(req.actorLabel()),why=reason(req.reason());
    // The same pessimistic instance lock is used by the draft save path,
    // so status changes cannot race data changes in a different transaction.
    var inst=instances.lockByIdAndOrganizationId(id,req.organizationId()).orElseThrow(
      ()->new ResponseStatusException(HttpStatus.NOT_FOUND,"INSTANCE_NOT_FOUND"));
    if(inst.getCurrentRevisionId()==null)throw conflict("EMPTY_DRAFT_CANNOT_ADVANCE");
    if(req.expectedRevision()!=inst.getLatestRevisionNo())throw conflict("STALE_DATA_REVISION");
    if(req.expectedStatusVersion()!=inst.getStatusVersion())throw conflict("STALE_STATUS_VERSION");
    String from=inst.getWorkflowStatus();
    String to=req.targetStatus();
    boolean allowed=("DRAFT".equals(from)&&"READY_FOR_REVIEW".equals(to))
      ||("READY_FOR_REVIEW".equals(from)&&"DRAFT".equals(to));
    if(!allowed)throw conflict("INVALID_STATUS_TRANSITION");
    inst.setWorkflowStatus(to);
    inst.setStatusVersion(inst.getStatusVersion()+1);
    instances.saveAndFlush(inst);
    FormStatusEvent ev=new FormStatusEvent();ev.setId(UUID.randomUUID());
    ev.setInstanceId(inst.getId());ev.setFromStatus(from);ev.setToStatus(to);
    ev.setStatusVersion(inst.getStatusVersion());ev.setRevisionId(inst.getCurrentRevisionId());
    ev.setActorLabel(who);ev.setReason(why);ev.setOccurredAt(Instant.now());
    events.saveAndFlush(ev);
    return view(inst);
  }
}
