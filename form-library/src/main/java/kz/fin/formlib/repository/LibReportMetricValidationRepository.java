package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibReportMetricValidation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface LibReportMetricValidationRepository
    extends JpaRepository<LibReportMetricValidation,UUID> {
  List<LibReportMetricValidation> findByReportIdAndValidationVersion(UUID reportId,Integer validationVersion);
}
