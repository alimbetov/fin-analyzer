package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormRevisionEvidence;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormRevisionEvidenceRepository extends JpaRepository<FormRevisionEvidence,UUID> {
  List<FormRevisionEvidence> findByRevisionId(UUID revisionId);
}
