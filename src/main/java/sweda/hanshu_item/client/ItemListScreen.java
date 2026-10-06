package sweda.hanshu_item.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.Hanshu_item;
import sweda.hanshu_item.overture.model.DefinitionId;
import sweda.hanshu_item.overture.model.DefinitionIssue;
import sweda.hanshu_item.overture.model.ItemDefinition;
import sweda.hanshu_item.overture.runtime.ModItemBuilder;
import sweda.hanshu_item.overture.runtime.ModItemLibrary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品列表页 —— 纵向单列，设计成在原版菜单尺寸下也能用。
 *
 * <p>布局（逻辑像素，由 {@link EditorLayout} 按屏幕尺寸推导）：
 * <pre>
 *        物品库                       ← 标题（居中）
 *  ┌──────────────────────────┐
 *  │ ▸ example_sword   [icon] │     ← 可滚动列表，整行可点
 *  │   example_bow     [icon] │
 *  │   …                      │
 *  └──────────────────────────┘
 *      状态 / 问题提示
 *   [新建][编辑][复制][删除]        ← 单行按钮，超宽自动换行
 *   [重载][校验][保存][关闭]
 * </pre>
 *
 * <p>关键修正：不再并排三栏。640×373 的逻辑分辨率下三栏必然溢出，
 * 所以编辑改为进入独立页面（{@link ItemDetailScreen}）。
 */
public class ItemListScreen extends Screen {

    private static final int ROW_HEIGHT = 20;

    private final List<DefinitionId> visibleIds = new ArrayList<>();
    private final List<DefinitionIssue> issues = new ArrayList<>();

    private @Nullable DefinitionId selectedId;
    private int scroll;
    private String status = "按 K 打开编辑器 · 编辑后记得保存";
    private int statusColor = EditorWidgets.COLOR_DIM;
    private int listTop;
    private int listBottom;
    private boolean geometryChecked;

    public ItemListScreen() {
        super(Component.literal("HanShu-Item 物品库"));
    }

    // --- 生命周期 -----------------------------------------------------------

    @Override
    protected void init() {
        if (visibleIds.isEmpty()) {
            rebuildVisibleIds();
        }
        buildWidgets();
    }

    private EditorLayout layout() {
        return EditorLayout.compute(width, height);
    }

    private void buildWidgets() {
        clearWidgets();
        EditorLayout layout = layout();
        if (!geometryChecked) {
            geometryChecked = true;
            List<String> problems = layout.validate();
            if (!problems.isEmpty()) {
                // 布局自检失败时直接说出来，而不是让用户面对歪掉的界面
                sweda.hanshu_item.Hanshu_item.LOGGER.warn("编辑器布局自检发现 {} 个问题: {}",
                        problems.size(), String.join("; ", problems));
                status = "⚠ 布局自检: " + problems.get(0);
                statusColor = EditorWidgets.COLOR_ERROR;
            }
        }

        listTop = layout.contentTop();
        listBottom = layout.contentBottom();

        List<Button> row1 = List.of(
                EditorWidgets.smallButton(font, "新建", b -> createNew()),
                EditorWidgets.smallButton(font, "库 SNBT", b -> openSnbt()),
                EditorWidgets.smallButton(font, "拿一个", b -> giveSelected()),
                EditorWidgets.smallButton(font, "复制", b -> duplicateSelected()),
                EditorWidgets.smallButton(font, "删除", b -> deleteSelected()));
        // 改动是立刻生效并写盘的，所以没有「保存」按钮 —— 它只会让人以为还有未提交的东西
        List<Button> row2 = List.of(
                EditorWidgets.smallButton(font, "高级定义", b -> openSelected()),
                EditorWidgets.smallButton(font, "从磁盘重载", b -> reload()),
                EditorWidgets.smallButton(font, "校验", b -> validateDraft()),
                EditorWidgets.smallButton(font, "关闭", b -> onClose()));

        int centerX = layout.panelX() + layout.panelWidth() / 2;
        int totalWidth = layout.panelWidth() + 40;
        int y1 = layout.toolbarY() + EditorLayout.BUTTON_ROW_1_OFFSET;
        int y2 = layout.toolbarY() + EditorLayout.BUTTON_ROW_2_OFFSET;
        EditorWidgets.layoutRow(row1, centerX, y1, 4, totalWidth);
        EditorWidgets.layoutRow(row2, centerX, y2, 4, totalWidth);
        row1.forEach(this::addRenderableWidget);
        row2.forEach(this::addRenderableWidget);
    }

