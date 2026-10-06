package sweda.hanshu_item.overture.model;

import com.google.gson.*;
import java.util.*;

/** 编号物品定义只保存物品类型、原生组件 SNBT 和同步排除路径。 */
public final class DefinitionJson {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private DefinitionJson() {}

    public static Gson gson() { return GSON; }

    public static JsonObject encodeDefinition(ItemDefinition definition) {
        JsonObject root = new JsonObject();
        root.addProperty("icon", definition.icon());
        root.addProperty("nbt", definition.nbt());
        if (!definition.syncFlags().isEmpty()) {
            JsonArray excluded = new JsonArray();
            definition.syncFlags().toList().forEach(excluded::add);
            root.add("sync-exclude", excluded);
        }
        return root;
    }

    public static ItemDefinition decodeDefinition(DefinitionId id, JsonObject root) {
        String icon = root.has("icon") ? root.get("icon").getAsString() : ItemDefinition.DEFAULT_ICON;
        String nbt = root.has("nbt") ? root.get("nbt").getAsString() : "{}";
        // 兼容此前 save 指令捕获的原生模板；旧玩法数据不转成原生 NBT。
        if (!root.has("nbt") && root.has("meta") && root.get("meta").isJsonObject()) {
            JsonElement captured = root.getAsJsonObject("meta").get("components");
            if (captured != null && captured.isJsonPrimitive() && captured.getAsJsonPrimitive().isString()) {
                nbt = captured.getAsString();
            }
        }
        List<String> excluded = new ArrayList<>();
        if (root.has("nbt") && root.has("sync-exclude")) {
            for (JsonElement path : root.getAsJsonArray("sync-exclude")) {
                excluded.add(path.getAsString());
            }
        }
        return new ItemDefinition(id, icon, nbt, SyncFlags.of(excluded));
    }

    public static JsonObject encodeAll(Map<DefinitionId, ItemDefinition> definitions) {
        JsonObject root = new JsonObject();
        new TreeMap<>(definitions).forEach((id, definition) -> root.add(id.full(), encodeDefinition(definition)));
        return root;
    }

    public static Map<DefinitionId, ItemDefinition> decodeAll(JsonObject root, String namespace) {
        return decodeAll(root, namespace, new ArrayList<>());
    }

    public static Map<DefinitionId, ItemDefinition> decodeAll(JsonObject root, String namespace,
                                                            List<DefinitionIssue> issues) {
        Map<DefinitionId, ItemDefinition> result = new LinkedHashMap<>();
        for (var entry : root.entrySet()) {
            try {
                String raw = entry.getKey();
                DefinitionId id = DefinitionId.parse(raw.contains(":") ? raw : namespace + ":" + raw);
                if (!id.isItem()) {
                    issues.add(DefinitionIssue.warn("<items>", raw, null, "旧事件模型或保留项不再加载"));
                    continue;
                }
                result.put(id, decodeDefinition(id, entry.getValue().getAsJsonObject()));
            } catch (RuntimeException e) {
                issues.add(DefinitionIssue.error("<items>", entry.getKey(), null, "定义解析失败: " + e.getMessage()));
            }
        }
        return result;
    }

    public static JsonObject parseObject(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    public static String toPrettyString(JsonElement json) { return GSON.toJson(json); }
}