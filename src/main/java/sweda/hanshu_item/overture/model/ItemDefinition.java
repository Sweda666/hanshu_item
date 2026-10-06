package sweda.hanshu_item.overture.model;

import java.util.Objects;

/** 一个编号对应一种物品和它的原生数据组件 SNBT。 */
public record ItemDefinition(DefinitionId id, String icon, String nbt, SyncFlags syncFlags) {
    public static final String DEFAULT_ICON = "minecraft:stone";

    public ItemDefinition {
        Objects.requireNonNull(id, "id");
        icon = icon == null || icon.isBlank() ? DEFAULT_ICON : icon;
        nbt = nbt == null || nbt.isBlank() ? "{}" : nbt;
        syncFlags = syncFlags == null ? SyncFlags.none() : syncFlags;
    }

    public static ItemDefinition blank(DefinitionId id) {
        return new ItemDefinition(id, DEFAULT_ICON, "{}", SyncFlags.none());
    }

    public ItemDefinition withId(DefinitionId value) {
        return new ItemDefinition(value, icon, nbt, syncFlags);
    }

    public ItemDefinition withIcon(String value) {
        return new ItemDefinition(id, value, nbt, syncFlags);
    }

    public ItemDefinition withNbt(String value) {
        return new ItemDefinition(id, icon, value, syncFlags);
    }

    public ItemDefinition withSyncFlags(SyncFlags value) {
        return new ItemDefinition(id, icon, nbt, value);
    }

    public boolean sameContent(ItemDefinition other) {
        return equals(other);
    }
}