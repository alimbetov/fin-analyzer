package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibMetricDefinition;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibMetricDefinitionRepository extends JpaRepository<LibMetricDefinition,UUID> {java.util.Optional<LibMetricDefinition> findByMetricCode(String metricCode); java.util.List<LibMetricDefinition> findAllByOrderByMetricCodeAsc();
  java.util.List<LibMetricDefinition> findByFormCodeOrderByMetricCodeAsc(String formCode);}
