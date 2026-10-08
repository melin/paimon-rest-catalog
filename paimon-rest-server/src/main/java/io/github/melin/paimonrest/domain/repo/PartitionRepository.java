package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.PartitionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartitionRepository extends JpaRepository<PartitionEntity, String> {

    Optional<PartitionEntity> findByTableIdAndSpecKey(String tableId, String specKey);

    List<PartitionEntity> findAllByTableIdOrderBySpecKeyAsc(String tableId);

    long deleteByTableId(String tableId);
}
