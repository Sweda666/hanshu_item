package sweda.hanshu_item.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.Hanshu_item;
import sweda.hanshu_item.overture.model.DefinitionId;
import sweda.hanshu_item.overture.model.SyncFlags;
import sweda.hanshu_item.overture.runtime.ItemDataAccessor;
import sweda.hanshu_item.overture.runtime.ModItemBuilder;
import sweda.hanshu_item.overture.runtime.SavedItemComponents;
import sweda.hanshu_item.overture.runtime.NativeNbtSync;
import sweda.hanshu_item.overture.runtime.ItemSyncService;
import sweda.hanshu_item.overture.model.Revision;

import java.util.ArrayList;
import java.util.List;

/** 原生组件树编辑器。支持编号模板和单件物品两种模式，逐字段设置同步参与状态。 */
public class RawNbtScreen extends Screen {

    private static final int ROW_HEIGHT = 18;
    private static final int CONTROL_HEIGHT = 16;
    private static final int INDENT = 10;
    private static final int ARROW_WIDTH = 9;
    private static final int BADGE_WIDTH = 18;
    private static final int KEY_WIDTH = 92;
    private static final int CHECKBOX_WIDTH = 12;
    private static final int SCROLLBAR_WIDTH = 4;
    private static final int GAP = 3;

    /** 底部按钮区高度，与 {@link EditorLayout} 保持一致的观感。 */
    private static final int FOOTER_HEIGHT = 40;

    private final @Nullable Screen parent;
    private final @Nullable net.minecraft.world.entity.player.Player player;

    private final @Nullable DefinitionId definitionId;
    private CompoundTag working;
    private NbtEntry root;
    private final List<NbtEntry> visible = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    /** 树内控件由本类渲染和派发事件，以支持滚动裁剪。 */
    private final List<EditBox> boxes = new ArrayList<>();
    private final List<SyncToggle> toggles = new ArrayList<>();

    private final EditBox searchBox;

    /** 几何全部由它推导；{@code init()} 时算一次。 */
    private RawNbtLayout layout = RawNbtLayout.compute(320, 240);

    private int treeX;
    private int treeY;
    private int treeWidth;
    private int treeHeight;

    private int scroll;
    private int focusedIndex = -1;
    private boolean draggingScrollbar;
    private int dragOffset;

    private String status = "";
    private int statusColor = EditorWidgets.COLOR_DIM;
    private boolean dirty;

    /** 打开编辑器时物品的指纹与数量，用于保存前确认没被换掉。 */
    private @Nullable String loadedFingerprint;
    private @Nullable DefinitionId rulesId;
    private @Nullable String loadedRulesRevision;
    private int loadedCount;

    /** 是否已经读过物品。见 {@link #init()}：重算几何时不能重读，否则会丢掉未保存的编辑。 */
    private boolean loaded;

    /** 同步规则在工作副本里改，保存时写入编号 JSON。 */
    private SyncFlags flags;

    /** 一行的控件集合。 */
    private static final class Row {
        final NbtEntry entry;
        final EditBox keyBox;
        final EditBox valueBox;
        final @Nullable SyncToggle toggle;
        int y;

        Row(NbtEntry entry, EditBox keyBox, EditBox valueBox, @Nullable SyncToggle toggle) {
            this.entry = entry;
            this.keyBox = keyBox;
            this.valueBox = valueBox;
            this.toggle = toggle;
        }
    }

    /**
     * @param parent 返回时回到哪个界面
     * @param player 用于渲染时解析玩家相关变量；可为 {@code null}
     */
    public RawNbtScreen(@Nullable Screen parent, @Nullable net.minecraft.world.entity.player.Player player) {
        this(parent, player, null);
    }

    protected RawNbtScreen(@Nullable Screen parent, @Nullable net.minecraft.world.entity.player.Player player,
                            @Nullable DefinitionId definitionId) {
        super(Component.literal(definitionId == null ? "原生 NBT 编辑器" : "编号物品 NBT 编辑器"));
        this.definitionId = definitionId;
        this.parent = parent;
        this.player = player;
        this.working = new CompoundTag();
        this.flags = SyncFlags.none();
        this.searchBox = new EditBox(Minecraft.getInstance().font, 0, 0, 100, CONTROL_HEIGHT,
                Component.literal("搜索"));
        this.searchBox.setHint(Component.literal("搜索键名/路径…"));
    }

    // --- 生命周期 -----------------------------------------------------------

