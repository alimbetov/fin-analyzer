package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibReportRevision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibReportRevisionRepository extends JpaRepository<LibReportRevision,UUID>{java.util.Optional<LibReportRevision> findByReportIdAndRevisionNo(UUID reportId,Integer revisionNo);}
