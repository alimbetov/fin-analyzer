package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibReportDefinition;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibReportDefinitionRepository extends JpaRepository<LibReportDefinition,UUID>{java.util.Optional<LibReportDefinition> findByIdAndOrganizationId(UUID id,UUID organizationId); java.util.Optional<LibReportDefinition> findByOrganizationIdAndReportCode(UUID organizationId,String reportCode);}