    @Override
    protected void init() {
        // 只在第一次 init 时读物品。
        //
        // init() 不只在打开界面时跑 —— 窗口尺寸变化、GUI Scale 变化都会让它重跑一次。
        // 如果每次都重新读，那么「改了一半去调窗口大小」会把未保存的编辑整片丢掉，
        // 而且丢得没有任何提示。所以后续 init 只重算几何，保留工作副本。
        if (!loaded) {
            loadFromItem();
            loaded = true;
        }

        // 全部几何交给 RawNbtLayout：每一行各占一条带，自上而下排布，不重叠。
        // 上一版把 Y 坐标写死在这里，结果标题、工具栏、搜索框、物品名压成一团。
        layout = RawNbtLayout.compute(width, height, true);
        List<String> geometryProblems = layout.validate();
        if (!geometryProblems.isEmpty()) {
            // 几何非法就直接记日志，不等到用户看见歪掉的界面
            sweda.hanshu_item.Hanshu_item.LOGGER.warn(
                    "RawNbtScreen 布局在 {}x{} 下有问题: {}", width, height, geometryProblems);
        }

        treeX = layout.tree().x() + 4;
        treeY = layout.tree().y();
        treeWidth = layout.tree().width();
        treeHeight = layout.tree().height();

        searchBox.setX(layout.search().x());
        searchBox.setY(layout.search().y());
        searchBox.setWidth(layout.search().width());

        buildWidgets();
        rebuildRows();
        layoutRows();
        layoutFooterAndToolbar();

        setFocused(searchBox);
        status = loadedRulesRevision == null ? "未绑定可用编号，可编辑原生 NBT"
                : "同步规则保存到编号 JSON，对全部同编号物品生效";
        statusColor = EditorWidgets.COLOR_DIM;
    }

    /** 页脚按钮与工具按钮按布局排布；工具栏在窄屏时折成两行。 */
    private void layoutFooterAndToolbar() {
        List<Button> footer = List.of(
                EditorWidgets.smallButton(font, "取消", b -> onClose()),
                EditorWidgets.smallButton(font, "保存编辑", b -> save()),
                EditorWidgets.smallButton(font, "完成", b -> closeWithSave()));
        EditorWidgets.layoutRow(footer, width / 2, layout.buttons().y(), 6, layout.buttons().width());
        footer.forEach(this::addRenderableWidget);

        // 工具按钮：每一行单独居中，放不下的由 layoutRow 移到屏幕外，不会堆在原点
        List<Button> tools = List.of(
                EditorWidgets.smallButton(font, "新增键", b -> addChild()),
                EditorWidgets.smallButton(font, "删除选中", b -> removeSelected()),
                EditorWidgets.smallButton(font, "展开全部", b -> setExpandedAll(true)),
                EditorWidgets.smallButton(font, "折叠全部", b -> setExpandedAll(false)),
                EditorWidgets.smallButton(font, "全部参与同步", b -> setAllSync(true)),
                EditorWidgets.smallButton(font, "全部不参与", b -> setAllSync(false)));
        tools.get(4).active = loadedRulesRevision != null;
        tools.get(5).active = loadedRulesRevision != null;

        int firstRowCount = layout.toolbarFirstRowCount();
        for (int row = 0; row < layout.toolbarRows(); row++) {
            int from = row == 0 ? 0 : firstRowCount;
            int to = row == 0 ? firstRowCount : tools.size();
            if (from >= to) {
                continue;
            }
            List<Button> slice = new java.util.ArrayList<>(tools.subList(from, to));
            EditorWidgets.layoutRow(slice, width / 2, layout.toolbarRowY(row), 4, layout.toolbarWidth());
            slice.forEach(this::addRenderableWidget);
        }
    }

    private void loadFromItem() {
        var definition = definitionId == null ? null : Hanshu_item.library().find(definitionId).orElse(null);
        var stack = heldStack();
        var level = Minecraft.getInstance().level;
        var components = definition != null ? SavedItemComponents.parse(definition.nbt())
                : stack == null ? new CompoundTag() : SavedItemComponents.encode(stack.getComponentsPatch(),
                        level == null ? null : level.registryAccess());
        working = NativeNbtSync.document(components);
        rulesId = definitionId != null ? definitionId : stack == null ? null : ItemDataAccessor.definitionId(stack);
        var ruleDefinition = rulesId == null ? null : Hanshu_item.library().find(rulesId).orElse(null);
        flags = ruleDefinition == null ? SyncFlags.none() : ruleDefinition.syncFlags();
        loadedRulesRevision = ruleDefinition == null ? null : Revision.of(ruleDefinition);
        root = NbtEntry.root(working);
        setExpandedRecursive(root, true);
        loadedFingerprint = definition != null ? Revision.of(definition)
                : stack == null ? null : Revision.canonicalNbt(components);
        loadedCount = stack == null ? 0 : stack.getCount();
    }

    private boolean stillSameItem() {
        var stack = heldStack();
        var level = Minecraft.getInstance().level;
        return stack != null && !stack.isEmpty() && stack.getCount() == loadedCount
                && java.util.Objects.equals(rulesId, ItemDataAccessor.definitionId(stack))
                && java.util.Objects.equals(loadedFingerprint, Revision.canonicalNbt(
                        SavedItemComponents.encode(stack.getComponentsPatch(), level == null ? null : level.registryAccess())));
    }

    private @Nullable net.minecraft.world.item.ItemStack heldStack() {
        var local = player != null ? player : Minecraft.getInstance().player;
        return local == null ? null : local.getMainHandItem();
    }
    private void buildWidgets() {
        boxes.clear();
        toggles.clear();
    }

    // --- 行构建 -------------------------------------------------------------

