package io.github.melin.paimonrest.web;

import io.github.melin.paimonrest.dto.ManagementDtos.CreatePrincipalRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.GrantPrincipalRoleRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.Principal;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalRoles;
import io.github.melin.paimonrest.dto.ManagementDtos.PrincipalWithCredentials;
import io.github.melin.paimonrest.dto.ManagementDtos.Principals;
import io.github.melin.paimonrest.dto.ManagementDtos.ResetPrincipalRequest;
import io.github.melin.paimonrest.dto.ManagementDtos.UpdatePrincipalRequest;
import io.github.melin.paimonrest.service.AuthorizationService;
import io.github.melin.paimonrest.service.PrincipalService;
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
 * 管理 API 的主体端点，共 8 个 operation。
 *
 * <p>整组要求服务管理员身份：管理规格的权限 enum 里没有覆盖「管理主体」的权限取值，
 * 因此这类操作按 Polaris 的做法归服务管理员，而不是虚构一个权限。
 */
@RestController
@RequestMapping("/api/management/v1/principals")
@RequiredArgsConstructor
public class PrincipalController {

    private final PrincipalService principalService;
    private final AuthorizationService authorizationService;

    /** {@code GET /principals}。 */
    @GetMapping
    public Principals list() {
        authorizationService.requireServiceAdmin("list principals");
        return principalService.list();
    }

    /** {@code POST /principals}：响应含明文密钥，仅此一次。 */
    @PostMapping
    public ResponseEntity<PrincipalWithCredentials> create(
            @RequestBody(required = false) CreatePrincipalRequest request) {
        authorizationService.requireServiceAdmin("create a principal");
        return ResponseEntity.status(HttpStatus.CREATED).body(principalService.create(request));
    }

    /** {@code GET /principals/{principalName}}。 */
    @GetMapping("/{principalName}")
    public Principal get(@PathVariable String principalName) {
        authorizationService.requireServiceAdmin("read a principal");
        return principalService.get(principalName);
    }

    /** {@code PUT /principals/{principalName}}。 */
    @PutMapping("/{principalName}")
    public Principal update(@PathVariable String principalName,
                            @RequestBody(required = false) UpdatePrincipalRequest request) {
        authorizationService.requireServiceAdmin("update a principal");
        return principalService.update(principalName, request);
    }

    /** {@code DELETE /principals/{principalName}}。 */
    @DeleteMapping("/{principalName}")
    public ResponseEntity<Void> delete(@PathVariable String principalName) {
        authorizationService.requireServiceAdmin("delete a principal");
        principalService.delete(principalName);
        return ResponseEntity.noContent().build();
    }

    /** {@code POST /principals/{principalName}/rotate}：换密钥、保留 clientId。 */
    @PostMapping("/{principalName}/rotate")
    public PrincipalWithCredentials rotate(@PathVariable String principalName) {
        authorizationService.requireServiceAdmin("rotate a principal's credentials");
        return principalService.rotate(principalName);
    }

    /** {@code POST /principals/{principalName}/reset}。 */
    @PostMapping("/{principalName}/reset")
    public PrincipalWithCredentials reset(@PathVariable String principalName,
                                         @RequestBody(required = false) ResetPrincipalRequest request) {
        authorizationService.requireServiceAdmin("reset a principal's credentials");
        return principalService.reset(principalName, request);
    }

    /** {@code GET /principals/{principalName}/principal-roles}。 */
    @GetMapping("/{principalName}/principal-roles")
    public PrincipalRoles listRoles(@PathVariable String principalName) {
        authorizationService.requireServiceAdmin("read a principal's roles");
        return principalService.listRoles(principalName);
    }

    /** {@code PUT /principals/{principalName}/principal-roles}：被授予的角色名在请求体里。 */
    @PutMapping("/{principalName}/principal-roles")
    public ResponseEntity<Void> grantRole(@PathVariable String principalName,
                                         @RequestBody(required = false) GrantPrincipalRoleRequest request) {
        authorizationService.requireServiceAdmin("assign a role to the principal");
        principalService.grantRole(principalName, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** {@code DELETE /principals/{principalName}/principal-roles/{principalRoleName}}。 */
    @DeleteMapping("/{principalName}/principal-roles/{principalRoleName}")
    public ResponseEntity<Void> revokeRole(@PathVariable String principalName,
                                          @PathVariable String principalRoleName) {
        authorizationService.requireServiceAdmin("remove a role from the principal");
        principalService.revokeRole(principalName, principalRoleName);
        return ResponseEntity.noContent().build();
    }
}
