package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.ManagementDtos.AddGrantRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.CatalogRole;
import io.github.melin.paimonrest.dto.ManagementDtos.CatalogRoles;
import io.github.melin.paimonrest.dto.ManagementDtos.CreateCatalogRoleRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.GrantResources;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalRoles;
import io.github.melin.paimonrest.dto.ManagementDtos.RevokeGrantRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.UpdateCatalogRoleRequest;
import io.github.melin.paimonrest.dto.Privilege;
import io.github.melin.paimonrest.service.AuthorizationService;
import io.github.melin.paimonrest.service.CatalogRoleService;
import io.github.melin.paimonrest.service.ResourceGrantService;
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
 * 管理 API 的 catalog role 与授权端点，共 11 个 operation。
 *
 * <p>整组都作用在某个 catalog 内部，因此统一要求调用者在该 catalog 上具备
 * {@code CATALOG_MANAGE_ACCESS}——规格对 {@code CATALOG_MANAGE_ACCESS} 的描述正是
 * 「Includes the ability to grant or revoke privileges on objects in a catalog to
 * catalog roles, and the ability to grant or revoke catalog roles to or from principal roles」，
 * 与本组端点的语义完全对应。
 *
 * <p>授权的增删沿用规格的动词约定：{@code PUT} 新增、{@code POST} 撤销，
 * 两者都返回 {@code 201}。
 */
@RestController
@RequestMapping("/api/management/v1/catalogs/{catalogName}/catalog-roles")
@RequiredArgsConstructor
public class CatalogRoleController {

    private final CatalogRoleService catalogRoleService;
    private final ResourceGrantService resourceGrantService;
    private final AuthorizationService authorizationService;

    /** {@code GET /catalogs/{catalogName}/catalog-roles}。 */
    @GetMapping
    public CatalogRoles list(@PathVariable String catalogName) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "list catalog roles");
        return catalogRoleService.list(catalogName);
    }

    /** {@code POST /catalogs/{catalogName}/catalog-roles}。 */
    @PostMapping
    public ResponseEntity<CatalogRole> create(@PathVariable String catalogName,
                                             @RequestBody(required = false) CreateCatalogRoleRequest request) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "create a catalog role");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(catalogRoleService.create(catalogName, request));
    }

    /** {@code GET /catalogs/{catalogName}/catalog-roles/{catalogRoleName}}。 */
    @GetMapping("/{catalogRoleName}")
    public CatalogRole get(@PathVariable String catalogName, @PathVariable String catalogRoleName) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "read a catalog role");
        return catalogRoleService.get(catalogName, catalogRoleName);
    }

    /** {@code PUT /catalogs/{catalogName}/catalog-roles/{catalogRoleName}}。 */
    @PutMapping("/{catalogRoleName}")
    public CatalogRole update(@PathVariable String catalogName,
                              @PathVariable String catalogRoleName,
                              @RequestBody(required = false) UpdateCatalogRoleRequest request) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "update a catalog role");
        return catalogRoleService.update(catalogName, catalogRoleName, request);
    }

    /** {@code DELETE /catalogs/{catalogName}/catalog-roles/{catalogRoleName}}。 */
    @DeleteMapping("/{catalogRoleName}")
    public ResponseEntity<Void> delete(@PathVariable String catalogName,
                                       @PathVariable String catalogRoleName) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "delete a catalog role");
        catalogRoleService.delete(catalogName, catalogRoleName);
        return ResponseEntity.noContent().build();
    }

    /** {@code GET /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/principal-roles}。 */
    @GetMapping("/{catalogRoleName}/principal-roles")
    public PrincipalRoles listPrincipalRoles(@PathVariable String catalogName,
                                             @PathVariable String catalogRoleName) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "read the principal roles of a catalog role");
        return catalogRoleService.listPrincipalRoles(catalogName, catalogRoleName);
    }

    /** {@code GET /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants}。 */
    @GetMapping("/{catalogRoleName}/grants")
    public GrantResources listGrants(@PathVariable String catalogName,
                                     @PathVariable String catalogRoleName) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "read the grants of a catalog role");
        return resourceGrantService.list(catalogName, catalogRoleName);
    }

    /** {@code PUT /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants}：新增授权。 */
    @PutMapping("/{catalogRoleName}/grants")
    public ResponseEntity<Void> addGrant(@PathVariable String catalogName,
                                         @PathVariable String catalogRoleName,
                                         @RequestBody(required = false) AddGrantRequest request) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "add a grant");
        resourceGrantService.add(catalogName, catalogRoleName, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** {@code POST /catalogs/{catalogName}/catalog-roles/{catalogRoleName}/grants}：撤销授权。 */
    @PostMapping("/{catalogRoleName}/grants")
    public ResponseEntity<Void> revokeGrant(@PathVariable String catalogName,
                                            @PathVariable String catalogRoleName,
                                            @RequestBody(required = false) RevokeGrantRequest request) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "revoke a grant");
        resourceGrantService.revoke(catalogName, catalogRoleName, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
