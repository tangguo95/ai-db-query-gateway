package com.tangguo.gateway.api;

import com.tangguo.gateway.server.ServerDtos.AccessRequest;
import com.tangguo.gateway.server.ServerDtos.Connection;
import com.tangguo.gateway.server.ServerDtos.ConnectionTestResult;
import com.tangguo.gateway.server.ServerDtos.ExecuteRequest;
import com.tangguo.gateway.server.ServerDtos.ExecuteResult;
import com.tangguo.gateway.server.ServerDtos.SaveRequest;
import com.tangguo.gateway.server.ServerDtos.ServerView;
import com.tangguo.gateway.server.ServerService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/servers")
public class ServerController {
    private final ServerService service;
    public ServerController(ServerService service) { this.service = service; }
    @GetMapping public List<ServerView> list() { return service.list(); }
    @PostMapping public ServerView create(@Valid @RequestBody SaveRequest request) { return service.save(null, request); }
    @PostMapping("/test-connection") public ConnectionTestResult testNew(@Valid @RequestBody SaveRequest request) { return service.testConfiguration(null, request); }
    @PostMapping("/{id}/test-connection") public ConnectionTestResult testConfiguration(@PathVariable String id, @Valid @RequestBody SaveRequest request) { return service.testConfiguration(id, request); }
    @DeleteMapping("/{id}") public void delete(@PathVariable String id) { service.delete(id); }
    @PutMapping("/{id}") public ServerView save(@PathVariable String id, @Valid @RequestBody SaveRequest request) { return service.save(id, request); }
    @GetMapping("/{id}/connection") public Connection connection(@PathVariable String id) { return service.configuration(id); }
    @PostMapping("/{id}/test") public ServerView test(@PathVariable String id) { return service.test(id); }
    @PostMapping("/{id}/disable") public ServerView disable(@PathVariable String id) { return service.disable(id); }
    @PutMapping("/{id}/access") public ServerView access(@PathVariable String id, @RequestBody AccessRequest request) { return service.access(id, request); }
    @PostMapping("/execute") public ExecuteResult execute(@Valid @RequestBody ExecuteRequest request) { return service.execute(request); }
}
