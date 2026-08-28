package dev.skullzz.mirage;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.block.DispenserBlock;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/** The {@code /mirage} command tree. Operators only. */
public final class MirageCommands {
    private static final SuggestionProvider<ServerCommandSource> SLOT_SUGGESTIONS =
            (context, builder) -> CommandSource.suggestMatching(GhostSlots.names(), builder);

    private MirageCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("mirage")
                .requires(source -> source.getPermissions().test(2))
                .then(ghost(registryAccess))
                .then(dispenser(registryAccess))
                .then(CommandManager.literal("refresh")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(MirageCommands::refresh))));
    }

    // ------------------------------------------------------------------ ghosts

    private static LiteralArgumentBuilder<ServerCommandSource> ghost(CommandRegistryAccess registryAccess) {
        return CommandManager.literal("ghost")
                .then(CommandManager.literal("set")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .then(CommandManager.argument("slot", StringArgumentType.word())
                                        .suggests(SLOT_SUGGESTIONS)
                                        .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
                                                .executes(context -> ghostSet(context, 1))
                                                .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                                        .executes(context -> ghostSet(context,
                                                                IntegerArgumentType.getInteger(context, "count"))))))))
                .then(CommandManager.literal("clear")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(MirageCommands::ghostClearAll)
                                .then(CommandManager.argument("slot", StringArgumentType.word())
                                        .suggests(SLOT_SUGGESTIONS)
                                        .executes(MirageCommands::ghostClearSlot))))
                .then(CommandManager.literal("list")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(MirageCommands::ghostList)));
    }

    private static int ghostSet(CommandContext<ServerCommandSource> context, int count) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
        String slotName = StringArgumentType.getString(context, "slot");

        int slot = GhostSlots.index(slotName);
        if (slot < 0) {
            context.getSource().sendError(Text.literal("Unknown slot '" + slotName
                    + "'. Use hotbar1-hotbar9, inv1-inv27, offhand, head, chest, legs or feet."));
            return 0;
        }

        ItemStack stack = ItemStackArgumentType.getItemStackArgument(context, "item").createStack(count, false);
        Map<Integer, ItemStack> ghosts = Mirage.STATE.ghosts
                .computeIfAbsent(target.getUuid(), uuid -> new LinkedHashMap<>());
        ghosts.put(slot, stack);

        Mirage.STATE.save(context.getSource().getServer());
        Mirage.pushGhosts(target, ghosts);

        context.getSource().sendFeedback(() -> Text.literal(nameOf(target) + " now sees "
                        + describe(stack) + " in " + slotName).formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int ghostClearAll(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");

        if (Mirage.STATE.ghosts.remove(target.getUuid()) == null) {
            context.getSource().sendError(Text.literal(nameOf(target) + " has no ghost items."));
            return 0;
        }

        Mirage.STATE.save(context.getSource().getServer());
        Mirage.resync(target);

        context.getSource().sendFeedback(() -> Text.literal("Cleared every ghost item from "
                + nameOf(target) + ".").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int ghostClearSlot(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
        String slotName = StringArgumentType.getString(context, "slot");

        int slot = GhostSlots.index(slotName);
        if (slot < 0) {
            context.getSource().sendError(Text.literal("Unknown slot '" + slotName + "'."));
            return 0;
        }

        Map<Integer, ItemStack> ghosts = Mirage.STATE.ghosts.get(target.getUuid());
        if (ghosts == null || ghosts.remove(slot) == null) {
            context.getSource().sendError(Text.literal("No ghost item in " + slotName + "."));
            return 0;
        }
        if (ghosts.isEmpty()) Mirage.STATE.ghosts.remove(target.getUuid());

        Mirage.STATE.save(context.getSource().getServer());
        Mirage.resync(target);

        context.getSource().sendFeedback(() -> Text.literal("Cleared the ghost item in " + slotName + ".")
                .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int ghostList(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
        Map<Integer, ItemStack> ghosts = Mirage.STATE.ghosts.get(target.getUuid());

        if (ghosts == null || ghosts.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal(nameOf(target)
                    + " has no ghost items."), false);
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.literal(nameOf(target) + " is seeing:")
                .formatted(Formatting.AQUA), false);
        for (Map.Entry<Integer, ItemStack> entry : ghosts.entrySet()) {
            String line = "  " + GhostSlots.nameOf(entry.getKey()) + ": " + describe(entry.getValue());
            context.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return ghosts.size();
    }

    // --------------------------------------------------------------- dispensers

    private static LiteralArgumentBuilder<ServerCommandSource> dispenser(CommandRegistryAccess registryAccess) {
        return CommandManager.literal("dispenser")
                .then(CommandManager.literal("show")
                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                .then(CommandManager.argument("slot", IntegerArgumentType.integer(0, 8))
                                        .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
                                                .executes(context -> dispenserShow(context, 1))
                                                .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                                        .executes(context -> dispenserShow(context,
                                                                IntegerArgumentType.getInteger(context, "count"))))))))
                .then(CommandManager.literal("result")
                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
                                        .executes(context -> dispenserResult(context, 1))
                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                                .executes(context -> dispenserResult(context,
                                                        IntegerArgumentType.getInteger(context, "count")))))))
                .then(CommandManager.literal("only")
                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .executes(MirageCommands::dispenserOnly))))
                .then(CommandManager.literal("everyone")
                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                .executes(MirageCommands::dispenserEveryone)))
                .then(CommandManager.literal("clear")
                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                                .executes(MirageCommands::dispenserClear)))
                .then(CommandManager.literal("list")
                        .executes(MirageCommands::dispenserList));
    }

    private static int dispenserShow(CommandContext<ServerCommandSource> context, int count) throws CommandSyntaxException {
        WorldPos key = posArg(context);
        int slot = IntegerArgumentType.getInteger(context, "slot");
        ItemStack stack = ItemStackArgumentType.getItemStackArgument(context, "item").createStack(count, false);

        Mirage.STATE.rigs.computeIfAbsent(key, pos -> new DispenserRig()).display.put(slot, stack);
        Mirage.STATE.save(context.getSource().getServer());
        warnIfNotADispenser(context, key);

        context.getSource().sendFeedback(() -> Text.literal("Dispenser at " + key + " now shows "
                + describe(stack) + " in slot " + slot + ".").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int dispenserResult(CommandContext<ServerCommandSource> context, int count) throws CommandSyntaxException {
        WorldPos key = posArg(context);
        ItemStack stack = ItemStackArgumentType.getItemStackArgument(context, "item").createStack(count, false);

        Mirage.STATE.rigs.computeIfAbsent(key, pos -> new DispenserRig()).result = stack;
        Mirage.STATE.save(context.getSource().getServer());
        warnIfNotADispenser(context, key);

        context.getSource().sendFeedback(() -> Text.literal("Dispenser at " + key + " will appear to fire "
                + describe(stack) + ".").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int dispenserOnly(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        WorldPos key = posArg(context);
        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");

        DispenserRig rig = Mirage.STATE.rigs.get(key);
        if (rig == null) {
            context.getSource().sendError(Text.literal("No dispenser is rigged at " + key + "."));
            return 0;
        }

        rig.onlyPlayer = target.getUuid();
        Mirage.STATE.save(context.getSource().getServer());

        context.getSource().sendFeedback(() -> Text.literal("Only " + nameOf(target)
                + " will see the fake contents at " + key + ".").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int dispenserEveryone(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        WorldPos key = posArg(context);

        DispenserRig rig = Mirage.STATE.rigs.get(key);
        if (rig == null) {
            context.getSource().sendError(Text.literal("No dispenser is rigged at " + key + "."));
            return 0;
        }

        rig.onlyPlayer = null;
        Mirage.STATE.save(context.getSource().getServer());

        context.getSource().sendFeedback(() -> Text.literal("Everybody will see the fake contents at " + key + ".")
                .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int dispenserClear(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        WorldPos key = posArg(context);

        if (Mirage.STATE.rigs.remove(key) == null) {
            context.getSource().sendError(Text.literal("No dispenser is rigged at " + key + "."));
            return 0;
        }

        Mirage.forgetDispenser(key);
        Mirage.STATE.save(context.getSource().getServer());

        context.getSource().sendFeedback(() -> Text.literal("Dispenser at " + key + " is honest again.")
                .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int dispenserList(CommandContext<ServerCommandSource> context) {
        if (Mirage.STATE.rigs.isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("No dispensers are rigged."), false);
            return 0;
        }

        context.getSource().sendFeedback(() -> Text.literal("Rigged dispensers:").formatted(Formatting.AQUA), false);
        for (Map.Entry<WorldPos, DispenserRig> entry : Mirage.STATE.rigs.entrySet()) {
            DispenserRig rig = entry.getValue();
            StringBuilder line = new StringBuilder("  " + entry.getKey());
            line.append(" - ").append(rig.display.size()).append(" fake slot(s)");
            if (!rig.result.isEmpty()) line.append(", fires ").append(describe(rig.result));
            if (rig.onlyPlayer != null) {
                ServerPlayerEntity only = context.getSource().getServer().getPlayerManager().getPlayer(rig.onlyPlayer);
                line.append(", only ").append(only == null ? rig.onlyPlayer.toString() : nameOf(only));
            }
            String text = line.toString();
            context.getSource().sendFeedback(() -> Text.literal(text), false);
        }
        return Mirage.STATE.rigs.size();
    }

    // ------------------------------------------------------------------ shared

    private static int refresh(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
        Mirage.resync(target);

        context.getSource().sendFeedback(() -> Text.literal("Resynced " + nameOf(target)
                + ". Any ghosts come back on the next refresh unless you cleared them.")
                .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static WorldPos posArg(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        BlockPos pos = BlockPosArgumentType.getBlockPos(context, "pos");
        return WorldPos.of(context.getSource().getWorld(), pos);
    }

    private static void warnIfNotADispenser(CommandContext<ServerCommandSource> context, WorldPos key) {
        ServerWorld world = context.getSource().getWorld();
        if (!(world.getBlockState(key.pos()).getBlock() instanceof DispenserBlock)) {
            context.getSource().sendFeedback(() -> Text.literal(
                    "Heads up: there is no dispenser at " + key + " yet. The rig is saved and will "
                            + "start working as soon as one is placed there.").formatted(Formatting.YELLOW), false);
        }
    }

    /** GameProfile is a record in current authlib, so the accessor is name(), not getName(). */
    private static String nameOf(ServerPlayerEntity player) {
        return player.getGameProfile().name();
    }

    private static String describe(ItemStack stack) {
        return stack.getCount() + "x " + stack.getName().getString();
    }
}
