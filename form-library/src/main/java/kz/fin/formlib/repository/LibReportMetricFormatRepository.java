package kz.fin.formlib.repository;

import kz.fin.formlib.domain.LibReportMetricFormat;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibReportMetricFormatRepository extends JpaRepository<LibReportMetricFormat,UUID> {
  List<LibReportMetricFormat> findByReportIdAndFormatVersion(UUID reportId,Integer formatVersion);
}
