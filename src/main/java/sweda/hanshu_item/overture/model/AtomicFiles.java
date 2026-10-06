package sweda.hanshu_item.overture.model;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 定义文件的原子读写。
 *
 * <h3>为什么必须原子</h3>
 * 这个模组的定位是<b>文件即真相源</b>：管理员直接用文本编辑器改 JSON，服务器盯着文件自动重载。
 * 那就有一个必须处理的情况 —— <b>服务器正好在文件写到一半时去读它</b>。
 *
 * <p>直接 {@code Files.writeString} 覆盖原文件时，目标文件会先被截断再逐段写入。
 * 一个每秒轮询文件的服务端有相当大的概率读到「截断到一半的 JSON」，
 * 结果是一次解析失败，而且失败得很随机（有时候好有时候坏，取决于时序），
 * 是最难查的那类问题。
 *
 * <p>做法是业界通用的 <b>写临时文件 + 原子改名</b>：
 * <ol>
 *   <li>把新内容写进同目录下的 {@code xxx.json.tmp}；</li>
 *   <li>{@code fsync}，确保内容真的落盘而不是停在页缓存里；</li>
 *   <li>{@code Files.move(..., ATOMIC_MOVE)} 覆盖目标 —— 这一步在同一个文件系统内是原子的。</li>
 * </ol>
 * 于是任何时刻去读目标文件，看到的要么是完整的旧内容，要么是完整的新内容，
 * 不存在「一半」这个中间状态。
 *
 * <p>临时文件放在<b>同目录</b>而不是系统临时目录：{@code ATOMIC_MOVE} 只在同一个文件系统内成立，
 * 跨盘移动会退化成「复制 + 删除」，那就不原子了。
 */
public final class AtomicFiles {

    /** 临时文件后缀。 */
    public static final String TEMP_SUFFIX = ".tmp";

    private AtomicFiles() {
    }

    /**
     * 原子地把 {@code content} 写入 {@code target}。
     *
     * @throws IOException 写临时文件或改名失败
     */
    public static void writeString(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = tempPathFor(target);

        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                // 落盘：否则断电/崩溃时可能留下一个内容为空的新文件
                channel.force(true);
            }
            moveInto(temp, target);
        } catch (IOException | RuntimeException e) {
            // 失败时清掉临时文件，免得目录里越积越多
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 删除失败不影响主流程，原始异常更重要
            }
            throw e;
        }
    }

    /** 临时文件路径（与目标同目录）。 */
    public static Path tempPathFor(Path target) {
        return target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
    }

    private static void moveInto(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // 有些文件系统（部分网络盘、老式 FAT）不支持原子改名。
            // 退化成普通替换：仍然比「直接覆盖」安全得多，因为内容已经完整落盘，
            // 窗口缩小到只剩 rename 这一步。
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 读取文件内容；文件不存在时返回 {@code null}（而不是抛异常）。
     *
     * <p>「文件不存在」在这个模组里是正常状态 —— 管理员可以删掉 items.json 表示空库。
     */
    public static @Nullable String readStringIfExists(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 文件的「指纹」：最后修改时间 + 大小。
     *
     * <p>用来判断文件有没有被外部改过。只看修改时间不够 ——
     * 有些编辑器保存时不更新毫秒精度的时间戳，或者同一秒内改了两次；
     * 带上大小能挡住其中一部分。真正的保险是读文件内容比对（见 {@code DefinitionFileWatcher}），
     * 这里只是第一层过滤，避免每次都去读盘。
     */
    public record Stamp(long modifiedMillis, long size) {

        public static final Stamp MISSING = new Stamp(-1L, -1L);

        public static Stamp of(Path path) {
            if (path != null && !Files.exists(path)
                    && "items".equals(path.getFileName().toString())
                    && Files.exists(path.resolveSibling("items.json"))) {
                path = path.resolveSibling("items.json");
            }
            if (path == null || !Files.exists(path)) {
                return MISSING;
            }
            try {
                if (Files.isDirectory(path)) {
                    // 目录本身的时间戳在“目录内文件被编辑”时不一定变化。
                    // 用所有 JSON 文件的路径、大小和修改时间合成目录指纹，
                    // 这样单文件布局也能被轮询监听可靠捕获。
                    long hash = 1125899906842597L;
                    long size = 0L;
                    try (var stream = Files.walk(path)) {
                        for (Path child : stream.filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().endsWith(".json"))
                                .sorted().toList()) {
                            hash = 31L * hash + path.relativize(child).toString().hashCode();
                            hash = 31L * hash + Files.getLastModifiedTime(child).toMillis();
                            long childSize = Files.size(child);
                            hash = 31L * hash + childSize;
                            // 同一毫秒内原样长度改写也要被发现，不能只依赖文件时间戳。
                            hash = 31L * hash + java.util.Arrays.hashCode(Files.readAllBytes(child));
                            size += childSize;
                        }
                    }
                    return new Stamp(hash & Long.MAX_VALUE, size);
                }
                return new Stamp(Files.getLastModifiedTime(path).toMillis(), Files.size(path));
            } catch (IOException e) {
                return MISSING;
            }
        }

        public boolean isMissing() {
            return modifiedMillis < 0;
        }

        public boolean differsFrom(Stamp other) {
            return other == null || modifiedMillis != other.modifiedMillis || size != other.size;
        }
    }
}
