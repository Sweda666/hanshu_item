package sweda.hanshu_item.overture.runtime;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import sweda.hanshu_item.Hanshu_item;


/** 保留已有的注册名，兼容旧存档中的物品及管理组件；新编号数据使用原生 NBT。 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, Hanshu_item.MODID);

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Hanshu_item.MODID);

    
    public static final RegistryObject<Item> MANAGED_ITEM =
            ITEMS.register("managed_item",
                    () -> new Item(new Item.Properties().setId(ITEMS.key("managed_item"))));

    
    public static final RegistryObject<DataComponentType<String>> OWNER =
            COMPONENTS.register("owner", () -> DataComponentType.<String>builder()
                    .persistent(com.mojang.serialization.Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    
    public static final RegistryObject<DataComponentType<String>> REVISION =
            COMPONENTS.register("revision", () -> DataComponentType.<String>builder()
                    .persistent(com.mojang.serialization.Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    private ModItems() {
    }

    
    public static void register(net.minecraftforge.eventbus.api.bus.BusGroup modBusGroup) {
        ITEMS.register(modBusGroup);
        COMPONENTS.register(modBusGroup);
    }
}
