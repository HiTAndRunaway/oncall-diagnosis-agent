package org.example.agent.tool;

/**
 * Agent 工具通用工具方法
 * <p>
 * 提供 JSON 转义、JSON 块提取、字符串截断等工具方法，
 * 供所有工具类复用。
 */
public final class ToolUtils {

    private ToolUtils() {
        // 工具类禁止实例化
    }

    /**
     * 对字符串进行 JSON 值转义（不包含引号包装）
     */
    public static String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 从 LLM 返回文本中提取 JSON 块（第一个 { 到最后一个 }）
     */
    public static String extractJsonBlock(String text) {
        if (text == null || text.isEmpty()) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 截断字符串到指定最大长度，超出部分用 "..." 替代
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
