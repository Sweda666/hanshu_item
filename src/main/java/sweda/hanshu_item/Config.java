package sweda.hanshu_item;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/** 编辑页面默认关闭，编号物品指令及同步独立工作。 */
@Mod.EventBusSubscriber(modid = Hanshu_item.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec.BooleanValue ENABLE_EDITOR = BUILDER
            .comment("Enable the in-game NBT editor (K) and item picker (L). Disabled by default.",
                    "原生 NBT 编辑页面开关（K / L），默认关闭。")
            .define("enableEditor", false);
    static final ForgeConfigSpec SPEC = BUILDER.build();
    public static volatile boolean enableEditor = false;

    @SubscribeEvent
    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) enableEditor = ENABLE_EDITOR.get();
    }
}