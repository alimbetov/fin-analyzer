package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormVersion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormVersionRepository extends JpaRepository<FormVersion,UUID> {java.util.Optional<FormVersion> findByFormIdAndVersionLabel(UUID formId,String versionLabel);}
