package sweda.hanshu_item.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;
import sweda.hanshu_item.overture.model.DefinitionId;

/** 编号模板和单件物品共用原生组件树编辑器。 */
public final class LibraryNbtScreen extends RawNbtScreen {
    public LibraryNbtScreen(@Nullable Screen parent, DefinitionId id) {
        super(parent, Minecraft.getInstance().player, id);
    }
}