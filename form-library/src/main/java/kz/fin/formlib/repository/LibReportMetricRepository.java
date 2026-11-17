package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibReportMetric;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibReportMetricRepository extends JpaRepository<LibReportMetric,UUID>{java.util.List<LibReportMetric> findByReportIdOrderByDisplayOrderAsc(UUID reportId);}
