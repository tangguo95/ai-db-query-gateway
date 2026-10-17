package com.tangguo.gateway.api;

import com.tangguo.gateway.api.ApiDtos.AdminProfileUpdateRequest;
import com.tangguo.gateway.api.ApiDtos.AdminProfileView;
import com.tangguo.gateway.api.ApiDtos.PasswordChangeRequest;
import com.tangguo.gateway.audit.AuditCommand;
import com.tangguo.gateway.audit.AuditService;
import com.tangguo.gateway.model.ActorType;
import com.tangguo.gateway.security.ActorContext;
import com.tangguo.gateway.security.AdminProfileService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final AdminProfileService profileService;
    private final AuditService auditService;
    private final ActorContext actorContext;

    public ProfileController(
            AdminProfileService profileService,
            AuditService auditService,
            ActorContext actorContext) {
        this.profileService = profileService;
        this.auditService = auditService;
        this.actorContext = actorContext;
    }

    @GetMapping
    AdminProfileView profile() {
        return profileService.profile(actorContext.actor());
    }

    @PutMapping
    @Transactional
    AdminProfileView update(@Valid @RequestBody AdminProfileUpdateRequest request) {
        String actor = actorContext.actor();
        AdminProfileView before = profileService.profile(actor);
        AdminProfileView after = profileService.update(actor, request);
        auditService.record(AuditCommand.simple(
                actor,
                ActorType.ADMIN,
                "ADMIN_PROFILE_UPDATED",
                "SUCCESS",
                Map.of(
                        "displayNameChanged", profileService.displayNameChanged(before, after),
                        "avatarChanged", profileService.avatarChanged(before, after))));
        return after;
    }

    @PutMapping("/password")
    void changePassword(@Valid @RequestBody PasswordChangeRequest request) {
        throw new GatewayException(org.springframework.http.HttpStatus.GONE,
                "LOCAL_LOGIN_REMOVED", "本机免登录模式不使用管理员登录密码");
    }
}
