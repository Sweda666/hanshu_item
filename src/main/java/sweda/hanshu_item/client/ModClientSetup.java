package sweda.hanshu_item.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import sweda.hanshu_item.Config;

/**
 * 客户端入口：注册打开编辑器的快捷键。
 *
 * <p>用 {@code Dist.CLIENT} 限定，专用服务端上不会加载这个类，也就不会引用任何仅客户端存在的类。
 *
 * <p>编辑页面默认关闭，需在配置中设置 {@code enableEditor=true}。
 * 快捷键默认是 <b>K</b>（可在原版按键设置里改）。打开编辑器不需要任何权限 ——
 * 它编辑的是本地定义库；要让改动对多人游戏生效，由服务端把定义下发或共享同一份文件。
 */
@Mod.EventBusSubscriber(modid = sweda.hanshu_item.Hanshu_item.MODID, value = Dist.CLIENT)
public final class ModClientSetup {

    /** 快捷键分类；26.1 要求注册一个带命名空间的分类。 */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath(
                    sweda.hanshu_item.Hanshu_item.MODID, "editor"));

    /**
     * 打开物品库 —— 库的主入口。
     *
     * <p>库页面列举每个编号 ID，选中后进 SNBT 页改数据、拿物品、把手上物品的数据捕获回定义。
     */
    public static final KeyMapping OPEN_EDITOR = new KeyMapping(
            "key.hanshu_item.open_editor",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY);

    /** 打开物品选择器（从背包/装备里挑一件，直接新建定义）。 */
    public static final KeyMapping OPEN_LIBRARY = new KeyMapping(
            "key.hanshu_item.open_library",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_L,
            CATEGORY);

    private ModClientSetup() {
    }

    /** 注册按键映射（模组总线事件）。 */
    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_EDITOR);
        event.register(OPEN_LIBRARY);
    }

    /** 每客户端 tick 检查按键。 */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        while (OPEN_EDITOR.consumeClick()) {
            if (!Config.enableEditor) {
                continue;
            }
            // K = 物品库：先看有哪些编号，再进去改
            minecraft.setScreen(new ItemListScreen());
        }
        while (OPEN_LIBRARY.consumeClick()) {
            if (!Config.enableEditor) {
                continue;
            }
            // L = 从背包里挑一件当模板，快速新建编号
            minecraft.setScreen(new ItemPickerScreen(null));
        }
    }
}
