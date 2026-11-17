package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibReportRevisionCell;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibReportRevisionCellRepository extends JpaRepository<LibReportRevisionCell,UUID>{java.util.List<LibReportRevisionCell> findByRevisionId(UUID revisionId);}
