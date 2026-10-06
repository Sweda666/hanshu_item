package sweda.hanshu_item.overture.runtime;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.overture.model.*;
import java.util.*;

/** 仅根据原生组件模板构建和同步编号物品。 */
public final class ModItemBuilder {
    private ModItemBuilder() {}

    public record Built(ItemStack stack, List<String> issues) {
        public boolean hasIssues() { return !issues.isEmpty(); }
    }

    private static @Nullable HolderLookup.Provider registries(@Nullable Player player) {
        if (player != null) return player.level().registryAccess();
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.registryAccess();
    }

    public static Built build(ItemDefinition definition, @Nullable Player player, int amount) {
        Identifier icon = Identifier.tryParse(definition.icon());
        if (icon == null || !BuiltInRegistries.ITEM.containsKey(icon))
            throw new IllegalArgumentException("物品类型不存在: " + definition.icon());
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(icon), Math.max(1, amount));
        stack.applyComponents(SavedItemComponents.decode(definition.nbt(), registries(player)));
        stamp(stack, definition);
        return new Built(stack, List.of());
    }

    public static List<String> refresh(ItemStack stack, ItemDefinition definition, @Nullable Player player) {
        var lookup = registries(player);
        var current = SavedItemComponents.encode(stack.getComponentsPatch(), lookup);
        var merged = NativeNbtSync.merge(SavedItemComponents.parse(definition.nbt()), current, definition.syncFlags());
        // 先完整解析候选补丁；解析失败时不改动原物品。
        var patch = SavedItemComponents.decode(merged, lookup);
        stack.applyComponents(patch);
        stamp(stack, definition);
        return List.of();
    }

    public static String expectedFingerprint(ItemStack stack, ItemDefinition definition) {
        return Revision.fingerprint(definition, definition.syncFlags());
    }

    public static boolean needsUpdate(ItemStack stack, ItemDefinition definition) {
        if (ItemDataAccessor.hasLegacySyncRules(stack)
                || !Objects.equals(ItemDataAccessor.definitionRevision(stack), Revision.of(definition))
                || !Objects.equals(ItemDataAccessor.revision(stack), expectedFingerprint(stack, definition))) {
            return true;
        }

        // 编号签名只表示「模板版本」，不能发现铁砧改名、第三方修改组件等物品自身漂移。
        // 将模板应用到当前组件后再比较；排除路径会先恢复当前值，因此弹药等状态不会被误判。
        var lookup = registries(null);
        var current = SavedItemComponents.encode(stack.getComponentsPatch(), lookup);
        var expected = NativeNbtSync.merge(SavedItemComponents.parse(definition.nbt()), current,
                definition.syncFlags());
        return !expected.equals(current);
    }

    public static void stamp(ItemStack stack, ItemDefinition definition) {
        ItemDataAccessor.stamp(stack, definition.id(), Revision.of(definition), expectedFingerprint(stack, definition));
    }

    public static boolean isManaged(ItemStack stack) { return ItemDataAccessor.isModItem(stack); }
}
