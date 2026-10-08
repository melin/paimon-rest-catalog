package com.example.paimonrest.service;

import com.example.paimonrest.dto.StorageDtos.StorageConfigInfo;
import com.example.paimonrest.support.CredentialManagerType;
import com.example.paimonrest.support.VendedStorageCredential;

/**
 * 数据访问凭据的下发策略。
 *
 * <p>对应 Polaris 的 {@code PolarisCredentialManager}：按 catalog 的存储配置，
 * 决定把什么交给引擎去访问该仓库。实现按
 * {@code paimon.rest.credential-manager.type} 选用，见 {@link StorageRuntimePolicy}。
 *
 * <p>入参是 {@code StorageConfigInfo} 而不是表实体：凭据取决于「这个 catalog
 * 用哪种存储、什么地址、哪个具名存储」，与具体是哪张表无关。
 * 表路径单独传入，是为了将来支持按路径收窄的凭据（例如 Azure 的 SAS 作用域
 * 收窄到表目录）。当前实现里 S3 与 GCS 的凭据与路径无关，但接口留出这个位置，
 * 免得将来收窄时又要改一遍所有调用点。
 */
public interface StorageCredentialManager {

    /** 本实现对应的配置取值。 */
    CredentialManagerType type();

    /**
     * 为该存储配置签发一次凭据。
     *
     * @param storage   catalog 的存储配置
     * @param tableId   表 id，作为自包含令牌的作用域标识
     * @param tablePath 表的基路径，可为 {@code null}
     * @throws com.example.paimonrest.support.ApiException 配置缺失或本部署不提供该能力
     */
    VendedStorageCredential vend(StorageConfigInfo storage, String tableId, String tablePath);
}
