package dev.skullzz.mirage.client;

import java.util.List;
import java.util.Map;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import dev.skullzz.mirage.Mirage;

/**
 * The client half of Mirage: fake items shown only on your own screen.
 *
 * <p>This works on any server, including ones you cannot install mods on, because it never
 * talks to the server. That also means nobody else sees any of it — for that, the mod has to
 * be installed server-side.
 */
public class MirageClient implements ClientModInitializer {
    private static KeyBinding nextResult;
    private static KeyBinding previousResult;
    private static KeyBinding openMenu;

    @Override
    public void onInitializeClient() {
        registerKeys();

        FakeLore.load();
        SelfFakes.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommandManager.literal("fake")
                        .then(ClientCommandManager.literal("ui").executes(MirageClient::openUi))
                        .then(inventoryBranch())
                        .then(enderBranch())
                        .then(dispenserBranch())
                        .then(arrowBranch())
                        .then(presetBranch())
                        .then(decorBranch())
                        .then(ClientCommandManager.literal("prices")
                                .then(ClientCommandManager.literal("reload")
                                        .executes(MirageClient::reloadPrices)))
                        .then(ClientCommandManager.literal("clear").executes(MirageClient::clearAll))
                        .then(ClientCommandManager.literal("list").executes(MirageClient::list))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Repaint every tick: the server overwrites a slot whenever the real item changes.
            if (client.player != null) SelfFakes.apply(client.player);
            ClientDispensers.tick(client);
            ClientDecor.tick(client.world);
            // A price that arrived from the API rebuilds the fakes once, not every tick.
            if (PriceApi.consumeDirty()) SelfFakes.rebuildAll();

            publishDashboard();
            int picked = WebDashboard.pollSelection();
            if (picked >= 0) applyDashboardSelection(client, picked);

            while (nextResult.wasPressed()) selectPreset(client, 1);
            while (previousResult.wasPressed()) selectPreset(client, -1);
            while (openMenu.wasPressed()) client.setScreen(new FakeItemsScreen());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SelfFakes.forgetShadows();
            ClientDispensers.reset();
            ClientDecor.reset();
        });

        Mirage.LOGGER.info("Mirage client ready. /fake ui");
    }

    private static void registerKeys() {
        // A key binding's category is an object rather than a string in this version, so
        // reuse a vanilla one instead of registering our own. The keys land under
        // Miscellaneous in Controls, which costs a heading and risks nothing.
        KeyBinding.Category category = KeyBinding.Category.MISC;

        nextResult = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.next_result", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET, category));
        previousResult = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.prev_result", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET, category));
        // Unbound by default: the menu has a command, and an accidental clash is worse.
        openMenu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.open_menu", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, category));
    }

    private static String lastPublished = "";

    /** Pushes the current selection to the dashboard, only when it has actually changed. */
    private static void publishDashboard() {
        if (!WebDashboard.isRunning()) return;

        List<FakeSpec> presets = ClientDispensers.presets();
        StringBuilder json = new StringBuilder("{\"presets\":[");
        for (int index = 0; index < presets.size(); index++) {
            FakeSpec spec = presets.get(index);
            if (index > 0) json.append(',');
            json.append("{\"name\":\"")
                    .append(WebDashboard.escape(spec.stack().getName().getString()))
                    .append("\",\"price\":\"")
                    .append(WebDashboard.escape(FakeLore.priceLabel(spec.stack(), spec.price)))
                    .append("\"}");
        }
        json.append("],\"active\":").append(ClientDispensers.presetIndex()).append('}');

        String built = json.toString();
        if (!built.equals(lastPublished)) {
            lastPublished = built;
            WebDashboard.publish(built);
        }
    }

    /** Applies a pick made in the browser, on the client thread where it is safe to. */
    private static void applyDashboardSelection(MinecraftClient client, int index) {
        List<FakeSpec> presets = ClientDispensers.presets();
        if (index >= presets.size()) return;

        // cyclePreset moves relative, so step by the difference to land on the chosen one.
        int current = ClientDispensers.presetIndex();
        int delta = current < 0 ? index + 1 : index - current;
        if (delta == 0) return;

        ClientDispensers.cyclePreset(delta);
        SelfFakes.save();
    }

    /** Flips to another preset result without opening anything anyone could see. */
    private static void selectPreset(MinecraftClient client, int delta) {
        FakeSpec spec = ClientDispensers.cyclePreset(delta);
        if (spec == null) return;

        SelfFakes.save();

        // Off unless asked for. /fake list and the dashboard both report the selection.
        if (SelfFakes.announceSwitching() && client.player != null) {
            client.player.sendMessage(Text.literal(spec.count + "x "
                    + spec.stack().getName().getString()).formatted(Formatting.GRAY), true);
        }
    }

    // ---------------------------------------------------------------- branches

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> inventoryBranch() {
        return ClientCommandManager.literal("set")
                .then(ClientCommandManager.argument("slot", StringArgumentType.word())
                        .suggests((context, builder) ->
                                CommandSource.suggestMatching(SelfFakes.slotNames(), builder))
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(context -> setInventory(context, 1, ""))
                                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                        .executes(context -> setInventory(context,
                                                IntegerArgumentType.getInteger(context, "count"), ""))
                                        .then(ClientCommandManager.argument("enchants", StringArgumentType.greedyString())
                                                .executes(context -> setInventory(context,
                                                        IntegerArgumentType.getInteger(context, "count"),
                                                        StringArgumentType.getString(context, "enchants")))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> enderBranch() {
        return ClientCommandManager.literal("ender")
                .then(ClientCommandManager.literal("clear").executes(context -> {
                    SelfFakes.clearAllContainers(MinecraftClient.getInstance().player);
                    return feedback(context, "Cleared the fake container contents.");
                }))
                .then(ClientCommandManager.argument("slot", IntegerArgumentType.integer(0, 26))
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(context -> setContainer(context, SelfFakes.ENDER_CHEST, 1))
                                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                        .executes(context -> setContainer(context, SelfFakes.ENDER_CHEST,
                                                IntegerArgumentType.getInteger(context, "count"))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> dispenserBranch() {
        return ClientCommandManager.literal("dispenser")
                .then(ClientCommandManager.literal("watch").executes(MirageClient::watchDispenser))
                .then(ClientCommandManager.literal("unwatch").executes(MirageClient::unwatchDispenser))
                .then(ClientCommandManager.literal("unwatchall").executes(context -> {
                    ClientDispensers.unwatchAll();
                    SelfFakes.save();
                    return feedback(context, "Stopped watching every dispenser.");
                }))
                .then(ClientCommandManager.literal("result")
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(context -> setResult(context, 1))
                                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                        .executes(context -> setResult(context,
                                                IntegerArgumentType.getInteger(context, "count"))))))
                .then(ClientCommandManager.argument("slot", IntegerArgumentType.integer(0, 8))
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(context -> setContainer(context, SelfFakes.DISPENSER, 1))
                                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                        .executes(context -> setContainer(context, SelfFakes.DISPENSER,
                                                IntegerArgumentType.getInteger(context, "count"))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> decorBranch() {
        return ClientCommandManager.literal("decor")
                .then(ClientCommandManager.literal("frame")
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(MirageClient::placeFrame)))
                .then(ClientCommandManager.literal("stand")
                        .then(ClientCommandManager.argument("material", StringArgumentType.word())
                                .executes(MirageClient::placeStand)))
                .then(ClientCommandManager.literal("remove").executes(MirageClient::removeDecor))
                .then(ClientCommandManager.literal("clear").executes(context -> {
                    ClientDecor.clear();
                    return feedback(context, "Removed every fake frame and stand.");
                }))
                .then(ClientCommandManager.literal("list").executes(context -> {
                    List<String> lines = ClientDecor.describe();
                    if (lines.isEmpty()) {
                        context.getSource().sendFeedback(Text.literal("No fake decor placed."));
                        return 0;
                    }
                    context.getSource().sendFeedback(Text.literal("Fake decor:").formatted(Formatting.AQUA));
                    for (String line : lines) {
                        context.getSource().sendFeedback(Text.literal("  " + line));
                    }
                    return lines.size();
                }));
    }

    private static int placeFrame(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) return error(context, "Look at the wall or block to hang it on.");

        String itemName = itemName(context);
        if (SelfFakes.lookupItem(itemName) == null) {
            return error(context, "No item called '" + itemName + "'.");
        }

        // Attached to the block you clicked, facing out along the face you clicked.
        ClientDecor.addFrame(hit.getBlockPos(), hit.getSide(), itemName);
        return feedback(context, "Placed a fake item frame holding " + itemName + ".");
    }

    private static int placeStand(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) return error(context, "Look at the block to stand it on.");

        String material = StringArgumentType.getString(context, "material");
        // Stand on top of the block that was clicked.
        if (!ClientDecor.addStand(hit.getBlockPos().up(), material)) {
            return error(context, "'" + material + "' is not an armour material or an item. "
                    + "Try netherite, diamond, iron, golden, chainmail or leather.");
        }
        return feedback(context, "Placed a fake armour stand in " + material + ".");
    }

    private static int removeDecor(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) return error(context, "Look at the decor you want gone.");

        int removed = ClientDecor.removeNear(hit.getBlockPos(), 3.0);
        if (removed == 0) return error(context, "Nothing fake within three blocks of there.");
        return feedback(context, "Removed " + removed + " fake piece(s).");
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> presetBranch() {
        return ClientCommandManager.literal("preset")
                .then(ClientCommandManager.literal("clear").executes(context -> {
                    ClientDispensers.clearPresets();
                    SelfFakes.save();
                    return feedback(context, "Cleared the dispenser presets.");
                }))
                .then(ClientCommandManager.literal("list").executes(MirageClient::listPresets))
                .then(ClientCommandManager.literal("add")
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(context -> addPreset(context, 1))
                                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                        .executes(context -> addPreset(context,
                                                IntegerArgumentType.getInteger(context, "count"))))));
    }

    private static int addPreset(CommandContext<FabricClientCommandSource> context, int count) {
        Item item = resolve(context);
        if (item == null) return 0;

        ClientDispensers.addPreset(new FakeSpec(item, count, ""));
        SelfFakes.save();
        return feedback(context, "Added " + count + "x " + itemName(context)
                + " as preset " + ClientDispensers.presets().size() + ".");
    }

    private static int listPresets(CommandContext<FabricClientCommandSource> context) {
        List<FakeSpec> presets = ClientDispensers.presets();
        if (presets.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("No dispenser presets set."));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("Dispenser presets ( ] and [ to cycle ):")
                .formatted(Formatting.AQUA));
        for (int index = 0; index < presets.size(); index++) {
            FakeSpec spec = presets.get(index);
            String marker = index == ClientDispensers.presetIndex() ? " <- active" : "";
            context.getSource().sendFeedback(Text.literal("  " + (index + 1) + ". " + spec.count
                    + "x " + spec.stack().getName().getString() + marker));
        }
        return presets.size();
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> arrowBranch() {
        return ClientCommandManager.literal("arrow")
                .then(ClientCommandManager.literal("target")
                        .executes(MirageClient::setArrowTarget)
                        .then(ClientCommandManager.argument("x", DoubleArgumentType.doubleArg())
                                .then(ClientCommandManager.argument("y", DoubleArgumentType.doubleArg())
                                        .then(ClientCommandManager.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(MirageClient::setArrowTargetExact)))))
                .then(ClientCommandManager.literal("clear").executes(context -> {
                    ClientDispensers.setArrowTarget(null);
                    SelfFakes.save();
                    return feedback(context, "Fake arrows turned off.");
                }));
    }

    private static int setArrowTarget(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(128.0);
        if (hit == null) {
            return error(context, "That ray hit nothing within 128 blocks. Aim at a solid block, "
                    + "or give the spot directly: /fake arrow target <x> <y> <z>");
        }

        // The precise point on the block face, so it lands on the pad rather than its middle.
        ClientDispensers.setArrowTarget(hit.getPos());
        SelfFakes.save();
        return feedback(context, "Fake arrows will land at " + describe(hit.getPos())
                + ". Watch the dispenser that fires them with /fake dispenser watch.");
    }

    private static int setArrowTargetExact(CommandContext<FabricClientCommandSource> context) {
        Vec3d target = new Vec3d(
                DoubleArgumentType.getDouble(context, "x"),
                DoubleArgumentType.getDouble(context, "y"),
                DoubleArgumentType.getDouble(context, "z"));

        ClientDispensers.setArrowTarget(target);
        SelfFakes.save();
        return feedback(context, "Fake arrows will land at " + describe(target) + ".");
    }

    // ------------------------------------------------------------------ actions

    private static int openUi(CommandContext<FabricClientCommandSource> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Deferred: the chat screen is still closing as this runs.
        client.execute(() -> client.setScreen(new FakeItemsScreen()));
        return 1;
    }

    private static int setInventory(CommandContext<FabricClientCommandSource> context, int count, String enchants) {
        String slotName = StringArgumentType.getString(context, "slot");
        int slot = SelfFakes.slotIndex(slotName);
        if (slot < 0) {
            return error(context, "Unknown slot '" + slotName + "'. Use hotbar1-hotbar9 or inv1-inv27.");
        }

        Item item = resolve(context);
        if (item == null) return 0;

        SelfFakes.set(slot, new FakeSpec(item, count, enchants));
        return feedback(context, "Showing " + count + "x " + itemName(context) + " in " + slotName + ".");
    }

    private static int setContainer(CommandContext<FabricClientCommandSource> context, int size, int count) {
        int slot = IntegerArgumentType.getInteger(context, "slot");
        Item item = resolve(context);
        if (item == null) return 0;

        SelfFakes.setContainer(size, slot, new FakeSpec(item, count, ""));
        String where = size == SelfFakes.DISPENSER ? "dispensers" : "ender chests";
        return feedback(context, where + " will show " + count + "x " + itemName(context)
                + " in slot " + slot + ".");
    }

    private static int setResult(CommandContext<FabricClientCommandSource> context, int count) {
        Item item = resolve(context);
        if (item == null) return 0;

        ClientDispensers.setResult(new FakeSpec(item, count, ""));
        SelfFakes.save();
        return feedback(context, "Watched dispensers will appear to fire " + count + "x "
                + itemName(context) + ".");
    }

    private static int watchDispenser(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) {
            return error(context, "That ray hit nothing within 64 blocks. Aim at the dispenser.");
        }

        ClientDispensers.watch(hit.getBlockPos());
        SelfFakes.save();
        return feedback(context, "Watching " + hit.getBlockPos().getX() + " "
                + hit.getBlockPos().getY() + " " + hit.getBlockPos().getZ() + " ("
                + ClientDispensers.watchedCount() + " watched). Set what comes out with "
                + "/fake dispenser result <item>.");
    }

    private static int unwatchDispenser(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) {
            return error(context, "That ray hit nothing within 64 blocks.");
        }

        if (!ClientDispensers.unwatch(hit.getBlockPos())) {
            return error(context, "That block was not being watched.");
        }
        SelfFakes.save();
        return feedback(context, "Stopped watching that block.");
    }

    private static int reloadPrices(CommandContext<FabricClientCommandSource> context) {
        FakeLore.load();
        SelfFakes.rebuildAll();
        return feedback(context, "Reloaded prices and refreshed every fake.");
    }

    private static int clearAll(CommandContext<FabricClientCommandSource> context) {
        SelfFakes.clearAll(MinecraftClient.getInstance().player);
        SelfFakes.clearAllContainers(MinecraftClient.getInstance().player);
        return feedback(context, "Cleared every fake.");
    }

    private static int list(CommandContext<FabricClientCommandSource> context) {
        int total = 0;
        total += listSection(context, "Inventory", SelfFakes.all(), true);
        total += listSection(context, "Dispensers", SelfFakes.allContainer(SelfFakes.DISPENSER), false);
        total += listSection(context, "Ender chests", SelfFakes.allContainer(SelfFakes.ENDER_CHEST), false);

        FakeSpec result = ClientDispensers.result();
        if (result != null) {
            context.getSource().sendFeedback(Text.literal("Dispenser output: " + result.count + "x "
                    + result.stack().getName().getString() + " ("
                    + ClientDispensers.watchedCount() + " watched)").formatted(Formatting.AQUA));
            total++;
        }
        if (total == 0) context.getSource().sendFeedback(Text.literal("No fakes set."));
        return total;
    }

    private static int listSection(CommandContext<FabricClientCommandSource> context, String label,
                                   Map<Integer, FakeSpec> entries, boolean named) {
        if (entries.isEmpty()) return 0;

        context.getSource().sendFeedback(Text.literal(label + ":").formatted(Formatting.AQUA));
        for (Map.Entry<Integer, FakeSpec> entry : entries.entrySet()) {
            String slot = named ? SelfFakes.slotName(entry.getKey()) : ("slot " + entry.getKey());
            FakeSpec spec = entry.getValue();
            context.getSource().sendFeedback(Text.literal("  " + slot + ": " + spec.count + "x "
                    + spec.stack().getName().getString()
                    + (spec.enchants.isEmpty() ? "" : " [" + spec.enchants + "]")));
        }
        return entries.size();
    }

    /**
     * What the player is actually looking at, out to {@code distance} blocks.
     *
     * <p>Deliberately not client.crosshairTarget: that only raycasts as far as your reach, and
     * past it returns a miss whose position is a few blocks in front of your face rather than
     * the thing you were aiming at.
     *
     * @return the block hit, or null if the ray hit nothing.
     */
    private static BlockHitResult lookedAt(double distance) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return null;

        HitResult hit = client.player.raycast(distance, 0.0F, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            return block;
        }
        return null;
    }

    private static String describe(Vec3d position) {
        return String.format("%.2f %.2f %.2f", position.x, position.y, position.z);
    }

    // ------------------------------------------------------------------ helpers

    private static String itemName(CommandContext<FabricClientCommandSource> context) {
        return StringArgumentType.getString(context, "item");
    }

    private static Item resolve(CommandContext<FabricClientCommandSource> context) {
        String name = itemName(context);
        Item item = SelfFakes.lookupItem(name);
        if (item == null) error(context, "No item called '" + name + "'.");
        return item;
    }

    private static int feedback(CommandContext<FabricClientCommandSource> context, String message) {
        context.getSource().sendFeedback(Text.literal(message).formatted(Formatting.GREEN));
        return 1;
    }

    private static int error(CommandContext<FabricClientCommandSource> context, String message) {
        context.getSource().sendFeedback(Text.literal(message).formatted(Formatting.RED));
        return 0;
    }
}
