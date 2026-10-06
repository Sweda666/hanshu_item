package sweda.hanshu_item.overture.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件监听逻辑的自检 —— 纯 Java（只碰一个临时文件），可直接运行：
 * <pre>
 * java -cp build/sourceSets/main sweda.hanshu_item.overture.model.FileChangeDetectorCheck
 * </pre>
 *
 * <p>「文件即真相源」这条定位能不能成立，全看这里的两件事有没有做对：
 * <ol>
 *   <li>服务器<b>不会读到写了一半的文件</b>；</li>
 *   <li>服务器<b>不会因为一次无意义的保存就全服重建</b>。</li>
 * </ol>
 * 两件事做错都不会抛异常，只会表现成「有时候生效有时候不生效」或者「一改配置就卡一下」——
 * 所以把它们写成断言，而不是靠手动改文件观察。
 */
public final class FileChangeDetectorCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        try (var writer = new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(writer);
            run();
        }
    }

    private static void run() throws IOException {
        checkNothingWhenUntouched();
        checkReloadAfterContentChanges();
        checkWaitsWhileStillBeingWritten();
        checkSameContentDoesNotReload();
        checkRejectedContentIsNotRetriedForever();
        checkMissingFileTreatedAsEmpty();
        checkAtomicWriteLeavesNoPartialFile();

        System.out.println();
        if (failures == 0) {
            System.out.println("文件监听自检全部通过。");
        } else {
            System.out.println("文件监听自检失败 " + failures + " 项。");
            System.exit(1);
        }
    }

    // --- 用例 ---------------------------------------------------------------

    /** 文件没动就什么都不做 —— 这是绝大多数轮询的结论，必须最便宜也最准确。 */
    private static void checkNothingWhenUntouched() {
        FileChangeDetector detector = new FileChangeDetector();
        AtomicFiles.Stamp stamp = new AtomicFiles.Stamp(1000L, 42L);
        detector.adopt(stamp, "{\"a\":1}");

        boolean allUnchanged = true;
        for (int i = 0; i < 10; i++) {
            allUnchanged &= detector.check(stamp, "{\"a\":1}") == FileChangeDetector.Verdict.UNCHANGED;
        }
        report("文件没动 → 不重载", allUnchanged, "连续 10 次检查都是 UNCHANGED");
    }

    /** 内容变了：第一次是「待定」，第二次确认稳定后才重载。 */
    private static void checkReloadAfterContentChanges() {
        FileChangeDetector detector = new FileChangeDetector();
        detector.adopt(new AtomicFiles.Stamp(1000L, 7L), "{\"a\":1}");

        AtomicFiles.Stamp changed = new AtomicFiles.Stamp(2000L, 7L);
        FileChangeDetector.Verdict first = detector.check(changed, "{\"a\":2}");
        FileChangeDetector.Verdict second = detector.check(changed, "{\"a\":2}");

        report("内容变了 → 稳定后重载",
                first == FileChangeDetector.Verdict.UNCHANGED && second == FileChangeDetector.Verdict.RELOAD,
                "第一次=" + first + "，第二次=" + second);
    }

    /**
     * 文件还在被写：指纹每轮都变，就一轮都不许动。
     *
     * <p>这一条是「不读到写了一半的 JSON」的核心保障。
     */
    private static void checkWaitsWhileStillBeingWritten() {
        FileChangeDetector detector = new FileChangeDetector();
        detector.adopt(new AtomicFiles.Stamp(1000L, 5L), "{}");

        // 模拟正在保存：大小与时间戳一轮一个样
        boolean neverReloaded = true;
        for (int i = 1; i <= 8; i++) {
            AtomicFiles.Stamp growing = new AtomicFiles.Stamp(1000L + i, 5L + i * 10L);
            neverReloaded &= detector.check(growing, "{\"a\":" + i) == FileChangeDetector.Verdict.UNCHANGED;
        }
        // 写完了：指纹连续两次相同，第二次才允许重载
        AtomicFiles.Stamp done = new AtomicFiles.Stamp(1009L, 105L);
        FileChangeDetector.Verdict firstSettled = detector.check(done, "{\"a\":9}");
        FileChangeDetector.Verdict settled = detector.check(done, "{\"a\":9}");

        report("写入过程中不重载",
                neverReloaded
                        && firstSettled == FileChangeDetector.Verdict.UNCHANGED
                        && settled == FileChangeDetector.Verdict.RELOAD,
                "8 轮增长期都没动，稳定后=" + settled);
    }

    /** 指纹变了但内容一样（编辑器原样重写）——不该重载，更不该全服同步。 */
    private static void checkSameContentDoesNotReload() {
        FileChangeDetector detector = new FileChangeDetector();
        String content = "{\"a\":1}";
        detector.adopt(new AtomicFiles.Stamp(1000L, 7L), content);

        // 时间戳变了，内容一模一样
        AtomicFiles.Stamp rewritten = new AtomicFiles.Stamp(5000L, 7L);
        detector.check(rewritten, content);
        FileChangeDetector.Verdict verdict = detector.check(rewritten, content);

        report("指纹变了但内容一样 → 不重载", verdict == FileChangeDetector.Verdict.SAME_CONTENT,
                "结论=" + verdict);
    }

    /**
     * 坏内容只报一次。
     *
     * <p>管理员改出一个语法错误的 JSON 之后，那个文件会一直躺在那里。
     * 如果不记下「这份坏内容已经报过了」，服务器会每 5 秒刷一次同样的错误日志。
     */
    private static void checkRejectedContentIsNotRetriedForever() {
        FileChangeDetector detector = new FileChangeDetector();
        detector.adopt(new AtomicFiles.Stamp(1000L, 5L), "{}");

        AtomicFiles.Stamp broken = new AtomicFiles.Stamp(2000L, 9L);
        FileChangeDetector.Verdict arm = detector.check(broken, "{broken");
        FileChangeDetector.Verdict firstTry = detector.check(broken, "{broken");
        // 重载失败，调用方把这份坏内容记为「已处理」
        detector.rejectContent("{broken");

        // 文件没再变：后续轮询都不该再要求重载
        boolean quiet = true;
        for (int i = 0; i < 5; i++) {
            FileChangeDetector.Verdict v = detector.check(broken, "{broken");
            if (v == FileChangeDetector.Verdict.RELOAD) {
                quiet = false;
            }
        }

        // 管理员改好了：必须能重新触发
        AtomicFiles.Stamp fixed = new AtomicFiles.Stamp(3000L, 7L);
        detector.check(fixed, "{\"a\":1}");
        FileChangeDetector.Verdict afterFix = detector.check(fixed, "{\"a\":1}");

        report("同一份坏内容不反复重载",
                arm == FileChangeDetector.Verdict.UNCHANGED
                        && firstTry == FileChangeDetector.Verdict.RELOAD && quiet
                        && afterFix == FileChangeDetector.Verdict.RELOAD,
                "首次=" + firstTry + "，之后 5 次未再触发=" + quiet + "，改好后=" + afterFix);
    }

    /** 文件被删掉等于空库 —— 这是正常操作，不该当成错误。 */
    private static void checkMissingFileTreatedAsEmpty() {
        FileChangeDetector detector = new FileChangeDetector();
        detector.adopt(new AtomicFiles.Stamp(1000L, 10L), "{\"a\":1}");

        AtomicFiles.Stamp missing = AtomicFiles.Stamp.MISSING;
        detector.check(missing, null);
        FileChangeDetector.Verdict verdict = detector.check(missing, null);

        report("文件被删 → 当成空库", verdict == FileChangeDetector.Verdict.RELOAD,
                "结论=" + verdict + "（null 内容视为空）");
    }

    /**
     * 原子写入：写到一半时目标文件里的内容必须还是<b>完整的旧内容</b>。
     *
     * <p>这一条直接验证 {@link AtomicFiles} 的核心承诺。做法是先写一份旧内容，
     * 再用原子写入覆盖，然后确认目录里没有留下会被误读的中间态文件。
     */
    private static void checkAtomicWriteLeavesNoPartialFile() throws IOException {
        Path dir = Files.createTempDirectory("hanshu-atomic-check");
        try {
            Path target = dir.resolve("items.json");
            String oldContent = "{\"old\":true}";
            String newContent = "{\"new\":true,\"pad\":\"" + "x".repeat(2000) + "\"}";

            AtomicFiles.writeString(target, oldContent);
            String afterFirst = Files.readString(target);
            AtomicFiles.writeString(target, newContent);
            String afterSecond = Files.readString(target);

            boolean noTempLeft = !Files.exists(AtomicFiles.tempPathFor(target));
            report("原子写入不留中间态",
                    oldContent.equals(afterFirst) && newContent.equals(afterSecond) && noTempLeft,
                    "两次写入内容都完整，临时文件已清理=" + noTempLeft);

            // 空目录里读不存在的文件应当得到 null 而不是异常
            report("读不存在的文件返回 null",
                    AtomicFiles.readStringIfExists(dir.resolve("nope.json")) == null,
                    "没有抛异常");
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录清不掉不影响断言结果
                }
            });
        }
    }

    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            System.out.printf("  OK   %-30s %s%n", name, detail);
        } else {
            failures++;
            System.out.printf("  FAIL %-30s %s%n", name, detail);
        }
    }
}
