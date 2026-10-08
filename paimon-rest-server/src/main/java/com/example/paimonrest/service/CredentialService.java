package com.example.paimonrest.service;

import com.example.paimonrest.config.RestServerProperties;
import com.example.paimonrest.domain.entity.TableEntity;
import com.example.paimonrest.dto.TableDtos;
import com.example.paimonrest.dto.TypeDtos;
import com.example.paimonrest.support.ApiException;
import com.example.paimonrest.support.Codecs;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 表级数据访问授权，对应 Polaris 的凭证下发模型。
 *
 * <p>引擎不直接持有对象存储的长期密钥：先向目录服务申请一个短时效、范围限定到单表的
 * 令牌，服务端确认调用方有权访问该表后才签发。这里签发的是自包含令牌，
 * 生产环境可替换为对接云 IAM 的 STS 调用。
 */
@Service
@RequiredArgsConstructor
public class CredentialService {

    private final TableLookup tableLookup;
    private final RestServerProperties properties;
    private final SecureRandom random = new SecureRandom();

    /** {@code GET .../tables/{table}/token}：签发数据访问令牌。 */
    @Transactional(readOnly = true)
    public TableDtos.GetTableDataTokenResponse token(String prefix, String database, String table) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        long expiresAt = System.currentTimeMillis() + properties.getCredential().getTtlSeconds() * 1000L;

        Map<String, String> token = new LinkedHashMap<>();
        token.put("accessKeyId", "PAIMON-" + target.getId());
        token.put("accessKeySecret", randomSecret(24));
        token.put("securityToken", randomSecret(48));
        token.put("expiration", Instant.ofEpochMilli(expiresAt).toString());
        if (target.getPath() != null) {
            token.put("tablePath", target.getPath());
        }
        return new TableDtos.GetTableDataTokenResponse(token, expiresAt);
    }

    /**
     * {@code POST .../tables/{table}/auth}：查询鉴权。
     *
     * <p>返回该查询需要附加的行过滤表达式与列脱敏规则；请求的列若不在表 schema 中，
     * 直接以 403 拒绝。当前实现不配置策略，因此返回空的过滤与脱敏规则。
     */
    @Transactional(readOnly = true)
    public TableDtos.AuthTableQueryResponse auth(String prefix, String database, String table,
                                                 TableDtos.AuthTableQueryRequest request) {
        TableEntity target = tableLookup.requireTable(prefix, database, table);
        if (request != null && request.select() != null && !request.select().isEmpty()) {
            Set<String> known = new HashSet<>(columnNames(Codecs.readSchema(target.getSchemaDoc())));
            for (String column : request.select()) {
                if (column != null && !column.isBlank() && !known.contains(column)) {
                    throw ApiException.forbidden("Table has no permission for column: " + column);
                }
            }
        }
        return new TableDtos.AuthTableQueryResponse(new ArrayList<>(), new LinkedHashMap<>());
    }

    private static List<String> columnNames(TypeDtos.Schema schema) {
        List<String> names = new ArrayList<>();
        for (TypeDtos.DataField field : schema.getFields()) {
            if (field != null && field.getName() != null) {
                names.add(field.getName());
            }
        }
        return names;
    }

    private String randomSecret(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
