package com.tangguo.gateway.query;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLSequenceExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.mysql.ast.clause.MySqlSelectIntoStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.MySqlOutFileExpr;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectQueryBlock;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitorAdapter;
import com.alibaba.druid.sql.parser.ParserException;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.tangguo.gateway.api.GatewayException;
import com.tangguo.gateway.config.GatewayProperties;
import com.tangguo.gateway.datasource.DataSourceService;
import com.tangguo.gateway.model.DatabaseType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class SqlPolicyService {
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern NUMBER_LITERAL =
            Pattern.compile("(?<![A-Za-z0-9_$])[-+]?\\d+(?:\\.\\d+)?(?![A-Za-z0-9_$])");
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "ABS",
            "ACOS",
            "ADD_MONTHS",
            "ASCII",
            "ASIN",
            "ATAN",
            "AVG",
            "BIT_AND",
            "BIT_OR",
            "CAST",
            "CEIL",
            "CEILING",
            "CHAR",
            "CHAR_LENGTH",
            "CHARACTER_LENGTH",
            "COALESCE",
            "CONCAT",
            "CONCAT_WS",
            "CONVERT",
            "CORR",
            "COS",
            "COUNT",
            "CURRENT_DATE",
            "CURRENT_TIMESTAMP",
            "DATE",
            "DATE_ADD",
            "DATE_FORMAT",
            "DATE_SUB",
            "DATEDIFF",
            "DAY",
            "DECODE",
            "DENSE_RANK",
            "EXP",
            "EXTRACT",
            "FIRST_VALUE",
            "FLOOR",
            "FROM_UNIXTIME",
            "GREATEST",
            "GROUP_CONCAT",
            "HEX",
            "HOUR",
            "IF",
            "IFNULL",
            "INSTR",
            "JSON_EXTRACT",
            "LAG",
            "LAST_DAY",
            "LAST_VALUE",
            "LEAD",
            "LEAST",
            "LEFT",
            "LENGTH",
            "LN",
            "LOCATE",
            "LOG",
            "LOWER",
            "LPAD",
            "LTRIM",
            "MAX",
            "MD5",
            "MIN",
            "MINUTE",
            "MOD",
            "MONTH",
            "MONTHS_BETWEEN",
            "NEXT_DAY",
            "NOW",
            "NULLIF",
            "NVL",
            "NVL2",
            "PERCENT_RANK",
            "POWER",
            "RANK",
            "REGEXP_REPLACE",
            "REPLACE",
            "RIGHT",
            "ROUND",
            "ROW_NUMBER",
            "RPAD",
            "RTRIM",
            "SECOND",
            "SHA1",
            "SHA2",
            "SIGN",
            "SIN",
            "SQRT",
            "STDDEV",
            "SUBSTR",
            "SUBSTRING",
            "SUM",
            "SYSDATE",
            "TAN",
            "TIMESTAMPDIFF",
            "TO_CHAR",
            "TO_DATE",
            "TO_NUMBER",
            "TRIM",
            "TRUNC",
            "UNHEX",
            "UNIX_TIMESTAMP",
            "UPPER",
            "VARIANCE",
            "WEEK",
            "YEAR");

    private final GatewayProperties properties;
    private final DataSourceService dataSourceService;

    public SqlPolicyService(GatewayProperties properties, DataSourceService dataSourceService) {
        this.properties = properties;
        this.dataSourceService = dataSourceService;
    }

    /**
     * AST 是查询准入的权威判断；词法预检只负责在解析前禁止注释和多语句分隔符。
     */
    public SqlAnalysis analyze(DatabaseType databaseType, String sql, int requestedMaxRows) {
        if (sql == null || sql.isBlank()) {
            reject("EMPTY_SQL", "SQL 不能为空");
        }
        if (sql.getBytes(StandardCharsets.UTF_8).length > properties.getQuery().getMaxSqlBytes()) {
            reject("SQL_TOO_LARGE", "SQL 超过 32 KiB 安全限制");
        }
        rejectCommentsAndDelimiters(sql);
        DbType dialect =
                databaseType == DatabaseType.OCEANBASE_ORACLE ? DbType.oracle : DbType.mysql;
        List<SQLStatement> statements;
        try {
            statements = SQLUtils.parseStatements(sql, dialect);
        } catch (ParserException | IllegalArgumentException exception) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST, "SQL_PARSE_FAILED", "SQL 无法按目标数据库方言安全解析", exception);
        }
        if (statements.size() != 1 || !(statements.getFirst() instanceof SQLSelectStatement)) {
            reject("ONLY_SINGLE_SELECT_ALLOWED", "仅允许单条 SELECT、UNION 或只读 CTE");
        }
        SQLSelectStatement selectStatement = (SQLSelectStatement) statements.getFirst();
        SQLSelect select = selectStatement.getSelect();
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null) {
            if (Boolean.TRUE.equals(with.getRecursive())) {
                reject("RECURSIVE_CTE_FORBIDDEN", "禁止递归 CTE");
            }
            for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                if (entry.getSubQuery() == null || entry.getReturningStatement() != null) {
                    reject("NON_SELECT_CTE_FORBIDDEN", "CTE 只能包含 SELECT");
                }
                try {
                    SchemaStatVisitor entryVisitor = SQLUtils.createSchemaStatVisitor(dialect);
                    entry.getSubQuery().accept(entryVisitor);
                    String alias = entry.getAlias();
                    boolean selfReference = alias != null && entryVisitor.getTables().keySet().stream()
                            .map(name -> name.getName().replace("`", "").replace("\"", ""))
                            .map(name -> name.substring(name.lastIndexOf('.') + 1))
                            .anyMatch(alias::equalsIgnoreCase);
                    if (selfReference) {
                        reject("RECURSIVE_CTE_FORBIDDEN", "禁止递归 CTE");
                    }
                } catch (GatewayException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    throw new GatewayException(
                            HttpStatus.BAD_REQUEST,
                            "SQL_PARSE_FAILED",
                            "CTE 包含无法安全检查的方言节点",
                            exception);
                }
            }
        }
        if (!select.getHints().isEmpty()) {
            reject("SQL_HINT_FORBIDDEN", "禁止优化器 Hint");
        }

        InspectionState inspection = new InspectionState(with);
        SQLASTVisitor visitor = databaseType == DatabaseType.OCEANBASE_ORACLE
                ? new OracleInspectionVisitor(inspection)
                : new MySqlInspectionVisitor(inspection);
        try {
            selectStatement.accept(visitor);
            SchemaStatVisitor schemaVisitor = SQLUtils.createSchemaStatVisitor(dialect);
            selectStatement.accept(schemaVisitor);
            schemaVisitor.getTables().keySet().forEach(name -> inspection.addTableName(name.getName()));
        } catch (RuntimeException exception) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "SQL_PARSE_FAILED",
                    "SQL 包含无法安全检查的方言节点",
                    exception);
        }
        if (inspection.locking) {
            reject("LOCKING_SELECT_FORBIDDEN", "禁止 FOR UPDATE、FOR SHARE 或其它锁查询");
        }
        if (inspection.into) {
            reject("SELECT_INTO_FORBIDDEN", "禁止 SELECT INTO/OUTFILE");
        }
        if (inspection.sequenceAccess) {
            reject("SEQUENCE_ACCESS_FORBIDDEN", "禁止 NEXTVAL/CURRVAL 等序列访问");
        }
        if (inspection.sessionVariableAccess) {
            reject("SESSION_VARIABLE_FORBIDDEN", "禁止读取或修改数据库会话变量");
        }
        if (!inspection.unknownFunctions.isEmpty()) {
            reject(
                    "UNKNOWN_FUNCTION_FORBIDDEN",
                    "包含未列入只读白名单的函数：" + String.join(", ", inspection.unknownFunctions));
        }
        if (inspection.databaseLink) {
            reject("DATABASE_LINK_FORBIDDEN", "禁止数据库链接或远程表引用");
        }

        List<String> risks = new ArrayList<>();
        if (inspection.selectAll) {
            risks.add("SELECT_ALL");
        }
        if (inspection.outerQueryWithoutWhere) {
            risks.add("UNFILTERED_DETAIL_QUERY");
        }
        if (inspection.tables.size() > 3) {
            risks.add("MORE_THAN_THREE_TABLES");
        }
        if (inspection.schemas.size() > 1) {
            risks.add("CROSS_SCHEMA_QUERY");
        }
        if (inspection.schemas.stream().anyMatch(dataSourceService::isSystemSchema)
                || inspection.tables.stream().anyMatch(SqlPolicyService::isSystemDictionaryObject)) {
            risks.add("SYSTEM_SCHEMA");
        }
        if (requestedMaxRows > properties.getQuery().getDefaultMaxRows()) {
            risks.add("MAX_ROWS_OVER_AUTO_LIMIT");
        }
        return new SqlAnalysis(
                fingerprint(sql),
                Set.copyOf(inspection.schemas),
                Set.copyOf(inspection.tables),
                List.copyOf(risks),
                inspection.parameterCount);
    }

    private void rejectCommentsAndDelimiters(String sql) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean backtick = false;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (singleQuote) {
                if (current == '\'' && next == '\'') {
                    index++;
                } else if (current == '\'') {
                    singleQuote = false;
                }
                continue;
            }
            if (doubleQuote) {
                if (current == '"' && next == '"') {
                    index++;
                } else if (current == '"') {
                    doubleQuote = false;
                }
                continue;
            }
            if (backtick) {
                if (current == '`') {
                    backtick = false;
                }
                continue;
            }
            if (current == '\'') {
                singleQuote = true;
            } else if (current == '"') {
                doubleQuote = true;
            } else if (current == '`') {
                backtick = true;
            } else if (current == ';') {
                reject("SQL_DELIMITER_FORBIDDEN", "禁止多语句分隔符");
            } else if (current == '@') {
                if (isDatabaseLinkMarker(sql, index)) {
                    reject("DATABASE_LINK_FORBIDDEN", "禁止数据库链接或远程表引用");
                }
                reject("SESSION_VARIABLE_FORBIDDEN", "禁止读取或修改数据库会话变量");
            } else if ((current == '-' && next == '-')
                    || current == '#'
                    || (current == '/' && next == '*')) {
                reject("SQL_COMMENT_FORBIDDEN", "禁止 SQL 注释和 Hint");
            }
        }
        if (singleQuote || doubleQuote || backtick) {
            reject("SQL_PARSE_FAILED", "SQL 字符串或标识符未闭合");
        }
    }

    private boolean isDatabaseLinkMarker(String sql, int index) {
        if (index == 0) {
            return false;
        }
        char previous = sql.charAt(index - 1);
        return Character.isLetterOrDigit(previous)
                || previous == '_'
                || previous == '$'
                || previous == '#'
                || previous == '"'
                || previous == '`';
    }

    private String fingerprint(String sql) {
        String normalized = STRING_LITERAL.matcher(sql).replaceAll("?");
        normalized = NUMBER_LITERAL.matcher(normalized).replaceAll("?");
        normalized = normalized.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    private void reject(String code, String message) {
        throw new GatewayException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static boolean isSystemDictionaryObject(String tableName) {
        String normalized = tableName.replace("`", "").replace("\"", "").toUpperCase(Locale.ROOT);
        String baseName = normalized.substring(normalized.lastIndexOf('.') + 1);
        return baseName.startsWith("DBA_")
                || baseName.startsWith("ALL_")
                || baseName.startsWith("USER_")
                || baseName.startsWith("V_$")
                || baseName.startsWith("GV_$");
    }

    private static final class InspectionState {
        private final Set<String> cteNames = new HashSet<>();
        private final Set<String> tables = new HashSet<>();
        private final Set<String> schemas = new HashSet<>();
        private final Set<String> unknownFunctions = new HashSet<>();
        private int queryBlockDepth;
        private int parameterCount;
        private boolean selectAll;
        private boolean outerQueryWithoutWhere;
        private boolean locking;
        private boolean into;
        private boolean databaseLink;
        private boolean sequenceAccess;
        private boolean sessionVariableAccess;

        private InspectionState(SQLWithSubqueryClause with) {
            if (with != null) {
                with.getEntries().stream()
                        .map(SQLWithSubqueryClause.Entry::getAlias)
                        .filter(java.util.Objects::nonNull)
                        .map(value -> value.toUpperCase(Locale.ROOT))
                        .forEach(cteNames::add);
            }
        }

        private boolean visitQueryBlock(SQLSelectQueryBlock queryBlock) {
            queryBlockDepth++;
            if (queryBlockDepth == 1 && queryBlock.getFrom() != null && queryBlock.getWhere() == null) {
                outerQueryWithoutWhere = true;
            }
            locking |= queryBlock.isForUpdate()
                    || queryBlock.isForShare()
                    || queryBlock.isSkipLocked()
                    || queryBlock.isNoWait();
            into |= queryBlock.getInto() != null;
            if (queryBlock.getHints() != null && !queryBlock.getHints().isEmpty()) {
                locking = true;
            }
            return true;
        }

        private void endQueryBlock() {
            queryBlockDepth--;
        }

        private boolean visitTable(SQLExprTableSource tableSource) {
            String table = tableSource.getTableName();
            String schema = tableSource.getSchema();
            if (table != null && !cteNames.contains(table.toUpperCase(Locale.ROOT))) {
                tables.add((schema == null ? "" : schema + ".") + table);
            }
            if (schema != null && !schema.isBlank()) {
                schemas.add(unquote(schema));
            }
            String expression = tableSource.getExpr().toString();
            databaseLink |= expression.contains("@");
            return true;
        }

        private boolean visitAllColumn() {
            selectAll = true;
            return true;
        }

        private boolean visitFunction(SQLMethodInvokeExpr function) {
            String name = function.getMethodName();
            if (name == null || !ALLOWED_FUNCTIONS.contains(name.toUpperCase(Locale.ROOT))) {
                unknownFunctions.add(name == null ? "<unknown>" : name);
            }
            return true;
        }

        private boolean visitVariable(SQLVariantRefExpr variable) {
            if ("?".equals(variable.getName())) {
                parameterCount++;
            } else {
                sessionVariableAccess = true;
            }
            return true;
        }

        private boolean visitSequence() {
            sequenceAccess = true;
            return false;
        }

        private void addTableName(String rawName) {
            if (rawName == null || rawName.isBlank()) {
                return;
            }
            String normalized = rawName.replace("`", "").replace("\"", "");
            String[] parts = normalized.split("\\.");
            String table = parts[parts.length - 1];
            if (cteNames.contains(table.toUpperCase(Locale.ROOT))) {
                return;
            }
            tables.add(normalized);
            if (parts.length > 1) {
                schemas.add(parts[parts.length - 2].toUpperCase(Locale.ROOT));
            }
        }

        private String unquote(String value) {
            return value.replace("`", "").replace("\"", "").toUpperCase(Locale.ROOT);
        }
    }

    private static final class MySqlInspectionVisitor extends MySqlASTVisitorAdapter {
        private final InspectionState state;

        private MySqlInspectionVisitor(InspectionState state) {
            this.state = state;
        }

        @Override
        public boolean visit(SQLSelectQueryBlock queryBlock) {
            return state.visitQueryBlock(queryBlock);
        }

        @Override
        public boolean visit(MySqlSelectQueryBlock queryBlock) {
            boolean visitChildren = state.visitQueryBlock(queryBlock);
            state.locking |= queryBlock.isLockInShareMode();
            if (queryBlock.getProcedureName() != null) {
                state.unknownFunctions.add("PROCEDURE");
            }
            return visitChildren;
        }

        @Override
        public void endVisit(SQLSelectQueryBlock queryBlock) {
            state.endQueryBlock();
        }

        @Override
        public void endVisit(MySqlSelectQueryBlock queryBlock) {
            state.endQueryBlock();
        }

        @Override
        public boolean visit(SQLExprTableSource tableSource) {
            return state.visitTable(tableSource);
        }

        @Override
        public boolean visit(SQLAllColumnExpr allColumnExpr) {
            return state.visitAllColumn();
        }

        @Override
        public boolean visit(SQLMethodInvokeExpr function) {
            return state.visitFunction(function);
        }

        @Override
        public boolean visit(SQLVariantRefExpr variable) {
            return state.visitVariable(variable);
        }

        @Override
        public boolean visit(SQLSequenceExpr sequence) {
            return state.visitSequence();
        }

        @Override
        public boolean visit(MySqlOutFileExpr outFile) {
            state.into = true;
            return false;
        }

        @Override
        public boolean visit(MySqlSelectIntoStatement selectInto) {
            state.into = true;
            return false;
        }
    }

    private static final class OracleInspectionVisitor extends OracleASTVisitorAdapter {
        private final InspectionState state;

        private OracleInspectionVisitor(InspectionState state) {
            this.state = state;
        }

        @Override
        public boolean visit(SQLSelectQueryBlock queryBlock) {
            return state.visitQueryBlock(queryBlock);
        }

        @Override
        public boolean visit(OracleSelectQueryBlock queryBlock) {
            return state.visitQueryBlock(queryBlock);
        }

        @Override
        public void endVisit(SQLSelectQueryBlock queryBlock) {
            state.endQueryBlock();
        }

        @Override
        public void endVisit(OracleSelectQueryBlock queryBlock) {
            state.endQueryBlock();
        }

        @Override
        public boolean visit(SQLExprTableSource tableSource) {
            return state.visitTable(tableSource);
        }

        @Override
        public boolean visit(SQLAllColumnExpr allColumnExpr) {
            return state.visitAllColumn();
        }

        @Override
        public boolean visit(SQLMethodInvokeExpr function) {
            return state.visitFunction(function);
        }

        @Override
        public boolean visit(SQLVariantRefExpr variable) {
            return state.visitVariable(variable);
        }

        @Override
        public boolean visit(SQLSequenceExpr sequence) {
            return state.visitSequence();
        }
    }
}
