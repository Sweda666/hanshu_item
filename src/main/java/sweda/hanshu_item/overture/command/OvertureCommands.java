package sweda.hanshu_item.overture.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import sweda.hanshu_item.Hanshu_item;
import sweda.hanshu_item.overture.model.*;
import sweda.hanshu_item.overture.runtime.*;
import java.io.IOException;
import java.util.*;

/** 管理编号物品的原生 NBT，不提供技能、冷却或随机词条功能。 */
public final class OvertureCommands {
    private OvertureCommands() {}
    private static boolean isOperator(CommandSourceStack source) {
        return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var syncCommand = Commands.literal("sync").executes(context -> sync(context, null))
                .then(Commands.argument("id", IdentifierArgument.id()).suggests(OvertureCommands::suggestIds)
                        .executes(context -> sync(context, rawArgument(context, "id"))));
        var rulesCommand = Commands.literal("rules")
                .then(Commands.literal("list").executes(context -> listRules(context, null))
                        .then(Commands.argument("id", IdentifierArgument.id()).suggests(OvertureCommands::suggestIds)
                                .executes(context -> listRules(context, rawArgument(context, "id")))))
                .then(Commands.literal("reset").executes(context -> setRules(context, null, true, null))
                        .then(Commands.argument("id", IdentifierArgument.id()).suggests(OvertureCommands::suggestIds)
                                .executes(context -> setRules(context, null, true, rawArgument(context, "id")))))
                .then(Commands.literal("set")
                        .then(Commands.argument("path", NbtPathArgument.nbtPath())
                                .then(Commands.literal("on").executes(context ->
                                        setRules(context, rawArgument(context, "path"), true, null))
                                        .then(Commands.argument("id", IdentifierArgument.id()).suggests(OvertureCommands::suggestIds)
                                                .executes(context -> setRules(context, rawArgument(context, "path"), true,
                                                        rawArgument(context, "id")))))
                                .then(Commands.literal("off").executes(context ->
                                        setRules(context, rawArgument(context, "path"), false, null))
                                        .then(Commands.argument("id", IdentifierArgument.id()).suggests(OvertureCommands::suggestIds)
                                                .executes(context -> setRules(context, rawArgument(context, "path"), false,
                                                        rawArgument(context, "id")))))));
        var root = Commands.literal("hanshu").requires(OvertureCommands::isOperator)
                .executes(OvertureCommands::help)
                .then(Commands.literal("list").executes(OvertureCommands::list))
                .then(Commands.literal("info").executes(OvertureCommands::info))
                .then(Commands.literal("unbind").executes(OvertureCommands::unbind))
                .then(Commands.literal("bind")
                        .then(Commands.argument("id", IdentifierArgument.id()).suggests(OvertureCommands::suggestIds)
                                .executes(OvertureCommands::bind)))
                .then(Commands.literal("save")
                        .then(Commands.argument("id", IdentifierArgument.id()).suggests(OvertureCommands::suggestIds)
                                .executes(OvertureCommands::saveHeld)))
                .then(syncCommand)
                .then(rulesCommand);
        root.then(Commands.literal("get")
                .then(Commands.argument("id", IdentifierArgument.id())
                        .suggests(OvertureCommands::suggestIds)
                        .executes(context -> get(context, 1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                                .executes(context -> get(context,
                                        IntegerArgumentType.getInteger(context, "amount"))))));
        dispatcher.register(root);
    }

    private static String rawArgument(CommandContext<CommandSourceStack> context, String name) {
        return context.getNodes().stream().filter(node -> node.getNode().getName().equals(name))
                .map(node -> node.getRange().get(context.getInput())).findFirst().orElseThrow();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestIds(CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(Hanshu_item.library().view().items().stream()
                .map(item -> item.id().namespace().equals(DefinitionId.DEFAULT_NAMESPACE)
                        ? item.id().path() : item.id().full()), builder);
    }

    private static int failure(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
        return 0;
    }

    private static int success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) failure(context, "该命令需要由玩家执行");
        return player;
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        for (String line : List.of(
                "/hanshu save <编号> — 保存并覆盖主手物品为编号模板",
                "/hanshu get <编号> [数量] — 取出编号物品",
                "/hanshu list / info — 列出编号 / 查看主手编号与规则",
                "/hanshu bind <编号> / unbind — 绑定或移除主手编号",
                "/hanshu rules set <路径> on|off [编码ID] — 设置字段同步规则",
                "/hanshu rules list|reset [编码ID] — 查看或清除规则",
                "/hanshu sync [编号] — 手动检查并同步落后物品",
                "省略编码 ID 时使用主手编号；主手没有编号时会报错。",
                "路径示例：nbt.GunCurrentAmmoCount；components.\"minecraft:damage\"")) {
            success(context, line);
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        var items = Hanshu_item.library().view().items();
        if (items.isEmpty()) return success(context, "编号库为空，手持物品执行 /hanshu save <编号> 创建。");
        for (var item : items) success(context, item.id().full() + " [" + item.icon() + "]");
        return items.size();
    }

    private static int saveHeld(CommandContext<CommandSourceStack> context) {
        var library = Hanshu_item.library();
        DefinitionId id = DefinitionId.tryParse(rawArgument(context, "id"));
        if (id == null || !id.isItem()) return failure(context, "编号格式不正确");
        ServerPlayer player = player(context);
        if (player == null) return 0;
        if (player.getMainHandItem().isEmpty()) return failure(context, "主手没有物品");

        ItemDefinition previous = library.find(id).orElse(null);
        ItemDefinition definition;
        try {
            definition = SavedItemComponents.capture(player.getMainHandItem(), id, library,
                    player.level().registryAccess());
        } catch (RuntimeException e) {
            return failure(context, "保存原生 NBT 失败: " + e.getMessage());
        }
        var result = library.put(definition);
        if (!result.accepted()) return failure(context, "编号定义不合法: " + result.errors());
        try {
            ItemSyncService.saveAndNote();
        } catch (IOException e) {
            if (previous == null) library.remove(id);
            else library.put(previous);
            return failure(context, "写盘失败，已恢复旧定义: " + e.getMessage());
        }
        ModItemBuilder.stamp(player.getMainHandItem(), definition);
        player.inventoryMenu.broadcastChanges();
        return success(context, (previous == null ? "已保存编号 " : "已覆盖编号 ") + id.full());
    }
    private static int get(CommandContext<CommandSourceStack> context, int amount) {
        ServerPlayer player = player(context);
        if (player == null) return 0;
        var definition = Hanshu_item.library().find(rawArgument(context, "id"));
        if (definition.isEmpty()) return failure(context, "找不到编号");
        try {
            ItemStack template = ModItemBuilder.build(definition.get(), player, 1).stack();
            int remaining = amount;
            while (remaining > 0) {
                int count = Math.min(remaining, template.getMaxStackSize());
                ItemStack portion = template.copyWithCount(count);
                remaining -= count;
                if (!player.getInventory().add(portion)) player.drop(portion, false);
            }
            player.inventoryMenu.broadcastChanges();
            return success(context, "已取出 " + amount + " 个 " + definition.get().id().full());
        } catch (RuntimeException e) { return failure(context, "物品构建失败: " + e.getMessage()); }
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = player(context);
        if (player == null) return 0;
        DefinitionId id = ItemDataAccessor.definitionId(player.getMainHandItem());
        if (id == null) return failure(context, "主手物品尚未绑定编号");
        var definition = Hanshu_item.library().find(id);
        if (definition.isEmpty()) return failure(context, "编号定义不存在: " + id.full());
        return success(context, "编号: " + id.full() + "；物品类型: " + definition.get().icon()
                + "；JSON 排除路径: " + definition.get().syncFlags().toList());
    }

    private static int unbind(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = player(context);
        if (player == null) return 0;
        ItemStack stack = player.getMainHandItem();
        DefinitionId id = ItemDataAccessor.definitionId(stack);
        if (id == null) return failure(context, "主手物品没有同步编号，无需移除");
        if (!ItemDataAccessor.unbind(stack)) return failure(context, "移除同步编号失败");
        player.inventoryMenu.broadcastChanges();
        return success(context, "已移除主手物品的同步编号 " + id.full() + "，保留其它原生 NBT；该物品不再自动同步");
    }

    private static int bind(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = player(context);
        if (player == null) return 0;
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) return failure(context, "主手没有物品");
        DefinitionId id = DefinitionId.tryParse(rawArgument(context, "id"));
        if (id == null || !id.isItem()) return failure(context, "编码 ID 格式不正确");
        var definition = Hanshu_item.library().find(id);
        if (definition.isEmpty()) return failure(context, "编码 ID 对应的定义不存在: " + id.full());
        String actualIcon = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!actualIcon.equals(definition.get().icon())) {
            return failure(context, "主手物品类型是 " + actualIcon + "，编号模板要求 " + definition.get().icon()
                    + "；请使用 /hanshu get " + id.full() + " 取出模板物品，或先用 /hanshu save <编号> 覆盖模板");
        }
        try {
            ModItemBuilder.refresh(stack, definition.get(), player);
            player.inventoryMenu.broadcastChanges();
            return success(context, "已将主手物品绑定到 " + id.full() + "，并按模板同步原生 NBT");
        } catch (RuntimeException e) {
            return failure(context, "绑定失败: " + e.getMessage());
        }
    }

    private static int sync(CommandContext<CommandSourceStack> context, String rawId) {
        var server = context.getSource().getServer();
        var result = rawId == null ? ItemSyncService.syncAll(server)
                : ItemSyncService.syncById(server, DefinitionId.parse(rawId));
        return success(context, result.summary());
    }

    private static DefinitionId ruleId(CommandContext<CommandSourceStack> context, String rawId) {
        if (rawId != null) {
            var id = DefinitionId.tryParse(rawId);
            if (id == null || !id.isItem()) {
                failure(context, "编码 ID 格式不正确: " + rawId);
                return null;
            }
            return id;
        }
        var player = context.getSource().getPlayer();
        if (player == null) {
            failure(context, "未输入编码 ID；执行者不是玩家，请在指令末尾指定编码 ID");
            return null;
        }
        var id = ItemDataAccessor.definitionId(player.getMainHandItem());
        if (id == null) failure(context, "未输入编码 ID，且主手物品没有编码 ID");
        return id;
    }

    private static int listRules(CommandContext<CommandSourceStack> context, String rawId) {
        var id = ruleId(context, rawId);
        if (id == null) return 0;
        var definition = Hanshu_item.library().find(id);
        if (definition.isEmpty()) return failure(context, "编号定义不存在: " + id.full());
        return success(context, "编号 " + id.full() + " 的 JSON 排除路径: " + definition.get().syncFlags().toList());
    }

    private static int setRules(CommandContext<CommandSourceStack> context, String path, boolean participates, String rawId) {
        var id = ruleId(context, rawId);
        if (id == null) return 0;
        var definition = Hanshu_item.library().find(id);
        if (definition.isEmpty()) return failure(context, "编码 ID 对应的定义不存在: " + id.full());
        try {
            SyncFlags flags = path == null ? SyncFlags.none() : definition.get().syncFlags();
            if (path != null) {
                path = NativeNbtSync.normalizePath(path);
                NativeNbtSync.parsePath(path);
                flags = flags.withSubtreeParticipates(path, participates);
            }
            Hanshu_item.library().updateSyncRules(id, flags);
            ItemSyncService.noteOwnWrite();
        } catch (IOException | RuntimeException e) { return failure(context, "保存编号同步规则失败: " + e.getMessage()); }
        success(context, "已保存编号 " + id.full() + " 的 JSON 同步规则，对全部同编号物品生效"
                + (path == null ? "；全部字段参与同步" : "；" + path + " = " + participates));
        return success(context, ItemSyncService.syncById(context.getSource().getServer(), id).summary());
    }
}
