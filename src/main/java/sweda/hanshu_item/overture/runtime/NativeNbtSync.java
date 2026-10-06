package sweda.hanshu_item.overture.runtime;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.nbt.*;
import java.util.*;
import sweda.hanshu_item.overture.model.SyncFlags;

/** 将定义组件应用到物品，并按原生 NBT 路径保留被排除的现有值。 */
public final class NativeNbtSync {
    private NativeNbtSync() {}

    public static String normalizePath(String input) {
        String path = input.trim();
        if (path.equals("nbt")) return "components.\"minecraft:custom_data\"";
        if (path.startsWith("nbt.")) return "components.\"minecraft:custom_data\"." + path.substring(4);
        if (!path.startsWith("components.")) throw new IllegalArgumentException("路径必须以 nbt 或 components 开头");
        return path;
    }

    public static NbtPathArgument.NbtPath parsePath(String path) {
        try {
            StringReader reader = new StringReader(normalizePath(path));
            var parsed = NbtPathArgument.nbtPath().parse(reader);
            if (reader.canRead()) throw new IllegalArgumentException("NBT 路径未解析完整");
            return parsed;
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("NBT 路径格式不正确: " + path, e);
        }
    }

    /** 路径支持带引号的键名和列表下标；一个排除路径只指向一个节点。 */
    public static CompoundTag merge(CompoundTag defined, CompoundTag current, SyncFlags flags) {
        CompoundTag result = current.copy();
        // 组件移除标记与赋值标记互斥。custom_data 内递归合并，未知第三方键保留。
        for (String key : defined.keySet()) {
            String counterpart = key.startsWith("!") ? key.substring(1) : "!" + key;
            result.remove(counterpart);
            Tag value = defined.get(key);
            if (value instanceof CompoundTag compound && result.get(key) instanceof CompoundTag existing) {
                result.put(key, overlay(compound, existing));
            } else result.put(key, value.copy());
        }
        CompoundTag originalDocument = document(current);
        CompoundTag mergedDocument = document(result);
        for (String path : flags.excludedPaths()) {
            var parsed = parsePath(path);
            List<Tag> originals;
            try { originals = parsed.get(originalDocument); }
            catch (CommandSyntaxException e) { originals = List.of(); }
            if (originals.size() > 1) throw new IllegalArgumentException("同步排除路径只能指向一个节点: " + path);
            try {
                if (originals.isEmpty()) parsed.remove(mergedDocument);
                else parsed.set(mergedDocument, originals.getFirst().copy());
                // 排除整个组件时连同组件的显式移除状态一起保留。
                String canonical = normalizePath(path);
                for (String key : defined.keySet()) {
                    String component = key.startsWith("!") ? key.substring(1) : key;
                    String componentPath = "components." + quote(component);
                    if (canonical.equals(componentPath)) {
                        var merged = mergedDocument.getCompound("components").orElseThrow();
                        merged.remove(component);
                        merged.remove("!" + component);
                        Tag old = current.get(component);
                        Tag removed = current.get("!" + component);
                        if (old != null) merged.put(component, old.copy());
                        if (removed != null) merged.put("!" + component, removed.copy());
                    }
                }
            } catch (CommandSyntaxException e) {
                throw new IllegalArgumentException("无法保留排除路径: " + path, e);
            }
        }
        var merged = mergedDocument.getCompound("components").orElseThrow();
        for (String key : new ArrayList<>(merged.keySet())) {
            if (key.startsWith("!") && merged.contains(key.substring(1))) merged.remove(key);
        }
        return merged;
    }

    private static CompoundTag overlay(CompoundTag defined, CompoundTag current) {
        CompoundTag result = current.copy();
        for (String key : defined.keySet()) {
            Tag value = defined.get(key);
            if (value instanceof CompoundTag compound && current.get(key) instanceof CompoundTag existing) {
                result.put(key, overlay(compound, existing));
            } else result.put(key, value.copy());
        }
        return result;
    }

    public static CompoundTag document(CompoundTag components) {
        CompoundTag doc = new CompoundTag();
        doc.put("components", components.copy());
        return doc;
    }

    public static String quote(String key) {
        if (key.matches("[a-zA-Z0-9_+-]+")) return key;
        return "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
