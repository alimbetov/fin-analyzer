package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibReportDerivedRule;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibReportDerivedRuleRepository extends JpaRepository<LibReportDerivedRule,UUID> {
  List<LibReportDerivedRule> findByReportIdAndDerivedVersion(UUID reportId,Integer derivedVersion);
}