    private void rebuildVisibleIds() {
        visibleIds.clear();
        // 直接读运行库：改了立刻生效，没有「草稿 / 已保存」两套状态
        Hanshu_item.library().definitions().definitions().forEach((id, definition) -> {
            if (definition != null && id.isItem()) {
                visibleIds.add(id);
            }
        });
        visibleIds.sort(DefinitionId::compareTo);
        if (selectedId != null && !visibleIds.contains(selectedId)) {
            selectedId = null;
        }
        if (selectedId == null && !visibleIds.isEmpty()) {
            selectedId = visibleIds.getFirst();
        }
    }

    private @Nullable ItemDefinition current() {
        if (selectedId == null) {
            return null;
        }
        ModItemLibrary library = Hanshu_item.library();
        return library == null ? null : library.find(selectedId).orElse(null);
    }

    /**
     * 把一批变更直接提交到运行库并写盘。
     *
     * <p><b>不再有「草稿」这一层。</b>原来这个页面把改动囤在本地 draft 里、要点「保存」才提交，
     * 而 {@link LibraryNbtScreen} 和 {@link LibraryNbtScreen} 都是直接读写运行库的 ——
     * 于是「新建」按钮造出来的定义只存在于草稿里，进编辑页立刻显示「定义已不存在」。
     * 一个页面两种数据源，迟早会对不上。
     *
     * <p>现在统一成「运行库是唯一状态」：改动当场提交、当场写盘。
     * 写盘是原子的，而且文件监听器会把这次写入认成自己的，不会反过来触发一次重载。
     */
    private boolean commit(Map<DefinitionId, ItemDefinition> mutations) {
        ModItemLibrary library = Hanshu_item.library();
        if (library == null) {
            status = "运行库还没准备好";
            statusColor = EditorWidgets.COLOR_ERROR;
            return false;
        }
        ModItemLibrary.CommitResult result = library.commit(mutations);
        issues.clear();
        issues.addAll(result.issues());
        if (!result.accepted()) {
            status = "改动被拒绝：" + result.errors().size() + " 个错误（库未改动）";
            statusColor = EditorWidgets.COLOR_ERROR;
            return false;
        }
        try {
            sweda.hanshu_item.overture.runtime.ItemSyncService.saveAndNote();
        } catch (IOException e) {
            status = "已应用但写盘失败: " + e.getMessage();
            statusColor = EditorWidgets.COLOR_ERROR;
            return false;
        }
        return true;
    }

    // --- 渲染 ---------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        // 26.1：背景（含模糊）已由 extractRenderStateWithTooltipAndSubtitles 调过，这里不能再调
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        EditorLayout layout = layout();
        extractor.centeredText(font, title, width / 2, layout.titleY(), EditorWidgets.COLOR_TEXT);

        EditorWidgets.panel(extractor, layout.panelX(), layout.contentTop(),
                layout.panelWidth(), layout.contentHeight());
        drawList(extractor, layout, mouseX, mouseY);

