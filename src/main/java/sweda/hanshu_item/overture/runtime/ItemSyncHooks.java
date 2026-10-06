package sweda.hanshu_item.overture.runtime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import sweda.hanshu_item.Hanshu_item;

/** 编号物品原生 NBT 同步的 Forge 生命周期钩子。 */
public final class ItemSyncHooks {

    private ItemSyncHooks() {
    }

    /** 由模组主类在构造阶段调用一次。 */
    public static void install() {
        // 登录：离线期间落下的更新在这里补
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ItemSyncService.onPlayerLogin(player);
            }
        });

        // 下线：写盘前最后补一次
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ItemSyncService.onPlayerLogout(player);
            }
        });

        // 区块加载：未加载区块里的容器在这里才第一次见到
        ChunkEvent.Load.BUS.addListener(event -> {
            if (event.getLevel() instanceof ServerLevel level
                    && event.getChunk() instanceof LevelChunk chunk) {
                ItemSyncService.onChunkLoad(chunk, level);
            }
        });

        // 区块卸载：清记录，下次加载重查
        ChunkEvent.Unload.BUS.addListener(event -> {
            if (event.getLevel() instanceof ServerLevel level
                    && event.getChunk() instanceof LevelChunk chunk) {
                ItemSyncService.onChunkUnload(chunk, level);
            }
        });

        // 打开容器：当场同步，玩家立刻看到新数值
        PlayerContainerEvent.Open.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ItemSyncService.Result result = ItemSyncService.syncMenu(event.getContainer(), player);
                if (result.changedAnything()) {
                    Hanshu_item.LOGGER.info("玩家 {} 打开容器时同步：{}",
                            player.getName().getString(), result.summary());
                }
            }
        });

        // 每 tick 检查定义有没有变 —— 变了就全服同步。这是自动更新的主路径。
        // 物品自身被铁砧、第三方模组或交互逻辑改写时，编号签名不会变化，文件监听也不会触发。
        // 定期检查在线玩家背包，确保这类漂移在短时间内恢复；排除路径仍由同步合并逻辑保留。
        TickEvent.PlayerTickEvent.Post.BUS.addListener(event -> {
            if (event.player() instanceof ServerPlayer player && player.tickCount % 10 == 0) {
                ItemSyncService.syncPlayer(player);
            }
        });

        TickEvent.ServerTickEvent.Post.BUS.addListener(event ->
                ItemSyncService.onServerTick(event.server()));
    }
}
