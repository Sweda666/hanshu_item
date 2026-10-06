package sweda.hanshu_item.overture.model;

import net.minecraft.resources.Identifier;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 物品定义 ID（对应 Overture 里 {@code items/} 下的顶级键）。
 *
 * <p>允许形如 {@code namespace:path} 的完全限定形式；未写命名空间时使用模组默认命名空间。
 * 与 Overture 一致，以 {@code __} 开头的 ID 是保留的（分组元数据），以 {@code $} 结尾的 ID
 * 表示“事件模型”而不是可发放物品。
 */
public record DefinitionId(String namespace, String path) implements Comparable<DefinitionId> {

    /**
     * 路径允许的字符。
     *
     * <p><b>必须包含 {@code $}</b> —— 事件模型就是用 {@code $} 结尾作标记的
     * （{@link #EVENT_MODEL_SUFFIX}）。这里曾经漏了它，后果是连锁的：
     * <ul>
     *   <li>{@code from: ["xxx$"]} 解析失败 → 被上层静默丢弃 → 校验永远发现不了；</li>
     *   <li>于是<b>事件模型机制在文件里根本定义不出来</b>：定义成 {@code xxx$} 的条目
     *       会被当成普通物品发放，而不是只可被引用；</li>
     *   <li>{@code isEventModel()} 对任何从文件读进来的定义都返回 false。</li>
     * </ul>
     * 是自检（一个「引用不存在的事件模型应当报错」的用例）把这个漏网抓出来的。
     *
     * <p>{@code $} 只能出现在结尾（见 {@link #isEventModel} 只认结尾），
     * 但这里不做位置限制 —— 让路径合法性只管「能不能解析」，
     * 「是不是合法的事件模型」交给校验层去判断，职责更清楚。
     */
    private static final Pattern PATH_PATTERN = Pattern.compile("[a-z0-9_./$-]{1,128}");

    public static final String DEFAULT_NAMESPACE = "hanshu_item";

    /** 事件模型后缀：以它结尾的定义只用于被其它定义 {@code from} 引用。 */
    public static final String EVENT_MODEL_SUFFIX = "$";

    /** 分组/保留前缀。 */
    public static final String RESERVED_PREFIX = "__";

    public DefinitionId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("非法的定义 ID 路径: " + path);
        }
        if (!PATH_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException("非法的命名空间: " + namespace);
        }
    }

    /** 解析 {@code path} 或 {@code namespace:path}。 */
    public static DefinitionId parse(String raw) {
        Objects.requireNonNull(raw, "raw");
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("定义 ID 不能为空");
        }
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return new DefinitionId(DEFAULT_NAMESPACE, trimmed);
        }
        return new DefinitionId(trimmed.substring(0, colon), trimmed.substring(colon + 1));
    }

    /** 尝试解析，失败返回 {@code null}（用于命令补全等容错场景）。 */
    public static DefinitionId tryParse(String raw) {
        try {
            return parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 是否是事件模型（只可被引用，不可发放）。 */
    public boolean isEventModel() {
        return path.endsWith(EVENT_MODEL_SUFFIX);
    }

    /** 是否是保留条目（分组元数据等）。 */
    public boolean isReserved() {
        return path.startsWith(RESERVED_PREFIX);
    }

    /** 是否可以作为物品 ID 发放。 */
    public boolean isItem() {
        return !isEventModel() && !isReserved();
    }

    /** 归一化为 Minecraft 的 {@link Identifier}。 */
    public Identifier toIdentifier() {
        return Identifier.fromNamespaceAndPath(namespace, path);
    }

    /** {@code namespace:path} 全称。 */
    public String full() {
        return namespace + ":" + path;
    }

    /** 归属的目录（用于编辑器里的树形分组），没有则为空串。 */
    public String folder() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** 叶子名（不含目录）。 */
    public String leaf() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    @Override
    public int compareTo(DefinitionId other) {
        int byNamespace = namespace.compareTo(other.namespace);
        return byNamespace != 0 ? byNamespace : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return full();
    }
}
