package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.TableSnapshotEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableSnapshotRepository extends JpaRepository<TableSnapshotEntity, String> {

    Optional<TableSnapshotEntity> findByTableIdAndSnapshotId(String tableId, long snapshotId);

    Optional<TableSnapshotEntity> findByTableIdAndUuid(String tableId, String uuid);

    Optional<TableSnapshotEntity> findFirstByTableIdOrderBySnapshotIdDesc(String tableId);

    Optional<TableSnapshotEntity> findByTableIdAndVersion(String tableId, int version);

    List<TableSnapshotEntity> findAllByTableIdOrderBySnapshotIdDesc(String tableId);

    long deleteByTableIdAndSnapshotIdGreaterThan(String tableId, long snapshotId);
}
