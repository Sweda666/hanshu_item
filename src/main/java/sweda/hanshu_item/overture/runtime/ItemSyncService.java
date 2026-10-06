package sweda.hanshu_item.overture.runtime;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.Hanshu_item;

import sweda.hanshu_item.overture.model.DefinitionId;
import sweda.hanshu_item.overture.model.ItemDefinition;
import sweda.hanshu_item.overture.model.Revision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 模板变化后同步编号物品的原生 NBT。覆盖在线玩家、打开的容器、已加载区块及掉落物；离线数据在下次加载时处理。 */
public final class ItemSyncService {

    /** 定时兜底的间隔（tick）。20 tick = 1 秒。 */
    public static final int TICK_INTERVAL = 100;

    /** 本次开机已经同步过的区块，避免每次区块加载都全量重扫。 */
    private static final Set<String> SYNCED_CHUNKS = ConcurrentHashMap.newKeySet();

    /** 文件监听与内存检测各自独立计数 —— 共用一个计数器会互相清零，见 {@link #onServerTick}。 */
    private static int fileTickCounter;
    private static int libraryTickCounter;

    /** 内存侧的变化检测：编辑器、命令这些内存提交走它。 */
    private static final LibraryWatcher WATCHER_LIBRARY = new LibraryWatcher();

    /** 文件侧的监听：管理员用文本编辑器改 JSON 走它。这是「文件即真相源」的执行体。 */
    private static DefinitionFileWatcher WATCHER = new DefinitionFileWatcher(null);

    /**
     * 定义对象 → 定义内容签名。用<b>标识</b>作键：定义不可变，改一次就是一个新对象。
     * 见 {@link #definitionRevision(ItemDefinition)}。访问一律在 {@code synchronized} 块里。
     */
    private static final Map<ItemDefinition, String> DEFINITION_REVISION_CACHE =
            new java.util.IdentityHashMap<>();

    /** 一次同步的结果。 */
    public record Result(int scanned, int updated, int failed, Map<String, Integer> byDefinition) {

        public static Result empty() {
            return new Result(0, 0, 0, Map.of());
        }

        public Result plus(Result other) {
            if (other.updated == 0 && other.scanned == 0) {
                return this;
            }
            Map<String, Integer> merged = new LinkedHashMap<>(byDefinition);
            other.byDefinition.forEach((key, value) -> merged.merge(key, value, Integer::sum));
            return new Result(scanned + other.scanned, updated + other.updated,
                    failed + other.failed, merged);
        }

        /** 记一次「扫到了且已更新」。 */
        public Result hit(String definitionId) {
            Map<String, Integer> merged = new LinkedHashMap<>(byDefinition);
            merged.merge(definitionId, 1, Integer::sum);
            return new Result(scanned + 1, updated + 1, failed, merged);
        }

        /** 记一次「扫到了但无需更新」。 */
        public Result miss() {
            return new Result(scanned + 1, updated, failed, byDefinition);
        }

        public Result failure() {
            return new Result(scanned + 1, updated, failed + 1, byDefinition);
        }

        public boolean changedAnything() {
            return updated > 0;
        }

        public String summary() {
            if (updated == 0 && failed == 0) {
                return "扫描 " + scanned + " 件，全部已是最新";
            }
            StringBuilder builder = new StringBuilder("扫描 ").append(scanned)
                    .append(" 件，更新 ").append(updated).append(" 件");
            if (!byDefinition.isEmpty()) {
                builder.append("：");
                byDefinition.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(6)
                        .forEach(entry -> builder.append(entry.getKey()).append('×')
                                .append(entry.getValue()).append(' '));
            }
            if (failed > 0) {
                builder.append("，失败 ").append(failed).append(" 件");
            }
            return builder.toString();
        }
    }

    private ItemSyncService() {
    }

    // --- 判定 ---------------------------------------------------------------

