package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibReportPeriod;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibReportPeriodRepository extends JpaRepository<LibReportPeriod,UUID>{java.util.List<LibReportPeriod> findByReportIdOrderByDisplayOrderAsc(UUID reportId);}
