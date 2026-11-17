package kz.fin.formlib.repository;
import kz.fin.formlib.domain.LibReportCommandReceipt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LibReportCommandReceiptRepository extends JpaRepository<LibReportCommandReceipt,UUID>{java.util.Optional<LibReportCommandReceipt> findByReportIdAndIdempotencyKey(UUID reportId,String key);}