    /** 按当前展开状态与搜索词重建可见行。 */
    private void rebuildRows() {
        String filter = searchBox.getValue().trim().toLowerCase(java.util.Locale.ROOT);

        List<NbtEntry> flat = new ArrayList<>();
        if (filter.isEmpty()) {
            root.flattenInto(flat);
        } else {
            root.flattenAllInto(flat);
        }

        visible.clear();
        if (filter.isEmpty()) {
            visible.addAll(flat);
        } else {
            // 搜索时无视折叠状态：只留下命中的节点、它们的祖先、以及它们的子孙。
            // 保留祖先是为了让树不断层 —— 否则命中的节点会凭空挂在根下面，看不出它在哪。
            java.util.Set<String> keep = new java.util.LinkedHashSet<>();
            for (NbtEntry entry : flat) {
                if (!matchesSelf(entry, filter)) {
                    continue;
                }
                keep.add(entry.path());
                for (NbtEntry cursor = entry.parent(); cursor != null; cursor = cursor.parent()) {
                    keep.add(cursor.path());
                }
            }
            for (NbtEntry entry : flat) {
                String path = entry.path();
                if (keep.contains(path) || insideKeptSubtree(path, keep)) {
                    visible.add(entry);
                }
            }
        }

        rows.clear();
        boxes.clear();
        toggles.clear();

        for (NbtEntry entry : visible) {
            EditBox keyBox = null;
            if (!entry.isRoot() && !entry.isListElement()) {
                keyBox = new EditBox(font, 0, 0, KEY_WIDTH, CONTROL_HEIGHT, Component.literal("键名"));
                keyBox.setValue(entry.key() == null ? "" : entry.key());
                boxes.add(keyBox);
            }

            EditBox valueBox = null;
            if (NbtValues.isEditableLeaf(entry.tag()) && !entry.managementField()) {
                valueBox = new EditBox(font, 0, 0, 80, CONTROL_HEIGHT, Component.literal("值"));
                valueBox.setValue(NbtValues.displayValue(entry.tag()));
                boxes.add(valueBox);
            }

            SyncToggle toggle = null;
            if (loadedRulesRevision != null && entry.syncToggleable() && entry.syncRelativePath() != null) {
                toggle = new SyncToggle(entry);
                toggles.add(toggle);
            }

            rows.add(new Row(entry, keyBox, valueBox, toggle));
        }
    }

    /** 某个路径是否落在被保留的子树里（命中节点的子孙也要显示出来）。 */
    private static boolean insideKeptSubtree(String path, java.util.Set<String> kept) {
        for (String ancestor : kept) {
            if (!ancestor.isEmpty() && path.startsWith(ancestor + ".")) {
                return true;
            }
        }
        return false;
    }

    /** 只看这一节点自身是否命中（键名或完整路径包含关键词，不区分大小写）。 */
    private static boolean matchesSelf(NbtEntry entry, String filter) {
        String path = entry.path().toLowerCase(java.util.Locale.ROOT);
        if (path.contains(filter)) {
            return true;
        }
        String key = entry.key();
        return key != null && key.toLowerCase(java.util.Locale.ROOT).contains(filter);
    }

    private void refreshSearch() {
        rebuildRows();
        scroll = 0;
        layoutRows();
    }