    /**
     * 判断一个物品是否落后于它的定义。
     *
     * <p>只比较模板和同步规则的签名，使用过程中弹药、耐久等状态变化不会触发重建。
     *
     * @return 过期时返回对应定义；无需更新或不受管理时返回空
     */
    public static Optional<ItemDefinition> outdatedDefinition(ItemStack stack) {
        ModItemLibrary library = Hanshu_item.library();
        if (library == null || stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        if (!ItemDataAccessor.isOvertureItem(stack)) {
            return Optional.empty();
        }
        DefinitionId id = ItemDataAccessor.definitionId(stack);
        if (id == null) {
            return Optional.empty();
        }
        ItemDefinition definition = library.find(id).orElse(null);
        if (definition == null) {
            // 定义被删了：物品还在，但不再受管理。别动它，别把玩家的东西改坏或销毁
            return Optional.empty();
        }
        String current = ItemDataAccessor.revision(stack);
        if (current == null || ItemDataAccessor.hasLegacySyncRules(stack)) {
            return Optional.of(definition);
        }

        // 先做一次廉价判断：生成这件物品时的那份定义，和现在这份是不是同一份。
        // 用存下来的 def 字段直接比字符串 —— 能挡掉绝大多数「定义改了」的情况，不必渲染整件物品。
        String storedDefinition = ItemDataAccessor.definitionRevision(stack);
        if (storedDefinition != null && !storedDefinition.equals(definitionRevision(definition))) {
            return Optional.of(definition);
        }
        // 定义没变，再比较编号 JSON 中规则的签名。
        return ModItemBuilder.needsUpdate(stack, definition)
                ? Optional.of(definition) : Optional.empty();
    }

    /** 按不可变定义对象缓存模板签名，避免重复编码。 */
    private static String definitionRevision(ItemDefinition definition) {
        // 取与「清空重建」放在同一个锁里：IdentityHashMap 不是线程安全的，
        // 而同步有可能被服务端主线程之外的调用碰到（命令、集成服的客户端线程）。
        synchronized (DEFINITION_REVISION_CACHE) {
            String cached = DEFINITION_REVISION_CACHE.get(definition);
            if (cached != null) {
                return cached;
            }
            if (DEFINITION_REVISION_CACHE.size() > 512) {
                DEFINITION_REVISION_CACHE.clear();
            }
            String computed = Revision.of(definition);
            DEFINITION_REVISION_CACHE.put(definition, computed);
            return computed;
        }
    }

    /**
     * 同步单个物品（就地改写）。
     *
     * @param context 用于解析占位符的玩家；可为 {@code null}（此时用物品自身的静态数据）
     * @return 是否发生了变化
     */
    public static boolean sync(ItemStack stack, @Nullable ServerPlayer context) {
        Optional<ItemDefinition> definition = outdatedDefinition(stack);
        if (definition.isEmpty()) {
            return false;
        }
        return rebuild(stack, definition.get(), context);
    }

    /** 无条件按定义重建（调用方已确认需要更新）。 */
    public static boolean rebuild(ItemStack stack, ItemDefinition definition, @Nullable ServerPlayer context) {
        try {
            ModItemBuilder.refresh(stack, definition, context);
            return true;
        } catch (RuntimeException e) {
            Hanshu_item.LOGGER.warn("同步物品失败（{}）: {}",
                    ItemDataAccessor.definitionId(stack), e.toString());
            return false;
        }
    }

    // --- 覆盖面 -------------------------------------------------------------

    /**
     * 扫一遍任意容器，把里面过期的物品更新掉。
     *
     * <p>这是所有「背包类」覆盖面的公共实现：玩家背包、箱子、桶、熔炉……
     * 全都可以看成一个 {@link Container}，区别只在于是谁在什么时候调它。
     * 抽出来还有个好处 —— 它能在没有玩家、没有世界的情况下被测（用一个内存容器就行）。
     *
     * @param container 要扫的容器
     * @param context   渲染时用的玩家上下文；可为 {@code null}
     * @return 本次扫描结果
     */
    public static Result sweepContainer(Container container, @Nullable ServerPlayer context) {
        Result result = Result.empty();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            Optional<ItemDefinition> definition = outdatedDefinition(stack);
            if (definition.isEmpty()) {
                continue;
            }
            if (rebuild(stack, definition.get(), context)) {
                container.setItem(slot, stack);
                result = result.hit(definition.get().id().full());
            } else {
                result = result.failure();
            }
        }
        if (result.changedAnything()) {
            container.setChanged();
        }
        return result;
    }

