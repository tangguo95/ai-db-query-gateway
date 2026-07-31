package com.tangguo.gateway.api;

import com.tangguo.gateway.api.ApiDtos.TokenCreateRequest;
import com.tangguo.gateway.api.ApiDtos.TokenCreated;
import com.tangguo.gateway.api.ApiDtos.TokenScopeUpdateRequest;
import com.tangguo.gateway.api.ApiDtos.TokenView;
import com.tangguo.gateway.security.ActorContext;
import com.tangguo.gateway.security.ApiTokenService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tokens")
public class TokenController {
    private final ApiTokenService tokenService;
    private final ActorContext actorContext;

    public TokenController(ApiTokenService tokenService, ActorContext actorContext) {
        this.tokenService = tokenService;
        this.actorContext = actorContext;
    }

    @GetMapping
    List<TokenView> list() {
        return tokenService.list();
    }

    @PostMapping
    TokenCreated create(@Valid @RequestBody TokenCreateRequest request) {
        return tokenService.create(request, actorContext.actor());
    }

    @PutMapping("/{id}/scope")
    TokenView updateScope(
            @PathVariable String id, @Valid @RequestBody TokenScopeUpdateRequest request) {
        return tokenService.updateScope(id, request, actorContext.actor());
    }

    @DeleteMapping("/{id}")
    void delete(@PathVariable String id) {
        tokenService.delete(id, actorContext.actor());
    }
}
