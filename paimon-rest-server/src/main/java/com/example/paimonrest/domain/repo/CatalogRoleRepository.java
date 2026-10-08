package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.CatalogRoleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** catalog role 仓储。同名 catalog role 可存在于不同 catalog，故查询均以 catalogId 限定。 */
public interface CatalogRoleRepository extends JpaRepository<CatalogRoleEntity, String> {

    Optional<CatalogRoleEntity> findByCatalogIdAndName(String catalogId, String name);

    boolean existsByCatalogIdAndName(String catalogId, String name);

    List<CatalogRoleEntity> findByCatalogIdOrderByNameAsc(String catalogId);

    List<CatalogRoleEntity> findAllByIdIn(Iterable<String> ids);

    void deleteByCatalogId(String catalogId);
}