    private int contentHeight() {
        return rows.size() * ROW_HEIGHT + 4;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - treeHeight);
    }

    private void layoutRows() {
        scroll = Math.max(0, Math.min(maxScroll(), scroll));
        int y = treeY + 2 - scroll;
        for (Row row : rows) {
            row.y = y;
            layoutRow(row);
            y += ROW_HEIGHT;
        }
    }

    /**
     * 一行内部的水平排布 —— 全部按 {@link #treeWidth} 推导，不写死绝对坐标。
     *
     * <p>顺序：[缩进][箭头][徽章][键名][:][值][✓]。值的宽度吃掉剩下的空间，
     * 所以窄屏上被压缩的永远是值输入框，而不是让某些控件跑出屏幕。
     */
    private void layoutRow(Row row) {
        int indent = row.entry.depth() * INDENT;
        int x = treeX + 4 + indent;
        int controlY = row.y + (ROW_HEIGHT - CONTROL_HEIGHT) / 2;

        x += ARROW_WIDTH;
        x += BADGE_WIDTH + GAP;

        if (row.keyBox != null) {
            row.keyBox.setX(x);
            row.keyBox.setY(controlY);
            row.keyBox.setWidth(KEY_WIDTH);
            x += KEY_WIDTH;
        } else if (row.entry.isListElement()) {
            // 列表项显示 [下标]，用固定宽度占位保持对齐
            x += KEY_WIDTH;
        }
        x += GAP + 4 + GAP; // 冒号

        int right = treeX + treeWidth - SCROLLBAR_WIDTH - 4;
        int reserved = row.toggle != null ? CHECKBOX_WIDTH + GAP : 0;
        if (row.valueBox != null) {
            int valueWidth = Math.max(30, right - reserved - x);
            row.valueBox.setX(x);
            row.valueBox.setY(controlY);
            row.valueBox.setWidth(valueWidth);
        }
        if (row.toggle != null) {
            row.toggle.setX(right - CHECKBOX_WIDTH);
            row.toggle.setY(controlY);
        }
    }

    // --- 渲染 ---------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // 背景已经由 extractRenderStateWithTooltipAndSubtitles → extractBackground 处理过，
        // 这里再调一次会触发「Can only blur once per frame」
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        var stack = heldStack();
        Component title = Component.literal(definitionId == null ? "原生 NBT 编辑器" : "编号物品 NBT · " + definitionId.full());
        extractor.centeredText(font, title.getString(), width / 2,
                layout.title().y(), EditorWidgets.COLOR_TEXT);
        if (stack != null && !stack.isEmpty()) {
            DefinitionId id = ItemDataAccessor.definitionId(stack);
            String subtitle = stack.getHoverName().getString()
                    + (id == null ? "（未受管理）" : "  ·  " + id.full());
            extractor.centeredText(font, EditorWidgets.clip(font, subtitle, layout.subtitle().width()),
                    width / 2, layout.subtitle().y(),
                    id == null ? EditorWidgets.COLOR_WARN : EditorWidgets.COLOR_ACCENT);
        }

        EditorWidgets.panel(extractor, treeX, treeY, treeWidth, treeHeight);
        renderSearchBox(extractor, mouseX, mouseY, partialTick);
        renderTree(extractor, mouseX, mouseY);
        renderFooter(extractor);

        // 搜索框和工具/页脚按钮由 Screen 渲染，树里的输入框自己渲染
        for (EditBox box : boxes) {
            if (isBoxVisible(box)) {
                box.extractRenderState(extractor, mouseX, mouseY, partialTick);
            }
        }
        for (SyncToggle toggle : toggles) {
            if (isToggleVisible(toggle)) {
                toggle.extractRenderState(extractor, mouseX, mouseY, partialTick);
            }
        }
        renderScrollbar(extractor, mouseX, mouseY);
    }

    /**
     * 搜索框不属于 renderableWidgets（位置由布局单独给），手动画。
     *
     * <p>标签占的是布局里单独留出的一条带，不是「框左边 30px」这种估算 ——
     * 上一版就是靠估算写的，框贴着左边缘时标签直接被裁掉。
     */
    private void renderSearchBox(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.text(font, Component.literal("搜索"),
                layout.searchLabel().x(), layout.searchLabel().y(), EditorWidgets.COLOR_DIM);
        searchBox.extractRenderState(extractor, mouseX, mouseY, partialTick);
    }

    private void renderFooter(GuiGraphicsExtractor extractor) {
        String syncState = loadedRulesRevision == null ? "同步：未绑定可用编号"
                : "编号 " + rulesId.full() + "：" + (flags.isEmpty() ? "全部参与同步"
                : "保留 " + flags.excludedPaths().size() + " 个路径") + "（规则存入 JSON）";
        String hint = syncState + " · " + (dirty ? status + "   ●未保存" : status);
        extractor.centeredText(font, EditorWidgets.clip(font, hint, layout.status().width()), width / 2,
                layout.status().y(), dirty ? EditorWidgets.COLOR_WARN : statusColor);
    }

    private boolean isBoxVisible(EditBox box) {
        return box.getY() >= treeY && box.getY() + box.getHeight() <= treeY + treeHeight;
    }

    private boolean isToggleVisible(SyncToggle toggle) {
        return toggle.getY() >= treeY && toggle.getY() + toggle.getHeight() <= treeY + treeHeight;
    }

    private boolean isRowVisible(Row row) {
        return row.y + ROW_HEIGHT > treeY && row.y < treeY + treeHeight;
    }

    private void renderTree(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        extractor.enableScissor(treeX, treeY, treeX + treeWidth, treeY + treeHeight);
        try {
            for (Row row : rows) {
                if (isRowVisible(row)) {
                    renderRowDecorations(extractor, row, mouseX, mouseY);
                }
            }
        } finally {
            extractor.disableScissor();
        }
    }

    /** 一行的缩进参考线、展开箭头、类型徽章、键名与冒号 —— 输入框本身另行渲染。 */
    private void renderRowDecorations(GuiGraphicsExtractor extractor, Row row, int mouseX, int mouseY) {
        NbtEntry entry = row.entry;
        int x = treeX + 4;
        int textY = row.y + (ROW_HEIGHT - 8) / 2;

        // 缩进参考线：每一层画一条竖线，形成 IBE Editor 那种树状引导
        for (int level = 0; level < entry.depth(); level++) {
            int lineX = x + level * INDENT + 4;
            extractor.fill(lineX, row.y, lineX + 1, row.y + ROW_HEIGHT, 0x30FFFFFF);
        }
        x += entry.depth() * INDENT;

        // 展开箭头（仅容器）
        if (entry.hasChildren()) {
            boolean hovered = mouseX >= x && mouseX < x + ARROW_WIDTH
                    && mouseY >= row.y && mouseY < row.y + ROW_HEIGHT;
            String arrow = entry.expanded() ? "▼" : "▶";
            extractor.text(font, Component.literal(arrow), x, textY,
                    hovered ? EditorWidgets.COLOR_TEXT : EditorWidgets.COLOR_DIM);
        }
        x += ARROW_WIDTH;

        // 类型徽章
        int badgeColor = NbtValues.colorOf(entry.tag());
        extractor.fill(x, row.y + 3, x + BADGE_WIDTH - 4, row.y + ROW_HEIGHT - 3, (badgeColor & 0x00FFFFFF) | 0x70000000);
        extractor.text(font, Component.literal(NbtValues.badgeOf(entry.tag())), x + 2, textY, badgeColor);
        x += BADGE_WIDTH + GAP;

        // 键名或列表下标；有输入框时输入框自己画，这里只补冒号
        if (row.keyBox == null) {
            String label = entry.isRoot() ? "(root)"
                    : entry.isListElement() ? "[" + entry.listIndex() + "]" : String.valueOf(entry.key());
            extractor.text(font, Component.literal(EditorWidgets.clip(font, label, KEY_WIDTH - 4)),
                    x, textY, entry.isRoot() ? EditorWidgets.COLOR_ACCENT : EditorWidgets.COLOR_TEXT);
        }
        int colonX = x + KEY_WIDTH + GAP;
        extractor.text(font, Component.literal(":"), colonX, textY, EditorWidgets.COLOR_DIM);

        // 容器没有值输入框，用一行灰字概要代替
        if (row.valueBox == null) {
            String summary = NbtValues.displayValue(entry.tag());
            int valueX = colonX + 10;
            int room = Math.max(20, treeX + treeWidth - SCROLLBAR_WIDTH - 6 - valueX
                    - (row.toggle != null ? CHECKBOX_WIDTH + GAP : 0));
            // 身份字段（id/def/rev）是只读的，用另一种颜色区分开，
            // 免得用户以为「这个框怎么点不动」
            int color = entry.managementField() ? EditorWidgets.COLOR_ACCENT : EditorWidgets.COLOR_DIM;
            extractor.text(font, Component.literal(EditorWidgets.clip(font, summary, room)),
                    valueX, textY, color);
            if (entry.managementField()) {
                String hint = "（模组维护，只读）";
                int hintX = valueX + font.width(EditorWidgets.clip(font, summary, room)) + 4;
                if (hintX + font.width(hint) < treeX + treeWidth - SCROLLBAR_WIDTH) {
                    extractor.text(font, Component.literal(hint), hintX, textY,
                            EditorWidgets.COLOR_DIM);
                }
            }
        }
    }

    private void renderScrollbar(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int trackX = treeX + treeWidth - SCROLLBAR_WIDTH - 1;
        int trackTop = treeY + 2;
        int trackHeight = treeHeight - 4;
        extractor.fill(trackX, trackTop, trackX + SCROLLBAR_WIDTH, trackTop + trackHeight, 0x50000000);

        int thumbHeight = thumbHeight(trackHeight);
        int thumbY = thumbY(trackTop, trackHeight, thumbHeight);
        boolean hovered = mouseX >= trackX && mouseX <= trackX + SCROLLBAR_WIDTH
                && mouseY >= thumbY && mouseY <= thumbY + thumbHeight;
        extractor.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight,
                hovered || draggingScrollbar ? EditorWidgets.COLOR_ACCENT : 0xFF909090);
    }

    private int thumbHeight(int trackHeight) {
        double ratio = (double) treeHeight / Math.max(1, contentHeight());
        return Math.max(14, (int) (trackHeight * ratio));
    }

    private int thumbY(int trackTop, int trackHeight, int thumbHeight) {
        int max = maxScroll();
        if (max <= 0) {
            return trackTop;
        }
        return trackTop + (int) ((trackHeight - thumbHeight) * ((double) scroll / max));
    }

    // --- 输入 ---------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 先给 Screen 的按钮一次机会（工具行与页脚）
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (searchBox.mouseClicked(event, doubleClick)) {
            setFocused(searchBox);
            clearBoxFocus();
            return true;
        }

        // 滚动条
        if (event.button() == 0 && maxScroll() > 0 && insideTree(event.x(), event.y())) {
            int trackX = treeX + treeWidth - SCROLLBAR_WIDTH - 1;
            if (event.x() >= trackX && event.x() <= trackX + SCROLLBAR_WIDTH) {
                int trackTop = treeY + 2;
                int trackHeight = treeHeight - 4;
                int thumbHeight = thumbHeight(trackHeight);
                int thumbY = thumbY(trackTop, trackHeight, thumbHeight);
                if (event.y() >= thumbY && event.y() <= thumbY + thumbHeight) {
                    draggingScrollbar = true;
                    dragOffset = (int) event.y() - thumbY;
                } else {
                    scroll = clamp(scroll + (event.y() < thumbY ? -treeHeight : treeHeight));
                    layoutRows();
                }
                return true;
            }
        }

        if (!insideTree(event.x(), event.y())) {
            clearBoxFocus();
            return false;
        }

        // 勾选框（只在完整可见时才响应，避免「半露出的控件被点到」）
        for (SyncToggle toggle : toggles) {
            if (!isToggleVisible(toggle)) {
                continue;
            }
            if (event.x() >= toggle.getX() && event.x() <= toggle.getX() + toggle.getWidth()
                    && event.y() >= toggle.getY() && event.y() <= toggle.getY() + toggle.getHeight()) {
                toggle.click();
                return true;
            }
        }

        // 展开箭头
        Row row = rowAt(event.x(), event.y());
        if (row != null && isOnArrow(row, event.x())) {
            row.entry.toggleExpanded();
            rebuildRows();
            layoutRows();
            return true;
        }

        // 行内输入框
        for (int i = 0; i < rows.size(); i++) {
            Row candidate = rows.get(i);
            if (!isRowVisible(candidate)) {
                continue;
            }
            if (hit(candidate.keyBox, event)) {
                focusBox(i, candidate.keyBox);
                return true;
            }
            if (hit(candidate.valueBox, event)) {
                focusBox(i, candidate.valueBox);
                return true;
            }
        }

        // 点空白：选中该行并收焦点
        if (row != null) {
            focusedIndex = rows.indexOf(row);
            clearBoxFocus();
            return true;
        }
        clearBoxFocus();
        return true;
    }

    private boolean hit(@Nullable EditBox box, MouseButtonEvent event) {
        return box != null
                && event.x() >= box.getX() && event.x() <= box.getX() + box.getWidth()
                && event.y() >= box.getY() && event.y() <= box.getY() + box.getHeight();
    }

    private void focusBox(int index, EditBox box) {
        focusedIndex = index;
        for (EditBox other : boxes) {
            other.setFocused(other == box);
        }
    }

    private void clearBoxFocus() {
        for (EditBox box : boxes) {
            box.setFocused(false);
        }
        focusedIndex = -1;
    }

    private @Nullable Row rowAt(double mouseX, double mouseY) {
        for (Row row : rows) {
            if (isRowVisible(row) && mouseY >= row.y && mouseY < row.y + ROW_HEIGHT) {
                return row;
            }
        }
        return null;
    }

    private boolean isOnArrow(Row row, double mouseX) {
        int arrowX = treeX + 4 + row.entry.depth() * INDENT;
        return mouseX >= arrowX && mouseX < arrowX + ARROW_WIDTH && row.entry.hasChildren();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (!draggingScrollbar) {
            return super.mouseDragged(event, dragX, dragY);
        }
        int max = maxScroll();
        if (max <= 0) {
            return true;
        }
        int trackTop = treeY + 2;
        int trackHeight = treeHeight - 4;
        int thumbHeight = thumbHeight(trackHeight);
        int span = Math.max(1, trackHeight - thumbHeight);
        double position = ((event.y() - dragOffset) - trackTop) / (double) span;
        scroll = clamp((int) Math.round(position * max));
        layoutRows();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingScrollbar = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (insideTree(mouseX, mouseY) && maxScroll() > 0) {
            scroll = clamp(scroll - (int) (scrollY * 14));
            layoutRows();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // 搜索框与行内输入框优先
        if (searchBox.isFocused() && searchBox.keyPressed(event)) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
                commitSearch();
            }
            return true;
        }
        for (EditBox box : boxes) {
            if (box.isFocused() && box.keyPressed(event)) {
                dirty = true;
                return true;
            }
        }
        // 回车提交这一行的编辑；Esc 关页面
        if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER && focusedIndex >= 0) {
            commitRow(rows.get(focusedIndex));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (searchBox.isFocused() && searchBox.charTyped(event)) {
            refreshSearch();
            return true;
        }
        for (EditBox box : boxes) {
            if (box.isFocused() && box.charTyped(event)) {
                dirty = true;
                return true;
            }
        }
        return super.charTyped(event);
    }

    /** 搜索框只在回车时真正过滤，避免每敲一个字就重建整棵树。 */
    private void commitSearch() {
        refreshSearch();
        setFocused(searchBox);
    }

    private boolean insideTree(double x, double y) {
        return x >= treeX && x <= treeX + treeWidth && y >= treeY && y <= treeY + treeHeight;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(maxScroll(), value));
    }

    // --- 编辑动作 -----------------------------------------------------------

    /** 把某一行输入框里的键名与值写回 NBT 树。 */
    private void commitRow(Row row) {
        if (row.keyBox != null) {
            String typed = row.keyBox.getValue();
            String oldKey = row.entry.key();
            String oldPath = row.entry.syncRelativePath();
            if (!typed.equals(oldKey) && !row.entry.rename(typed)) {
                status = "键名无法使用：" + typed + "（为空或与同级重名）";
                statusColor = EditorWidgets.COLOR_ERROR;
                row.keyBox.setValue(oldKey == null ? "" : oldKey);
            } else if (!typed.equals(oldKey)) {
                dirty = true;
                // 改名等于换了一条路径：同步标记要跟着搬，否则旧标记会变成一个永不匹配的幽灵
                if (definitionId != null) remapSyncFlag(row.entry, oldPath);
            }
        }
        if (row.valueBox != null) {
            // 记下改动前的文本，用来判断「用户到底动没动这一格」
            String before = NbtValues.displayValue(row.entry.tag());
            String typed = row.valueBox.getValue();
            if (!row.entry.setValueFromText(typed)) {
                status = "值不合法：" + typed + "（这一项的类型是 " + NbtValues.typeNameOf(row.entry.tag()) + "）";
                statusColor = EditorWidgets.COLOR_ERROR;
                row.valueBox.setValue(NbtValues.displayValue(row.entry.tag()));
            } else if (!typed.equals(before)) {
                dirty = true;
            }
        }
    }

    /** 改名后把同步标记从旧路径搬到新路径。 */
    private void remapSyncFlag(NbtEntry entry, @Nullable String oldRelative) {
        String relative = entry.syncRelativePath();
        if (relative == null || relative.isEmpty() || oldRelative == null) {
            return;
        }
        flags = SyncFlags.of(flags.toList().stream().map(path ->
                path.equals(oldRelative) || path.startsWith(oldRelative + ".") || path.startsWith(oldRelative + "[")
                        ? relative + path.substring(oldRelative.length()) : path).toList());
    }

    /** 提交所有行 —— 保存前必须做，否则最后没按回车的那个输入框会白填。 */
    private void commitAllRows() {
        for (Row row : rows) {
            commitRow(row);
        }
    }

    private void addChild() {
        NbtEntry target = focusedIndex >= 0 && focusedIndex < rows.size()
                ? rows.get(focusedIndex).entry : root;
        NbtEntry container = nearestContainer(target);
        if (container == null) {
            status = "这个节点不能装子项（只有复合与列表可以）";
            statusColor = EditorWidgets.COLOR_ERROR;
            return;
        }
        if (container.addChild()) {
            container.setExpanded(true);
            rebuildRows();
            layoutRows();
            dirty = true;
            status = "已新增一项，改完键名和值记得按回车";
            statusColor = EditorWidgets.COLOR_TEXT;
        }
    }

    private @Nullable NbtEntry nearestContainer(@Nullable NbtEntry entry) {
        for (NbtEntry cursor = entry; cursor != null; cursor = cursor.parent()) {
            if (NbtValues.isContainer(cursor.tag())) {
                return cursor;
            }
        }
        return null;
    }

    private void removeSelected() {
        if (focusedIndex < 0 || focusedIndex >= rows.size()) {
            status = "先点一行再删除";
            statusColor = EditorWidgets.COLOR_WARN;
            return;
        }
        NbtEntry entry = rows.get(focusedIndex).entry;
        if (entry.isRoot()) {
            status = "根节点不能删";
            statusColor = EditorWidgets.COLOR_WARN;
            return;
        }
        // 删掉一个 原生 NBT 下的键，顺手清掉它的同步标记，免得留下永不匹配的条目
        String syncPath = entry.syncRelativePath();
        if (definitionId != null && syncPath != null && !syncPath.isEmpty()) {
            flags = flags.withoutSubtree(syncPath);
        }
        if (entry.removeSelf()) {
            rebuildRows();
            layoutRows();
            focusedIndex = -1;
            dirty = true;
            status = "已删除";
            statusColor = EditorWidgets.COLOR_TEXT;
        }
    }

    private void setExpandedAll(boolean expanded) {
        setExpandedRecursive(root, expanded);
        rebuildRows();
        layoutRows();
    }

    private static void setExpandedRecursive(NbtEntry entry, boolean expanded) {
        entry.setExpanded(expanded);
        for (NbtEntry child : entry.children()) {
            setExpandedRecursive(child, expanded);
        }
    }

    /** 批量设置 组件子树下所有键的同步参与状态。 */
    private void setAllSync(boolean participates) {
        if (loadedRulesRevision == null) return;
        List<String> paths = collectSyncPaths();
        if (paths.isEmpty()) {
            status = "这个物品没有可编辑的组件";
            statusColor = EditorWidgets.COLOR_WARN;
            return;
        }
        flags = participates ? SyncFlags.none() : SyncFlags.of(paths);
        dirty = true;
        status = participates ? "原生 NBT 全部改为参与同步" : "原生 NBT 全部改为不参与同步";
        statusColor = EditorWidgets.COLOR_TEXT;
        // 勾选状态变了，重建行以刷新勾选框外观
        rebuildRows();
        layoutRows();
    }

    /** 收集 组件子树下所有可勾选的相对路径（含嵌套子键）。 */
    private List<String> collectSyncPaths() {
        List<NbtEntry> flat = new ArrayList<>();
        root.flattenAllInto(flat);
        List<String> paths = new ArrayList<>();
        for (NbtEntry entry : flat) {
            String relative = entry.syncRelativePath();
            if (relative != null && !relative.isEmpty()) {
                paths.add(relative);
            }
        }
        return paths;
    }

    // --- 保存 ---------------------------------------------------------------

    private void save() {
        commitAllRows();
        if (!applyToItem()) {
            return;
        }
        dirty = false;
        status = definitionId == null ? "已保存原生 NBT；同步规则保存在编号 JSON" : "已保存编号原生 NBT 与同步规则并安排同步";
        statusColor = EditorWidgets.COLOR_TEXT;
    }

    private void closeWithSave() {
        commitAllRows();
        if (applyToItem()) Minecraft.getInstance().setScreen(parent);
    }

    private boolean applyToItem() {
        var components = working.getCompound("components").orElseGet(CompoundTag::new);
        var level = Minecraft.getInstance().level;
        try {
            var patch = SavedItemComponents.decode(components, level == null ? null : level.registryAccess());
            if (definitionId != null) {
                var library = Hanshu_item.library();
                var previous = library.find(definitionId).orElse(null);
                if (previous == null || !java.util.Objects.equals(loadedFingerprint, Revision.of(previous))) {
                    status = "编号定义在编辑期间已改变，请重新打开";
                    statusColor = EditorWidgets.COLOR_ERROR;
                    return false;
                }
                var updated = previous.withNbt(components.toString()).withSyncFlags(flags);
                var result = library.put(updated);
                if (!result.accepted()) throw new IllegalArgumentException(result.errors().toString());
                try { ItemSyncService.saveAndNote(); }
                catch (java.io.IOException e) { library.put(previous); throw new IllegalStateException(e); }
                loadedFingerprint = Revision.of(updated);
                loadedRulesRevision = loadedFingerprint;
                var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null) server.execute(() -> ItemSyncService.syncById(server, definitionId));
            } else {
                var stack = heldStack();
                if (stack == null || !stillSameItem()) {
                    status = "物品在编辑期间已改变，请重新打开";
                    statusColor = EditorWidgets.COLOR_ERROR;
                    return false;
                }
                var library = Hanshu_item.library();
                var previous = rulesId == null ? null : library.find(rulesId).orElse(null);
                if (!java.util.Objects.equals(loadedRulesRevision, previous == null ? null : Revision.of(previous))) {
                    status = "编号定义在编辑期间已改变，请重新打开";
                    statusColor = EditorWidgets.COLOR_ERROR;
                    return false;
                }
                boolean rulesChanged = previous != null && !flags.equals(previous.syncFlags());
                var candidate = stack.copy();
                var oldRoot = ItemDataAccessor.rootOf(ItemDataAccessor.rawNbt(stack));
                candidate.applyComponents(patch);
                ItemDataAccessor.writeRawNbt(candidate, ItemDataAccessor.withRoot(ItemDataAccessor.rawNbt(candidate), oldRoot));
                var updated = previous == null ? null : previous.withSyncFlags(flags);
                if (updated != null) ModItemBuilder.stamp(candidate, updated);
                if (rulesChanged) {
                    try { library.updateSyncRules(rulesId, flags); }
                    catch (java.io.IOException e) { throw new IllegalStateException(e); }
                    ItemSyncService.noteOwnWrite();
                }
                stack.applyComponents(candidate.getComponentsPatch());
                loadedRulesRevision = updated == null ? null : Revision.of(updated);
                loadedFingerprint = Revision.canonicalNbt(SavedItemComponents.encode(stack.getComponentsPatch(),
                        level == null ? null : level.registryAccess()));
                if (player != null) player.containerMenu.broadcastChanges();
                var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                if (rulesChanged && server != null) server.execute(() -> ItemSyncService.syncById(server, rulesId));
            }
            return true;
        } catch (RuntimeException e) {
            status = "保存失败: " + e.getMessage();
            statusColor = EditorWidgets.COLOR_ERROR;
            return false;
        }
    }
    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // --- 勾选框控件 ---------------------------------------------------------

    /**
     * 「参与同步」勾选框。
     *
     * <p>三态：勾上（这一条跟着定义走）、空框（这一条保留物品自己的值）、
     * 横线（本节点跟着走，但它的某些子键被取消了 —— 免得以为子树全是参与）。
     */
    private final class SyncToggle extends AbstractWidget {

        private final NbtEntry entry;

        SyncToggle(NbtEntry entry) {
            super(0, 0, CHECKBOX_WIDTH, CHECKBOX_WIDTH, Component.literal("参与同步"));
            this.entry = entry;
        }

        private String relativePath() {
            String path = entry.syncRelativePath();
            return path == null ? "" : path;
        }

        private boolean participates() {
            String path = relativePath();
            return path.isEmpty() || flags.participates(path);
        }

        private boolean partial() {
            String path = relativePath();
            return !path.isEmpty() && participates() && flags.hasExcludedUnder(path);
        }

        void click() {
            String path = relativePath();
            boolean target = !participates();
            if (path.isEmpty()) {
                // 这是 组件根：整棵子树一起设
                java.util.List<String> children = collectSyncPaths();
                for (String child : children) {
                    flags = flags.withParticipates(child, target);
                }
            } else {
                flags = flags.withSubtreeParticipates(path, target);
            }
            dirty = true;
            status = "编号同步规则已改，保存后对全部同编号物品生效";
            statusColor = EditorWidgets.COLOR_WARN;
            rebuildRows();
            layoutRows();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
            boolean on = participates();
            boolean partial = partial();
            int border = on ? EditorWidgets.COLOR_ACCENT : EditorWidgets.COLOR_BORDER;
            int fill = isHovered() ? EditorWidgets.COLOR_ROW_HOVER
                    : (on ? 0x5070A0FF : 0x60000000);

            extractor.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
            extractor.fill(getX(), getY(), getX() + getWidth(), getY() + 1, border);
            extractor.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), border);
            extractor.fill(getX(), getY(), getX() + 1, getY() + getHeight(), border);
            extractor.fill(getX() + getWidth() - 1, getY(), getX() + getWidth(), getY() + getHeight(), border);

            String mark = partial ? "–" : (on ? "✓" : "");
            if (!mark.isEmpty()) {
                extractor.centeredText(Minecraft.getInstance().font, mark,
                        getX() + getWidth() / 2, getY() + 3,
                        on ? EditorWidgets.COLOR_ACCENT : EditorWidgets.COLOR_WARN);
            }
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            click();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
            defaultButtonNarrationText(narration);
        }
    }
}
