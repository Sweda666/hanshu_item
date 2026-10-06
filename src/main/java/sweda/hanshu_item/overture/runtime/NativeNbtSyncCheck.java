package sweda.hanshu_item.overture.runtime;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import sweda.hanshu_item.overture.model.*;
import java.util.*;
import net.minecraft.nbt.*;

/** 验证弹药数量、父节点、组件和缺失字段的排除不会被模板覆盖。 */
public final class NativeNbtSyncCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var defined = SavedItemComponents.parse("{\"minecraft:custom_data\":{AmmoCount:30,Power:20,Gun:{Reloading:0b}},\"minecraft:damage\":1}");
        var current = SavedItemComponents.parse("{\"minecraft:custom_data\":{AmmoCount:7,Power:10,Gun:{Reloading:1b},OtherMod:42L},\"minecraft:damage\":17}");
        var flags = SyncFlags.of(List.of(NativeNbtSync.normalizePath("nbt.AmmoCount")));
        var result = NativeNbtSync.merge(defined, current, flags);
        var nbt = result.getCompound("minecraft:custom_data").orElseThrow();
        check(nbt.getInt("AmmoCount").orElseThrow() == 7, "排除弹药后仍为 7 发");
        check(nbt.getInt("Power").orElseThrow() == 20, "其他字段同步");
        check(nbt.getLong("OtherMod").orElseThrow() == 42L, "第三方字段保留");
        var allSync = NativeNbtSync.merge(defined, current, SyncFlags.none());
        check(allSync.getCompound("minecraft:custom_data").orElseThrow().getInt("AmmoCount").orElseThrow() == 30, "默认参与");
        var parentExcluded = NativeNbtSync.merge(defined, current, SyncFlags.of(List.of(
                NativeNbtSync.normalizePath("nbt.Gun"), "components.\"minecraft:damage\"")));
        check(parentExcluded.getCompound("minecraft:custom_data").orElseThrow().getCompound("Gun").orElseThrow()
                .getByte("Reloading").orElseThrow() == 1, "保留换弹状态");
        check(parentExcluded.getInt("minecraft:damage").orElseThrow() == 17, "保留耐久组件");
        var absent = NativeNbtSync.merge(defined, SavedItemComponents.parse("{}"), flags);
        check(!absent.getCompound("minecraft:custom_data").orElseThrow().contains("AmmoCount"), "缺失字段保持缺失");
        var removed = SavedItemComponents.parse("{\"!minecraft:damage\":{}}");
        var kept = NativeNbtSync.merge(removed, current, SyncFlags.of(List.of("components.\"minecraft:damage\"")));
        check(kept.getInt("minecraft:damage").orElseThrow() == 17 && !kept.contains("!minecraft:damage"), "排除组件不被移除");
        check(result.equals(NativeNbtSync.merge(defined, result, flags)), "同步幂等");
        var removeCustom = SavedItemComponents.parse("{\"!minecraft:custom_data\":{}}");
        var preservedAmmo = NativeNbtSync.merge(removeCustom, current, flags);
        check(preservedAmmo.getCompound("minecraft:custom_data").orElseThrow()
                .getInt("AmmoCount").orElseThrow() == 7 && !preservedAmmo.contains("!minecraft:custom_data"),
                "移除组件时仍保留排除的弹药字段");
        var quotedTemplate = SavedItemComponents.parse("{\"minecraft:custom_data\":{\"gun.state\":{Ammo:30},Magazines:[30,30]}}");
        var quotedCurrent = SavedItemComponents.parse("{\"minecraft:custom_data\":{\"gun.state\":{Ammo:7},Magazines:[7,5]}}");
        var quotedResult = NativeNbtSync.merge(quotedTemplate, quotedCurrent, SyncFlags.of(List.of(
                NativeNbtSync.normalizePath("nbt.\"gun.state\".Ammo"),
                NativeNbtSync.normalizePath("nbt.Magazines[1]"))));
        var quotedData = quotedResult.getCompound("minecraft:custom_data").orElseThrow();
        check(quotedData.getCompound("gun.state").orElseThrow().getInt("Ammo").orElseThrow() == 7,
                "带点号的原生键名可排除");
        check(quotedData.getList("Magazines").orElseThrow().get(0).equals(IntTag.valueOf(30))
                && quotedData.getList("Magazines").orElseThrow().get(1).equals(IntTag.valueOf(5)),
                "按列表下标保留单项");
        var definition = ItemDefinition.blank(DefinitionId.parse("1001")).withNbt(defined.toString());
        check(!Revision.of(definition).equals(Revision.of(definition.withNbt(current.toString()))), "模板修改产生新签名");
        var json = DefinitionJson.encodeDefinition(definition);
        check(!json.has("data") && !json.has("meta") && !json.has("event") && !json.has("quality"), "无玩法数据区");
        var legacy = DefinitionJson.parseObject("{\"icon\":\"minecraft:stone\",\"data\":{\"skill\":1},\"meta\":{}}");
        legacy.getAsJsonObject("meta").addProperty("components", defined.toString());
        check(defined.toString().equals(DefinitionJson.decodeDefinition(definition.id(), legacy).nbt()), "旧模板兼容");
        System.out.println("Native NBT ammo exclusion checks passed.");
    }
    private static void check(boolean passed, String message) { if (!passed) throw new AssertionError(message); }
}
