package sweda.hanshu_item.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.Hanshu_item;
import sweda.hanshu_item.overture.model.DefinitionId;
import sweda.hanshu_item.overture.model.ItemDefinition;
import sweda.hanshu_item.overture.runtime.ItemDataAccessor;
import sweda.hanshu_item.overture.runtime.ModItemLibrary;
import sweda.hanshu_item.overture.runtime.ItemSyncService;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑器入口 —— 从背包 / 手持物品里挑一个来编辑。
 *
 * <p>这是按实际使用习惯改的：之前要先进「物品库」再选一个抽象定义，
 * 而真正想改的往往是手里或背包里那个具体物品。CAD Editor 就是这种「就地编辑」的思路。
 *
 * <p>布局照搬 CAD Editor 的骨架：
 * <pre>
 *  ┌──────────────────────────────────────────────┐
 *  │                 选择物品                      │  ← 头部居中
 *  ├──────────────────────────────────────────────┤
 *  │  [编辑手持] [物品库…] [新建…]                  │  ← 按钮栏
 *  │   手持 / 副手 / 护甲                          │  ← 装备行
 *  │   ┌──┬──┬──┬──┬──┬──┬──┬──┬──┐              │  ← 背包网格（9 列）
 *  │   ├──┼──┼──┼──┼──┼──┼──┼──┼──┤              │
 *  │   └──┴──┴──┴──┴──┴──┴──┴──┴──┘              │
 *  ├──────────────────────────────────────────────┤
 *  │        空格子=点击创建 · 已有定义=直接编辑      │  ← 提示
 *  │                [打开物品库] [关闭]              │
 *  └──────────────────────────────────────────────┘
 * </pre>
 *
 * <p>点击规则：
 * <ul>
 *   <li>物品<b>已受本模组管理</b>（带 {@code hanshu_item} 数据）→ 直接打开它的定义</li>
 *   <li>物品是普通原版物品 → 自动为它创建一个定义（图标/名称取自该物品）并立即开始编辑</li>
 * </ul>
 */
public class ItemPickerScreen extends Screen {

    private static final int SLOT_SIZE = 18;
    private static final int COLUMNS = 9;

    /** 背包主区 27 格。 */
    private static final int MAIN_SLOTS = 27;

    private final ItemListScreen libraryParent;

    private int gridLeft;
    private int gridTop;
    private int equippedTop;
    private String status = "点背包里的物品即可编辑（会自动为它创建定义）";
    private int statusColor = EditorWidgets.COLOR_DIM;
    private @Nullable String hoveredTip;

    public ItemPickerScreen(@Nullable ItemListScreen libraryParent) {
        super(Component.literal("选择要编辑的物品"));
        this.libraryParent = libraryParent;
    }

    private EditorLayout layout() {
        return EditorLayout.compute(width, height);
    }

    @Override
    protected void init() {
        EditorLayout layout = layout();
        int centerX = layout.panelX() + layout.panelWidth() / 2;

        // 顶部一行按钮，全部居中并按可用宽度自动排布。
        // 注意只构造一次：按钮一旦 addRenderableWidget 就被 Screen 接管，
        // 重复构造同一批按钮会让它们叠在同一个坐标上，点哪个都说不准。
        int usable = layout.panelWidth() + 40;
        List<Button> top = List.of(
                EditorWidgets.smallButton(font, "原始 NBT…", b -> openRawNbt()),
                EditorWidgets.smallButton(font, "编辑定义", b -> editHeld()),
                EditorWidgets.smallButton(font, "物品库…", b -> openLibrary()),
                EditorWidgets.smallButton(font, "新建物品", b -> createAndEdit()));
        EditorWidgets.layoutRow(top, centerX, layout.contentTop() + 2, 4, usable);
        top.forEach(this::addRenderableWidget);

        // 网格尺寸
        int gridWidth = COLUMNS * SLOT_SIZE;
        gridLeft = centerX - gridWidth / 2;
        equippedTop = layout.contentTop() + 24;
        gridTop = equippedTop + SLOT_SIZE + 8;

        List<Button> footer = List.of(
                EditorWidgets.smallButton(font, "打开物品库", b -> openLibrary()),
                EditorWidgets.smallButton(font, "关闭", b -> onClose()));
        EditorWidgets.layoutRow(footer, centerX, layout.toolbarY() + EditorLayout.BUTTON_ROW_2_OFFSET, 4, usable);
        footer.forEach(this::addRenderableWidget);
    }

    /** 直接编辑手持物品的原始 NBT 与同步参与状态。 */
    private void openRawNbt() {
        Minecraft.getInstance().setScreen(new RawNbtScreen(this, player()));
    }

    private Player player() {
        return Minecraft.getInstance().player;
    }

