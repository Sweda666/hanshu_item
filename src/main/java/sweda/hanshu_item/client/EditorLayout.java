package sweda.hanshu_item.client;

/**
 * 编辑器布局计算 —— 所有尺寸都由屏幕尺寸推导，不写死像素。
 *
 * <p>为什么要单独抽出来：MC 的界面坐标是「逻辑像素」，实际可用空间 = 窗口尺寸 / GUI Scale。
 * 一台 2560×1494、GUI Scale=4 的机器只有 <b>640×373</b> 逻辑像素 —— 之前那版三栏布局
 * 硬编码需要 678px，右侧编辑区直接被挤出屏幕，这就是「GUI 很歪、没法用」的原因。
 *
 * <p>所以这里采用和原版菜单一致的策略：
 * <ul>
 *   <li><b>纵向单列</b>，主内容区居中</li>
 *   <li>列宽 = min(可用宽度, 上限)，保证窄屏也放得下</li>
 *   <li>字段行高固定（16px 输入框 + 4px 间距），内容超出时靠分页而不是硬塞</li>
 * </ul>
 *
 * <p>本类刻意做成纯函数，{@link #compute} 不依赖任何 MC 类型，方便单独核对几何是否越界。
 */
public record EditorLayout(int screenWidth,
                           int screenHeight,
                           int titleY,
                           int contentTop,
                           int contentBottom,
                           int panelX,
                           int panelWidth,
                           int toolbarY,
                           int toolbarHeight) {

    /** 输入框/按钮的标准高度。 */
    public static final int ROW_HEIGHT = 16;

    /** 两行之间的垂直间距。 */
    public static final int ROW_GAP = 4;

    /** 内容面板相对左右边缘的最小留白。 */
    private static final int MIN_MARGIN = 8;

    /** 内容列的最大宽度：太宽反而难读，也让输入框不至于长得离谱。 */
    private static final int MAX_COLUMN_WIDTH = 340;

    /** 标题占用的高度。 */
    private static final int TITLE_HEIGHT = 14;

    /**
     * 底部工具条高度：必须容纳「状态行 + 两行按钮」且互不重叠。
     * 14（状态行）+ 2 + 16（第一行按钮）+ 2 + 16（第二行按钮）+ 2（下边距）= 52
     */
    private static final int TOOLBAR_HEIGHT = 52;

    /** 状态文本行（在按钮之上）。 */
    public static final int STATUS_Y_OFFSET = -50;

    /** 工具条第一行按钮相对 toolbarY 的偏移。 */
    public static final int BUTTON_ROW_1_OFFSET = -34;

    /** 工具条第二行按钮相对 toolbarY 的偏移。 */
    public static final int BUTTON_ROW_2_OFFSET = -16;

    /** 字段面板相对 toolbarY 的收尾偏移（留出状态行）。 */
    public static final int FIELDS_BOTTOM_OFFSET = -36;

    /** 面板内边距。 */
    public static final int PADDING = 6;

    /**
     * 状态行与第一行按钮之间要留的空气。
     *
     * <p>状态行是文字（高 8），按钮是控件（高 16），两者各自有自己的基线。
     * 之前把「问题摘要」画在 {@code footerY() - 10}，而 footerY 是 {@code toolbarY - 10}，
     * 于是它落在 {@code toolbarY - 20} —— 正好压在第二行按钮（{@code toolbarY - 16}）上。
     * 现在把状态行定义成一条明确的带，并断言它不与按钮行重叠。
     */
    private static final int STATUS_HEIGHT = 10;

    public static EditorLayout compute(int screenWidth, int screenHeight) {
        int margin = Math.max(MIN_MARGIN, screenWidth / 40);
        int available = Math.max(80, screenWidth - margin * 2);
        int columnWidth = Math.min(available, MAX_COLUMN_WIDTH);
        int panelX = (screenWidth - columnWidth) / 2;

        int titleY = Math.max(2, margin / 2);
        int contentTop = titleY + TITLE_HEIGHT;
        int toolbarHeight = TOOLBAR_HEIGHT;
        int toolbarY = screenHeight - toolbarHeight - Math.max(2, margin / 3);
        // 内容区必须停在状态行之上，否则会压住「已保存」这类提示 —— 由 validate() 强制检查
        int statusLine = toolbarY + STATUS_Y_OFFSET;
        int contentBottom = Math.max(contentTop + 30, statusLine - ROW_GAP);

        return new EditorLayout(screenWidth, screenHeight, titleY,
                contentTop, contentBottom, panelX, columnWidth, toolbarY, toolbarHeight);
    }

    /** 面板内部可用的内容宽度（扣掉两侧内边距）。 */
    public int innerWidth() {
        return Math.max(40, panelWidth - PADDING * 2);
    }

    /** 面板内部内容的左边界。 */
    public int innerLeft() {
        return panelX + PADDING;
    }

    /** 内容区的垂直空间。 */
    public int contentHeight() {
        return contentBottom - contentTop;
    }

    /**
     * 一屏能放下的「整行」数量（每个整行 = 一行标签 + 一个输入框 ≈ 28px）。
     * 用于决定是否分页。
     */
    public int rowsPerScreen() {
        return Math.max(1, (contentHeight() - PADDING * 2) / (ROW_HEIGHT * 2 + ROW_GAP * 2));
    }

    /** 第 {@code index} 个整行的输入框 Y 坐标（从内容区顶部算起）。 */
    public int fieldY(int index) {
        return contentTop + PADDING + index * (ROW_HEIGHT * 2 + ROW_GAP * 2) + ROW_HEIGHT - 2;
    }

    /** 第 {@code index} 个整行的标签 Y 坐标。 */
    public int labelY(int index) {
        return fieldY(index) - ROW_HEIGHT + 2;
    }

    /** 底部页脚（状态文本）的 Y 坐标。 */
    public int footerY() {
        return toolbarY - 10;
    }

    /**
     * 状态行这条带的顶部 Y 与高度。
     *
     * <p>状态文本要画在 {@code statusY()}，<b>不要</b>再自己减偏移 ——
     * 「在 footerY 上减 10」这类临时偏移正是上一版让状态文字压住按钮的原因。
     */
    public int statusY() {
        return toolbarY + STATUS_Y_OFFSET;
    }

    public int statusHeight() {
        return STATUS_HEIGHT;
    }

    /** 第一行按钮的 Y。 */
    public int buttonRow1Y() {
        return toolbarY + BUTTON_ROW_1_OFFSET;
    }

    /** 第二行按钮的 Y。 */
    public int buttonRow2Y() {
        return toolbarY + BUTTON_ROW_2_OFFSET;
    }

    /**
     * 自检：所有关键矩形必须落在屏幕内且不互相重叠。
     * 编辑器构造时调用，出问题会直接记日志，而不是等用户看到歪掉的界面。
     *
     * @return 问题描述列表，空表示几何合法
     */
    public java.util.List<String> validate() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (panelX < 0) {
            problems.add("面板左边越界: " + panelX);
        }
        if (panelX + panelWidth > screenWidth) {
            problems.add("面板右边越界: " + (panelX + panelWidth) + " > " + screenWidth);
        }
        if (contentTop < 0) {
            problems.add("内容区顶部越界: " + contentTop);
        }
        if (contentBottom > screenHeight) {
            problems.add("内容区底部越界: " + contentBottom + " > " + screenHeight);
        }
        if (contentTop >= contentBottom) {
            problems.add("内容区高度非正: " + contentTop + " >= " + contentBottom);
        }
        if (toolbarY + toolbarHeight > screenHeight) {
            problems.add("工具条越界: " + (toolbarY + toolbarHeight) + " > " + screenHeight);
        }
        if (contentBottom > toolbarY) {
            problems.add("内容区与工具条重叠: " + contentBottom + " > " + toolbarY);
        }
        // 状态行 + 两行按钮必须依次排列、互不重叠且都在屏幕内
        int statusLine = toolbarY + STATUS_Y_OFFSET;
        int row1 = toolbarY + BUTTON_ROW_1_OFFSET;
        int row2 = toolbarY + BUTTON_ROW_2_OFFSET;
        if (statusLine < 0) {
            problems.add("状态行越界: " + statusLine);
        }
        if (row1 < statusLine + 10) {
            problems.add("第一行按钮压住状态行: " + row1 + " <= " + (statusLine + 10));
        }
        if (row2 < row1 + ROW_HEIGHT) {
            problems.add("两行按钮重叠: " + row2 + " < " + (row1 + ROW_HEIGHT));
        }
        if (row2 + ROW_HEIGHT > screenHeight) {
            problems.add("第二行按钮越界: " + (row2 + ROW_HEIGHT) + " > " + screenHeight);
        }
        // 状态行（文字）不能与任何一行按钮在垂直方向上重叠。
        // 这条断言是补的：上一版把状态文字画在 toolbarY-20，正好压住第二行按钮，
        // 而原来的 validate() 只检查按钮之间，没检查文字与按钮。
        int statusTop = statusY();
        int statusBottom = statusTop + statusHeight();
        if (statusTop < 0) {
            problems.add("状态行越界: " + statusTop);
        }
        if (statusBottom > row2) {
            problems.add("状态行压住第二行按钮: " + statusBottom + " > " + row2);
        }
        if (statusBottom > row1) {
            problems.add("状态行压住第一行按钮: " + statusBottom + " > " + row1);
        }
        if (contentBottom > statusLine) {
            problems.add("内容区压住状态行: " + contentBottom + " > " + statusLine);
        }
        if (innerWidth() < 40) {
            problems.add("内容宽度过窄: " + innerWidth());
        }
        if (titleY < 0) {
            problems.add("标题越界: " + titleY);
        }
        return problems;
    }

    /** 一行文字的最大字符宽度估算（用于裁剪），按字号 6px/字符保守估计。 */
    public int maxTextChars() {
        return Math.max(8, innerWidth() / 6);
    }
}
