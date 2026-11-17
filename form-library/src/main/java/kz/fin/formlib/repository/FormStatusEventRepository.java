package kz.fin.formlib.repository;
import kz.fin.formlib.domain.FormStatusEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface FormStatusEventRepository extends JpaRepository<FormStatusEvent,UUID> {
 List<FormStatusEvent> findByInstanceIdOrderByStatusVersionAsc(UUID instanceId);
}
