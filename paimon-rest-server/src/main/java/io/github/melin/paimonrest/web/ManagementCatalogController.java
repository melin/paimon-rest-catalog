package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.config.RequestContext;
import io.github.melin.paimonrest.dto.ManagementDtos.Catalog;
import io.github.melin.paimonrest.dto.ManagementDtos.Catalogs;
import io.github.melin.paimonrest.dto.ManagementDtos.CreateCatalogRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.UpdateCatalogRequest;
import io.github.melin.paimonrest.dto.Privilege;
import io.github.melin.paimonrest.service.AuthorizationService;
import io.github.melin.paimonrest.service.ManagementCatalogService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理 API 的 catalog 端点。
 *
 * <p>路径与 {@code spec/polaris-management-service.yml} 一致，基址为
 * {@code /api/management/v1}。与 catalog API（{@code /v1/**}）并存在同一进程，
 * 这一点与 Polaris 把管理服务与 catalog 服务放在同一个进程的做法相同。
 */
@RestController
@RequestMapping("/api/management/v1/catalogs")
@RequiredArgsConstructor
public class ManagementCatalogController {

    private final ManagementCatalogService catalogService;
    private final AuthorizationService authorizationService;

    /**
     * {@code GET /catalogs}。
     *
     * <p>开启授权时按调用者的可见范围过滤：服务管理员看到全部 catalog，
     * 其余调用者只看到自己至少持有一个 catalog role 的 catalog。
     * 这样未授权调用方不会通过 catalog 列表探测到服务端有哪些 catalog。
     */
    @GetMapping
    public Catalogs list() {
        Catalogs all = catalogService.list();
        if (!authorizationService.enabled() || authorizationService.isServiceAdmin(RequestContext.principal())) {
            return all;
        }
        List<Catalog> visible = new ArrayList<>();
        for (Catalog catalog : all.catalogs()) {
            if (!authorizationService.catalogRoleIdsOf(RequestContext.principal(), catalog.name()).isEmpty()) {
                visible.add(catalog);
            }
        }
        return new Catalogs(visible);
    }

    /** {@code POST /catalogs}。创建 catalog 尚无权限可依，故要求服务管理员身份。 */
    @PostMapping
    public ResponseEntity<Catalog> create(@RequestBody CreateCatalogRequest request) {
        authorizationService.requireServiceAdmin("create a catalog");
        Catalog created = catalogService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** {@code GET /catalogs/{catalogName}}。 */
    @GetMapping("/{catalogName}")
    public Catalog get(@PathVariable String catalogName) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_READ_PROPERTIES,
                "read the catalog");
        return catalogService.get(catalogName);
    }

    /** {@code PUT /catalogs/{catalogName}}。 */
    @PutMapping("/{catalogName}")
    public Catalog update(@PathVariable String catalogName,
                          @RequestBody UpdateCatalogRequest request) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_WRITE_PROPERTIES,
                "update the catalog");
        return catalogService.update(catalogName, request);
    }

    /** {@code DELETE /catalogs/{catalogName}}。 */
    @DeleteMapping("/{catalogName}")
    public ResponseEntity<Void> delete(@PathVariable String catalogName) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "delete the catalog");
        catalogService.delete(catalogName);
        return ResponseEntity.noContent().build();
    }
}
