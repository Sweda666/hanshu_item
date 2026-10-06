package sweda.hanshu_item.overture.runtime;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.overture.model.*;
import java.util.*;

/** 原生 NBT 读写及最少的编号管理信息；不再创建自定义玩法 data 数据区。 */
public final class ItemDataAccessor {
    public static final String ROOT_KEY = "hanshu_item";
    public static final String KEY_ID = "id";
    public static final String KEY_REVISION = "rev";
    public static final String KEY_DEFINITION_REVISION = "def";
    private ItemDataAccessor() {}

    public static CompoundTag rawNbt(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    public static void writeRawNbt(ItemStack stack, CompoundTag tag) {
        if (tag == null || tag.isEmpty()) stack.remove(DataComponents.CUSTOM_DATA);
        else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static CompoundTag rootOf(@Nullable CompoundTag outer) {
        return outer == null ? new CompoundTag() : outer.getCompound(ROOT_KEY).orElseGet(CompoundTag::new);
    }

    public static CompoundTag withRoot(@Nullable CompoundTag outer, CompoundTag root) {
        CompoundTag result = outer == null ? new CompoundTag() : outer.copy();
        CompoundTag clean = cleanManagementRoot(root);
        if (clean.isEmpty()) result.remove(ROOT_KEY);
        else result.put(ROOT_KEY, clean);
        return result;
    }

    private static CompoundTag cleanManagementRoot(CompoundTag old) {
        CompoundTag clean = new CompoundTag();
        for (String key : List.of(KEY_ID, KEY_REVISION, KEY_DEFINITION_REVISION)) {
            Tag value = old.get(key);
            if (value != null) clean.put(key, value.copy());
        }
        return clean;
    }

    public static @Nullable DefinitionId definitionIdOf(CompoundTag root) {
        return DefinitionId.tryParse(root.getString(KEY_ID).orElse(""));
    }
    public static @Nullable DefinitionId definitionId(ItemStack stack) { return definitionIdOf(rootOf(rawNbt(stack))); }
    public static boolean isModItem(ItemStack stack) { return definitionId(stack) != null; }
    public static boolean isOvertureItem(ItemStack stack) { return isModItem(stack); }
    public static @Nullable String revision(ItemStack stack) { return rootOf(rawNbt(stack)).getString(KEY_REVISION).orElse(null); }
    public static @Nullable String definitionRevision(ItemStack stack) { return rootOf(rawNbt(stack)).getString(KEY_DEFINITION_REVISION).orElse(null); }

    public static void stamp(ItemStack stack, DefinitionId id, String definitionRevision, String fingerprint) {
        CompoundTag root = cleanManagementRoot(rootOf(rawNbt(stack)));
        root.putString(KEY_ID, id.full());
        root.putString(KEY_DEFINITION_REVISION, definitionRevision);
        root.putString(KEY_REVISION, fingerprint);
        writeRawNbt(stack, withRoot(rawNbt(stack), root));
    }

    /** 旧版本实例规则不再参与同步；下次同步时清理掉。 */
    public static boolean hasLegacySyncRules(ItemStack stack) {
        return rootOf(rawNbt(stack)).contains("exclude");
    }

    /** 移除编号管理节点，保留物品其它原生 CUSTOM_DATA。 */
    public static boolean unbind(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CompoundTag raw = rawNbt(stack);
        if (!raw.contains(ROOT_KEY)) return false;
        raw.remove(ROOT_KEY);
        writeRawNbt(stack, raw);
        return true;
    }

    public static CompoundTag fullNbt(ItemStack stack, @Nullable HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) return new CompoundTag();
        var ops = registries == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, registries);
        return (CompoundTag) ItemStack.CODEC.encodeStart(ops, stack).getOrThrow();
    }
}
