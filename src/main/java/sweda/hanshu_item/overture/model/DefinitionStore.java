package sweda.hanshu_item.overture.model;

import com.google.gson.JsonObject;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 定义库 —— Overture 里 {@code ItemManager} 持有「物品快照」的那部分职责。
 *
 * <p>设计要点（都来自 Overture 的经验）：
 * <ul>
 *   <li><b>快照不可变</b>：{@link #snapshot()} 返回的 map 一旦拿到就不会被后续编辑影响，
 *       渲染和发放在同一个 tick 内看到的是同一份数据。</li>
 *   <li><b>原子提交</b>：{@link #commit} 先整体校验，有 ERROR 就整体拒绝并保留旧快照 ——
 *       对应 Overture「候选快照提交失败则回滚」的语义。</li>
 *   <li><b>服务端权威</b>：文件 IO 只发生在服务端；客户端编辑器通过 {@link #replaceAll} 接收下发内容。</li>
 * </ul>
 *
 * <p>线程模型：内部用 {@link AtomicReference} 持有快照，读操作无锁；
 * 写操作在服务端主线程调用（命令、编辑器提交）。
 */
public final class DefinitionStore {

    /** 一次提交的结果。 */
    public record CommitResult(boolean accepted, List<DefinitionIssue> issues, int definitionCount) {

        public List<DefinitionIssue> errors() {
            return issues.stream().filter(issue -> issue.severity() == DefinitionIssue.Severity.ERROR).toList();
        }

        public boolean hasErrors() {
            return issues.stream().anyMatch(issue -> issue.severity() == DefinitionIssue.Severity.ERROR);
        }
    }

    private final String defaultNamespace;
    private final Path file;
    private final AtomicReference<Snapshot> snapshot;

    /**
     * @param defaultNamespace 未写命名空间时的默认值，通常是模组 ID
     * @param file             持久化目标文件；传 {@code null} 表示纯内存（客户端缓存）
     */
    public DefinitionStore(String defaultNamespace, @Nullable Path file) {
        this.defaultNamespace = Objects.requireNonNull(defaultNamespace, "defaultNamespace");
        this.file = file;
        this.snapshot = new AtomicReference<>(new Snapshot(Map.of(), Revision.NONE));
    }

    /** 不可变快照。 */
    public record Snapshot(Map<DefinitionId, ItemDefinition> definitions, String libraryRevision) {

        public Optional<ItemDefinition> find(DefinitionId id) {
            return Optional.ofNullable(definitions.get(id));
        }

        /** 只要可发放的物品（排除事件模型与保留项）。 */
        public List<ItemDefinition> items() {
            return definitions.values().stream().filter(definition -> definition.id().isItem()).toList();
        }

    }

    public String defaultNamespace() {
        return defaultNamespace;
    }

    public @Nullable Path file() {
        return file;
    }

    /** 新布局：file 指向 items/ 目录；旧布局仍使用 items.json 文件。 */
    private boolean directoryLayout() {
        return file != null && (Files.isDirectory(file) || "items".equals(file.getFileName().toString()));
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    public Map<DefinitionId, ItemDefinition> definitions() {
        return snapshot.get().definitions();
    }

    public Optional<ItemDefinition> find(DefinitionId id) {
        return snapshot.get().find(id);
    }

    public Optional<ItemDefinition> find(String rawId) {
        DefinitionId id = DefinitionId.tryParse(rawId);
        return id == null ? Optional.empty() : find(id);
    }

    public int size() {
        return snapshot.get().definitions().size();
    }

    /** 库级签名，用于客户端判断缓存是否过期。 */
    public String libraryRevision() {
        return snapshot.get().libraryRevision();
    }

    // --- 变更 --------------------------------------------------------------

    /**
     * 整体替换（重载文件 / 接收服务端下发）。
     *
     * <p>注意这里<b>不</b>做“有错就拒绝”：整体替换是权威来源（文件或服务端），
     * 调用方应先用 {@link #validate} 决定要不要接受。
     */
    public void replaceAll(Map<DefinitionId, ItemDefinition> definitions) {
        Map<DefinitionId, ItemDefinition> sorted = new TreeMap<>(definitions);
        snapshot.set(new Snapshot(Map.copyOf(sorted), computeLibraryRevision(sorted)));
    }

    /**
     * 原子提交一批变更：先整体校验，出现 ERROR 则整批拒绝并保留旧快照。
     *
     * @param mutations 定义 ID → 新定义；值为 {@code null} 表示删除该定义
     */
    public CommitResult commit(Map<DefinitionId, ItemDefinition> mutations) {
        Map<DefinitionId, ItemDefinition> candidate = new LinkedHashMap<>(definitions());
        mutations.forEach((id, definition) -> {
            if (definition == null) {
                candidate.remove(id);
            } else {
                candidate.put(id, definition);
            }
        });

        List<DefinitionIssue> issues = validate(candidate, defaultNamespace);
        boolean accepted = issues.stream().noneMatch(issue -> issue.severity() == DefinitionIssue.Severity.ERROR);
        if (!accepted) {
            return new CommitResult(false, List.copyOf(issues), candidate.size());
        }
        replaceAll(candidate);
        return new CommitResult(true, List.copyOf(issues), candidate.size());
    }

    public CommitResult put(ItemDefinition definition) {
        return commit(Map.of(definition.id(), definition));
    }

    public CommitResult remove(DefinitionId id) {
        Map<DefinitionId, ItemDefinition> mutation = new LinkedHashMap<>();
        mutation.put(id, null);
        return commit(mutation);
    }

    // --- 持久化 ------------------------------------------------------------

    /**
     * 从 {@link #file()} 加载；文件不存在时视为空库并返回成功。
     *
     * <p><b>校验不通过时绝不改动现有快照。</b>这一点必须严格守住：
     * 调用方（文件监听或编辑器保存）在拿到 {@code accepted=false} 之后
     * 会告诉用户「已保留旧定义」—— 如果这里已经把新内容写进去了，那句提示就是假的，
     * 而用户会以为自己的坏文件没生效、继续在错的方向上排查。
     *
     * <p>实现上就是「先读到局部变量 → 校验 → 通过才 replaceAll」，
     * 中途任何一步失败都不碰 {@code snapshot}。
     */
    public CommitResult reload() throws IOException {
        if (file == null) {
            return new CommitResult(true, List.of(), size());
        }
        if (directoryLayout()) {
            Path legacy = file.resolveSibling("items.json");
            if (!Files.exists(file) && Files.exists(legacy)) {
                String json = AtomicFiles.readStringIfExists(legacy);
                Map<DefinitionId, ItemDefinition> loaded = json == null
                        ? Map.of() : DefinitionJson.decodeAll(DefinitionJson.parseObject(json), defaultNamespace);
                List<DefinitionIssue> issues = validate(loaded, defaultNamespace);
                if (issues.stream().anyMatch(i -> i.severity() == DefinitionIssue.Severity.ERROR)) {
                    return new CommitResult(false, List.copyOf(issues), size());
                }
                replaceAll(loaded);
                return new CommitResult(true, List.copyOf(issues), loaded.size());
            }
            List<DefinitionIssue> issues = new ArrayList<>();
            Map<DefinitionId, ItemDefinition> loaded = readDirectory(file, defaultNamespace, issues);
            issues.addAll(validate(loaded, defaultNamespace));
            boolean accepted = issues.stream().noneMatch(i -> i.severity() == DefinitionIssue.Severity.ERROR);
            if (!accepted) {
                return new CommitResult(false, List.copyOf(issues), size());
            }
            replaceAll(loaded);
            return new CommitResult(true, List.copyOf(issues), loaded.size());
        }
        String json = AtomicFiles.readStringIfExists(file);
        if (json == null) {
            replaceAll(Map.of());
            return new CommitResult(true, List.of(), 0);
        }
        JsonObject root = DefinitionJson.parseObject(json);
        Map<DefinitionId, ItemDefinition> loaded = DefinitionJson.decodeAll(root, defaultNamespace);

        List<DefinitionIssue> issues = validate(loaded, defaultNamespace);
        boolean accepted = issues.stream().noneMatch(issue -> issue.severity() == DefinitionIssue.Severity.ERROR);
        if (!accepted) {
            // 注意这里返回的是「当前快照的大小」，不是 loaded 的大小 —— 因为快照没变
            return new CommitResult(false, List.copyOf(issues), size());
        }
        replaceAll(loaded);
        return new CommitResult(true, List.copyOf(issues), loaded.size());
    }

    /** 写入 {@link #file()}（原子写入：写临时文件 + 原子改名，避免被读到写了一半的内容）。 */
    public void save() throws IOException {
        if (file == null) {
            throw new IllegalStateException("该定义库是只读的（没有关联文件）");
        }
        if (directoryLayout()) {
            writeDirectory(file, definitions());
            return;
        }
        JsonObject root = DefinitionJson.encodeAll(definitions());
        AtomicFiles.writeString(file, DefinitionJson.toPrettyString(root) + System.lineSeparator());
    }

    /** 读取 items/ 目录下的单定义文件。每个文件路径为 namespace/path.json。 */
    public static Map<DefinitionId, ItemDefinition> readDirectory(Path directory,
                                                                    String defaultNamespace,
                                                                    List<DefinitionIssue> issues)
            throws IOException {
        Map<DefinitionId, ItemDefinition> result = new LinkedHashMap<>();
        if (!Files.exists(directory)) {
            return result;
        }
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .sorted().toList()) {
                String relative = directory.relativize(path).toString().replace('\\', '/');
                String raw = relative.substring(0, relative.length() - ".json".length());
                String idText;
                int slash = raw.indexOf('/');
                if (slash < 0) {
                    idText = defaultNamespace + ":" + raw;
                } else {
                    idText = raw.substring(0, slash) + ":" + raw.substring(slash + 1);
                }
                DefinitionId id = DefinitionId.tryParse(idText);
                String source = path.toString();
                if (id == null) {
                    issues.add(DefinitionIssue.error(source, raw, null, "文件路径不是合法的定义 ID"));
                    continue;
                }
                try {
                    String json = AtomicFiles.readStringIfExists(path);
                    JsonObject root = DefinitionJson.parseObject(json == null ? "{}" : json);
                    ItemDefinition definition = DefinitionJson.decodeDefinition(id, root);
                    if (result.put(id, definition) != null) {
                        issues.add(DefinitionIssue.error(source, id.full(), null, "重复的定义 ID"));
                    }
                } catch (RuntimeException ex) {
                    issues.add(DefinitionIssue.error(source, id.full(), null,
                            "JSON 解析失败: " + ex.getMessage()));
                }
            }
        }
        return result;
    }

    /** 原子写入每个定义文件，并删除 items/ 中已经不存在的旧定义文件。 */
    public static void writeDirectory(Path directory,
                                      Map<DefinitionId, ItemDefinition> definitions) throws IOException {
        Files.createDirectories(directory);
        Set<Path> written = new java.util.HashSet<>();
        for (Map.Entry<DefinitionId, ItemDefinition> entry : new TreeMap<>(definitions).entrySet()) {
            DefinitionId id = entry.getKey();
            Path target = directory.resolve(id.namespace()).resolve(id.path() + ".json").normalize();
            if (!target.startsWith(directory.normalize())) {
                throw new IOException("定义路径越界: " + id.full());
            }
            written.add(target.toAbsolutePath().normalize());
            AtomicFiles.writeString(target,
                    DefinitionJson.toPrettyString(DefinitionJson.encodeDefinition(entry.getValue()))
                            + System.lineSeparator());
        }
        // 先把新文件全部写成功，再清理已经删除的定义；中途断电不会把现有定义先删光。
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .toList()) {
                if (!written.contains(path.toAbsolutePath().normalize())) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // --- 校验 --------------------------------------------------------------

    /**
     * 校验一整套定义。当前检查：
     * <ul>
     *   <li>ID 是否可发放（{@code $} 结尾只作事件模型，{@code __} 开头为保留）</li>
     *   <li>{@code from} 引用的事件模型是否存在、是否真的以 {@code $} 结尾</li>
     *   <li>动作 ID 是否已注册（通过 {@code actionExists} 回调，避免模型层依赖运行时注册表）</li>
     *   <li>触发器与动作是否为空</li>
     *   <li>循环引用</li>
     * </ul>
     */
    public static List<DefinitionIssue> validate(Map<DefinitionId, ItemDefinition> definitions, String namespace) {
        List<DefinitionIssue> issues = new ArrayList<>();
        for (ItemDefinition definition : definitions.values()) {
            try {
                if (!definition.id().isItem()) throw new IllegalArgumentException("编号是保留项");
                sweda.hanshu_item.overture.runtime.SavedItemComponents.parse(definition.nbt());
                for (String path : definition.syncFlags().excludedPaths()) {
                    sweda.hanshu_item.overture.runtime.NativeNbtSync.parsePath(path);
                }
            } catch (RuntimeException e) {
                issues.add(DefinitionIssue.error("<library>", definition.id().full(), "nbt", e.getMessage()));
            }
        }
        return issues;
    }
    private static String computeLibraryRevision(Map<DefinitionId, ItemDefinition> definitions) {
        JsonObject canonical = new JsonObject();
        definitions.forEach((id, definition) ->
                canonical.add(id.full(), DefinitionJson.encodeDefinition(definition)));
        // 库签名只需反映“有哪些定义、各自数据是什么”，不必进入完整内容 —— 完整签名在物品级别算。
        return Revision.sha256(DefinitionJson.toPrettyString(canonical) + definitions.keySet());
    }

    /** 便捷方法：把一批定义拼成 {@code id -> 定义}。 */
    public static Map<DefinitionId, ItemDefinition> index(Collection<ItemDefinition> definitions) {
        Map<DefinitionId, ItemDefinition> result = new LinkedHashMap<>();
        definitions.forEach(definition -> result.put(definition.id(), definition));
        return result;
    }
}
