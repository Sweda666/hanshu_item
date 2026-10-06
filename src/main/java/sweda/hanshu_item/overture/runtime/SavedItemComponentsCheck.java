package sweda.hanshu_item.overture.runtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import sweda.hanshu_item.overture.model.DefinitionId;
import sweda.hanshu_item.overture.model.ItemDefinition;
import sweda.hanshu_item.overture.command.OvertureCommands;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** 验证保存手持模板时组件经 SNBT、定义 JSON、磁盘重载后仍能完整还原。 */
public final class SavedItemComponentsCheck {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkCommandIds();
        checkManagementCleanup();
        CompoundTag custom = new CompoundTag();
        custom.putLong("serial", Long.MAX_VALUE);
        custom.putByte("flag", (byte) 1);
        custom.putIntArray("coordinates", new int[]{1, -2, 3});
        CompoundTag managed = new CompoundTag();
        managed.putString("id", "hanshu_item:old_id");
        custom.put(ItemDataAccessor.ROOT_KEY, managed);
        Component name = Component.literal("测试物品").withStyle(Style.EMPTY.withColor(0x12AB34).withItalic(false));
        ItemLore lore = new ItemLore(List.of(Component.literal("第一行").withStyle(Style.EMPTY.withBold(true)),
                Component.literal("第二行")));
        DataComponentPatch patch = DataComponentPatch.builder()
                .set(DataComponents.CUSTOM_NAME, name)
                .set(DataComponents.LORE, lore)
                .set(DataComponents.DAMAGE, 17)
                .set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false)
                .set(DataComponents.CUSTOM_DATA, CustomData.of(custom))
                .remove(DataComponents.ATTRIBUTE_MODIFIERS)
                .build();
        CompoundTag encoded = SavedItemComponents.encode(patch, null);
        DefinitionId id = DefinitionId.parse("folder/1001");
        var dir = Files.createTempDirectory("hanshu-saved-components-");
        try {
            ModItemLibrary library = new ModItemLibrary(DefinitionId.DEFAULT_NAMESPACE, dir);
            var result = library.put(ItemDefinition.blank(id).withIcon("minecraft:diamond_sword")
                    .withNbt(encoded.toString()));
            check(result.accepted(), "模板定义应当通过校验");
            library.save();
            ModItemLibrary reloaded = new ModItemLibrary(DefinitionId.DEFAULT_NAMESPACE, dir);
            check(reloaded.load().accepted(), "定义应能从磁盘重载");
            var restored = SavedItemComponents.decode(reloaded.find(id).orElseThrow()
                    .nbt(), null);
            check(name.equals(restored.get(DataComponentMap.EMPTY, DataComponents.CUSTOM_NAME)), "名称及颜色样式应保留");
            check(lore.equals(restored.get(DataComponentMap.EMPTY, DataComponents.LORE)), "Lore 及样式应保留");
            check(restored.get(DataComponentMap.EMPTY, DataComponents.DAMAGE) == 17, "耐久损耗应保留");
            check(Boolean.FALSE.equals(restored.get(DataComponentMap.EMPTY, DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
                    "false 值应保留");
            check(restored.entrySet().stream().anyMatch(entry -> entry.getKey() == DataComponents.ATTRIBUTE_MODIFIERS
                    && entry.getValue().isEmpty()), "显式移除组件应保留");
            CompoundTag restoredCustom = restored.get(DataComponentMap.EMPTY, DataComponents.CUSTOM_DATA).copyTag();
            CompoundTag expectedCustom = custom.copy();
            expectedCustom.remove(ItemDataAccessor.ROOT_KEY);
            check(expectedCustom.equals(restoredCustom), "第三方 NBT、long 精度、byte 类型和数组应保留");
            check(!restoredCustom.contains(ItemDataAccessor.ROOT_KEY), "模板不能带着来源编号");
            check(custom.contains(ItemDataAccessor.ROOT_KEY), "保存不能修改来源数据");
            try {
                SavedItemComponents.decode("{broken", null);
                throw new AssertionError("错误 SNBT 必须报错，不能静默生成空模板");
            } catch (IllegalArgumentException expected) {
                // 取出指令会报告这个错误，且不会发放缺失数据的物品。
            }
            System.out.println("Saved item components round-trip checks passed.");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void check(boolean passed, String message) {
        if (!passed) {
            throw new AssertionError(message);
        }
    }

    private static void checkManagementCleanup() {
        var outer = SavedItemComponents.parse("{AmmoCount:7,hanshu_item:{id:\"hanshu_item:1001\",def:\"template\",rev:\"revision\",exclude:[\"old\"],data:{skill:1}}}");
        var cleaned = ItemDataAccessor.withRoot(outer, ItemDataAccessor.rootOf(outer));
        var root = ItemDataAccessor.rootOf(cleaned);
        check(root.keySet().equals(java.util.Set.of("id", "def", "rev")), "物品只存编号和签名，不存同步规则");
        check(cleaned.getInt("AmmoCount").orElseThrow() == 7, "管理信息清理保留第三方弹药");
        check(ItemDataAccessor.rootOf(outer).contains("exclude"), "清理不修改来源 NBT");
    }

    /** ItemStack 的默认组件需要游戏注册表完成绑定，此检查由服务器启动自检调用。 */
    public static void checkNativeRefresh() {
        var id = DefinitionId.parse("checks/ammo");
        var excludedAmmo = sweda.hanshu_item.overture.model.SyncFlags.of(List.of(
                NativeNbtSync.normalizePath("nbt.AmmoCount")));
        var definition = ItemDefinition.blank(id)
                .withNbt("{\"minecraft:custom_data\":{AmmoCount:30,Power:10}}")
                .withSyncFlags(excludedAmmo);
        var stack = ModItemBuilder.build(definition, null, 1).stack();
        check(!ItemDataAccessor.rootOf(ItemDataAccessor.rawNbt(stack)).contains("exclude"), "取出物品不携带同步规则");
        var nativeData = ItemDataAccessor.rawNbt(stack);
        nativeData.putInt("AmmoCount", 7);
        ItemDataAccessor.writeRawNbt(stack, nativeData);
        check(!ModItemBuilder.needsUpdate(stack, definition), "使用过程中弹药变化不触发模板重建");
        nativeData.putInt("Power", 11);
        ItemDataAccessor.writeRawNbt(stack, nativeData);
        check(ModItemBuilder.needsUpdate(stack, definition), "检测到物品原生 NBT 漂移");
        ModItemBuilder.refresh(stack, definition, null);
        check(ItemDataAccessor.rawNbt(stack).getInt("Power").orElseThrow() == 10,
                "检测到漂移后恢复模板值");
        check(ItemDataAccessor.rawNbt(stack).getInt("AmmoCount").orElseThrow() == 7,
                "检测漂移后仍保留排除的弹药");
        var oldRoot = ItemDataAccessor.rootOf(nativeData);
        oldRoot.put("data", new CompoundTag());
        var legacyExclusions = new net.minecraft.nbt.ListTag();
        legacyExclusions.add(net.minecraft.nbt.StringTag.valueOf(NativeNbtSync.normalizePath("nbt.Power")));
        oldRoot.put("exclude", legacyExclusions);
        nativeData.put(ItemDataAccessor.ROOT_KEY, oldRoot);
        ItemDataAccessor.writeRawNbt(stack, nativeData);
        check(ModItemBuilder.needsUpdate(stack, definition), "旧物品排除标记触发清理");
        var updated = definition.withNbt("{\"minecraft:custom_data\":{AmmoCount:30,Power:20}}");
        ModItemBuilder.refresh(stack, updated, null);
        check(ItemDataAccessor.rawNbt(stack).getInt("AmmoCount").orElseThrow() == 7,
                "实际物品同步保留弹药");
        check(ItemDataAccessor.rawNbt(stack).getInt("Power").orElseThrow() == 20,
                "实际物品同步其他字段");
        check(!ItemDataAccessor.rootOf(ItemDataAccessor.rawNbt(stack)).contains("data"),
                "同步清除旧玩法数据区");
        check(!ItemDataAccessor.rootOf(ItemDataAccessor.rawNbt(stack)).contains("exclude"), "同步清除旧物品规则");
        var allSync = updated.withSyncFlags(sweda.hanshu_item.overture.model.SyncFlags.none());
        ModItemBuilder.refresh(stack, allSync, null);
        check(ItemDataAccessor.rawNbt(stack).getInt("AmmoCount").orElseThrow() == 30,
                "删除 JSON 排除后恢复同步，不受旧物品规则影响");
        check(!ModItemBuilder.needsUpdate(stack, allSync), "同步后指纹收敛");
    }

    private static void checkCommandIds() throws Exception {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        OvertureCommands.register(dispatcher);
        var root = dispatcher.getRoot().getChild("hanshu");
        for (String command : List.of("save", "get", "sync")) {
            var idArgument = (ArgumentCommandNode<?, ?>) root.getChild(command).getChild("id");
            for (String rawId : List.of("1001", "weapons/1001", "custom:weapons/1001")) {
                var reader = new StringReader(rawId);
                Object parsed = idArgument.getType().parse(reader);
                String expected = rawId.contains(":") ? rawId : "minecraft:" + rawId;
                check(expected.equals(parsed.toString()) && !reader.canRead(),
                        command + " 应支持不加引号的完整编号 " + rawId);
            }
        }
        check(root.getChild("save").getChild("id") != null && root.getChild("save").getChild("id").getCommand() != null, "save 编号指令已注册");
        check(root.getChild("unbind").getCommand() != null, "unbind 指令已注册");
        check(root.getChild("bind").getChild("id") != null
                && root.getChild("bind").getChild("id").getCommand() != null, "bind 指令已注册");
        check(root.getChild("rules").getChild("set") != null, "rules set 指令已注册");
        // 分别检查 sync 执行子树和 rules 规则子树；权限和实际执行仍由 hanshu 根指令控制。
        var syncDispatcher = new CommandDispatcher<CommandSourceStack>();
        syncDispatcher.getRoot().addChild(root.getChild("sync"));
        syncDispatcher.getRoot().addChild(root.getChild("rules"));
        for (String action : List.of("set nbt.AmmoCount off", "set nbt.AmmoCount on",
                "set components.\"minecraft:damage\" off", "list", "reset")) {
            String withoutId = "rules " + action;
            var implicit = syncDispatcher.parse(withoutId, null);
            check(!implicit.getReader().canRead() && implicit.getExceptions().isEmpty()
                    && implicit.getContext().getCommand() != null, "省略编码 ID 的指令可以完整解析: " + withoutId);
            check(implicit.getContext().getNodes().stream().noneMatch(node -> node.getNode().getName().equals("id")),
                    "省略编码 ID 时不产生显式目标参数");
            for (String rawId : List.of("001", "ak_47", "weapons/ak_47", "custom:weapons/ak_47")) {
                String input = withoutId + " " + rawId;
                var explicit = syncDispatcher.parse(input, null);
                check(!explicit.getReader().canRead() && explicit.getExceptions().isEmpty()
                        && explicit.getContext().getCommand() != null, "显式编码 ID 的指令可以完整解析: " + input);
                String parsedId = explicit.getContext().getNodes().stream()
                        .filter(node -> node.getNode().getName().equals("id"))
                        .map(node -> node.getRange().get(input)).findFirst().orElseThrow();
                check(rawId.equals(parsedId), "编码 ID 按字符串原样保留，含前导零、目录和命名空间");
            }
        }
        for (String input : List.of("sync", "sync 1001", "sync weapons/ak_47", "sync custom:weapons/ak_47")) {
            var parsed = syncDispatcher.parse(input, null);
            check(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty()
                    && parsed.getContext().getCommand() != null, "sync 只接受可选编号并执行同步: " + input);
        }
        for (String command : List.of("list", "info", "bind", "unbind", "save", "get")) {
            syncDispatcher.getRoot().addChild(root.getChild(command));
        }
        for (String input : List.of(
                "list", "info", "bind 1001", "unbind", "save 1001", "get 1001", "get 1001 64")) {
            var parsed = syncDispatcher.parse(input, null);
            check(!parsed.getReader().canRead() && parsed.getExceptions().isEmpty()
                    && parsed.getContext().getCommand() != null, "完整指令可解析（省略 hanshu 前缀）: " + input);
        }
    }
}
