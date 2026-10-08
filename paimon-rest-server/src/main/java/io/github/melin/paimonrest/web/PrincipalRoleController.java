package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.ManagementDtos.CatalogRoles;
import io.github.melin.paimonrest.dto.ManagementDtos.CreatePrincipalRoleRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.GrantCatalogRoleRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalRole;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalRoles;
import io.github.melin.paimonrest.dto.ManagementDtos.Principals;
import io.github.melin.paimonrest.dto.ManagementDtos.UpdatePrincipalRoleRequest;
import io.github.melin.paimonrest.dto.Privilege;
import io.github.melin.paimonrest.service.AuthorizationService;
import io.github.melin.paimonrest.service.PrincipalRoleService;
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
 * 管理 API 的 principal role 端点，共 9 个 operation。
 *
 * <p>principal role 自身的管理按服务管理员处理（同 {@link PrincipalController} 的理由）；
 * 但「把 catalog role 授予 principal role」这一步会修改某个 catalog 的授权关系，
 * 因此要求调用者在**该 catalog** 上具备 {@code CATALOG_MANAGE_ACCESS}。
 */
@RestController
@RequestMapping("/api/management/v1/principal-roles")
@RequiredArgsConstructor
public class PrincipalRoleController {

    private final PrincipalRoleService principalRoleService;
    private final AuthorizationService authorizationService;

    /** {@code GET /principal-roles}。 */
    @GetMapping
    public PrincipalRoles list() {
        authorizationService.requireServiceAdmin("list principal roles");
        return principalRoleService.list();
    }

    /** {@code POST /principal-roles}。 */
    @PostMapping
    public ResponseEntity<PrincipalRole> create(
            @RequestBody(required = false) CreatePrincipalRoleRequest request) {
        authorizationService.requireServiceAdmin("create a principal role");
        return ResponseEntity.status(HttpStatus.CREATED).body(principalRoleService.create(request));
    }

    /** {@code GET /principal-roles/{principalRoleName}}。 */
    @GetMapping("/{principalRoleName}")
    public PrincipalRole get(@PathVariable String principalRoleName) {
        authorizationService.requireServiceAdmin("read a principal role");
        return principalRoleService.get(principalRoleName);
    }

    /** {@code PUT /principal-roles/{principalRoleName}}。 */
    @PutMapping("/{principalRoleName}")
    public PrincipalRole update(@PathVariable String principalRoleName,
                                @RequestBody(required = false) UpdatePrincipalRoleRequest request) {
        authorizationService.requireServiceAdmin("update a principal role");
        return principalRoleService.update(principalRoleName, request);
    }

    /** {@code DELETE /principal-roles/{principalRoleName}}。 */
    @DeleteMapping("/{principalRoleName}")
    public ResponseEntity<Void> delete(@PathVariable String principalRoleName) {
        authorizationService.requireServiceAdmin("delete a principal role");
        principalRoleService.delete(principalRoleName);
        return ResponseEntity.noContent().build();
    }

    /** {@code GET /principal-roles/{principalRoleName}/principals}。 */
    @GetMapping("/{principalRoleName}/principals")
    public Principals listPrincipals(@PathVariable String principalRoleName) {
        authorizationService.requireServiceAdmin("read the principals of a principal role");
        return principalRoleService.listPrincipals(principalRoleName);
    }

    /** {@code GET /principal-roles/{principalRoleName}/catalog-roles/{catalogName}}。 */
    @GetMapping("/{principalRoleName}/catalog-roles/{catalogName}")
    public CatalogRoles listCatalogRoles(@PathVariable String principalRoleName,
                                        @PathVariable String catalogName) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "read the catalog roles of a principal role");
        return principalRoleService.listCatalogRoles(principalRoleName, catalogName);
    }

    /** {@code PUT /principal-roles/{principalRoleName}/catalog-roles/{catalogName}}。 */
    @PutMapping("/{principalRoleName}/catalog-roles/{catalogName}")
    public ResponseEntity<Void> grantCatalogRole(
            @PathVariable String principalRoleName,
            @PathVariable String catalogName,
            @RequestBody(required = false) GrantCatalogRoleRequest request) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "assign a catalog role");
        principalRoleService.grantCatalogRole(principalRoleName, catalogName, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** {@code DELETE /principal-roles/{principalRoleName}/catalog-roles/{catalogName}/{catalogRoleName}}。 */
    @DeleteMapping("/{principalRoleName}/catalog-roles/{catalogName}/{catalogRoleName}")
    public ResponseEntity<Void> revokeCatalogRole(@PathVariable String principalRoleName,
                                                 @PathVariable String catalogName,
                                                 @PathVariable String catalogRoleName) {
        authorizationService.requireCatalog(catalogName, Privilege.CATALOG_MANAGE_ACCESS,
                "remove a catalog role");
        principalRoleService.revokeCatalogRole(principalRoleName, catalogName, catalogRoleName);
        return ResponseEntity.noContent().build();
    }
}
