package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.ConsumerEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumerRepository extends JpaRepository<ConsumerEntity, String> {

    Optional<ConsumerEntity> findByTableIdAndConsumerId(String tableId, String consumerId);

    List<ConsumerEntity> findAllByTableIdOrderByConsumerIdAsc(String tableId);

    long deleteByTableId(String tableId);
}
