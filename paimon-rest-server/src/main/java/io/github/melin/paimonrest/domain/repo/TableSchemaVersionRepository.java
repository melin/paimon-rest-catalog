package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.TableSchemaVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableSchemaVersionRepository extends JpaRepository<TableSchemaVersionEntity, String> {

    Optional<TableSchemaVersionEntity> findByTableIdAndSchemaId(String tableId, long schemaId);

    List<TableSchemaVersionEntity> findAllByTableIdOrderBySchemaIdAsc(String tableId);
}