        // 状态行只有一条：问题摘要（若有）拼在状态文字前面。
        // 上一版把摘要单独画在另一个 Y 上，正好压住按钮 —— 一条带放一条文字就没这个问题。
        String line = statusLine();
        extractor.centeredText(font,
                Component.literal(EditorWidgets.clip(font, line, layout.panelWidth() + 40)),
                width / 2, layout.statusY(), statusColor);
    }

    /** 状态行文本：有问题时把摘要并进来，而不是另起一行。 */
    private String statusLine() {
        if (issues.isEmpty()) {
            return status;
        }
        long errors = issues.stream().filter(i -> i.severity() == DefinitionIssue.Severity.ERROR).count();
        long warnings = issues.stream().filter(i -> i.severity() == DefinitionIssue.Severity.WARNING).count();
        String summary = errors > 0
                ? ("✖ " + errors + " 错误 / " + warnings + " 警告")
                : ("⚠ " + warnings + " 警告");
        return summary + " · " + status;
    }

    private void drawList(GuiGraphicsExtractor extractor, EditorLayout layout, int mouseX, int mouseY) {
        if (visibleIds.isEmpty()) {
            extractor.text(font, Component.literal("还没有物品定义"),
                    layout.innerLeft(), layout.contentTop() + EditorLayout.PADDING + 4, EditorWidgets.COLOR_DIM);
            extractor.text(font, Component.literal("点下面「新建」创建一个"),
                    layout.innerLeft(), layout.contentTop() + EditorLayout.PADDING + 18, EditorWidgets.COLOR_DIM);
            return;
        }

        int innerLeft = layout.innerLeft();
        int innerWidth = layout.innerWidth();
        int rowY = listTop + EditorLayout.PADDING - scroll;

        for (DefinitionId id : visibleIds) {
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) {
                rowY += ROW_HEIGHT;
                continue;
            }
            ItemDefinition definition = Hanshu_item.library().find(id).orElse(null);
            if (definition == null) {
                rowY += ROW_HEIGHT;
                continue;
            }
            boolean hovered = mouseX >= innerLeft && mouseX <= innerLeft + innerWidth
                    && mouseY >= rowY && mouseY <= rowY + ROW_HEIGHT;
            boolean selected = id.equals(selectedId);

            ItemStack icon = ModItemBuilder.build(definition, minecraft.player, 1).stack();
            ItemDefinition shown = definition;
            EditorWidgets.row(extractor, font, innerLeft, rowY, innerWidth, ROW_HEIGHT - 1,
                    Component.literal(EditorWidgets.clip(font, shown.id().leaf(), innerWidth - 24)),
                    null, hovered, selected);
            extractor.item(icon, innerLeft + innerWidth - 18, rowY + 1);
            if (hovered) {
                extractor.setTooltipForNextFrame(font, icon, mouseX, mouseY);
            }
            rowY += ROW_HEIGHT;
        }
    }

    // --- 输入 ---------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        EditorLayout layout = layout();
        if (event.button() == 0) {
            int innerLeft = layout.innerLeft();
            int innerWidth = layout.innerWidth();
            if (event.x() >= innerLeft && event.x() <= innerLeft + innerWidth
                    && event.y() >= listTop && event.y() <= listBottom) {
                int index = (int) ((event.y() - listTop - EditorLayout.PADDING + scroll) / ROW_HEIGHT);
                if (index >= 0 && index < visibleIds.size()) {
                    DefinitionId clicked = visibleIds.get(index);
                    if (clicked.equals(selectedId) && doubleClick) {
                        // 双击直接进 SNBT 页 —— 库的主动作就是「看/改这个编号的数据」
                        openSnbt();
                    } else {
                        selectedId = clicked;
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        EditorLayout layout = layout();
        if (mouseX >= layout.innerLeft() && mouseX <= layout.innerLeft() + layout.innerWidth()) {
            int contentHeight = visibleIds.size() * ROW_HEIGHT + EditorLayout.PADDING * 2;
            int maxScroll = Math.max(0, contentHeight - layout.contentHeight());
            scroll = (int) Math.max(0, Math.min(maxScroll, scroll - scrollY * ROW_HEIGHT));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // --- 动作 ---------------------------------------------------------------

    private void createNew() {
        ModItemLibrary library = Hanshu_item.library();
        String base = "new_item";
        int index = 1;
        DefinitionId id = DefinitionId.parse(base);
        while (library.find(id).isPresent()) {
            id = DefinitionId.parse(base + "_" + index++);
        }
        ItemDefinition fresh = ItemDefinition.blank(id);
        // 先落库再进编辑页：编辑页是直接从运行库读定义的，
        // 只放进草稿的话它立刻会显示「定义已不存在」
        if (!commit(Map.of(id, fresh))) {
            return;
        }
        selectedId = id;
        rebuildVisibleIds();
        status = "已创建 " + id.full();
        statusColor = EditorWidgets.COLOR_TEXT;
        openSelected();
    }

    /** 打开选中编号的 SNBT 页面 —— 库的主入口。 */
    private void openSnbt() {
        ItemDefinition definition = current();
        if (definition == null) {
            status = "请先在列表里选中一个物品";
            statusColor = EditorWidgets.COLOR_WARN;
            return;
        }
        minecraft.setScreen(new LibraryNbtScreen(this, definition.id()));
    }

    /** 按当前定义构建一件给玩家。 */
    private void giveSelected() {
        ItemDefinition definition = current();
        if (definition == null) {
            status = "请先在列表里选中一个物品";
            statusColor = EditorWidgets.COLOR_WARN;
            return;
        }
        var player = minecraft.player;
        if (player == null) {
            status = "不在游戏里";
            statusColor = EditorWidgets.COLOR_ERROR;
            return;
        }
        var built = ModItemBuilder.build(definition, player, 1);
        if (!player.getInventory().add(built.stack())) {
            player.drop(built.stack(), false);
        }
        player.containerMenu.broadcastChanges();
        status = "已拿取一个 " + definition.id().full();
        statusColor = EditorWidgets.COLOR_TEXT;
    }

    /** 编号物品原生 NBT 编辑器。 */
    private void openSelected() {
        ItemDefinition definition = current();
        if (definition == null) {
            status = "请先在列表里选中一个物品";
            statusColor = EditorWidgets.COLOR_WARN;
            return;
        }
        minecraft.setScreen(new LibraryNbtScreen(this, definition.id()));
    }

    private void duplicateSelected() {
        ItemDefinition source = current();
        if (source == null) {
            status = "请先选中一个物品";
            statusColor = EditorWidgets.COLOR_WARN;
            return;
        }
        ModItemLibrary library = Hanshu_item.library();
        DefinitionId id = DefinitionId.parse(source.id().path() + "_copy");
        int index = 1;
        while (library.find(id).isPresent()) {
            id = DefinitionId.parse(source.id().path() + "_copy" + index++);
        }
        if (!commit(Map.of(id, copyOf(source, id)))) {
            return;
        }
        selectedId = id;
        rebuildVisibleIds();
        buildWidgets();
        status = "已复制为 " + id.full();
        statusColor = EditorWidgets.COLOR_TEXT;
    }

    private void deleteSelected() {
        if (selectedId == null) {
            status = "请先选中一个物品";
            statusColor = EditorWidgets.COLOR_WARN;
            return;
        }
        DefinitionId removed = selectedId;
        Map<DefinitionId, ItemDefinition> mutations = new LinkedHashMap<>();
        mutations.put(removed, null);
        if (!commit(mutations)) {
            return;
        }
        selectedId = null;
        rebuildVisibleIds();
        buildWidgets();
        status = "已删除 " + removed.full();
        statusColor = EditorWidgets.COLOR_TEXT;
    }

    private void reload() {
        try {
            ModItemLibrary.CommitResult result = Hanshu_item.library().load();
            selectedId = null;
            rebuildVisibleIds();
            buildWidgets();
            status = "已从磁盘重载：" + result.itemCount() + " 个定义";
            statusColor = EditorWidgets.COLOR_TEXT;
        } catch (IOException e) {
            status = "重载失败: " + e.getMessage();
            statusColor = EditorWidgets.COLOR_ERROR;
        }
    }

    private void validateDraft() {
        List<DefinitionIssue> all = collectIssues();
        issues.clear();
        issues.addAll(all);
        long errors = all.stream().filter(i -> i.severity() == DefinitionIssue.Severity.ERROR).count();
        long warnings = all.stream().filter(i -> i.severity() == DefinitionIssue.Severity.WARNING).count();
        status = "校验完成：" + errors + " 个错误，" + warnings + " 个警告";
        statusColor = errors > 0 ? EditorWidgets.COLOR_ERROR
                : (warnings > 0 ? EditorWidgets.COLOR_WARN : EditorWidgets.COLOR_TEXT);
    }

    private List<DefinitionIssue> collectIssues() {
        Map<DefinitionId, ItemDefinition> all = Hanshu_item.library().definitions().definitions();
        List<DefinitionIssue> result = new ArrayList<>(
                sweda.hanshu_item.overture.model.DefinitionStore.validate(all, Hanshu_item.MODID));
        return result;
    }

    static ItemDefinition copyOf(ItemDefinition source, DefinitionId newId) {
        // 复制时保留原生组件和同步排除规则。
        return source.withId(newId);
    }

    /**
     * 供详情页返回时同步。
     *
     * <p>详情页本来就已经直接写运行库了（见 {@code LibraryNbtScreen.saveDefinition}），
     * 这里保留这个方法只是为了兼容它的调用点；真正的状态始终在运行库。
     */
    void onDraftChanged(ItemDefinition definition) {
        // 运行库是唯一状态，详情页自己会写；这里只需刷新一次列表
        rebuildVisibleIds();
    }

    /** 详情页读取定义（只读视图）。 */
    Map<DefinitionId, ItemDefinition> draftView() {
        return Hanshu_item.library().definitions().definitions();
    }

    /** 详情页用于「上一个/下一个」导航。 */
    List<DefinitionId> visibleIds() {
        return visibleIds;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
