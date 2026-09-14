package com.tangguo.gateway.audit;

import com.tangguo.gateway.api.ApiDtos.AuditView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 生成标准 OOXML 工作簿，仅导出审计元数据，所有文本显式使用字符串单元格。 */
public final class AuditExcel {
    private AuditExcel() {}
    private static final Map<String, String> EVENTS = Map.ofEntries(
            Map.entry("DATASOURCE_BACKUP_REQUESTED", "申请导出数据源备份"),
            Map.entry("DATASOURCE_BACKUP_EXPORTED", "导出加密数据源备份"),
            Map.entry("DATASOURCE_BACKUP_FAILED", "导出数据源备份失败"),
            Map.entry("DATASOURCE_IMPORT_REQUESTED", "申请导入数据源备份"),
            Map.entry("DATASOURCE_IMPORT_COMPLETED", "数据源备份导入完成"),
            Map.entry("DATASOURCE_IMPORT_FAILED", "数据源备份验证失败"),
            Map.entry("AUDIT_VIEWED", "查看审计记录"),
            Map.entry("AUDIT_EXPORTED", "导出审计记录"),
            Map.entry("ADMIN_PASSWORD_CHANGED", "修改管理员密码"),
            Map.entry("ADMIN_PASSWORD_CHANGE_FAILED", "修改管理员密码失败"),
            Map.entry("ADMIN_PROFILE_UPDATED", "更新用户信息"),
            Map.entry("DATASOURCE_CREATED", "新增数据源"),
            Map.entry("DATASOURCE_CREATE_REQUESTED", "申请新增数据源"),
            Map.entry("DATASOURCE_CREDENTIAL_CHANGED", "更新数据源凭据"),
            Map.entry("DATASOURCE_DELETED", "删除数据源"),
            Map.entry("DATASOURCE_DELETE_REQUESTED", "申请删除数据源"),
            Map.entry("DATASOURCE_DISABLED", "停用数据源"),
            Map.entry("DATASOURCE_RECHECK_FAILED", "数据源复检失败"),
            Map.entry("DATASOURCE_SECRET_UPDATED", "更新数据源密钥"),
            Map.entry("DATASOURCE_TESTED", "数据源连接检查完成"),
            Map.entry("DATASOURCE_TEST_REQUESTED", "申请检查数据源连接"),
            Map.entry("DATASOURCE_AUTO_RECHECK_POLICY_CHANGED", "修改数据源自动复检开关"),
            Map.entry("DATASOURCE_UPDATED", "更新数据源配置"),
            Map.entry("HTTP_API_COMPLETED", "接口请求完成"),
            Map.entry("HTTP_API_REQUESTED", "接口请求开始"),
            Map.entry("LOGIN_FAILED", "登录失败"),
            Map.entry("LOGIN_RATE_LIMITED", "登录被限流"),
            Map.entry("LOGIN_REQUESTED", "登录请求"),
            Map.entry("LOGIN_SUCCESS", "登录成功"),
            Map.entry("QUERY_APPROVAL_REQUESTED", "提交审批申请"),
            Map.entry("QUERY_APPROVED", "审批通过"),
            Map.entry("QUERY_CANCEL_ACCEPTED", "接受取消查询"),
            Map.entry("QUERY_CANCELLED", "取消查询"),
            Map.entry("QUERY_CANCEL_REQUESTED", "申请取消查询"),
            Map.entry("QUERY_EXECUTED", "查询执行完成"),
            Map.entry("QUERY_EXECUTION_STARTED", "开始执行查询"),
            Map.entry("QUERY_FAILED", "查询执行失败"),
            Map.entry("QUERY_PENDING_APPROVAL", "进入待审批队列"),
            Map.entry("QUERY_POLICY_AUTO_APPROVED", "风险查询免审批放行"),
            Map.entry("QUERY_POLICY_APPROVED", "策略校验通过"),
            Map.entry("QUERY_POLICY_REJECTED", "策略拒绝查询"),
            Map.entry("QUERY_PREVIEW_FAILED", "查询预检失败"),
            Map.entry("QUERY_PREVIEW_REJECTED", "查询预检拒绝"),
            Map.entry("QUERY_PREVIEW_REQUESTED", "发起查询预检"),
            Map.entry("QUERY_PREVIEW_SUCCEEDED", "查询预检通过"),
            Map.entry("QUERY_REJECTED", "查询被拒绝"),
            Map.entry("QUERY_REQUESTED", "提交查询申请"),
            Map.entry("QUERY_REQUEST_REJECTED", "查询请求被拒绝"),
            Map.entry("QUERY_APPROVAL_POLICY_CHANGED", "修改查询审批开关"),
            Map.entry("TOKEN_CREATED", "创建访问令牌"),
            Map.entry("TOKEN_CREATE_REQUESTED", "申请创建访问令牌"),
            Map.entry("TOKEN_DELETED", "吊销访问令牌"),
            Map.entry("TOKEN_DELETE_REQUESTED", "申请吊销访问令牌"),
            Map.entry("TOKEN_SCOPE_UPDATED", "更新令牌数据源范围"),
            Map.entry("TOKEN_SCOPE_UPDATE_REQUESTED", "申请更新令牌范围"));
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("REQUESTED", "已申请"), Map.entry("APPROVED", "已批准"),
            Map.entry("PENDING_APPROVAL", "待审批"), Map.entry("SUCCESS", "成功"),
            Map.entry("FAILED", "失败"), Map.entry("EXECUTED", "已执行"),
            Map.entry("EXECUTING", "执行中"), Map.entry("REJECTED", "已拒绝"),
            Map.entry("EXPIRED", "已过期"), Map.entry("CANCELLED", "已取消"),
            Map.entry("TIMED_OUT", "已超时"), Map.entry("ADMIN", "网页管理员"),
            Map.entry("API_TOKEN", "AI 访问令牌"), Map.entry("SYSTEM", "系统任务"),
            Map.entry("ANONYMOUS", "未登录请求"),
            Map.entry("QUERY_APPROVAL_REQUESTED", "提交审批申请"),
            Map.entry("QUERY_APPROVED", "审批通过"),
            Map.entry("QUERY_PENDING_APPROVAL", "进入待审批队列"),
            Map.entry("QUERY_POLICY_AUTO_APPROVED", "风险查询免审批放行"),
            Map.entry("QUERY_EXECUTED", "查询执行完成"),
            Map.entry("QUERY_FAILED", "查询执行失败"),
            Map.entry("QUERY_REQUESTED", "提交查询申请"),
            Map.entry("AUDIT_VIEWED", "查看审计记录"),
            Map.entry("AUDIT_EXPORTED", "导出审计记录"));

    public static byte[] write(List<AuditView> items) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>""");
            entry(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            entry(zip, "xl/workbook.xml", """
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="查询审计记录" sheetId="1" r:id="rId1"/></sheets></workbook>""");
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>""");
            var sheet = new StringBuilder("""
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
                    <cols><col min="1" max="1" width="12" customWidth="1"/><col min="2" max="17" width="26" customWidth="1"/></cols><sheetData>
                    """);
            row(sheet, "审计序号", "发生时间（" + ZoneId.systemDefault() + "）", "事件", "事件代码",
                    "操作主体类型", "操作主体", "数据源", "数据源编号", "处理结果", "查询用途",
                    "查询编号", "SQL 指纹", "耗时（毫秒）", "行数", "字节数", "错误代码", "审计链校验");
            var time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
            for (var item : items) {
                row(sheet, item.sequenceNo(), time.format(item.occurredAt()),
                        EVENTS.getOrDefault(item.eventType(), "其他操作"), item.eventType(),
                        LABELS.getOrDefault(item.actorType(), item.actorType()), item.actor(),
                        item.dataSourceName(), item.dataSourceId(), LABELS.getOrDefault(item.status(), item.status()),
                        item.purpose(), item.queryId(), item.sqlFingerprint(), item.durationMs(),
                        item.rowCount(), item.byteCount(), item.errorCode(), item.chainValid() ? "通过" : "失败");
            }
            sheet.append("</sheetData><autoFilter ref=\"A1:Q").append(items.size() + 1).append("\"/></worksheet>");
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return output.toByteArray();
    }

    private static void row(StringBuilder sheet, Object... values) {
        sheet.append("<row>");
        for (Object value : values) {
            if (value instanceof Number) {
                sheet.append("<c><v>").append(value).append("</v></c>");
            } else {
                // inlineStr 避免用途或数据库对象名被 Excel 解释为公式。
                sheet.append("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(value == null ? "" : value.toString())).append("</t></is></c>");
            }
        }
        sheet.append("</row>");
    }

    private static String escape(String text) {
        var safe = new StringBuilder();
        text.codePoints().limit(16_000).filter(c -> c == 9 || c == 10 || c == 13
                || (c >= 32 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD) || c >= 0x10000)
                .forEach(safe::appendCodePoint);
        return safe.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
