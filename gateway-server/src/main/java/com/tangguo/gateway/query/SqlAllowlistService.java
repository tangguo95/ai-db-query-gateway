package com.tangguo.gateway.query;

import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.datasource.DataSourceRepository;
import com.tangguo.gateway.model.DatabaseType;
import com.tangguo.gateway.security.SettingService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Hint 每次准入读取持久化规则；旧函数配置仅为兼容保留，不再限制查询。 */
@Service
public class SqlAllowlistService {
    public static final Set<String> SUPPORTED_HINTS = Set.of("MATERIALIZE", "INLINE", "MERGE", "NO_MERGE");
    private final SettingService settings;
    private final DataSourceRepository sources;

    public SqlAllowlistService(SettingService settings, DataSourceRepository sources) {
        this.settings = settings;
        this.sources = sources;
    }

    public Set<String> functions(String sourceId) {
        return sourceId == null ? Set.of() : read("sql.allowlist.functions." + sourceId);
    }

    public Set<String> hints(DatabaseType type) {
        return read("sql.allowlist.hints." + type.name());
    }

    public void saveFunctions(String sourceId, List<String> names) {
        sources.require(sourceId);
        Set<String> validated = validate(names);
        settings.put("sql.allowlist.functions." + sourceId, String.join(",", validated));
    }

    public void saveHints(DatabaseType type, List<String> names) {
        Set<String> validated = validate(names);
        if (!SUPPORTED_HINTS.containsAll(validated)) invalid("当前仅支持 MATERIALIZE、INLINE、MERGE、NO_MERGE 无参数 Hint");
        settings.put("sql.allowlist.hints." + type.name(), String.join(",", validated));
    }

    private Set<String> read(String key) {
        return settings.get(key).filter(s -> !s.isBlank())
                .map(s -> Set.copyOf(Arrays.asList(s.split(",")))).orElse(Set.of());
    }

    private Set<String> validate(List<String> names) {
        if (names == null || names.size() > 200) invalid("每组最多允许 200 个名称");
        Set<String> result = new TreeSet<>();
        for (String name : names) {
            if (name == null || !name.trim().matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                invalid("名称只能包含英文字母、数字和下划线，且以字母开头");
            }
            result.add(name.trim().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    private void invalid(String message) {
        throw new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_SQL_ALLOWLIST", message);
    }
}
