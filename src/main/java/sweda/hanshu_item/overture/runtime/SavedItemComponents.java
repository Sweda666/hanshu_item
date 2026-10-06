package sweda.hanshu_item.overture.runtime;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.overture.model.*;

/** 原生组件的 SNBT 编解码，保留 NBT 类型、long 精度和组件移除标记。 */
public final class SavedItemComponents {
    private SavedItemComponents() {}

    public static ItemDefinition capture(ItemStack stack, DefinitionId id, ModItemLibrary library,
                                          HolderLookup.Provider registries) {
        if (stack.isEmpty()) throw new IllegalArgumentException("主手没有物品");
        ItemDefinition origin = library.find(id).orElse(null);
        return new ItemDefinition(id, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                encode(stack.getComponentsPatch(), registries).toString(),
                origin == null ? SyncFlags.none() : origin.syncFlags());
    }

    public static CompoundTag encode(DataComponentPatch patch, @Nullable HolderLookup.Provider registries) {
        var ops = registries == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, registries);
        CompoundTag encoded = ((CompoundTag) DataComponentPatch.CODEC.encodeStart(ops, patch).getOrThrow()).copy();
        encoded.getCompound("minecraft:custom_data").ifPresent(tag -> tag.remove(ItemDataAccessor.ROOT_KEY));
        for (String key : java.util.List.of("hanshu_item:owner", "hanshu_item:revision",
                "!hanshu_item:owner", "!hanshu_item:revision")) encoded.remove(key);
        return encoded;
    }

    public static DataComponentPatch decode(String saved, @Nullable HolderLookup.Provider registries) {
        return decode(parse(saved), registries);
    }

    public static CompoundTag parse(String saved) {
        try { return TagParser.parseCompoundFully(saved); }
        catch (CommandSyntaxException e) { throw new IllegalArgumentException("组件 SNBT 格式不正确", e); }
    }

    public static DataComponentPatch decode(CompoundTag saved, @Nullable HolderLookup.Provider registries) {
        var ops = registries == null ? NbtOps.INSTANCE : RegistryOps.create(NbtOps.INSTANCE, registries);
        return DataComponentPatch.CODEC.parse(ops, saved).getOrThrow();
    }
}
