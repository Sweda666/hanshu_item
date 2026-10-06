package sweda.hanshu_item.overture.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 原生 NBT 同步排除路径。默认全部参与，父节点排除覆盖其字段和列表元素。 */
public final class SyncFlags {

    private final Set<String> excludedPaths;

    private SyncFlags(Set<String> excludedPaths) {
        this.excludedPaths = Set.copyOf(excludedPaths);
    }

    public static SyncFlags none() {
        return new SyncFlags(Set.of());
    }

    /** 合并两个来源的排除路径；任一方排除，该路径就不参与同步。 */
    public SyncFlags plus(SyncFlags other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        Set<String> merged = new LinkedHashSet<>(excludedPaths);
        merged.addAll(other.excludedPaths);
        return new SyncFlags(merged);
    }

    /**
     * 从编号 JSON 的字符串集合还原。
     *
     * <p>会顺手做去空白、去空串，免得手工改存档写进去的空行变成一个「永不匹配」的幽灵条目。
     */
    public static SyncFlags of(Collection<String> rawPaths) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : rawPaths) {
            if (raw == null) {
                continue;
            }
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized.isEmpty() ? none() : new SyncFlags(normalized);
    }

    /** 全部<b>不</b>参与同步的路径，按字典序（写回文件/存档时输出稳定）。 */
    public Set<String> excludedPaths() {
        return new TreeSet<>(excludedPaths);
    }

    public boolean isEmpty() {
        return excludedPaths.isEmpty();
    }

    /**
     * 某路径是否参与同步。
     *
     * <p>祖先规则：把 {@code components."minecraft:custom_data".Gun} 整个取消勾选，等于它下面所有子键都取消 ——
     * 否则「取消勾选一个对象」和「逐个取消勾选它的成员」语义会不一致，用户会困惑。
     */
    public boolean participates(String path) {
        Objects.requireNonNull(path, "path");
        return excludedPaths.stream().noneMatch(excluded -> inSubtree(path, excluded));
    }

    /** 是否有任何被排除的路径落在这个前缀子树内（编辑器用来画「部分勾选」）。 */
    public boolean hasExcludedUnder(String prefix) {
        return excludedPaths.stream().anyMatch(path -> !path.equals(prefix) && inSubtree(path, prefix));
    }

    /** 设置某路径是否参与同步，返回新实例。 */
    public SyncFlags withParticipates(String path, boolean participates) {
        Set<String> updated = new LinkedHashSet<>(excludedPaths);
        if (participates) {
            updated.remove(path);
        } else {
            updated.add(path);
        }
        return updated.isEmpty() ? none() : new SyncFlags(updated);
    }

    /** 整个子树都跟着一起设，返回新实例（取消勾选父节点时用）。 */
    public SyncFlags withSubtreeParticipates(String prefix, boolean participates) {
        Set<String> updated = new LinkedHashSet<>(excludedPaths);
        if (participates) {
            updated.removeIf(path -> inSubtree(path, prefix));
        } else {
            updated.removeIf(path -> inSubtree(path, prefix));
            updated.add(prefix);
        }
        return updated.isEmpty() ? none() : new SyncFlags(updated);
    }

    /** 删除某路径相关的标记（键被删掉时清理，免得留下永不匹配的条目）。 */
    public SyncFlags withoutSubtree(String prefix) {
        Set<String> updated = new LinkedHashSet<>(excludedPaths);
        updated.removeIf(path -> inSubtree(path, prefix));
        return updated.isEmpty() ? none() : new SyncFlags(updated);
    }

    /** 序列化成字符串列表（写进编号 JSON）。 */
    public List<String> toList() {
        return List.copyOf(excludedPaths());
    }

    private static boolean inSubtree(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + ".") || path.startsWith(prefix + "[");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SyncFlags that && excludedPaths.equals(that.excludedPaths);
    }

    @Override
    public int hashCode() {
        return excludedPaths.hashCode();
    }

    @Override
    public String toString() {
        return excludedPaths.isEmpty() ? "SyncFlags(全部参与)" : "SyncFlags(不参与=" + excludedPaths() + ")";
    }
}
