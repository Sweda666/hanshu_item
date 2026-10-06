package sweda.hanshu_item.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 「原始数据（树）编辑器」的几何计算 —— 所有尺寸由屏幕尺寸推导，不写死像素。
 *
 * <h3>为什么要单独抽出来</h3>
 * 上一版把这几行的 Y 坐标直接写死在 {@code init()} 里（工具栏 22、搜索框 26、
 * 树从 46 开始），结果三行互相压在一起：标题被工具栏盖住、搜索框横跨整行盖住按钮、
 * 物品名被压在搜索框下面。写死坐标的布局在某一组尺寸下看着没问题，
 * 换个分辨率或换个 GUI Scale 就塌了 —— 而且塌得不明显，只是「有点乱」。
 *
 * <p>所以这里改成和 {@link EditorLayout} 同一套做法：
 * <ul>
 *   <li>纯函数，{@link #compute} 不依赖任何 MC 类型，能在游戏外核对几何；</li>
 *   <li>每一行各占一条 {@link Band}，自上而下依次排布，不重叠；</li>
 *   <li>窄屏时工具栏自动折成两行（而不是把按钮挤出屏幕）；</li>
 *   <li>{@link #validate()} 把所有关键矩形检查一遍，出问题当场能看出来。</li>
 * </ul>
 */
public record RawNbtLayout(
        int screenWidth,
        int screenHeight,
        Band title,
        Band subtitle,
        Band toolbar,
        Band searchLabel,
        Band search,
        Band tree,
        Band status,
        Band buttons,
        int toolbarRows,
        /** 扣除固定行之后，树面板理论上能占的高度。窗口太矮时它会小于 {@link #MIN_TREE_HEIGHT}。 */
        int availableTreeHeight) {

    /** 一条水平带：占据 [y, y+height)。 */
    public record Band(int x, int y, int width, int height) {

        public int bottom() {
            return y + height;
        }

        /** 两条带是否在垂直方向上真的重叠（留 0 容差：相邻不算重叠）。 */
        public boolean overlapsVertically(Band other) {
            return y < other.bottom() && other.y < bottom();
        }
    }

    /** 左右留白。 */
    private static final int MARGIN = 6;
    /** 各带之间的垂直间距。 */
    private static final int GAP = 3;
    /** 标题与副标题的字号（MC 默认字体行高 9）。 */
    private static final int TEXT_HEIGHT = 9;
    /** 输入框/按钮的标准高度。 */
    private static final int CONTROL_HEIGHT = 20;
    /** 工具按钮行高。 */
    private static final int BUTTON_HEIGHT = 20;
    /** 底部页脚高度：状态行 + 按钮行 + 间距。 */
    private static final int FOOTER_HEIGHT = CONTROL_HEIGHT + GAP + TEXT_HEIGHT;
    /** 树面板的最小高度：再小就没法用了，宁可让搜索框让位。 */
    private static final int MIN_TREE_HEIGHT = 60;
    /** 工具按钮一排最多放几个，超过就在窄屏时折行。 */
    private static final int TOOLBAR_BUTTONS = 6;
    /** 一个工具按钮的估算宽度（真实宽度按文字算，这里只用于判断要不要折行）。 */
    private static final int ESTIMATED_TOOLBAR_BUTTON_WIDTH = 74;
    /** 按钮之间的间距。 */
    private static final int BUTTON_GAP = 4;

    /**
     * @param toolbarNeedsTwoRows 是否允许工具栏折成两行（窄屏）
     */
    /**
     * @param screenWidth  逻辑宽度
     * @param screenHeight 逻辑高度
     */
    public static RawNbtLayout compute(int screenWidth, int screenHeight) {
        return compute(screenWidth, screenHeight, false);
    }

    /**
     * @param extraToolbarRow 是否再加一行工具栏（按钮多于一排放得下时用）
     */
    public static RawNbtLayout compute(int screenWidth, int screenHeight, boolean extraToolbarRow) {
        int usable = Math.max(120, screenWidth - MARGIN * 2);
        int x = (screenWidth - usable) / 2;

        // 一行放不下就折行；调用方明确要两行时也给两行
        int toolbarRows = extraToolbarRow ? 2 : chooseToolbarRows(usable);
        int toolbarHeight = toolbarRows * BUTTON_HEIGHT + (toolbarRows - 1) * GAP;

        int y = Math.max(2, MARGIN / 2);
        Band title = new Band(x, y, usable, TEXT_HEIGHT);
        y = title.bottom() + GAP;

        Band subtitle = new Band(x, y, usable, TEXT_HEIGHT);
        y = subtitle.bottom() + GAP;

        Band toolbar = new Band(x, y, usable, toolbarHeight);
        y = toolbar.bottom() + GAP;

        // 搜索行：标签「搜索」在左，输入框紧随其后。
        // 上一版把标签画在框左边 30px 处而框贴着左边缘，标签直接被裁掉了。
        int labelWidth = 26;
        Band searchLabel = new Band(x, y + (CONTROL_HEIGHT - TEXT_HEIGHT) / 2, labelWidth, TEXT_HEIGHT);
        int searchWidth = Math.max(80, Math.min(usable - labelWidth - GAP - 40, 180));
        Band search = new Band(x + labelWidth + GAP, y, searchWidth, CONTROL_HEIGHT);
        y = search.bottom() + GAP;

        // 页脚从底部往上量
        int footerTop = screenHeight - FOOTER_HEIGHT - Math.max(2, MARGIN / 3);
        Band status = new Band(x, footerTop, usable, TEXT_HEIGHT);
        Band buttons = new Band(x, status.bottom() + GAP, usable, CONTROL_HEIGHT);

        int treeBottom = status.y() - GAP;
        // 树能占的高度 = 从树的顶到状态行上方。这里<b>不能</b>写 Math.max(MIN_TREE_HEIGHT, ...)：
        // 窗口很矮时那会把树撑到状态行上面去，变成重叠 —— 重叠比「树矮」严重得多。
        // 所以先老老实实受限于可用空间，最小高度只是一个「放得下时才要求」的目标。
        int availableTreeHeight = Math.max(1, treeBottom - y);
        Band tree = new Band(x, y, usable, availableTreeHeight);

        return new RawNbtLayout(screenWidth, screenHeight, title, subtitle, toolbar,
                searchLabel, search, tree, status, buttons, toolbarRows, availableTreeHeight);
    }

    /** 一排放不下就折成两行。 */
    private static int chooseToolbarRows(int usable) {
        int oneRow = TOOLBAR_BUTTONS * ESTIMATED_TOOLBAR_BUTTON_WIDTH
                + (TOOLBAR_BUTTONS - 1) * BUTTON_GAP;
        return usable >= oneRow ? 1 : 2;
    }

    /** 工具栏第一行放几个按钮。 */
    public int toolbarFirstRowCount() {
        return toolbarRows == 1 ? TOOLBAR_BUTTONS : (TOOLBAR_BUTTONS + 1) / 2;
    }

    /** 工具栏可用的宽度（与其它行同宽）。 */
    public int toolbarWidth() {
        return Math.max(120, screenWidth - MARGIN * 2);
    }

    /** 工具栏第 {@code row} 行（0 起）的 Y 坐标。 */
    public int toolbarRowY(int row) {
        return toolbar.y() + row * (BUTTON_HEIGHT + GAP);
    }

    /** 工具栏占的垂直范围的高度。 */
    public int toolbarHeight() {
        return toolbarRows * BUTTON_HEIGHT + (toolbarRows - 1) * GAP;
    }

    /** 树面板内部内容的可用宽度（扣掉滚动条与内边距）。 */
    public int treeInnerWidth() {
        return Math.max(40, tree.width() - 8);
    }

    /**
     * 自检：所有带必须落在屏幕内、自上而下不重叠、且都有合理高度。
     *
     * <p>编辑器构造时调用，出问题直接记日志 —— 而不是等用户看到歪掉的界面。
     *
     * @return 问题描述列表，空表示几何合法
     */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();

        List<Band> ordered = List.of(title, subtitle, toolbar, search, tree, status, buttons);
        String[] names = {"标题", "副标题", "工具栏", "搜索框", "树面板", "状态行", "页脚按钮"};

        for (int i = 0; i < ordered.size(); i++) {
            Band band = ordered.get(i);
            if (band.x() < 0) {
                problems.add(names[i] + " 左边越界: " + band.x());
            }
            if (band.x() + band.width() > screenWidth) {
                problems.add(names[i] + " 右边越界: " + (band.x() + band.width()) + " > " + screenWidth);
            }
            if (band.y() < 0) {
                problems.add(names[i] + " 顶部越界: " + band.y());
            }
            if (band.bottom() > screenHeight) {
                problems.add(names[i] + " 底部越界: " + band.bottom() + " > " + screenHeight);
            }
            if (band.width() < 40) {
                problems.add(names[i] + " 宽度过窄: " + band.width());
            }
        }

        // 搜索标签必须完整落在屏幕内，且不能压住输入框
        if (searchLabel.x() < 0 || searchLabel.x() + searchLabel.width() > screenWidth) {
            problems.add("搜索标签越界: " + searchLabel.x() + ".."
                    + (searchLabel.x() + searchLabel.width()));
        }
        if (searchLabel.x() + searchLabel.width() > search.x()) {
            problems.add("搜索标签压住搜索框: " + (searchLabel.x() + searchLabel.width())
                    + " > " + search.x());
        }

        // 工具栏现在是一条正式的带，重叠检查已经覆盖它；这里只需确认它接在副标题下面
        if (toolbar.y() < subtitle.bottom() + GAP) {
            problems.add("工具栏压住副标题: " + toolbar.y() + " < " + (subtitle.bottom() + GAP));
        }
        if (toolbar.bottom() > search.y()) {
            problems.add("工具栏压住搜索框: " + toolbar.bottom() + " > " + search.y());
        }

        // 自上而下的顺序不能乱
        for (int i = 0; i + 1 < ordered.size(); i++) {
            Band above = ordered.get(i);
            Band below = ordered.get(i + 1);
            if (above.overlapsVertically(below)) {
                problems.add(names[i] + " 与 " + names[i + 1] + " 重叠: "
                        + above.y() + ".." + above.bottom() + " vs " + below.y() + ".." + below.bottom());
            }
        }

        if (tree.height() < MIN_TREE_HEIGHT && availableTreeHeight >= MIN_TREE_HEIGHT) {
            // 只有「本来放得下却被压扁了」才算 bug；窗口本身太矮则不是布局的错
            problems.add("树面板高度不足: " + tree.height() + " < " + MIN_TREE_HEIGHT
                    + "（可用 " + availableTreeHeight + "）");
        }
        if (subtitle.overlapsVertically(title)) {
            problems.add("副标题压住标题");
        }
        if (search.overlapsVertically(subtitle)) {
            problems.add("搜索框压住副标题");
        }

        return problems;
    }
}
