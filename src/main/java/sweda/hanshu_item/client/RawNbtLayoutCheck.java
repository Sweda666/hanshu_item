package sweda.hanshu_item.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 「原始数据（树）编辑器」布局自检 —— 纯 Java、不依赖 Minecraft，可直接运行：
 * <pre>
 * java -cp build/sourceSets/main sweda.hanshu_item.client.RawNbtLayoutCheck
 * </pre>
 *
 * <p>为什么要单独查：这个编辑器的第一版把每一行的 Y 坐标写死在 {@code init()} 里
 * （工具栏 22、搜索框 26、树从 46 开始），结果三行压在一起 ——
 * 标题被工具栏盖住、搜索框横跨整行盖住按钮、物品名被压在搜索框下面。
 * 写死坐标的布局在作者那台机器上看着是对的，换个 GUI Scale 就塌，而且塌得不明显。
 *
 * <p>所以这里把「任何分辨率下都不重叠、不越界、都还有可用空间」写成断言。
 * 分辨率是按<b>逻辑像素</b>给的（MC 的 GUI 坐标 = 窗口尺寸 / GUI Scale），
 * 所以 2560×1494 在 Scale 4 下就是 640×373 —— 那才是真正要保证的尺寸。
 */
public final class RawNbtLayoutCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        try (var writer = new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setOut(writer);
            run();
        }
    }

    private static void run() {
        checkCommonScales();
        checkNoOverlapAcrossManySizes();
        checkNarrowScreenFoldsToolbar();
        checkBandsStayInsideScreen();
        checkDockedEdgeCases();
        checkTwoRowToolbar();
        printReferenceGeometry();

        System.out.println();
        if (failures == 0) {
            System.out.println("原始 NBT 编辑器布局自检全部通过。");
        } else {
            System.out.println("原始 NBT 编辑器布局自检失败 " + failures + " 项。");
            System.exit(1);
        }
    }

    /** 明确要两行工具栏时（按钮多的页面），几何同样必须合法。 */
    private static void checkTwoRowToolbar() {
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        for (int[] size : new int[][]{{320, 240}, {640, 373}, {960, 540}, {1920, 1017}}) {
            RawNbtLayout l = RawNbtLayout.compute(size[0], size[1], true);
            List<String> problems = l.validate();
            // 两行工具栏会挤掉树的高度，极矮的窗口允许「树面板高度不足」这一条
            List<String> hard = problems.stream()
                    .filter(p -> !p.contains("树面板高度不足"))
                    .toList();
            if (l.toolbarRows() != 2 || !hard.isEmpty()) {
                ok = false;
                detail.append("\n      ").append(size[0]).append('x').append(size[1])
                        .append(" rows=").append(l.toolbarRows()).append(' ').append(hard);
            }
        }
        report("两行工具栏几何合法", ok, ok ? "4 种尺寸通过" : detail.toString());
    }

    /**
     * 打印参考分辨率下的实际几何 —— 出问题时能直接对着数字看，不用再猜。
     *
     * <p>640×373 就是当初出问题的那台（2560×1494 窗口 / GUI Scale 4）。
     */
    private static void printReferenceGeometry() {
        System.out.println();
        System.out.println("参考几何：");
        for (int[] size : new int[][]{{640, 373}, {320, 240}}) {
            RawNbtLayout l = RawNbtLayout.compute(size[0], size[1]);
            System.out.printf("  %dx%d  标题 y=%d | 副标题 y=%d | 工具栏 %d 行 @y=%d | 搜索标签 x=%d,y=%d w=%d | "
                            + "搜索框 x=%d,y=%d w=%d | 树 y=%d h=%d | 状态行 y=%d | 按钮 y=%d%n",
                    size[0], size[1],
                    l.title().y(), l.subtitle().y(), l.toolbarRows(), l.toolbarRowY(0),
                    l.searchLabel().x(), l.searchLabel().y(), l.searchLabel().width(),
                    l.search().x(), l.search().y(), l.search().width(),
                    l.tree().y(), l.tree().height(), l.status().y(), l.buttons().y());
        }
    }

    // --- 用例 ---------------------------------------------------------------

    /** 几个真实场景的逻辑分辨率（含 2560×1494 @ Scale 4 = 640×373）。 */
    private static void checkCommonScales() {
        int[][] sizes = {
                {320, 240},   // 很小的窗口
                {427, 240},   // 854×480 @ Scale 2
                {640, 373},   // 2560×1494 @ Scale 4 ← 出问题的那台
                {854, 480},   // 1920×1080 @ Scale 2（近似）
                {960, 540},
                {1280, 720},
                {1920, 1017},
        };
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        for (int[] size : sizes) {
            RawNbtLayout layout = RawNbtLayout.compute(size[0], size[1]);
            List<String> problems = layout.validate();
            if (!problems.isEmpty()) {
                ok = false;
                detail.append("\n      ").append(size[0]).append('x').append(size[1])
                        .append(" → ").append(problems);
            }
        }
        report("常见分辨率几何合法", ok, ok ? sizes.length + " 种尺寸全部通过" : detail.toString());
    }

    /** 从很小到很大扫一遍宽度与高度，任何一个尺寸出问题都算失败。 */
    private static void checkNoOverlapAcrossManySizes() {
        List<String> broken = new ArrayList<>();
        int checked = 0;
        for (int w = 200; w <= 1600; w += 13) {
            for (int h = 140; h <= 1100; h += 17) {
                checked++;
                RawNbtLayout layout = RawNbtLayout.compute(w, h);
                List<String> problems = layout.validate();
                // 极窄极矮的窗口允许「树面板高度不足」，但不允许重叠或越界
                List<String> hard = problems.stream()
                        .filter(p -> !p.contains("树面板高度不足"))
                        .toList();
                if (!hard.isEmpty()) {
                    if (broken.size() < 5) {
                        broken.add(w + "x" + h + " → " + hard);
                    }
                }
            }
        }
        report("扫描 " + checked + " 种尺寸无重叠越界", broken.isEmpty(),
                broken.isEmpty() ? "全部通过" : String.join("; ", broken));
    }

    /** 窄屏时工具栏必须折行，而不是把按钮挤出屏幕。 */
    private static void checkNarrowScreenFoldsToolbar() {
        RawNbtLayout narrow = RawNbtLayout.compute(320, 240);
        RawNbtLayout wide = RawNbtLayout.compute(960, 540);
        boolean folded = narrow.toolbarRows() == 2 && narrow.toolbarFirstRowCount() == 3;
        boolean single = wide.toolbarRows() == 1 && wide.toolbarFirstRowCount() == 6;
        // 折行后第二行必须还在树面板上方
        int secondRowY = narrow.toolbarRowY(1);
        boolean fits = secondRowY + 20 <= narrow.search().y();
        report("窄屏折行 / 宽屏单行", folded && single && fits,
                "320×240 → " + narrow.toolbarRows() + " 行；960×540 → " + wide.toolbarRows() + " 行");
    }

    /** 每条带都必须完整落在屏幕内。 */
    private static void checkBandsStayInsideScreen() {
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        int[][] sizes = {{320, 240}, {640, 373}, {960, 540}};
        for (int[] size : sizes) {
            RawNbtLayout l = RawNbtLayout.compute(size[0], size[1]);
            for (var entry : List.of(
                    java.util.Map.entry("标题", l.title()),
                    java.util.Map.entry("副标题", l.subtitle()),
                    java.util.Map.entry("搜索标签", l.searchLabel()),
                    java.util.Map.entry("搜索框", l.search()),
                    java.util.Map.entry("树面板", l.tree()),
                    java.util.Map.entry("状态行", l.status()),
                    java.util.Map.entry("页脚按钮", l.buttons()))) {
                var band = entry.getValue();
                if (band.x() < 0 || band.y() < 0
                        || band.x() + band.width() > size[0]
                        || band.bottom() > size[1]) {
                    ok = false;
                    detail.append("\n      ").append(size[0]).append('x').append(size[1])
                            .append(' ').append(entry.getKey()).append(" 越界: ")
                            .append(band.x()).append(',').append(band.y()).append(' ')
                            .append(band.width()).append('x').append(band.height());
                }
            }
        }
        report("所有带都在屏幕内", ok, ok ? "3 种尺寸逐带检查通过" : detail.toString());
    }

    /** 退化尺寸不能抛异常、不能算出负高度。 */
    private static void checkDockedEdgeCases() {
        boolean ok = true;
        StringBuilder detail = new StringBuilder();
        int[][] sizes = {{1, 1}, {40, 40}, {80, 60}, {200, 140}, {4000, 3000}};
        for (int[] size : sizes) {
            try {
                RawNbtLayout l = RawNbtLayout.compute(size[0], size[1]);
                if (l.tree().height() <= 0 || l.buttons().height() <= 0 || l.search().width() <= 0) {
                    ok = false;
                    detail.append("\n      ").append(size[0]).append('x').append(size[1])
                            .append(" 出现非正尺寸: tree.h=").append(l.tree().height())
                            .append(" search.w=").append(l.search().width());
                }
            } catch (RuntimeException e) {
                ok = false;
                detail.append("\n      ").append(size[0]).append('x').append(size[1])
                        .append(" 抛异常: ").append(e);
            }
        }
        report("退化尺寸不崩且尺寸为正", ok, ok ? "含 1x1 与 4000x3000" : detail.toString());
    }

    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            System.out.printf("  OK   %-28s %s%n", name, detail);
        } else {
            failures++;
            System.out.printf("  FAIL %-28s %s%n", name, detail);
        }
    }
}
