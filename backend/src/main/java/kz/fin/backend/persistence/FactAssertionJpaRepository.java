package kz.fin.backend.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FactAssertionJpaRepository extends JpaRepository<FactAssertionJpaEntity, UUID> {
}
