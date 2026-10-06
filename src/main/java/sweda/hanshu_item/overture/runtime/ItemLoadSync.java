package sweda.hanshu_item.overture.runtime;

import net.minecraft.world.Container;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 「物品一出现就同步」的入口 —— 对应需求：
 * <blockquote>当物品初始化时，如果其有编码 ID，则根据编码 ID 进行匹配，更新为同步储存的物品</blockquote>
 *
 * <h3>为什么最终没有做成「物品自己的回调」</h3>
 * 试过两条看起来更直接的路，都不成立，记在这里免得以后有人再走一遍：
 *
 * <ol>
 *   <li><b>{@code AttachCapabilitiesEvent.ItemStacks}</b> —— 它确实是在 {@code ItemStack}
 *       构造函数里 fire 的，但<b>是懒的</b>：Forge 的 {@code CapabilityProvider} 只在
 *       {@code isLazy} 为假、或者真的有人查询能力时才会 fire
 *       （见 {@code CapabilityProvider.gatherCapabilities} / {@code getCapabilities}）。
 *       而规范要求「没有能力提供者就不要 fire」，所以<b>只要没有别的模组查询这件物品的能力，
 *       这个事件永远不会触发</b>。挂上去等于什么都没挂 —— 这个坑是被启动自检当场抓出来的。</li>
 *   <li><b>自定义 {@code Item} 子类的 {@code inventoryTick}</b> —— 物品的 {@code Item}
 *       是由定义里的 {@code icon} 决定的<b>原版物品</b>（铁剑、钻石剑……），
 *       不是我们的 {@code managed_item}。所以那个回调根本不会被调用。</li>
 * </ol>
 *
 * <h3>实际做法：变化驱动的全容器扫描</h3>
 * 不做「每件物品自己知道自己过期了」，而是反过来 ——
 * <b>定义变了就把该扫的地方扫一遍</b>。扫描范围由 {@link ItemSyncService} 负责，
 * 本类只记录「上次扫描是什么时候、扫了多少」，供日志与自检查看。
 *
 * <p>{@code inventoryTick} 那条路虽然不成立，但它的<b>思路</b>是对的：
 * 物品在哪里、谁在用它，决定了什么时候该更新它。所以覆盖面是这样分的：
 *
 * <table>
 *   <tr><th>物品在哪</th><th>什么时候更新</th></tr>
 *   <tr><td>在线玩家背包 / 护甲 / 副手 / 光标</td><td>定义一变（下一个检查周期）立即扫</td></tr>
 *   <tr><td>掉落物</td><td>同上</td></tr>
 *   <tr><td>玩家打开着的容器</td><td>同上 + 打开容器的那一刻</td></tr>
 *   <tr><td>已加载区块里的容器</td><td>区块加载时，以及定义变化后的全服扫描</td></tr>
 *   <tr><td>离线玩家背包 / 未加载区块</td><td>它们下次出现时（登录 / 区块加载）</td></tr>
 * </table>
 */
public final class ItemLoadSync {

    private ItemLoadSync() {
    }

    /**
     * 把一个容器里的物品按编码追到最新定义。
     *
     * <p>对「物品初始化」这件事而言，这是最贴近的落点：
     * 无论物品是刚被反序列化出来、刚被塞进箱子、还是刚从玩家手里放到地上，
     * 只要它出现在一个我们能扫到的容器里，下一个检查周期就会被对上号。
     */
    public static ItemSyncService.Result sweep(Container container, @Nullable ServerPlayer context) {
        return ItemSyncService.sweepContainer(container, context);
    }

    /** 便于日志阅读：这个物品属于哪个编码。 */
    static String describe(net.minecraft.world.item.ItemStack stack) {
        var id = ItemDataAccessor.definitionId(stack);
        return id == null ? "<无编码>" : id.full();
    }
}
