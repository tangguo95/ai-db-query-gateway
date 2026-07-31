package com.tangguo.gateway.security;

import com.tangguo.gateway.model.ActorType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class ActorContext {

    public String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return "anonymous";
        }
        return authentication.getDetails() instanceof TokenContext token
                ? "token:" + token.tokenId()
                : authentication.getName();
    }

    public ActorType actorType() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return ActorType.ANONYMOUS;
        }
        return authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_AI".equals(authority.getAuthority()))
                ? ActorType.API_TOKEN
                : ActorType.ADMIN;
    }

    public TokenContext requireToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getDetails() instanceof TokenContext context)) {
            throw new org.springframework.security.access.AccessDeniedException("Bearer Token required");
        }
        return context;
    }

    public boolean isAi() {
        return actorType() == ActorType.API_TOKEN;
    }
}
