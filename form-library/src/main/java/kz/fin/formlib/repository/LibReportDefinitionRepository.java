package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibReportDefinition;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
public interface LibReportDefinitionRepository extends JpaRepository<LibReportDefinition,UUID>{java.util.Optional<LibReportDefinition> findByIdAndOrganizationId(UUID id,UUID organizationId); java.util.Optional<LibReportDefinition> findByOrganizationIdAndReportCode(UUID organizationId,String reportCode);
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select r from LibReportDefinition r where r.id=:id and r.organizationId=:organizationId")
java.util.Optional<LibReportDefinition> lockByIdAndOrganizationId(
  @Param("id") UUID id,@Param("organizationId") UUID organizationId);
}
