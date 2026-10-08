package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.PrincipalRoleAssignmentEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 「principal role 授予主体」关系仓储。 */
public interface PrincipalRoleAssignmentRepository
        extends JpaRepository<PrincipalRoleAssignmentEntity, String> {

    List<PrincipalRoleAssignmentEntity> findByPrincipalId(String principalId);

    List<PrincipalRoleAssignmentEntity> findByPrincipalRoleId(String principalRoleId);

    boolean existsByPrincipalIdAndPrincipalRoleId(String principalId, String principalRoleId);

    void deleteByPrincipalId(String principalId);

    void deleteByPrincipalRoleId(String principalRoleId);

    void deleteByPrincipalIdAndPrincipalRoleId(String principalId, String principalRoleId);
}