    /** 在线玩家：背包 36 格 + 护甲 + 副手 + 光标上的物品。 */
    public static Result syncPlayer(ServerPlayer player) {
        // 背包本身就是个 Container（含护甲与副手），直接复用同一段实现
        Result result = sweepContainer(player.getInventory(), player);

        // 光标上、合成格里的物品也要顾及：玩家把装备拖到光标上不该逃过同步
        ItemStack carried = player.containerMenu.getCarried();
        Optional<ItemDefinition> carriedDefinition = outdatedDefinition(carried);
        if (carriedDefinition.isPresent()) {
            if (rebuild(carried, carriedDefinition.get(), player)) {
                player.containerMenu.setCarried(carried);
                result = result.hit(carriedDefinition.get().id().full());
            } else result = result.failure();
        }
        if (result.changedAnything()) {
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) {
                player.containerMenu.broadcastChanges();
            }
        }
        return result;
    }

    /**
     * 玩家当前打开的界面。
     *
     * <p>遍历 {@link Slot} 而不是拿背后的 {@link Container}：这样连同其它模组自己实现的菜单
     * 一起覆盖了，而且改完直接生效、玩家当场看见数值变了。
     * 前 36 个槽位是玩家背包，已经由 {@link #syncPlayer} 处理，这里跳过以免重复计数。
     */
    public static Result syncMenu(AbstractContainerMenu menu, @Nullable ServerPlayer context) {
        Result result = Result.empty();
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            Optional<ItemDefinition> definition = outdatedDefinition(stack);
            if (definition.isEmpty()) {
                continue;
            }
            if (rebuild(stack, definition.get(), context)) {
                slot.set(stack);
                result = result.hit(definition.get().id().full());
            } else {
                result = result.failure();
            }
        }
        if (result.changedAnything()) {
            menu.broadcastChanges();
        }
        return result;
    }

    /** 一个容器方块实体（箱子、桶、熔炉…）。 */
    public static Result syncContainer(Container container, @Nullable ServerPlayer context) {
        Result result = Result.empty();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            Optional<ItemDefinition> definition = outdatedDefinition(stack);
            if (definition.isEmpty()) {
                continue;
            }
            if (rebuild(stack, definition.get(), context)) {
                container.setItem(slot, stack);
                result = result.hit(definition.get().id().full());
            } else {
                result = result.failure();
            }
        }
        if (result.changedAnything()) {
            container.setChanged();
        }
        return result;
    }

    /** 一个已加载区块里的所有容器。 */
    public static Result syncChunk(LevelChunk chunk, @Nullable ServerPlayer context) {
        Result result = Result.empty();
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (blockEntity instanceof Container container) {
                result = result.plus(syncContainer(container, context));
            }
        }
        return result;
    }

    /** 一个维度的所有掉落物。 */
    public static Result syncDroppedItems(ServerLevel level) {
        Result result = Result.empty();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            Optional<ItemDefinition> definition = outdatedDefinition(stack);
            if (definition.isEmpty()) {
                continue;
            }
            if (rebuild(stack, definition.get(), null)) {
                itemEntity.setItem(stack);
                result = result.hit(definition.get().id().full());
            } else {
                result = result.failure();
            }
        }
        return result;
    }

    /**
     * 一个维度能扫到的全部东西：掉落物 + 在线玩家及其界面 + <b>玩家周围的已加载区块</b>。
     *
     * <p>为什么绕一圈按玩家坐标取区块，而不是「遍历已加载区块」：26.1 的
     * {@code ServerChunkCache} <b>没有</b>暴露已加载区块的集合（只有 {@code getLoadedChunksCount()}），
     * 所以没有现成的列表可遍历。退而求其次用 {@link ServerChunkCache#getChunkNow(int, int)}：
     * 它对未加载区块返回 {@code null}，正好是我们要的语义，而且不会触发加载。
     * 代价是要自己给个范围 —— 取玩家的视野距离，超出视野的区块反正也不在内存里。
     */
    public static Result syncLevel(ServerLevel level) {
        Result result = syncDroppedItems(level);
        for (ServerPlayer player : level.players()) {
            result = result.plus(syncPlayer(player));
            result = result.plus(syncMenu(player.containerMenu, player));
            result = result.plus(syncChunksAround(level, player));
        }
        return result;
    }

    /** 玩家视野范围内所有<b>已加载</b>区块里的容器。 */
    private static Result syncChunksAround(ServerLevel level, ServerPlayer player) {
        Result result = Result.empty();
        int radius = Math.max(1, level.getServer().getPlayerList().getViewDistance());
        int centerX = player.chunkPosition().x();
        int centerZ = player.chunkPosition().z();
        Set<Long> visited = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                if (!visited.add(ChunkPos.pack(x, z))) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk != null) {
                    result = result.plus(syncChunk(chunk, player));
                }
            }
        }
        return result;
    }

    /** 全服同步：所有维度 + 所有在线玩家。{@code /hanshu sync} 的执行体。 */
    public static Result syncAll(MinecraftServer server) {
        Result result = Result.empty();
        for (ServerLevel level : server.getAllLevels()) {
            result = result.plus(syncLevel(level));
        }
        return result;
    }

    /** 只同步某个编码 ID 的物品（改完一个定义做定点同步，比全服扫便宜得多）。 */
    public static Result syncById(MinecraftServer server, DefinitionId id) {
        String target = id.full();
        Result total = Result.empty();

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ItemEntity itemEntity) {
                    Result one = syncOne(itemEntity.getItem(), target, null);
                    if (one.updated() > 0) {
                        itemEntity.setItem(itemEntity.getItem());
                    }
                    total = total.plus(one);
                }
            }
            // 玩家周围的已加载区块里的容器
            int radius = Math.max(1, server.getPlayerList().getViewDistance());
            Set<Long> visited = new LinkedHashSet<>();
            for (ServerPlayer player : level.players()) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int x = player.chunkPosition().x() + dx;
                        int z = player.chunkPosition().z() + dz;
                        if (!visited.add(ChunkPos.pack(x, z))) {
                            continue;
                        }
                        LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                        if (chunk == null) {
                            continue;
                        }
                        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                            if (blockEntity instanceof Container container) {
                                total = total.plus(syncContainerMatching(container, target, player));
                            }
                        }
                    }
                }
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                total = total.plus(syncOne(inventory.getItem(slot), target, player));
            }
            for (Slot slot : player.containerMenu.slots) {
                total = total.plus(syncOne(slot.getItem(), target, player));
            }
            player.inventoryMenu.broadcastChanges();
            if (player.containerMenu != player.inventoryMenu) {
                player.containerMenu.broadcastChanges();
            }
        }
        return total;
    }

    /** 容器里所有该编码的物品。 */
    private static Result syncContainerMatching(Container container, String target, @Nullable ServerPlayer context) {
        Result result = Result.empty();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !ItemDataAccessor.isOvertureItem(stack)) {
                continue;
            }
            Result one = syncOne(stack, target, context);
            if (one.updated() > 0) {
                container.setItem(slot, stack);
            }
            result = result.plus(one);
        }
        if (result.changedAnything()) {
            container.setChanged();
        }
        return result;
    }

    private static Result syncOne(ItemStack stack, String targetId, @Nullable ServerPlayer context) {
        if (stack.isEmpty() || !ItemDataAccessor.isOvertureItem(stack)) {
            return Result.empty();
        }
        DefinitionId id = ItemDataAccessor.definitionId(stack);
        if (id == null || !id.full().equals(targetId)) {
            return Result.empty();
        }
        Optional<ItemDefinition> definition = outdatedDefinition(stack);
        if (definition.isEmpty()) {
            return Result.empty().miss();
        }
        return rebuild(stack, definition.get(), context)
                ? Result.empty().hit(targetId)
                : Result.empty().failure();
    }

    // --- 事件钩子 -----------------------------------------------------------

    /** 区块加载：把这一区块的容器补同步一次（每个区块每次开机只做一次）。 */
    public static void onChunkLoad(LevelChunk chunk, ServerLevel level) {
        String key = chunkKey(level, chunk);
        if (!SYNCED_CHUNKS.add(key)) {
            return;
        }
        Result result = syncChunk(chunk, null);
        if (result.changedAnything()) {
            Hanshu_item.LOGGER.info("区块 {} 载入时同步：{}", key, result.summary());
        }
    }

    /** 区块卸载：忘掉记录，下次加载重新检查（期间定义可能又改了）。 */
    public static void onChunkUnload(LevelChunk chunk, ServerLevel level) {
        SYNCED_CHUNKS.remove(chunkKey(level, chunk));
    }

    /** 玩家登录：把离线期间落下的更新补上。 */
    public static void onPlayerLogin(ServerPlayer player) {
        Result result = syncPlayer(player);
        if (result.changedAnything()) {
            Hanshu_item.LOGGER.info("玩家 {} 登录时同步：{}", player.getName().getString(), result.summary());
        }
    }

    /** 玩家下线：写盘之前再补一次，尽量让存档里的数据是最新的。 */
    public static void onPlayerLogout(ServerPlayer player) {
        syncPlayer(player);
    }

    /**
     * 每 tick 的检查，两条路径：
     *
     * <ol>
     *   <li><b>外部改了文件</b>（这条是主路径）—— {@link DefinitionFileWatcher} 发现磁盘上的
     *       定义变了就重载，并告诉我们哪些编码受影响，只同步那些编码；</li>
     *   <li><b>内存里的定义变了</b> —— 编辑器保存等操作走的是内存提交，
     *       文件也跟着写了，但变化检测器可能先一步看到内存变化；这种情况做一次全服扫描。</li>
     * </ol>
     *
     * <p>为什么两条都要：文件是真相源，但内存里的改动（编辑器）不该等到 5 秒后的文件轮询才生效。
     */
    public static void onServerTick(MinecraftServer server) {
        // 两条路径各自计数。曾经共用同一个计数器，结果是它们互相把对方的计数清零 ——
        // 每 5 秒只有一条能跑，文件监听实际变成每 10 秒才轮询一次。
        // 这种「看起来能用但慢一倍」的 bug 不会报错，只会让人怀疑功能到底生效没有。
        if (++fileTickCounter >= TICK_INTERVAL) {
            fileTickCounter = 0;
            pollFiles(server);
        }
        if (++libraryTickCounter >= TICK_INTERVAL) {
            libraryTickCounter = 0;
            pollLibrary(server);
        }
    }

    /** 路径 1：磁盘上的定义文件被外部改了？只同步受影响的编码。这是「文件即真相源」的主路径。 */
    private static void pollFiles(MinecraftServer server) {
        DefinitionFileWatcher.ReloadOutcome outcome = WATCHER.poll();
        if (!outcome.applied()) {
            return;
        }
        Set<DefinitionId> changed = outcome.changed();
        if (changed.isEmpty()) {
            Hanshu_item.LOGGER.info("HanShu-Item [同步] 定义文件已重载，没有编码内容变化");
        } else if (changed.size() > MAX_TARGETED_IDS) {
            // 变了太多编码（典型情况：只改了展示方案，于是「所有编码」都算受影响）。
            // 这时逐个编码各扫一遍区块是 O(编码数 × 区块数) 的浪费 ——
            // 直接扫一次全服更划算，而且内容比对仍然会挡掉没有实际变化的物品。
            Result result = syncAll(server);
            Hanshu_item.LOGGER.info("HanShu-Item [同步] 定义文件改动涉及 {} 个编码，改为全服同步：{}",
                    changed.size(), result.summary());
        } else {
            Result result = syncByIds(server, changed);
            Hanshu_item.LOGGER.info("HanShu-Item [同步] 定义文件改动，已同步 {} 个编码：{}",
                    changed.size(), result.summary());
        }
        // 文件重载本身也算「定义变了」：让内存侧检测器把基线推平，免得下一轮又全服扫一次
        WATCHER_LIBRARY.invalidate();
        WATCHER_LIBRARY.changedSinceLastCheck();
    }

    /**
     * 定点同步的编码数上限。
     *
     * <p>超过它就不值得逐个编码扫了：每个编码都要重扫一遍「掉落物 + 玩家周围已加载区块」，
     * 而区块那一维是共享的 —— N 个编码扫 N 遍同一批区块，纯属重复劳动。
     * 64 是个保守值：真正的「改了一个定义」几乎总是 1~3 个编码，
     * 只有「展示方案变了」这种影响面覆盖全部的情况才会冲上来。
     */
    private static final int MAX_TARGETED_IDS = 64;

    /** 排查用：把文件轮询的判定打到日志。 */
    private static volatile boolean TRACE = false;

    /** 供命令/自检开启文件监听日志。 */
    public static void setTrace(boolean enabled) {
        TRACE = enabled;
        DefinitionFileWatcher.setTrace(enabled);
    }

    /** 是否开着文件监听日志。 */
    public static boolean traceEnabled() {
        return TRACE;
    }

    /** 供命令/自检读取当前文件监听状态。 */
    public static String describeWatcher() {
        return WATCHER.describeFiles() + " 磁盘[" + WATCHER.describeLive() + "]";
    }

    /** 立即做一次文件轮询（不等 5 秒间隔）—— 命令与自检用。 */
    public static DefinitionFileWatcher.ReloadOutcome pollFilesNow() {
        return WATCHER.poll();
    }

    /** 路径 2：内存里的定义被改了（编辑器 / 命令）？做全服扫描。 */
    private static void pollLibrary(MinecraftServer server) {
        if (!WATCHER_LIBRARY.changedSinceLastCheck()) {
            return;
        }
        Result result = syncAll(server);
        Hanshu_item.LOGGER.info("HanShu-Item [同步] 定义已变化，全服同步：{}", result.summary());
    }

    /** 服务端启动、定义库装好之后调用：绑定要盯的文件，并把当前状态记为基线。 */
    public static void bindFiles() {
        ModItemLibrary library = Hanshu_item.library();
        if (library == null) {
            return;
        }
        WATCHER = new DefinitionFileWatcher(library.definitionFile());
        WATCHER.syncBaseline();
        WATCHER_LIBRARY.invalidate();
        // 任何走 library.save() 的写盘都会回到这里更新基线，调用点不必自己记得通知
        library.onSave(saved -> noteOwnWrite());
        Hanshu_item.LOGGER.info("HanShu-Item [文件监听] 已开始监听定义文件：{}", WATCHER.describeFiles());
    }

    /** 服务端自己写过盘之后调用：把新指纹记为基线，免得把自己的写入当成外部改动。 */
    public static void noteOwnWrite() {
        WATCHER.syncBaseline();
    }

    /**
     * 保存定义库，并把结果文件记为「自己的写入」。
     *
     * <p>所有会写盘的地方都该走这个而不是直接 {@code library.save()}：
     * 少了这一步，服务器会在下一次文件轮询时把自己刚写的文件当成「外部改动了」，
     * 于是白白重载一次并同步一遍。功能上不算错，但日志会莫名其妙多一行，
     * 而且多了一次无谓的全服扫描。
     */
    public static void saveAndNote() throws java.io.IOException {
        ModItemLibrary library = Hanshu_item.library();
        if (library == null) {
            return;
        }
        library.save();
        noteOwnWrite();

        // 命令和编辑器都在这里完成定义写盘。立刻在服务端应用新模板，避免必须等待
        // 文件轮询（以及文件稳定性检查的下一轮）后玩家才看到变化。
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> {
                Result result = syncAll(server);
                if (result.changedAnything()) {
                    Hanshu_item.LOGGER.info("HanShu-Item [同步] 保存定义后立即同步：{}", result.summary());
                }
            });
        }
    }

    /** 服务端停机或重载定义后调用，清掉区块记录与变化检测，让变化重新被察觉。 */
    public static void resetChunkCache() {
        SYNCED_CHUNKS.clear();
        fileTickCounter = 0;
        libraryTickCounter = 0;
        WATCHER.reset();
        // 强制下一次检查认为「定义变了」：重载定义后就一定会做一次全服同步。
        WATCHER_LIBRARY.invalidate();
    }

    /**
     * 只同步指定的一批编码 —— 文件改动之后走这条，避免全服无差别扫描。
     *
     * <p>每个编码仍然要扫「掉落物 + 玩家周围已加载区块的容器」，
     * 但比「扫所有维度所有容器」省得多；而且内容没变的编码根本不会进来。
     */
    public static Result syncByIds(MinecraftServer server, java.util.Collection<DefinitionId> ids) {
        Result total = Result.empty();
        for (DefinitionId id : ids) {
            total = total.plus(syncById(server, id));
        }
        return total;
    }

    private static String chunkKey(ServerLevel level, LevelChunk chunk) {
        Identifier dimension = level.dimension().identifier();
        return dimension + "@" + chunk.getPos().x() + "," + chunk.getPos().z();
    }

    /** 区块坐标打包成一个 long，用于去重（同一玩家周围不同偏移可能指向同一区块）。 */
    private static long chunkPosKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // --- 供命令/调试使用 ----------------------------------------------------

    /** 供编辑器/命令展示：某物品的指纹与期望指纹是否一致。 */
    public static boolean fingerprintMatches(ItemStack stack, ItemDefinition definition) {
        String current = ItemDataAccessor.revision(stack);
        if (current == null) {
            return false;
        }
        ModItemLibrary library = Hanshu_item.library();
        if (library == null) {
            return false;
        }
        return current.equals(ModItemBuilder.expectedFingerprint(stack, definition));
    }

    /** 列出全部维度名，用于日志和命令回显。 */
    public static List<String> levelNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        server.getAllLevels().forEach(level -> names.add(level.dimension().identifier().toString()));
        return names;
    }

    /** 已记录区块数，用于自检与调试。 */
    public static int trackedChunkCount() {
        return SYNCED_CHUNKS.size();
    }

    /** 在完整游戏环境里验证编号物品能构建和同步且签名收敛。 */
    public static boolean selfTest() {
        var library = Hanshu_item.library();
        if (library == null) return true;
        int checked = 0;
        boolean passed = true;
        try {
            SavedItemComponentsCheck.checkNativeRefresh();
        } catch (RuntimeException | AssertionError e) {
            passed = false;
            Hanshu_item.LOGGER.warn("HanShu-Item [自检] 原生弹药排除: {}", e.toString());
        }
        for (var definition : library.view().items()) {
            try {
                ItemStack stack = ModItemBuilder.build(definition, null, 1).stack();
                ModItemBuilder.refresh(stack, definition, null);
                if (ModItemBuilder.needsUpdate(stack, definition)) throw new IllegalStateException("同步后仍判定过期");
                checked++;
            } catch (RuntimeException e) {
                passed = false;
                Hanshu_item.LOGGER.warn("HanShu-Item [自检] {}: {}", definition.id().full(), e.toString());
            }
        }
        Hanshu_item.LOGGER.info("HanShu-Item [自检] 原生 NBT 存取与同步：{} 个定义通过", checked);
        return passed;
    }
    public static Set<String> touchedDefinitions(Result result) {
        return new LinkedHashSet<>(result.byDefinition().keySet());
    }
}
