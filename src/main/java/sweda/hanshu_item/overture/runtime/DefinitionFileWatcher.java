package sweda.hanshu_item.overture.runtime;

import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.Hanshu_item;
import sweda.hanshu_item.overture.model.AtomicFiles;
import sweda.hanshu_item.overture.model.DefinitionId;
import sweda.hanshu_item.overture.model.DefinitionIssue;
import sweda.hanshu_item.overture.model.FileChangeDetector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 盯着定义文件，外部一改就自动重载。
 *
 * <p>这是「<b>文件即真相源</b>」这条定位的执行体：管理员不需要执行任何命令，
 * 用文本编辑器改完 {@code items/<namespace>/<path>.json} 保存，服务器自己就会把新定义读进来、
 * 并同步到所有同编码的物品。
 *
 * <h3>为什么不注册 WatchService</h3>
 * {@code WatchService} 看起来更「正确」，但在这个场景里有两个麻烦：
 * <ul>
 *   <li>它<b>会漏事件也会重复</b>：很多编辑器保存时是「写临时文件 + 改名」，
 *       不同平台上收到的可能是 CREATE、MODIFY、DELETE 的任意组合；</li>
 *   <li>事件到达的时机与「文件已经写完」没有关系，仍然要做稳定判定。</li>
 * </ul>
 * 而轮询的代价其实很低 —— 两次 {@code stat} 系统调用，每 {@link #POLL_INTERVAL} tick 一次。
 * 本项目已经有一个每 tick 的检查循环，顺手带上就行，不必再引入一个后台线程与它的生命周期问题。
 *
 * <h3>三道防线</h3>
 * 全部落在 {@link FileChangeDetector} 里（稳定性、内容去重），本类只负责
 * 「读文件 → 交给判定器 → 需要时重载 → 算出受影响的编码」。
 * 读失败与解析失败都保留旧快照，且同一份坏内容只报一次错。
 */
public final class DefinitionFileWatcher {

    /** 轮询间隔（tick）。20 tick = 1 秒；100 tick = 5 秒。 */
    public static final int POLL_INTERVAL = 100;

    /** 一次重载的结果。 */
    public record ReloadOutcome(boolean applied, Set<DefinitionId> changed, List<DefinitionIssue> issues) {

        public static ReloadOutcome nothing() {
            return new ReloadOutcome(false, Set.of(), List.of());
        }

        public boolean hasErrors() {
            return issues.stream().anyMatch(issue -> issue.severity() == DefinitionIssue.Severity.ERROR);
        }
    }

    private final Path definitionFile;

    private final FileChangeDetector definitionDetector = new FileChangeDetector();


    private boolean primed;

    public DefinitionFileWatcher(@Nullable Path definitionFile) {
        this.definitionFile = definitionFile;

    }

    /** 排查用：确认这个监听器到底在盯哪个文件。 */
    public String describeFiles() {
        return "items=" + definitionFile
                + ", primed=" + primed + ", " + describeBaseline();
    }

    /** 记下当前文件状态作为基线 —— 启动时、以及模组自己刚写过盘之后调用。 */
    public void syncBaseline() {
        definitionDetector.adopt(AtomicFiles.Stamp.of(definitionFile), readQuietly(definitionFile));

        primed = true;
    }

    /**
     * 做一次文件判定。间隔控制由调用方负责（{@code ItemSyncService.onServerTick} 已经按 tick 计数限频）。
     *
     * <p>这里<b>刻意不再自带一个 tick 计数器</b>。之前两边各有一个，看似双保险，
     * 实际是「外层每 100 tick 调一次 → 内层才 +1」，内层要凑满 100 需要 10000 tick ——
     * 结果文件改动要等八分钟才被发现。功能「能用」，只是慢得离谱，最难查。
     * 限频只能有一处，多了就是乘法。
     *
     * @return 本次是否执行了重载，以及哪些编码受影响
     */
    public ReloadOutcome poll() {
        if (!primed) {
            syncBaseline();
            return ReloadOutcome.nothing();
        }

        // 两个文件各自判定。注意即使只有一个该重载，也要把另一个也喂一遍 ——
        // 否则那个文件的「待定」状态会一直挂着，等它真的写完时反而被判成还在变。
        FileChangeDetector.Verdict definitionVerdict = definitionDetector.check(
                AtomicFiles.Stamp.of(definitionFile), readQuietly(definitionFile));
        if (definitionVerdict != FileChangeDetector.Verdict.RELOAD) return ReloadOutcome.nothing();
        return reload();
    }

    /** 排查用：把每轮轮询的判定打到日志（默认关，需要时调 {@link #setTrace}）。 */
    private static volatile boolean TRACE = false;

    public static void setTrace(boolean enabled) {
        TRACE = enabled;
    }

    /** 内容确实变了才走到这里。 */
    private ReloadOutcome reload() {
        ModItemLibrary library = Hanshu_item.library();
        if (library == null || library.directory() == null) {
            return ReloadOutcome.nothing();
        }

        Set<DefinitionId> before = new LinkedHashSet<>(library.definitions().definitions().keySet());
        try {
            ModItemLibrary.CommitResult result = library.load();
            if (!result.accepted()) {
                // 保留旧快照（load 内部已保证），报告一次；同一份坏内容不会每 5 秒重报
                Hanshu_item.LOGGER.warn("HanShu-Item [文件重载] 文件有 {} 个错误，已保留旧定义（现有物品不受影响）",
                        result.errors().size());
                result.errors().forEach(issue ->
                        Hanshu_item.LOGGER.warn("HanShu-Item [文件重载] {}", issue));
                return new ReloadOutcome(false, Set.of(), result.issues());
            }
            Set<DefinitionId> changed = result.changedIds().isEmpty()
                    ? diffAll(before, library.definitions().definitions().keySet())
                    : new LinkedHashSet<>(result.changedIds());
            Hanshu_item.LOGGER.info("HanShu-Item [文件重载] 已读取磁盘上的编号物品：{} 个定义{}",
                    result.itemCount(),
                    changed.isEmpty() ? "" : "，" + changed.size() + " 个编码内容有变");
            return new ReloadOutcome(true, changed, result.issues());
        } catch (IOException | RuntimeException e) {
            Hanshu_item.LOGGER.warn("HanShu-Item [文件重载] 读取失败，已保留旧定义：{}", e.toString());
            return new ReloadOutcome(false, Set.of(), List.of());
        }
    }

    /**
     * 兜底：{@code changedIds} 为空时（展示方案变了但定义没变）要怎么划范围。
     *
     * <p>展示方案会影响所有物品的外观，所以答案是「全部编码都要过一遍」——
     * 但每个物品的内容比对仍然会挡掉没有实际变化的那些，不会白白重建。
     */
    private static Set<DefinitionId> diffAll(Set<DefinitionId> before, Set<DefinitionId> after) {
        Set<DefinitionId> all = new LinkedHashSet<>(after);
        all.addAll(before);
        return all;
    }

    private static @Nullable String readQuietly(Path path) {
        try {
            if (path != null && !java.nio.file.Files.exists(path)
                    && "items".equals(path.getFileName().toString())) {
                path = path.resolveSibling("items.json");
            }
            if (path != null && java.nio.file.Files.isDirectory(path)) {
                StringBuilder snapshot = new StringBuilder();
                try (var stream = java.nio.file.Files.walk(path)) {
                    for (Path file : stream.filter(java.nio.file.Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted().toList()) {
                        snapshot.append(path.relativize(file).toString().replace('\\', '/'))
                                .append('\n')
                                .append(java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8))
                                .append('\n');
                    }
                }
                return snapshot.toString();
            }
            return AtomicFiles.readStringIfExists(path);
        } catch (IOException e) {
            return null;
        }
    }

    /** 便于自检与调试。 */
    public String describeBaseline() {
        return "items=" + definitionDetector.baseline();
    }

    /** 现在从磁盘读到的指纹（与基线对比就知道「变化有没有被发现」）。 */
    public String describeLive() {
        return "items=" + AtomicFiles.Stamp.of(definitionFile);
    }

    /** 停机复位。 */
    public void reset() {
        definitionDetector.reset();

        primed = false;
    }
}
