package sweda.hanshu_item.overture.model;

/**
 * 一条校验/加载问题，对应 Overture {@code ReloadReport} 里的 issue。
 *
 * @param severity  严重级别
 * @param source    来源（文件路径或 {@code <editor>}）
 * @param definitionId 相关定义 ID，可能为空
 * @param path      定义内的 JSON 路径，可能为空
 * @param message   人类可读描述
 */
public record DefinitionIssue(Severity severity, String source, String definitionId, String path, String message) {

    public enum Severity {
        /** 只是建议，不影响加载。 */
        INFO,
        /** 能加载，但行为可能不符合预期。 */
        WARNING,
        /** 该定义不可用，会被跳过。 */
        ERROR
    }

    public static DefinitionIssue error(String source, String definitionId, String path, String message) {
        return new DefinitionIssue(Severity.ERROR, source, definitionId, path, message);
    }

    public static DefinitionIssue warn(String source, String definitionId, String path, String message) {
        return new DefinitionIssue(Severity.WARNING, source, definitionId, path, message);
    }

    public static DefinitionIssue info(String source, String definitionId, String path, String message) {
        return new DefinitionIssue(Severity.INFO, source, definitionId, path, message);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append('[').append(severity).append("] ");
        if (definitionId != null && !definitionId.isBlank()) {
            builder.append(definitionId);
            if (path != null && !path.isBlank()) {
                builder.append('@').append(path);
            }
            builder.append(": ");
        }
        builder.append(message);
        return builder.toString();
    }
}
