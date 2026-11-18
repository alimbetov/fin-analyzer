package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormInstance;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormInstanceRepository extends JpaRepository<FormInstance,UUID> {java.util.Optional<FormInstance> findByIdAndOrganizationId(UUID id,UUID organizationId);
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select i from FormInstance i where i.id=:id and i.organizationId=:organizationId")
java.util.Optional<FormInstance> lockByIdAndOrganizationId(@Param("id") UUID id,
  @Param("organizationId") UUID organizationId);
@Query("""
 select i from FormInstance i where
   i.organizationId=:organizationId and i.economicEntityId=:economicEntityId
   and i.businessScopeId=:businessScopeId and i.projectId=:projectId
   and i.formId=:formId and i.periodType=:periodType
   and i.periodCode=:periodCode and i.coverage=:coverage and i.currency=:currency
   and i.reportingBasis=:reportingBasis and i.dataSpace=:dataSpace
 """)
java.util.Optional<FormInstance> findExactSlot(
  @Param("organizationId") UUID organizationId,
  @Param("economicEntityId") UUID economicEntityId,
  @Param("businessScopeId") UUID businessScopeId,
  @Param("projectId") UUID projectId,
  @Param("formId") UUID formId,
  @Param("periodType") String periodType,
  @Param("periodCode") String periodCode,
  @Param("coverage") String coverage,
  @Param("currency") String currency,
  @Param("reportingBasis") String reportingBasis,
  @Param("dataSpace") String dataSpace);

}
