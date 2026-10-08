package com.example.paimonrest.domain.repo;

import com.example.paimonrest.domain.entity.ResourceGrantEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 资源授权仓储。 */
public interface ResourceGrantRepository extends JpaRepository<ResourceGrantEntity, String> {

    List<ResourceGrantEntity> findByCatalogRoleId(String catalogRoleId);

    boolean existsByCatalogRoleIdAndResourceTypeAndNamespaceKeyAndObjectNameAndPrivilege(
            String catalogRoleId, String resourceType, String namespaceKey,
            String objectName, String privilege);

    List<ResourceGrantEntity> findByCatalogRoleIdAndResourceTypeAndNamespaceKeyAndObjectNameAndPrivilege(
            String catalogRoleId, String resourceType, String namespaceKey,
            String objectName, String privilege);

    void deleteByCatalogRoleId(String catalogRoleId);

    /** 按 catalog role 集合批量取授权，供授权判定一次装载。 */
    List<ResourceGrantEntity> findByCatalogRoleIdIn(Iterable<String> catalogRoleIds);
}
