package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormInstance;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormInstanceRepository extends JpaRepository<FormInstance,UUID> {java.util.Optional<FormInstance> findByIdAndOrganizationId(UUID id,UUID organizationId);}
