package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.TableSnapshotEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableSnapshotRepository extends JpaRepository<TableSnapshotEntity, String> {

    Optional<TableSnapshotEntity> findByTableIdAndSnapshotId(String tableId, long snapshotId);

    Optional<TableSnapshotEntity> findByTableIdAndUuid(String tableId, String uuid);

    Optional<TableSnapshotEntity> findFirstByTableIdOrderBySnapshotIdDesc(String tableId);

    /** {@code EARLIEST}：按快照 id 取最早的那个。 */
    Optional<TableSnapshotEntity> findFirstByTableIdOrderBySnapshotIdAsc(String tableId);

    List<TableSnapshotEntity> findAllByTableIdOrderBySnapshotIdDesc(String tableId);

    long deleteByTableIdAndSnapshotIdGreaterThan(String tableId, long snapshotId);
}
