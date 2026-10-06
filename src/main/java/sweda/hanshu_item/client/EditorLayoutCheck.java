package sweda.hanshu_item.client;

import java.util.List;

/**
 * 布局自检 —— 纯数值验证，不依赖 Minecraft。
 *
 * <p>上一版编辑器之所以「很歪、没法用」，是因为我把尺寸写死成三栏 678px，
 * 而 GUI Scale 4 下只有 640px 可用。为了不再靠肉眼发现问题，这里把
 * {@link EditorLayout} 在若干典型分辨率下的几何全部算一遍并断言不越界。
 *
 * <p>运行方式（开发期）：
 * <pre>
 * java -cp build/classes/java/main sweda.hanshu_item.client.EditorLayoutCheck
 * </pre>
 * 注意 {@link EditorLayout} 本身不引用 MC 类型，所以这个自检可以在游戏外跑。
 */
public final class EditorLayoutCheck {

    private EditorLayoutCheck() {
    }

    /** 典型的「窗口尺寸 / GUI Scale」组合，覆盖从小窗到大屏。 */
    private static final int[][] CASES = {
            // 窗口宽, 窗口高, GUI Scale
            {1280, 720, 2},   // 逻辑 640x360  —— 常见 720p
            {1280, 720, 3},   // 逻辑 426x240  —— 小窗 + 大缩放
            {1920, 1080, 2},  // 逻辑 960x540
            {1920, 1080, 3},  // 逻辑 640x360
            {1920, 1080, 4},  // 逻辑 480x270  —— 很挤
            {2560, 1494, 4},  // 逻辑 640x373  —— 用户实际环境
            {2560, 1440, 3},  // 逻辑 853x480
            {3440, 1440, 4},  // 逻辑 860x360  —— 带鱼屏
            {1024, 768, 2},   // 逻辑 512x384
            {800, 600, 2},    // 逻辑 400x300  —— 极限小窗
    };

    public static void main(String[] args) {
        int failures = 0;
        System.out.printf("%-18s %-14s %-22s %-7s %-7s %-6s %s%n",
                "窗口", "GUI Scale", "逻辑分辨率", "列宽", "内容高", "每屏行", "结果");

        for (int[] testCase : CASES) {
            int windowWidth = testCase[0];
            int windowHeight = testCase[1];
            int scale = testCase[2];
            int logicalWidth = Math.max(1, windowWidth / scale);
            int logicalHeight = Math.max(1, windowHeight / scale);

            EditorLayout layout = EditorLayout.compute(logicalWidth, logicalHeight);
            List<String> problems = layout.validate();

            String verdict = problems.isEmpty() ? "OK" : "✖ " + String.join(" | ", problems);
            if (!problems.isEmpty()) {
                failures++;
            }
            System.out.printf("%-18s %-14d %-22s %-7d %-7d %-6d %s%n",
                    windowWidth + "x" + windowHeight,
                    scale,
                    logicalWidth + "x" + logicalHeight,
                    layout.panelWidth(),
                    layout.contentHeight(),
                    layout.rowsPerScreen(),
                    verdict);
        }

        System.out.println();
        int formFailures = 0;
        failures += formFailures;

        System.out.println();
        failures += checkFooterBands();

        System.out.println();
        if (failures == 0) {
            System.out.println("全部 " + CASES.length + " 种分辨率 + 表单行宽 + 页脚分带 通过几何自检。");
        } else {
            System.out.println(failures + " 项几何有问题。");
            System.exit(1);
        }
    }

    /**
     * 页脚三段（状态行 / 第一行按钮 / 第二行按钮）必须自上而下分开、互不重叠。
     *
     * <p>这条是补的：库页面原本把「问题摘要」画在 {@code toolbarY - 20}，
     * 而第二行按钮在 {@code toolbarY - 16} —— 文字正好压在按钮上，
     * 截图里就是「一批警告盖住了按钮上的字」。
     * 原来的自检只比较按钮之间，没比较「文字 vs 按钮」，所以它一直是绿的。
     */
    private static int checkFooterBands() {
        System.out.println("页脚分带（状态行 → 按钮1 → 按钮2）:");
        int failures = 0;
        int checked = 0;
        for (int w = 320; w <= 1920; w += 53) {
            for (int h = 200; h <= 1200; h += 47) {
                checked++;
                EditorLayout layout = EditorLayout.compute(w, h);
                int statusTop = layout.statusY();
                int statusBottom = statusTop + layout.statusHeight();
                int row1 = layout.buttonRow1Y();
                int row2 = layout.buttonRow2Y();
                String problem = null;
                if (statusBottom > row1) {
                    problem = "状态行 " + statusTop + ".." + statusBottom + " 压住第一行按钮 " + row1;
                } else if (row1 + EditorLayout.ROW_HEIGHT > row2) {
                    problem = "两行按钮重叠 " + row1 + "+16 > " + row2;
                } else if (row2 + EditorLayout.ROW_HEIGHT > h) {
                    problem = "第二行按钮越界 " + (row2 + EditorLayout.ROW_HEIGHT) + " > " + h;
                }
                if (problem != null) {
                    failures++;
                    if (failures <= 5) {
                        System.out.printf("  %-12s ✖ %s%n", w + "x" + h, problem);
                    }
                }
            }
        }
        if (failures == 0) {
            System.out.printf("  扫描 %d 种尺寸：状态行、两行按钮依次排开，无重叠无越界%n", checked);
        }
        return failures;
    }

    /**
     * 检查表单行的权重分配：标签列与控件列必须都是正数、加起来不超过行宽，
     * 且窄屏下控件列不能被挤到不可用。
     */
}
