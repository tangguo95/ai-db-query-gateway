package com.tangguo.gateway.api;

import com.tangguo.gateway.security.ActorContext;
import com.tangguo.gateway.server.ServerDtos.ExecuteRequest;
import com.tangguo.gateway.server.ServerDtos.ExecuteResult;
import com.tangguo.gateway.server.ServerService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/servers")
public class AiServerController {
    private final ServerService service;
    private final ActorContext actor;
    public AiServerController(ServerService service, ActorContext actor) { this.service = service; this.actor = actor; }
    @GetMapping public List<Map<String, Object>> list() {
        var token = actor.requireToken();
        return service.list().stream().filter(s -> s.enabled() && token.permitsServer(s.id()))
                .map(s -> Map.<String, Object>of("id", s.id(), "name", s.name(), "fullAccess", s.fullAccess())).toList();
    }
    @PostMapping("/execute") public ExecuteResult execute(@Valid @RequestBody ExecuteRequest request) {
        actor.requireToken();
        return service.execute(request);
    }
}
