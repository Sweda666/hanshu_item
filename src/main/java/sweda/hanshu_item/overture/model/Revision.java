package sweda.hanshu_item.overture.model;

import com.google.gson.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 只根据编号模板和规则计算签名；物品的弹药、耐久等使用状态不会触发重建。 */
public final class Revision {
    public static final String NONE = "";
    private Revision() {}

    public static String of(ItemDefinition definition) {
        try {
            JsonObject json = DefinitionJson.encodeDefinition(definition);
            json.addProperty("id", definition.id().full());
            json.addProperty("nbt", canonicalNbt(TagParser.parseCompoundFully(definition.nbt())));
            return sha256(canonicalize(json));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalArgumentException("物品组件 SNBT 格式不正确", e);
        }
    }

    public static String fingerprint(ItemDefinition definition, SyncFlags flags) {
        return sha256(of(definition) + "\n" + String.join("\n", flags.toList()));
    }

    public static String canonicalNbt(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>(compound.keySet());
            Collections.sort(keys);
            StringJoiner result = new StringJoiner(",", "{", "}");
            for (String key : keys) {
                result.add(new JsonPrimitive(key).toString() + ":" + canonicalNbt(compound.get(key)));
            }
            return result.toString();
        }
        if (tag instanceof ListTag list) {
            StringJoiner result = new StringJoiner(",", "[", "]");
            for (Tag child : list) result.add(canonicalNbt(child));
            return result.toString();
        }
        return tag.toString();
    }

    public static String canonicalize(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            new TreeMap<>(value.getAsJsonObject().asMap()).forEach((key, child) ->
                    sorted.add(key, JsonParser.parseString(canonicalize(child))));
            return sorted.toString();
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            value.getAsJsonArray().forEach(child -> array.add(JsonParser.parseString(canonicalize(child))));
            return array.toString();
        }
        return value.toString();
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}