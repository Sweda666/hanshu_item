package sweda.hanshu_item.overture.runtime;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import sweda.hanshu_item.overture.model.*;
import java.nio.file.*;
import java.util.*;

/** 原生模板加载、失败时保留旧快照、仅报告变更编号。 */
public final class LibraryLoadCheck {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var dir = Files.createTempDirectory("hanshu-native-library-");
        try {
            var id = DefinitionId.parse("1001");
            var library = new ModItemLibrary("hanshu_item", dir);
            var original = ItemDefinition.blank(id).withNbt("{\"minecraft:custom_data\":{AmmoCount:30}}");
            check(library.put(original).accepted(), "合法模板可提交");
            var replacement = original.withIcon("minecraft:paper")
                    .withNbt("{\"minecraft:custom_data\":{AmmoCount:7}}");
            check(library.put(replacement).accepted() && library.find(id).orElseThrow().equals(replacement),
                    "已有编号可以被新模板覆盖");
            check(library.put(original).accepted(), "覆盖测试后可恢复原模板");
            library.save();
            var reloaded = new ModItemLibrary("hanshu_item", dir);
            var first = reloaded.load();
            check(first.accepted() && first.changedIds().equals(Set.of(id)), "首次加载报告变更编号");
            check(reloaded.load().changedIds().isEmpty(), "相同内容不重复同步");
            var target = dir.resolve("items/hanshu_item/1001.json");
            Files.writeString(target, "{\"icon\":\"minecraft:stone\",\"nbt\":\"{broken\"}");
            check(!reloaded.load().accepted() && reloaded.find(id).orElseThrow().equals(original), "非法 SNBT 保留旧快照");
            library.put(original.withNbt("{\"minecraft:custom_data\":{AmmoCount:30,Power:2}}"));
            library.save();
            var result = reloaded.load();
            check(result.accepted() && result.changedIds().equals(Set.of(id)), "仅报告变更编号");
            Files.delete(target);
            check(reloaded.load().accepted() && reloaded.view().items().isEmpty(), "移除文件加载为空库");
            checkSyncRulePersistence(dir.resolve("rule-checks"));
            System.out.println("Native item library load checks passed.");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void checkSyncRulePersistence(Path dir) throws Exception {
        var id = DefinitionId.parse("1001");
        var otherId = DefinitionId.parse("1002");
        var library = new ModItemLibrary("hanshu_item", dir);
        var original = ItemDefinition.blank(id).withNbt("{\"minecraft:custom_data\":{AmmoCount:30,Power:10}}");
        var other = original.withId(otherId);
        check(library.put(original).accepted() && library.put(other).accepted(), "创建两个独立编号");
        library.save();
        var excludedAmmo = SyncFlags.of(List.of(NativeNbtSync.normalizePath("nbt.AmmoCount")));
        library.updateSyncRules(id, excludedAmmo);
        var target = dir.resolve("items/hanshu_item/1001.json");
        var json = DefinitionJson.parseObject(Files.readString(target));
        check(json.getAsJsonArray("sync-exclude").get(0).getAsString().equals(excludedAmmo.toList().getFirst()),
                "规则直接写入对应编号 JSON");
        check(json.get("nbt").getAsString().equals(original.nbt()), "修改规则不改变模板 NBT");
        var reloaded = new ModItemLibrary("hanshu_item", dir);
        check(reloaded.load().accepted() && reloaded.find(id).orElseThrow().syncFlags().equals(excludedAmmo),
                "重启重载后规则保留");
        check(reloaded.find(otherId).orElseThrow().equals(other), "修改一个编号不影响其他编号");
        try {
            library.updateSyncRules(id, SyncFlags.of(List.of("invalid.path")));
            throw new AssertionError("非法路径必须拒绝");
        } catch (IllegalArgumentException expected) {
            check(library.find(id).orElseThrow().syncFlags().equals(excludedAmmo), "非法规则保留旧配置");
        }
        library.updateSyncRules(id, SyncFlags.none());
        check(!DefinitionJson.parseObject(Files.readString(target)).has("sync-exclude"), "reset 清除 JSON 排除规则");
        check(reloaded.load().accepted() && reloaded.find(id).orElseThrow().syncFlags().isEmpty(), "reset 重载后默认全参与");
        var failedLibrary = new ModItemLibrary("hanshu_item", dir.resolve("failed-save"));
        check(failedLibrary.put(original).accepted(), "创建写盘失败用例");
        Files.writeString(dir.resolve("failed-save"), "block directory creation");
        try {
            failedLibrary.updateSyncRules(id, excludedAmmo);
            throw new AssertionError("写盘失败必须报告");
        } catch (java.io.IOException expected) {
            check(failedLibrary.find(id).orElseThrow().equals(original), "写盘失败撤回内存规则");
        }
    }
    private static void check(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}
