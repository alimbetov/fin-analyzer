package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormMetricMappingCandidate;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormMetricMappingCandidateRepository extends JpaRepository<FormMetricMappingCandidate,UUID> {}
