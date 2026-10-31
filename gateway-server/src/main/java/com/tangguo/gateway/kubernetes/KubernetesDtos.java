package com.tangguo.gateway.kubernetes;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class KubernetesDtos {
    private KubernetesDtos() {}
    public record SaveRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 32768) String kubeconfig,
            @Size(max = 253) String context,
            @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 63) String> namespaces,
            boolean allowContainerQuery) {}
    public record ClusterView(String id, String name, String context, List<String> namespaces,
                              boolean allowContainerQuery, boolean enabled, String updatedAt) {}
    public record ResourceRequest(
            @NotBlank @Size(max = 128) String clusterId,
            @NotBlank @Size(max = 100) String resource,
            @Size(max = 63) String namespace,
            @Size(max = 253) String name,
            @Size(max = 500) String labelSelector,
            @Size(max = 4096) String continueToken,
            @Min(1) @Max(100) Integer limit,
            @NotBlank @Size(max = 500) String purpose) {}
    public record LogsRequest(
            @NotBlank @Size(max = 128) String clusterId,
            @NotBlank @Size(max = 63) String namespace,
            @NotBlank @Size(max = 253) String pod,
            @NotBlank @Size(max = 253) String container,
            @Min(1) @Max(2000) Integer tailLines,
            @Min(1) @Max(86400) Integer sinceSeconds,
            Boolean previous,
            @NotBlank @Size(max = 500) String purpose) {}
    public record ContainerRequest(
            @NotBlank @Size(max = 128) String clusterId,
            @NotBlank @Size(max = 63) String namespace,
            @NotBlank @Size(max = 253) String pod,
            @NotBlank @Size(max = 253) String container,
            @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 1024) String> command,
            @NotBlank @Size(max = 500) String purpose) {}
    public record QueryResult(String format, String content, String continueToken, long durationMs) {}
}
