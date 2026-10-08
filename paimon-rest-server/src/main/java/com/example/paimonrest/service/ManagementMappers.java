package com.example.paimonrest.service;

import com.example.paimonrest.domain.entity.CatalogRoleEntity;
import com.example.paimonrest.domain.entity.PrincipalEntity;
import com.example.paimonrest.domain.entity.PrincipalRoleEntity;
import com.example.paimonrest.dto.ManagementDtos.CatalogRole;
import com.example.paimonrest.dto.ManagementDtos.Principal;
import com.example.paimonrest.dto.ManagementDtos.PrincipalRole;
import java.util.LinkedHashMap;

/**
 * 管理实体 → 管理规格 DTO 的映射。
 *
 * <p>抽成静态工具而非放在各服务里，是为了打断
 * {@link PrincipalService} 与 {@link PrincipalRoleService} 之间的构造器依赖环：
 * 两者都需要把「另一方」的实体转成 DTO，若把映射放在服务上就会互相注入。
 */
public final class ManagementMappers {

    private ManagementMappers() {
    }

    /** 实体 → 规格 {@code Principal}。不含任何密钥字段。 */
    public static Principal toDto(PrincipalEntity entity) {
        return new Principal(
                entity.getName(),
                entity.getClientId(),
                new LinkedHashMap<>(entity.getProperties()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getEntityVersion());
    }

    /** 实体 → 规格 {@code PrincipalRole}。 */
    public static PrincipalRole toDto(PrincipalRoleEntity entity) {
        return new PrincipalRole(
                entity.getName(),
                entity.isFederated(),
                new LinkedHashMap<>(entity.getProperties()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getEntityVersion());
    }

    /** 实体 → 规格 {@code CatalogRole}。 */
    public static CatalogRole toDto(CatalogRoleEntity entity) {
        return new CatalogRole(
                entity.getName(),
                new LinkedHashMap<>(entity.getProperties()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getEntityVersion());
    }
}
