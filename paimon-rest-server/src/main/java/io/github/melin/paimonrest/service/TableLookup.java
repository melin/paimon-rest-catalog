package io.github.melin.paimonrest.service;

import io.github.melin.paimonrest.domain.entity.DatabaseEntity;
import io.github.melin.paimonrest.domain.entity.TableEntity;
import io.github.melin.paimonrest.domain.repo.TableRepository;
import io.github.melin.paimonrest.support.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定位 database 与 table 的公共入口。
 *
 * <p>拆成独立组件是为了避免 {@code TableService} 与 {@code PartitionService}
 * 之间出现循环依赖：两者都只依赖这里，不互相依赖。
 */
@Service
@RequiredArgsConstructor
public class TableLookup {

    private final DatabaseService databaseService;
    private final TableRepository tableRepository;

    /** 按路径解析 database，不存在时抛出 404 DATABASE。 */
    @Transactional(readOnly = true)
    public DatabaseEntity requireDatabase(String prefix, String databaseName) {
        return databaseService.require(prefix, databaseName);
    }

    /** 按路径解析表，不存在时抛出 404 TABLE。 */
    @Transactional(readOnly = true)
    public TableEntity requireTable(String prefix, String databaseName, String tableName) {
        DatabaseEntity database = databaseService.require(prefix, databaseName);
        return tableRepository
                .findByCatalogIdAndDatabaseIdAndName(database.getCatalogId(), database.getId(), tableName)
                .orElseThrow(() -> ApiException.tableNotExist(tableName));
    }
}
