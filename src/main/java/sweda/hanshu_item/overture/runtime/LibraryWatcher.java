package sweda.hanshu_item.overture.runtime;

/**
 * 监听「定义库被改了没有」。
 *
 * <p>这是整个自动同步的<b>触发器</b>：与其每 tick 无脑全服扫描（几千件物品、纯浪费），
 * 不如先问一句「定义变了吗」—— 没变就什么都不用做。
 *
 * <p>依据是 {@link ModItemLibrary#definitions()} 自带的库级签名（{@code libraryRevision()}），
 * 那是定义存储每次提交时算出来的内容签名；再带上展示方案表的哈希
 * —— 展示库换的是不可变快照，比它自己最省事。
 *
 * <p>两者都没动，就说明定义确实没变，可以放心跳过这一轮扫描。
 */
public final class LibraryWatcher {

    private int lastLibraryRevision = 0;

    private boolean primed;

    /**
     * 上一次检查之后，定义库是否发生了变化。
     *
     * <p>第一次调用总是返回 {@code true} —— 启动时本来就要做一次全服同步，
     * 把「初始化」和「变化」两条路径合成一条，少一个分支就少一处漏掉的机会。
     */
    public boolean changedSinceLastCheck() {
        ModItemLibrary library = sweda.hanshu_item.Hanshu_item.library();
        if (library == null) {
            return false;
        }
        int libraryRevision = library.definitions().libraryRevision().hashCode();


        if (primed && libraryRevision == lastLibraryRevision) {
            return false;
        }
        lastLibraryRevision = libraryRevision;

        primed = true;
        return true;
    }

    /** 直接标记为「已变化」。用在明确知道定义被改了的场合。 */
    public void invalidate() {
        primed = false;
    }

    /** 停机复位，避免集成服重开世界时沿用上一次的签名。 */
    public void reset() {
        invalidate();
    }
}
