package sweda.hanshu_item.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 编辑器控件集。
 *
 * <p>26.1 的 GUI 是「extract 渲染状态」模型：屏幕实现
 * {@code extractRenderState(GuiGraphicsExtractor,...)}，绘制用 {@code fill/text/item}。
 * 自定义控件继承 {@link AbstractWidget} 并实现 {@code extractWidgetRenderState}。
 */
public final class EditorWidgets {

    private EditorWidgets() {
    }

    public static final int COLOR_PANEL = 0xC0101010;
    public static final int COLOR_BORDER = 0xFF3A3A3A;
    public static final int COLOR_ROW_HOVER = 0x30FFFFFF;
    public static final int COLOR_ROW_SELECTED = 0x6070A0FF;
    public static final int COLOR_TEXT = 0xFFFFFFFF;
    public static final int COLOR_DIM = 0xFFA0A0A0;
    public static final int COLOR_WARN = 0xFFFFAA00;
    public static final int COLOR_ERROR = 0xFFFF5555;
    public static final int COLOR_ACCENT = 0xFF70A0FF;
    public static final int COLOR_LOCKED = 0xFFFFCC44;

    /** 带描边的面板。 */
    public static void panel(GuiGraphicsExtractor extractor, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        extractor.fill(x - 1, y - 1, x + width + 1, y + height + 1, COLOR_BORDER);
        extractor.fill(x, y, x + width, y + height, COLOR_PANEL);
    }

    /** 列表行。 */
    public static void row(GuiGraphicsExtractor extractor,
                           net.minecraft.client.gui.Font font,
                           int x, int y, int width, int height,
                           Component label,
                           @Nullable Component subtitle,
                           boolean hovered,
                           boolean selected) {
        int background = selected ? COLOR_ROW_SELECTED : (hovered ? COLOR_ROW_HOVER : 0);
        if (background != 0) {
            extractor.fill(x, y, x + width, y + height, background);
        }
        int textY = subtitle == null ? y + (height - 8) / 2 : y + 3;
        extractor.text(font, label, x + 3, textY, selected ? COLOR_TEXT : COLOR_DIM);
        if (subtitle != null) {
            extractor.text(font, subtitle, x + 3, textY + font.lineHeight + 1, COLOR_DIM);
        }
    }

    /**
     * {@code !!} 锁定开关 —— 对应 Overture 的锁定规则。
     *
     * <p>三态外观：未锁定（灰边）、部分锁定（黄边，表示子路径被锁）、锁定（实心黄）。
     */
    public static final class LockToggle extends AbstractWidget {

        private final Supplier<Boolean> stateSupplier;
        private final Supplier<Boolean> partialSupplier;
        private final Consumer<Boolean> onToggle;

        public LockToggle(int x, int y, int size,
                          Supplier<Boolean> stateSupplier,
                          Supplier<Boolean> partialSupplier,
                          Consumer<Boolean> onToggle) {
            super(x, y, size, size, Component.literal("!!"));
            this.stateSupplier = stateSupplier;
            this.partialSupplier = partialSupplier;
            this.onToggle = onToggle;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
            boolean locked = Boolean.TRUE.equals(stateSupplier.get());
            boolean partial = !locked && Boolean.TRUE.equals(partialSupplier.get());
            int border = locked ? COLOR_LOCKED : (partial ? COLOR_WARN : COLOR_BORDER);
            int fill = locked ? 0x60FFCC44 : (isHovered() ? COLOR_ROW_HOVER : 0x80000000);

            extractor.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), fill);
            extractor.fill(getX(), getY(), getX() + getWidth(), getY() + 1, border);
            extractor.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), border);
            extractor.fill(getX(), getY(), getX() + 1, getY() + getHeight(), border);
            extractor.fill(getX() + getWidth() - 1, getY(), getX() + getWidth(), getY() + getHeight(), border);
            extractor.centeredText(Minecraft.getInstance().font, "!!",
                    getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2 + 1,
                    locked ? COLOR_LOCKED : COLOR_DIM);
        }

        @Override
        public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            onToggle.accept(!Boolean.TRUE.equals(stateSupplier.get()));
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narration) {
            defaultButtonNarrationText(narration);
        }
    }

    /** 小按钮：宽度按文字自适应，避免固定宽度在窄屏上溢出。 */
    public static Button smallButton(net.minecraft.client.gui.Font font, String label, Button.OnPress onPress) {
        return Button.builder(Component.literal(label), onPress)
                .size(Math.max(28, font.width(label) + 10), 16)
                .build();
    }

    /** 按顺序水平排布一行按钮，自动居中；返回占用宽度。 */
    public static int layoutRow(List<Button> buttons, int centerX, int y, int gap, int maxWidth) {
        List<Button> visible = new ArrayList<>();
        int total = 0;
        for (Button button : buttons) {
            total += button.getWidth() + (visible.isEmpty() ? 0 : gap);
            if (total > maxWidth && !visible.isEmpty()) {
                break;
            }
            visible.add(button);
        }
        if (visible.isEmpty()) {
            return 0;
        }
        int x = centerX - total / 2;
        for (Button button : visible) {
            button.setX(x);
            button.setY(y);
            x += button.getWidth() + gap;
        }
        // 放不下的按钮移到屏幕外，避免堆在原点
        for (int i = visible.size(); i < buttons.size(); i++) {
            buttons.get(i).setX(-1000);
            buttons.get(i).setY(-1000);
        }
        return total;
    }

    /** 按像素宽度裁剪文本。 */
    public static String clip(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int limit = Math.max(0, maxWidth - font.width(ellipsis));
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (font.width(builder.toString() + text.charAt(i)) > limit) {
                break;
            }
            builder.append(text.charAt(i));
        }
        return builder + ellipsis;
    }

    public static int severityColor(sweda.hanshu_item.overture.model.DefinitionIssue.Severity severity) {
        return switch (severity) {
            case ERROR -> COLOR_ERROR;
            case WARNING -> COLOR_WARN;
            case INFO -> COLOR_DIM;
        };
    }
}
