package sweda.hanshu_item;

import com.mojang.logging.LogUtils;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import sweda.hanshu_item.overture.command.OvertureCommands;
import sweda.hanshu_item.overture.runtime.*;
import java.io.IOException;
import java.nio.file.Path;

/** 编号物品的原生 NBT 存储、取出和同步入口。 */
@Mod(Hanshu_item.MODID)
public class Hanshu_item {
    public static final String MODID = "hanshu_item";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static ModItemLibrary library;

    public Hanshu_item(FMLJavaModLoadingContext context) {
        ModItems.register(context.getModBusGroup());
        library = new ModItemLibrary(MODID, configDirectory());
        ItemSyncHooks.install();
        RegisterCommandsEvent.BUS.addListener(event -> OvertureCommands.register(event.getDispatcher()));
        ServerStartedEvent.BUS.addListener(this::onServerStarted);
        ServerStoppingEvent.BUS.addListener(this::onServerStopping);
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    public static Path configDirectory() { return FMLPaths.CONFIGDIR.get().resolve(MODID); }
    public static ModItemLibrary library() { return library; }

    private void onServerStarted(ServerStartedEvent event) {
        try {
            var result = library.load();
            result.issues().forEach(issue -> LOGGER.warn("HanShu-Item: {}", issue));
            LOGGER.info("HanShu-Item: 已加载 {} 个原生 NBT 编号物品", result.itemCount());
            ItemSyncService.bindFiles();
            ItemSyncService.selfTest();
        } catch (IOException e) {
            LOGGER.error("HanShu-Item: 读取编号物品定义失败", e);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        try { library.save(); }
        catch (IOException e) { LOGGER.error("HanShu-Item: 保存编号物品定义失败", e); }
        ItemSyncService.resetChunkCache();
    }
}