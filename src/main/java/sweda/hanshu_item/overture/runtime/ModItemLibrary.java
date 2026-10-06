package sweda.hanshu_item.overture.runtime;

import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.overture.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** 原生 NBT 编号物品库，使用不可变快照并在校验成功后整体提交。 */
public final class ModItemLibrary {
    public static final String ITEMS_DIRECTORY = "items";
    public static final String ITEMS_FILE = "items.json";
    private final String namespace;
    private final Path directory;
    private final DefinitionStore definitions;
    private SaveListener saveListener;

    public record CommitResult(boolean accepted, List<DefinitionIssue> issues, int itemCount,
                               Set<DefinitionId> changedIds) {
        public List<DefinitionIssue> errors() {
            return issues.stream().filter(issue -> issue.severity() == DefinitionIssue.Severity.ERROR).toList();
        }
    }

    public record View(Map<DefinitionId, ItemDefinition> definitions) {
        public List<ItemDefinition> items() {
            return definitions.values().stream().filter(definition -> definition.id().isItem())
                    .sorted(Comparator.comparing(ItemDefinition::id)).toList();
        }
    }

    public ModItemLibrary(String namespace, @Nullable Path directory) {
        this.namespace = namespace;
        this.directory = directory;
        definitions = new DefinitionStore(namespace, directory == null ? null : directory.resolve(ITEMS_DIRECTORY));
    }

    public String namespace() { return namespace; }
    public @Nullable Path directory() { return directory; }
    public DefinitionStore definitions() { return definitions; }
    public View view() { return new View(definitions.definitions()); }
    public Optional<ItemDefinition> find(DefinitionId id) { return definitions.find(id); }
    public Optional<ItemDefinition> find(String rawId) { return definitions.find(rawId); }

    public CommitResult commit(Map<DefinitionId, ItemDefinition> mutations) {
        Map<DefinitionId, ItemDefinition> before = definitions.definitions();
        var result = definitions.commit(mutations);
        return new CommitResult(result.accepted(), result.issues(), result.definitionCount(),
                result.accepted() ? changedIds(before, definitions.definitions()) : Set.of());
    }

    public CommitResult put(ItemDefinition definition) { return commit(Map.of(definition.id(), definition)); }

    /** 修改编号规则并落盘；写盘失败时保留旧规则。 */
    public ItemDefinition updateSyncRules(DefinitionId id, SyncFlags flags) throws IOException {
        ItemDefinition previous = find(id).orElseThrow(() -> new IllegalArgumentException("编号定义不存在: " + id.full()));
        ItemDefinition updated = previous.withSyncFlags(flags);
        var result = put(updated);
        if (!result.accepted()) throw new IllegalArgumentException("同步规则不合法: " + result.errors());
        try {
            save();
        } catch (IOException | RuntimeException e) {
            put(previous);
            throw e;
        }
        return updated;
    }

    public CommitResult remove(DefinitionId id) {
        Map<DefinitionId, ItemDefinition> mutations = new LinkedHashMap<>();
        mutations.put(id, null);
        return commit(mutations);
    }

    public CommitResult load() throws IOException {
        Map<DefinitionId, ItemDefinition> before = definitions.definitions();
        var result = definitions.reload();
        return new CommitResult(result.accepted(), result.issues(), result.definitionCount(),
                result.accepted() ? changedIds(before, definitions.definitions()) : Set.of());
    }

    private static Set<DefinitionId> changedIds(Map<DefinitionId, ItemDefinition> before,
                                                 Map<DefinitionId, ItemDefinition> after) {
        Set<DefinitionId> changed = new LinkedHashSet<>();
        Set<DefinitionId> ids = new LinkedHashSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (DefinitionId id : ids) if (!Objects.equals(before.get(id), after.get(id))) changed.add(id);
        return Set.copyOf(changed);
    }

    public interface SaveListener { void afterSave(ModItemLibrary library); }
    public void onSave(SaveListener listener) { saveListener = listener; }
    public void save() throws IOException {
        definitions.save();
        if (saveListener != null) saveListener.afterSave(this);
    }
    public @Nullable Path definitionFile() { return definitions.file(); }
}
