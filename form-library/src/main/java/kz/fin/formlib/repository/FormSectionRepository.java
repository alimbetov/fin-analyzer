package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormSection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormSectionRepository extends JpaRepository<FormSection,UUID> {java.util.List<FormSection> findByFormVersionIdOrderBySectionOrderAsc(UUID formVersionId);}
