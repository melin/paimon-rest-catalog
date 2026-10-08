package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.CatalogRoleAssignmentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 「catalog role 授予 principal role」关系仓储。 */
public interface CatalogRoleAssignmentRepository
        extends JpaRepository<CatalogRoleAssignmentEntity, String> {

    List<CatalogRoleAssignmentEntity> findByPrincipalRoleId(String principalRoleId);

    List<CatalogRoleAssignmentEntity> findByCatalogRoleId(String catalogRoleId);

    boolean existsByPrincipalRoleIdAndCatalogRoleId(String principalRoleId, String catalogRoleId);

    void deleteByPrincipalRoleId(String principalRoleId);

    void deleteByCatalogRoleId(String catalogRoleId);

    void deleteByPrincipalRoleIdAndCatalogRoleId(String principalRoleId, String catalogRoleId);
}