    /** 背包主区 27 格在网格里的索引范围。 */
    private static final int HOTBAR_START = 0;

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);

        EditorLayout layout = layout();
        extractor.centeredText(font, title, width / 2, layout.titleY(), EditorWidgets.COLOR_TEXT);

        Player player = player();
        if (player == null) {
            extractor.centeredText(font, Component.literal("没有玩家上下文"),
                    width / 2, equippedTop, EditorWidgets.COLOR_ERROR);
            return;
        }

        hoveredTip = null;
        drawEquipped(extractor, player, mouseX, mouseY);
        drawInventory(extractor, player, mouseX, mouseY);

        if (hoveredTip != null) {
            extractor.textWithBackdrop(font, Component.literal(hoveredTip), mouseX + 8, mouseY - 4,
                    layout.panelWidth(), 0xFF000000);
        }

        extractor.centeredText(font,
                Component.literal(EditorWidgets.clip(font, status, layout.panelWidth() + 40)),
                width / 2, layout.toolbarY() - 24, statusColor);
    }

    /** 护甲 + 双手：索引 36..39 是护甲，40 是副手，主手单独标注。 */
    private void drawEquipped(GuiGraphicsExtractor extractor, Player player, int mouseX, int mouseY) {
        Inventory inventory = player.getInventory();
        extractor.text(font, Component.literal("装备"), gridLeft, equippedTop - 10, EditorWidgets.COLOR_DIM);

        // 顺序：头盔 胸甲 护腿 靴子 副手 主手
        int[] sources = {39, 38, 37, 36, 40, -1};
        String[] names = {"头盔", "胸甲", "护腿", "靴子", "副手", "主手"};
        int x = gridLeft;
        for (int i = 0; i < sources.length; i++) {
            ItemStack stack = sources[i] < 0
                    ? inventory.getItem(inventory.getSelectedSlot())
                    : inventory.getItem(sources[i]);
            boolean hovered = drawSlot(extractor, x, equippedTop, stack, mouseX, mouseY);
            if (hovered) {
                hoveredTip = names[i] + "：" + (stack.isEmpty() ? "空" : stack.getHoverName().getString());
            }
            x += SLOT_SIZE + 2;
        }
    }

    private void drawInventory(GuiGraphicsExtractor extractor, Player player, int mouseX, int mouseY) {
        Inventory inventory = player.getInventory();
        extractor.text(font, Component.literal("背包"), gridLeft, gridTop - 10, EditorWidgets.COLOR_DIM);

        // 上 27 格（主背包）显示在网格里
        for (int index = 0; index < MAIN_SLOTS; index++) {
            int column = index % COLUMNS;
            int row = index / COLUMNS;
            int x = gridLeft + column * SLOT_SIZE;
            int y = gridTop + row * SLOT_SIZE;
            // Inventory 槽位 9..35 是主背包，0..8 是快捷栏
            ItemStack stack = inventory.getItem(index + 9);
            boolean hovered = drawSlot(extractor, x, y, stack, mouseX, mouseY);
            if (hovered && !stack.isEmpty()) {
                DefinitionId bound = ItemDataAccessor.definitionId(stack);
                hoveredTip = stack.getHoverName().getString()
                        + (bound == null ? "（未绑定定义）" : "  →  " + bound.full());
            }
        }

        // 快捷栏单独一行
        int hotbarY = gridTop + 3 * SLOT_SIZE;
        extractor.text(font, Component.literal("快捷栏"), gridLeft, hotbarY - 10, EditorWidgets.COLOR_DIM);
        for (int index = 0; index < COLUMNS; index++) {
            ItemStack stack = inventory.getItem(HOTBAR_START + index);
            int x = gridLeft + index * SLOT_SIZE;
            int y = hotbarY;
            boolean hovered = drawSlot(extractor, x, y, stack, mouseX, mouseY);
            if (hovered && !stack.isEmpty()) {
                hoveredTip = stack.getHoverName().getString();
            }
        }
    }

    /** 画一个物品格；返回是否被悬停。 */
    private boolean drawSlot(GuiGraphicsExtractor extractor, int x, int y, ItemStack stack,
                             int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + SLOT_SIZE - 2 && mouseY >= y && mouseY < y + SLOT_SIZE - 2;
        extractor.fill(x, y, x + SLOT_SIZE - 2, y + SLOT_SIZE - 2, hovered ? 0xFF606060 : 0xFF303030);
        if (!stack.isEmpty()) {
            extractor.item(stack, x, y);
            extractor.itemDecorations(font, stack, x, y);
        }
        return hovered;
    }

    // --- 输入 ---------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            Player player = player();
            if (player != null) {
                ItemStack picked = stackAt(event.x(), event.y(), player);
                if (picked != null) {
                    if (picked.isEmpty()) {
                        status = "这一格是空的";
                        statusColor = EditorWidgets.COLOR_WARN;
                    } else {
                        openEditorFor(picked);
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** 命中测试：返回该坐标对应的物品格内容（空背包格返回 {@link ItemStack#EMPTY}），未命中返回 null。 */
    private @Nullable ItemStack stackAt(double mouseX, double mouseY, Player player) {
        Inventory inventory = player.getInventory();

        // 装备行
        int[] sources = {39, 38, 37, 36, 40, -1};
        int x = gridLeft;
        for (int source : sources) {
            if (hit(mouseX, mouseY, x, equippedTop)) {
                return source < 0 ? inventory.getItem(inventory.getSelectedSlot()) : inventory.getItem(source);
            }
            x += SLOT_SIZE + 2;
        }

        // 主背包 9..35 映射到网格
        for (int index = 0; index < MAIN_SLOTS; index++) {
            int column = index % COLUMNS;
            int row = index / COLUMNS;
            int slotX = gridLeft + column * SLOT_SIZE;
            int slotY = gridTop + row * SLOT_SIZE;
            if (hit(mouseX, mouseY, slotX, slotY)) {
                return inventory.getItem(index + 9);
            }
        }

        // 快捷栏
        int hotbarY = gridTop + 3 * SLOT_SIZE;
        for (int index = 0; index < COLUMNS; index++) {
            if (hit(mouseX, mouseY, gridLeft + index * SLOT_SIZE, hotbarY)) {
                return inventory.getItem(index);
            }
        }
        return null;
    }

    private static boolean hit(double mouseX, double mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + SLOT_SIZE - 2 && mouseY >= y && mouseY < y + SLOT_SIZE - 2;
    }

    // --- 动作 ---------------------------------------------------------------

    private void editHeld() {
        Player player = player();
        if (player == null) {
            return;
        }
        ItemStack held = player.getInventory().getItem(player.getInventory().getSelectedSlot());
        if (held.isEmpty()) {
            status = "主手是空的";
            statusColor = EditorWidgets.COLOR_WARN;
            return;
        }
        openEditorFor(held);
    }

    /**
     * 打开某个具体物品的编辑器。
     *
     * <p>没有绑定定义时自动创建一个：图标取自该物品，名称取自它当前的显示名，
     * 这样「拿起来就能改」而不是先让你去建一个空定义。
     */
    private void openEditorFor(ItemStack stack) {
        DefinitionId bound = ItemDataAccessor.definitionId(stack);
        ItemDefinition definition;
        boolean created = false;

        if (bound != null && Hanshu_item.library().find(bound).isPresent()) {
            definition = Hanshu_item.library().find(bound).orElseThrow();
        } else {
            definition = createDefinitionFor(stack);
            created = true;
        }

        if (created) {
            status = "已为 " + stack.getHoverName().getString() + " 创建定义 " + definition.id().full();
            statusColor = EditorWidgets.COLOR_TEXT;
        }
        Minecraft.getInstance().setScreen(
                new LibraryNbtScreen(this, definition.id()));
    }

    /** 为一件已有物品生成定义：ID 取自注册名，冲突则加后缀。 */
    private ItemDefinition createDefinitionFor(ItemStack stack) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        DefinitionId id = DefinitionId.parse(path);
        int suffix = 1;
        while (Hanshu_item.library().find(id).isPresent()) {
            id = DefinitionId.parse(path + "_" + suffix++);
        }

        ItemDefinition definition = sweda.hanshu_item.overture.runtime.SavedItemComponents.capture(
                stack, id, Hanshu_item.library(), Minecraft.getInstance().level.registryAccess());
        // 立即可用：写入运行库并落盘，避免「编辑完却没保存」导致下次又要重建
        ModItemLibrary.CommitResult result = Hanshu_item.library().put(definition);
        if (result.accepted()) {
            try {
                ItemSyncService.saveAndNote();
            } catch (java.io.IOException e) {
                Hanshu_item.LOGGER.warn("保存新建定义失败: {}", e.getMessage());
            }
        }
        return definition;
    }

    /** 创建空的编号模板并打开原生 NBT 编辑器。 */
    private void createAndEdit() {
        String base = "new_item";
        DefinitionId id = DefinitionId.parse(base);
        int suffix = 1;
        while (Hanshu_item.library().find(id).isPresent()) {
            id = DefinitionId.parse(base + "_" + suffix++);
        }
        ItemDefinition definition = ItemDefinition.blank(id);

        Hanshu_item.library().put(definition);
        try {
            ItemSyncService.saveAndNote();
        } catch (java.io.IOException e) {
            Hanshu_item.LOGGER.warn("保存新建定义失败: {}", e.getMessage());
        }
        Minecraft.getInstance().setScreen(new LibraryNbtScreen(this, id));
    }

    private void openLibrary() {
        Minecraft.getInstance().setScreen(libraryParent != null ? libraryParent : new ItemListScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
