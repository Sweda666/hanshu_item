package sweda.hanshu_item.overture.model;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 「一个文件被外部改了没有」的判定器 —— 不碰磁盘,只看传进来的指纹与内容。
 *
 * <h3>为什么单独抽出来</h3>
 * 这段逻辑决定了「什么时候去重载定义」,而它有两个必须做对的点,
 * 任何一个做错都不会报错,只会表现得「有时候生效有时候不生效」:
 *
 * <ol>
 *   <li><b>稳定性判定</b>:管理员用文本编辑器保存文件时,内容是<b>逐步</b>落盘的。
 *       服务器如果在写入过程中去读,会拿到截断到一半的 JSON。
 *       所以指纹一变不能立刻动,要等它<b>连续一次检查都没再变</b>,才认为写完了。</li>
 *   <li><b>内容去重</b>:指纹变了不代表内容变了 ——
 *       编辑器可能只是原样重写了一遍,或者只改了换行。
 *       只有内容真的不同才该重载,否则每次保存都会触发一次无谓的全服同步。</li>
 * </ol>
 *
 * <p>抽成纯类还有个直接好处:它能在不启动游戏、不碰磁盘的情况下被断言,
 * 而这两个点是整个「文件即真相源」定位最容易出问题的地方。
 */
public final class FileChangeDetector {

    /** 一次检查的结论。 */
    public enum Verdict {
        /** 文件没动（或还在写),什么都不用做。 */
        UNCHANGED,
        /** 文件已经写完,且内容与上次不同 —— 该重载了。 */
        RELOAD,
        /** 文件已经写完,但内容其实一样 —— 只更新基线,不要重载。 */
        SAME_CONTENT
    }

    private AtomicFiles.Stamp baseline = AtomicFiles.Stamp.MISSING;

    /** 待确认稳定的指纹;为 null 表示没在等。 */
    private AtomicFiles.Stamp pending;

    /** 上次实际采用的内容;用它做「内容真的变了吗」的比对。 */
    private String adoptedContent;

    /**
     * 用当前状态重置基线（启动时、以及自己写盘之后调用）。
     *
     * @param content 当前文件内容;文件不存在传 {@code null}
     */
    public void adopt(@Nullable AtomicFiles.Stamp stamp, @Nullable String content) {
        this.baseline = stamp == null ? AtomicFiles.Stamp.MISSING : stamp;
        this.pending = null;
        this.adoptedContent = content;
    }

    /**
     * 喂一次当前状态,拿到结论。
     *
     * @param currentStamp  当前指纹
     * @param currentContent 当前内容;读失败传 {@code null}
     */
    public Verdict check(@Nullable AtomicFiles.Stamp currentStamp, @Nullable String currentContent) {
        AtomicFiles.Stamp current = currentStamp == null ? AtomicFiles.Stamp.MISSING : currentStamp;

        if (!current.differsFrom(baseline)) {
            // 指纹没变:顺手清掉待定状态
            pending = null;
            return Verdict.UNCHANGED;
        }

        if (pending == null) {
            // 第一次发现变化 —— 可能正在写,记下来等下一轮
            pending = current;
            return Verdict.UNCHANGED;
        }
        if (pending.differsFrom(current)) {
            // 还在变（文件正在被写）
            pending = current;
            return Verdict.UNCHANGED;
        }

        // 连续两轮指纹一致:认为写完了
        baseline = current;
        pending = null;

        if (Objects.equals(adoptedContent, currentContent)) {
            return Verdict.SAME_CONTENT;
        }
        adoptedContent = currentContent;
        return Verdict.RELOAD;
    }

    /** 内容不同、但被拒绝采用时调用(例如 JSON 有错):记下它,别对同一份坏内容反复报错。 */
    public void rejectContent(@Nullable String content) {
        this.adoptedContent = content;
    }

    /** 上一次采用的内容。 */
    public @Nullable String adoptedContent() {
        return adoptedContent;
    }

    /** 基线指纹,便于日志与自检。 */
    public AtomicFiles.Stamp baseline() {
        return baseline;
    }

    public void reset() {
        adopt(AtomicFiles.Stamp.MISSING, null);
    }
}
