package dev.skullzz.mirage.client;

import java.util.List;
import java.util.Locale;
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

import net.minecraft.block.DispenserBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Item;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
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
    private static KeyBinding cycleRig;
    private static KeyBinding armNext;
    private static KeyBinding fireNow;
    private static KeyBinding refill;
    private static KeyBinding clearFakes;
    private static KeyBinding cycleWinner;
    private static KeyBinding winFirst;
    private static KeyBinding winSecond;
    private static KeyBinding power;
    private static KeyBinding cutBlock;
    private static KeyBinding callFirst;
    private static KeyBinding callSecond;

    @Override
    public void onInitializeClient() {
        registerKeys();

        FakeLore.load();
        SelfFakes.load();
        FakeBlocks.load();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommandManager.literal("fake")
                        .then(ClientCommandManager.literal("ui").executes(MirageClient::openUi))
                        .then(inventoryBranch())
                        .then(enderBranch())
                        .then(dispenserBranch())
                        .then(arrowBranch())
                        .then(presetBranch())
                        .then(decorBranch())
                        .then(buildBranch())
                        .then(rigBranch())
                        .then(ClientCommandManager.literal("prices")
                                .then(ClientCommandManager.literal("reload")
                                        .executes(MirageClient::reloadPrices)))
                        .then(ClientCommandManager.literal("collect")
                                .then(ClientCommandManager.literal("on").executes(context -> {
                                    SelfFakes.setAutoCollect(true);
                                    return feedback(context, "Fakes fired from a dispenser will "
                                            + "land in your inventory.");
                                }))
                                .then(ClientCommandManager.literal("off").executes(context -> {
                                    SelfFakes.setAutoCollect(false);
                                    return feedback(context, "Fakes will just vanish instead.");
                                })))
                        .then(ClientCommandManager.literal("debug")
                                .then(ClientCommandManager.literal("on").executes(context -> {
                                    ClientDispensers.setDebug(true);
                                    return feedback(context, "Watcher debug on. Every fire it "
                                            + "spots is printed here.");
                                }))
                                .then(ClientCommandManager.literal("off").executes(context -> {
                                    ClientDispensers.setDebug(false);
                                    return feedback(context, "Watcher debug off.");
                                })))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            setPower(MinecraftClient.getInstance(), false);
                            return feedback(context, "Everything off. Nothing is being faked, "
                                    + "and nothing has been forgotten.");
                        }))
                        .then(ClientCommandManager.literal("on").executes(context -> {
                            setPower(MinecraftClient.getInstance(), true);
                            return feedback(context, "Everything back on.");
                        }))
                        .then(ClientCommandManager.literal("clear").executes(MirageClient::clearAll))
                        .then(ClientCommandManager.literal("list").executes(MirageClient::list))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Repaint every tick: the server overwrites a slot whenever the real item changes.
            if (client.player != null) SelfFakes.apply(client.player);
            ClientDispensers.tick(client);
            ClientDecor.tick(client.world);
            FakeBlocks.tick(client);
            FakeHands.tick(client);
            // A price that arrived from the API rebuilds the fakes once, not every tick.
            if (PriceApi.consumeDirty()) SelfFakes.rebuildAll();

            publishDashboard();

            // Before every other switch, and the only one that still works when it is off.
            Boolean askedPower = WebDashboard.pollPower();
            if (askedPower != null) setPower(client, askedPower);
            while (power.wasPressed()) setPower(client, !SelfFakes.enabled());

            if (!SelfFakes.enabled()) {
                if (drainKeys()) say(client, "Mirage is off. Press N to bring it back.");
                return;
            }

            int picked = WebDashboard.pollSelection();
            if (picked >= 0) applyDashboardSelection(client, picked);

            String pickedWinner = WebDashboard.pollWinner();
            if (pickedWinner != null) {
                RigProfile paper = ClientDispensers.active();
                paper.winner = pickedWinner.equals("*") ? "" : pickedWinner;
                paper.roundTick = Long.MIN_VALUE;
                SelfFakes.save();
            }

            String pickedRig = WebDashboard.pollRig();
            if (pickedRig != null && ClientDispensers.use(pickedRig)) {
                SelfFakes.save();
                nagIfUnset(client);
            }

            int pickedShot = WebDashboard.pollShot();
            if (pickedShot > 0) {
                RigProfile profile = ClientDispensers.active();
                profile.bulletAt = pickedShot;
                profile.tidyRoulette();
                SelfFakes.save();
            }
            int arming = WebDashboard.pollArm();
            if (arming == 1) {
                ClientDispensers.armNext();
            } else if (arming == 0) {
                ClientDispensers.disarm();
            }
            if (WebDashboard.pollReset()) {
                ClientDispensers.active().resetShots();
                SelfFakes.save();
            }

            rememberOpenDispenser(client);

            while (armNext.wasPressed()) ClientDispensers.armNext();
            while (fireNow.wasPressed()) fireLookedAtOrAll(client);
            while (refill.wasPressed()) refillLookedAt(client);
            while (clearFakes.wasPressed()) clearInventoryFakes(client);
            while (cutBlock.wasPressed()) cutLookedAt(client);
            while (callFirst.wasPressed()) takeCall(client, true);
            while (callSecond.wasPressed()) takeCall(client, false);
            while (cycleWinner.wasPressed()) stepWinner(client);
            while (winFirst.wasPressed()) setWinner(client, 0);
            while (winSecond.wasPressed()) setWinner(client, 1);
            if (WebDashboard.pollFire()) fireLookedAtOrAll(client);
            if (WebDashboard.pollRefill()) {
                ClientDispensers.refillWatched();
                SelfFakes.save();
            }
            while (cycleRig.wasPressed()) {
                if (ClientDispensers.cycleProfile(1) == null) continue;
                SelfFakes.save();
                nagIfUnset(client);
            }
            while (nextResult.wasPressed()) selectPreset(client, 1);
            while (previousResult.wasPressed()) selectPreset(client, -1);
            while (openMenu.wasPressed()) client.setScreen(new FakeItemsScreen());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            SelfFakes.forgetShadows();
            ClientDispensers.reset();
            ClientDecor.reset();
            FakeBlocks.reset();
        });

        FakeClicks.register();
        FakeHands.register();
        Mirage.LOGGER.info("Mirage client ready. /fake ui");
    }

    private static void registerKeys() {
        // A key binding's category is an object rather than a string in this version, so
        // reuse a vanilla one instead of registering our own. The keys land under
        // Miscellaneous in Controls, which costs a heading and risks nothing.
        KeyBinding.Category category = KeyBinding.Category.MISC;

        nextResult = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.next_result", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F, category));
        previousResult = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.prev_result", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R, category));
        armNext = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.arm_next", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_SEMICOLON, category));
        fireNow = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.fire_now", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_APOSTROPHE, category));
        refill = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.refill", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H, category));
        clearFakes = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.clear_fakes", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K, category));
        cycleWinner = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.cycle_winner", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M, category));
        winFirst = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.win_first", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Z, category));
        winSecond = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.win_second", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X, category));
        power = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.power", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N, category));
        cutBlock = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.cut_block", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B, category));
        callFirst = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.call_first", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET, category));
        callSecond = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.call_second", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET, category));
        cycleRig = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.cycle_rig", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSLASH, category));
        // Unbound by default: the menu has a command, and an accidental clash is worse.
        openMenu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.mirage.open_menu", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, category));
    }

    private static String lastPublished = "";

    /** Pushes the current state to the dashboard, only when it has actually changed. */
    private static void publishDashboard() {
        if (!WebDashboard.isRunning()) return;

        RigProfile profile = ClientDispensers.active();
        StringBuilder json = new StringBuilder("{\"on\":")
                .append(SelfFakes.enabled())
                .append(",\"rig\":\"")
                .append(WebDashboard.escape(profile.name)).append("\",\"rigs\":[");

        boolean first = true;
        for (String name : ClientDispensers.profiles().keySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(WebDashboard.escape(name)).append('"');
        }

        json.append("],\"presets\":[");
        for (int index = 0; index < profile.presets.size(); index++) {
            FakeSpec spec = profile.presets.get(index);
            if (index > 0) json.append(',');
            json.append("{\"name\":\"")
                    .append(WebDashboard.escape(spec.stack().getName().getString()))
                    .append("\",\"price\":\"")
                    .append(WebDashboard.escape(FakeLore.priceLabel(spec.stack(), spec.price)))
                    .append("\"}");
        }

        json.append("],\"active\":").append(profile.presetIndex()).append(",\"fixed\":[");
        first = true;
        for (Map.Entry<BlockPos, FakeSpec> entry : profile.perDispenser.entrySet()) {
            if (!first) json.append(',');
            first = false;

            BlockPos pos = entry.getKey();
            FakeSpec spec = entry.getValue();
            json.append("{\"pos\":\"")
                    .append(pos.getX()).append(' ').append(pos.getY()).append(' ').append(pos.getZ())
                    .append("\",\"name\":\"")
                    .append(WebDashboard.escape(spec.count + "x " + spec.stack().getName().getString()))
                    .append("\"}");
        }
        json.append(']');

        RigProfile shown = profile;
        json.append(",\"roulette\":{\"on\":").append(shown.roulette);
        if (shown.roulette) {
            json.append(",\"chambers\":").append(shown.chambers)
                    .append(",\"bulletAt\":").append(shown.bulletAt)
                    .append(",\"shot\":").append(shown.shot)
                    .append(",\"manual\":").append(shown.manualTrigger)
                    .append(",\"armed\":").append(shown.armed)
                    .append(",\"bullet\":\"")
                    .append(WebDashboard.escape(shown.bullet == null ? "nothing" : shown.bullet.label()))
                    .append("\",\"blank\":\"")
                    .append(WebDashboard.escape(shown.blank == null ? "nothing" : shown.blank.label()))
                    .append('"');
        }
        json.append("},\"paper\":{\"on\":").append(shown.paper);
        if (shown.paper) {
            json.append(",\"winner\":\"").append(WebDashboard.escape(shown.winner))
                    .append("\",\"sides\":[");

            first = true;
            for (String side : shown.sideNames()) {
                if (!first) json.append(',');
                first = false;
                json.append('"').append(WebDashboard.escape(side)).append('"');
            }
            json.append(']');
        }
        json.append("}}");

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
                .then(ClientCommandManager.literal("fire").executes(MirageClient::fireDispenser))
                .then(ClientCommandManager.literal("sweep").executes(context -> {
                    int gone = ClientDispensers.clearStanding();
                    if (gone == 0) return error(context, "Nothing is standing out there.");
                    return feedback(context, "Cleared " + gone + " off the floor.");
                }))
                .then(ClientCommandManager.literal("fill").executes(MirageClient::fillDispenser))
                .then(ClientCommandManager.literal("unfill").executes(MirageClient::unfillDispenser))
                .then(ClientCommandManager.literal("status").executes(MirageClient::dispenserStatus))
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

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> buildBranch() {
        return ClientCommandManager.literal("build")
                .then(ClientCommandManager.literal("corner").executes(MirageClient::buildCorner))
                .then(ClientCommandManager.literal("save")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(MirageClient::buildSave)))
                .then(ClientCommandManager.literal("put")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(MirageClient::buildPut)))
                .then(ClientCommandManager.literal("take")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    if (!FakeBlocks.take(name)) {
                                        return error(context, "'" + name + "' is not standing.");
                                    }
                                    FakeBlocks.persist();
                                    return feedback(context, "Took '" + name + "' down.");
                                })))
                .then(ClientCommandManager.literal("takeall").executes(context -> {
                    FakeBlocks.takeAll();
                    FakeBlocks.persist();
                    return feedback(context, "Took every build down.");
                }))
                .then(ClientCommandManager.literal("forget")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    if (!FakeBlocks.forget(name)) {
                                        return error(context, "No build called '" + name + "'.");
                                    }
                                    FakeBlocks.persist();
                                    return feedback(context, "Forgot '" + name + "'.");
                                })))
                .then(ClientCommandManager.literal("cut")
                        .executes(context -> buildCut(context, 0, true))
                        .then(ClientCommandManager.argument("radius",
                                IntegerArgumentType.integer(0, FakeBlocks.MAX_CUT_RADIUS))
                                .executes(context -> buildCut(context,
                                        IntegerArgumentType.getInteger(context, "radius"), true))))
                .then(ClientCommandManager.literal("uncut")
                        .executes(context -> buildCut(context, 0, false))
                        .then(ClientCommandManager.argument("radius",
                                IntegerArgumentType.integer(0, FakeBlocks.MAX_CUT_RADIUS))
                                .executes(context -> buildCut(context,
                                        IntegerArgumentType.getInteger(context, "radius"), false))))
                .then(ClientCommandManager.literal("uncutall")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    int filled = FakeBlocks.uncutAll(name);
                                    if (filled == 0) {
                                        return error(context, "'" + name + "' has no holes in "
                                                + "it, or is not standing.");
                                    }
                                    FakeBlocks.persist();
                                    return feedback(context, "Filled " + filled
                                            + " blocks back in.");
                                })))
                .then(ClientCommandManager.literal("list").executes(MirageClient::buildList));
    }

    /**
     * Takes a hole out of a standing build, or fills one back in.
     *
     * <p>What it is for is making room for something that has to really be there: a
     * dispenser, a chest, a sign. The look of the place stays, and the machine in it works.
     */
    private static int buildCut(CommandContext<FabricClientCommandSource> context,
                                int radius, boolean out) {
        BlockHitResult hit = lookedAt(128.0);
        if (hit == null) return error(context, "Look at the spot you want to open up.");

        BlockPos pos = hit.getBlockPos();
        String name = FakeBlocks.buildAt(pos);
        if (name == null) {
            return error(context, "Nothing of yours is standing there.");
        }

        int count = out ? FakeBlocks.cut(pos, radius) : FakeBlocks.uncut(pos, radius);
        if (count == 0) {
            return error(context, out ? "Already open there." : "Nothing was cut there.");
        }

        FakeBlocks.persist();
        return feedback(context, (out ? "Took " : "Filled ") + count + " block"
                + (count == 1 ? "" : "s") + (out ? " out of '" : " back into '") + name
                + "'. " + FakeBlocks.cutCount(name) + " open in total.");
    }

    private static int buildCorner(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(128.0);
        if (hit == null) return error(context, "Look at a block to set a corner.");

        int which = FakeBlocks.corner(hit.getBlockPos());
        BlockPos pos = hit.getBlockPos();

        if (which == 1) {
            return feedback(context, "Corner 1 at " + pos.getX() + " " + pos.getY() + " "
                    + pos.getZ() + ". Now look at the opposite corner and run it again.");
        }
        long size = FakeBlocks.regionSize();
        if (size > FakeBlocks.MAX_BLOCKS) {
            return feedback(context, "Corner 2 set, but that box is " + size + " positions, "
                    + "over the " + FakeBlocks.MAX_BLOCKS + " limit. Air is not carried, so "
                    + "saving it may still fit; if not, take it in pieces.");
        }
        return feedback(context, "Corner 2 set, " + size
                + " positions in the box. /fake build save <name>");
    }

    private static int buildSave(CommandContext<FabricClientCommandSource> context) {
        if (!FakeBlocks.hasRegion()) {
            return error(context, "Set both corners first with /fake build corner.");
        }
        if (FakeBlocks.regionSize() > FakeBlocks.MAX_BLOCKS) {
            return error(context, "That box is " + FakeBlocks.regionSize() + " blocks, over the "
                    + FakeBlocks.MAX_BLOCKS + " limit. Pick a smaller one.");
        }

        String name = StringArgumentType.getString(context, "name");
        FakeBlocks.Build build = FakeBlocks.save(MinecraftClient.getInstance().world, name);
        if (build == null) {
            return error(context, "Nothing but air in that box, or the chunks are not loaded.");
        }

        FakeBlocks.persist();
        return feedback(context, "Saved '" + name + "': " + build.count() + " blocks, "
                + build.size() + ". Stand it up with /fake build put " + name + ".");
    }

    private static int buildPut(CommandContext<FabricClientCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");

        // The corner goes where you are looking, or at your feet if that is nothing.
        BlockHitResult hit = lookedAt(128.0);
        BlockPos corner = hit != null ? hit.getBlockPos().up()
                : MinecraftClient.getInstance().player == null ? null
                : MinecraftClient.getInstance().player.getBlockPos();
        if (corner == null) return error(context, "Look at where the corner should go.");

        int count = FakeBlocks.put(name, corner);
        if (count < 0) return error(context, "No build called '" + name + "'.");

        FakeBlocks.persist();
        return feedback(context, "'" + name + "' is up: " + count + " blocks from "
                + corner.getX() + " " + corner.getY() + " " + corner.getZ() + ".");
    }

    private static int buildList(CommandContext<FabricClientCommandSource> context) {
        Map<String, FakeBlocks.Build> all = FakeBlocks.builds();
        if (all.isEmpty()) {
            context.getSource().sendFeedback(Text.literal("No builds saved. Set two corners "
                    + "round something and /fake build save <name>."));
            return 0;
        }

        context.getSource().sendFeedback(Text.literal("Builds:").formatted(Formatting.AQUA));
        for (FakeBlocks.Build build : all.values()) {
            BlockPos at = FakeBlocks.placed().get(build.name);
            context.getSource().sendFeedback(Text.literal("  " + build.name + " - "
                    + build.count() + " blocks, " + build.size()
                    + (at == null ? ", down"
                            : ", up at " + at.getX() + " " + at.getY() + " " + at.getZ())));
        }
        return all.size();
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> rigBranch() {
        return ClientCommandManager.literal("rig")
                .then(ClientCommandManager.literal("list").executes(MirageClient::listRigs))
                .then(ClientCommandManager.literal("new")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    ClientDispensers.create(name);
                                    ClientDispensers.use(name);
                                    SelfFakes.save();
                                    return feedback(context, "Created rig '" + name + "' and switched to it.");
                                })))
                .then(ClientCommandManager.literal("use")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        ClientDispensers.profiles().keySet(), builder))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    if (!ClientDispensers.use(name)) {
                                        return error(context, "No rig called '" + name + "'.");
                                    }
                                    SelfFakes.save();

                                    int parts = ClientDispensers.partsInGame();
                                    return feedback(context, "Now using rig '" + name + "'."
                                            + (parts == 0 ? " No machines set up for it yet - "
                                                    + "look at each and press H." : ""));
                                })))
                .then(ClientCommandManager.literal("delete")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(
                                        ClientDispensers.profiles().keySet(), builder))
                                .executes(context -> {
                                    String name = StringArgumentType.getString(context, "name");
                                    if (!ClientDispensers.delete(name)) {
                                        return error(context, "No rig called '" + name + "'.");
                                    }
                                    SelfFakes.save();
                                    return feedback(context, "Deleted rig '" + name + "'.");
                                })))
                .then(ClientCommandManager.literal("place")
                        .then(ClientCommandManager.literal("on").executes(context -> {
                            ClientDispensers.active().placeOutput = true;
                            SelfFakes.save();
                            return feedback(context, "Machines put their answer down as a "
                                    + "block, the way a shulker box is placed.");
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            ClientDispensers.clearStanding();
                            ClientDispensers.active().placeOutput = false;
                            SelfFakes.save();
                            return feedback(context, "Machines throw their answer out again.");
                        })))
                .then(ClientCommandManager.literal("unset").executes(MirageClient::unsetDispenserResult))
                .then(ClientCommandManager.literal("arm").executes(context -> {
                    ClientDispensers.armNext();
                    return feedback(context, "Next shot from this rig is the loaded one.");
                }))
                .then(ClientCommandManager.literal("disarm").executes(context -> {
                    ClientDispensers.disarm();
                    return feedback(context, "Disarmed.");
                }))
                .then(ClientCommandManager.literal("reset").executes(context -> {
                    ClientDispensers.active().resetShots();
                    SelfFakes.save();
                    return feedback(context, "Chamber count back to zero.");
                }))
                .then(paperBranch())
                .then(towerBranch())
                .then(rouletteBranch())
                .then(ClientCommandManager.literal("set")
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(context -> setDispenserResult(context, 1))
                                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                        .executes(context -> setDispenserResult(context,
                                                IntegerArgumentType.getInteger(context, "count")))
                                        .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> setDispenserResult(context,
                                                        IntegerArgumentType.getInteger(context, "count")))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> towerBranch() {
        return ClientCommandManager.literal("tower")
                .then(ClientCommandManager.literal("on").executes(context -> {
                    ClientDispensers.active().tower = true;
                    SelfFakes.save();
                    return feedback(context, "Tower on. Watch the machines in floor order, "
                            + "then /fake dispenser fill each one.");
                }))
                .then(ClientCommandManager.literal("off").executes(context -> {
                    ClientDispensers.active().tower = false;
                    SelfFakes.save();
                    return feedback(context, "Tower off for this rig.");
                }))
                .then(ClientCommandManager.literal("floors")
                        .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 9))
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    profile.floors = IntegerArgumentType.getInteger(context, "count");
                                    if (profile.bustAt > profile.floors) profile.bustAt = 0;
                                    SelfFakes.save();
                                    return feedback(context, "A run is " + profile.floors
                                            + " floors.");
                                })))
                .then(ClientCommandManager.literal("floor")
                        .then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1, 9))
                                .executes(MirageClient::setTowerFloor)))
                .then(ClientCommandManager.literal("colours")
                        .then(ClientCommandManager.argument("first", StringArgumentType.word())
                                .then(ClientCommandManager.argument("second", StringArgumentType.word())
                                        .executes(MirageClient::setTowerColours))))
                .then(ClientCommandManager.literal("call")
                        .then(ClientCommandManager.argument("colour", StringArgumentType.word())
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    String colour = StringArgumentType
                                            .getString(context, "colour").toLowerCase(Locale.ROOT);
                                    String full = colour.contains("_") ? colour
                                            : colour + "_shulker_box";
                                    ClientDispensers.call(full);
                                    return feedback(context, "They called " + full + ". Floors "
                                            + "fire that back until the run is ended.");
                                })))
                .then(ClientCommandManager.literal("ends")
                        .then(ClientCommandManager.literal("armed").executes(context -> {
                            ClientDispensers.active().bustAt = 0;
                            SelfFakes.save();
                            return feedback(context, "A run ends only when you arm it.");
                        }))
                        .then(ClientCommandManager.argument("floor", IntegerArgumentType.integer(1, 9))
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    profile.bustAt = IntegerArgumentType.getInteger(context, "floor");
                                    SelfFakes.save();
                                    return feedback(context, "Every run ends on floor "
                                            + profile.bustAt + ".");
                                })));
    }

    private static int setTowerFloor(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) return error(context, "Look at the machine you are numbering.");

        RigProfile profile = ClientDispensers.active();
        int floor = IntegerArgumentType.getInteger(context, "number");
        profile.setFloor(hit.getBlockPos(), floor);

        ClientDispensers.fill(hit.getBlockPos());
        SelfFakes.save();
        return feedback(context, "That machine is floor " + floor + ".");
    }

    private static int setTowerColours(CommandContext<FabricClientCommandSource> context) {
        RigProfile profile = ClientDispensers.active();
        profile.towerA = StringArgumentType.getString(context, "first");
        profile.towerB = StringArgumentType.getString(context, "second");

        if (SelfFakes.lookupItem(profile.towerA) == null
                || SelfFakes.lookupItem(profile.towerB) == null) {
            return error(context, "One of those is not an item.");
        }

        ClientDispensers.refillWatched();
        SelfFakes.save();
        return feedback(context, "Floors now hold " + profile.towerA + " and "
                + profile.towerB + ".");
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> paperBranch() {
        return ClientCommandManager.literal("paper")
                .then(ClientCommandManager.literal("on").executes(context -> {
                    ClientDispensers.active().paper = true;
                    SelfFakes.save();
                    return feedback(context, "Paper game on. Watch the two dispensers and "
                            + "they get a side each, then /fake dispenser fill both.");
                }))
                .then(ClientCommandManager.literal("off").executes(context -> {
                    ClientDispensers.active().paper = false;
                    SelfFakes.save();
                    return feedback(context, "Paper game off for this rig.");
                }))
                .then(ClientCommandManager.literal("side")
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(MirageClient::setPaperSide)))
                .then(ClientCommandManager.literal("winner")
                        .then(ClientCommandManager.literal("chance").executes(context -> {
                            ClientDispensers.active().winner = "";
                            SelfFakes.save();
                            return feedback(context, "Left to chance.");
                        }))
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    profile.winner = StringArgumentType.getString(context, "name");
                                    profile.roundTick = Long.MIN_VALUE;
                                    SelfFakes.save();
                                    return feedback(context, profile.winner + " draws higher.");
                                })))
                .then(ClientCommandManager.literal("item")
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    profile.slipItem = StringArgumentType.getString(context, "item");
                                    SelfFakes.save();
                                    return feedback(context, "Slips are now "
                                            + profile.slipItem + ". Fill the dispensers again.");
                                })))
                .then(ClientCommandManager.literal("house")
                        .then(ClientCommandManager.literal("nobody").executes(context -> {
                            ClientDispensers.active().house = "";
                            SelfFakes.save();
                            return feedback(context, "Draws go to nobody, so the machines "
                                    + "never agree.");
                        }))
                        .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    profile.house = StringArgumentType.getString(context, "name");
                                    profile.roundTick = Long.MIN_VALUE;
                                    SelfFakes.save();
                                    return feedback(context, "A draw is now a win for "
                                            + profile.house + ".");
                                })))
                .then(ClientCommandManager.literal("ties")
                        .then(ClientCommandManager.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    profile.tieChance =
                                            IntegerArgumentType.getInteger(context, "percent");
                                    profile.roundTick = Long.MIN_VALUE;
                                    SelfFakes.save();
                                    return feedback(context, profile.tieChance + "% of the rounds "
                                            + (profile.house.isEmpty() ? "the house" : profile.house)
                                            + " takes come out level.");
                                })))
                .then(ClientCommandManager.literal("numbers")
                        .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(2, 9))
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    profile.numbers = IntegerArgumentType.getInteger(context, "count");
                                    SelfFakes.save();
                                    return feedback(context, "Slips run 1 to "
                                            + profile.numbers + ". Fill the dispensers again.");
                                })));
    }

    private static int setPaperSide(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) return error(context, "Look at the dispenser you are naming.");

        RigProfile profile = ClientDispensers.active();
        String side = StringArgumentType.getString(context, "name");
        profile.setSide(hit.getBlockPos(), side);

        // The slips carry the side in their names, so they have to be laid out again.
        ClientDispensers.fill(hit.getBlockPos());
        SelfFakes.save();
        return feedback(context, "That dispenser plays " + side + ", holding "
                + ClientDispensers.describeStock(hit.getBlockPos()) + ".");
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> rouletteBranch() {
        return ClientCommandManager.literal("roulette")
                .then(ClientCommandManager.literal("on").executes(context -> {
                    RigProfile profile = ClientDispensers.active();
                    profile.roulette = true;
                    profile.tidyRoulette();
                    SelfFakes.save();
                    return feedback(context, "Rig '" + profile.name + "' now fires on shot "
                            + profile.bulletAt + " of " + profile.chambers + ".");
                }))
                .then(ClientCommandManager.literal("off").executes(context -> {
                    ClientDispensers.active().roulette = false;
                    SelfFakes.save();
                    return feedback(context, "Roulette off for this rig.");
                }))
                .then(ClientCommandManager.literal("manual")
                        .then(ClientCommandManager.literal("on").executes(context -> {
                            RigProfile profile = ClientDispensers.active();
                            profile.roulette = true;
                            profile.manualTrigger = true;
                            SelfFakes.save();
                            return feedback(context, "This rig now fires the loaded item only "
                                    + "when armed.");
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            ClientDispensers.active().manualTrigger = false;
                            SelfFakes.save();
                            return feedback(context, "Back to counting chambers.");
                        })))
                .then(ClientCommandManager.literal("shot")
                        .then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1, 64))
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    profile.bulletAt = IntegerArgumentType.getInteger(context, "number");
                                    profile.tidyRoulette();
                                    SelfFakes.save();
                                    return feedback(context, "Loaded shot is now number "
                                            + profile.bulletAt + " of " + profile.chambers + ".");
                                })))
                .then(ClientCommandManager.literal("chambers")
                        .then(ClientCommandManager.argument("number", IntegerArgumentType.integer(1, 64))
                                .executes(context -> {
                                    RigProfile profile = ClientDispensers.active();
                                    profile.chambers = IntegerArgumentType.getInteger(context, "number");
                                    profile.tidyRoulette();
                                    SelfFakes.save();
                                    return feedback(context, "Cycle is now " + profile.chambers
                                            + " shots, loaded on " + profile.bulletAt + ".");
                                })))
                .then(ClientCommandManager.literal("bullet")
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(context -> setRouletteItem(context, true, 1))
                                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                        .executes(context -> setRouletteItem(context, true,
                                                IntegerArgumentType.getInteger(context, "count"))))))
                .then(ClientCommandManager.literal("blank")
                        .then(ClientCommandManager.literal("none").executes(context -> {
                            ClientDispensers.active().blank = null;
                            SelfFakes.save();
                            return feedback(context, "Other shots will fire nothing at all.");
                        }))
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(context -> setRouletteItem(context, false, 1))
                                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 127))
                                        .executes(context -> setRouletteItem(context, false,
                                                IntegerArgumentType.getInteger(context, "count"))))));
    }

    private static int setRouletteItem(CommandContext<FabricClientCommandSource> context,
                                       boolean loaded, int count) {
        FakeSpec spec = specFrom(context, count);
        if (spec == null) return 0;

        RigProfile profile = ClientDispensers.active();
        if (loaded) {
            profile.bullet = spec;
        } else {
            profile.blank = spec;
        }
        SelfFakes.save();
        return feedback(context, (loaded ? "Loaded shot" : "Other shots") + " will fire "
                + spec.count + "x " + spec.describe() + ".");
    }

    /** Fixes what the dispenser you are looking at fires, in the active rig. */
    private static int setDispenserResult(CommandContext<FabricClientCommandSource> context, int count) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) return error(context, "Look at the dispenser you want to fix.");

        FakeSpec spec = specFrom(context, count, displayNameArg(context));
        if (spec == null) return 0;

        ClientDispensers.setDispenserResult(hit.getBlockPos(), spec);
        SelfFakes.save();
        return feedback(context, "That dispenser will fire " + spec.count + "x " + spec.describe()
                + " in rig '" + ClientDispensers.activeName() + "'.");
    }

    private static int unsetDispenserResult(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) return error(context, "Look at the dispenser to unfix.");

        if (!ClientDispensers.clearDispenserResult(hit.getBlockPos())) {
            return error(context, "That dispenser had nothing fixed in this rig.");
        }
        SelfFakes.save();
        return feedback(context, "That dispenser is back to the cycled item.");
    }

    private static int listRigs(CommandContext<FabricClientCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("Rigs:").formatted(Formatting.AQUA));

        for (RigProfile profile : ClientDispensers.profiles().values()) {
            String marker = profile.name.equals(ClientDispensers.activeName()) ? " <- active" : "";
            context.getSource().sendFeedback(Text.literal("  " + profile.name + marker));

            FakeSpec selected = profile.selected();
            if (selected != null) {
                context.getSource().sendFeedback(Text.literal("      cycles to: "
                        + selected.count + "x " + selected.describe()));
            }
            for (Map.Entry<BlockPos, FakeSpec> entry : profile.perDispenser.entrySet()) {
                BlockPos pos = entry.getKey();
                context.getSource().sendFeedback(Text.literal("      " + pos.getX() + " " + pos.getY()
                        + " " + pos.getZ() + " fires " + entry.getValue().count + "x "
                        + entry.getValue().describe()));
            }
        }
        return ClientDispensers.profiles().size();
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
                                                IntegerArgumentType.getInteger(context, "count")))
                                        .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> addPreset(context,
                                                        IntegerArgumentType.getInteger(context, "count")))))));
    }

    private static int addPreset(CommandContext<FabricClientCommandSource> context, int count) {
        FakeSpec spec = specFrom(context, count, displayNameArg(context));
        if (spec == null) return 0;

        ClientDispensers.addPreset(spec);
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
        FakeSpec spec = specFrom(context, count);
        if (spec == null) return 0;

        SelfFakes.setContainer(size, slot, spec);
        String where = size == SelfFakes.DISPENSER ? "dispensers" : "ender chests";
        return feedback(context, where + " will show " + count + "x " + itemName(context)
                + " in slot " + slot + ".");
    }

    private static int setResult(CommandContext<FabricClientCommandSource> context, int count) {
        FakeSpec spec = specFrom(context, count);
        if (spec == null) return 0;

        ClientDispensers.setResult(spec);
        SelfFakes.save();
        return feedback(context, "Watched dispensers will appear to fire " + count + "x "
                + itemName(context) + ".");
    }

    /**
     * Fires the dispenser being looked at, or every watched one.
     *
     * <p>Aiming at a particular one matters when two dispensers are in a game together and
     * only one of them should appear to go off.
     */
    private static void fireLookedAtOrAll(MinecraftClient client) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit != null && ClientDispensers.watchedPositions().contains(hit.getBlockPos())) {
            ClientDispensers.fireNow(hit.getBlockPos());
            return;
        }

        if (ClientDispensers.fireAllWatched() == 0) {
            say(client, "No dispensers watched. Look at one and run /fake dispenser watch.");
        }
    }

    /**
     * Keeps track of which dispenser a container screen belongs to.
     *
     * <p>The screen itself does not say where it came from, but the block being looked at
     * when it opened does, and the view cannot move while a screen is up. So the last block
     * seen before the screen appeared is the one whose GUI is now open.
     */
    private static void rememberOpenDispenser(MinecraftClient client) {
        if (client.currentScreen != null || client.world == null) return;

        BlockHitResult hit = lookedAt(6.0);
        if (hit == null) return;
        if (!(client.world.getBlockState(hit.getBlockPos()).getBlock() instanceof DispenserBlock)) {
            return;
        }
        ClientDispensers.setOpenDispenser(hit.getBlockPos());
    }

    /**
     * Says when a game has been switched to that no machine is set up for.
     *
     * <p>The tower and the paper game are played on particular machines rather than on
     * whatever happens to be watched, so switching to one with none of them chosen leaves
     * every dispenser doing nothing. That is worth a line, even from a key meant to be
     * quiet, because the alternative is finding out mid-game.
     */
    private static void nagIfUnset(MinecraftClient client) {
        if (ClientDispensers.partsInGame() != 0) return;

        say(client, "Rig '" + ClientDispensers.activeName() + "' has no machines yet. Look at "
                + "each one and press H.");
    }

    /**
     * Takes the player's call for the floor they are about to play.
     *
     * <p>Silent like the other switches. The call is the whole input this game needs: what a
     * floor fires is their own answer, or the other colour when the run is ended.
     */
    private static void takeCall(MinecraftClient client, boolean first) {
        RigProfile profile = ClientDispensers.active();
        if (!profile.tower) {
            say(client, "Rig '" + profile.name + "' is not the tower.");
            return;
        }

        String colour = ClientDispensers.call(first ? profile.towerA : profile.towerB);
        if (!SelfFakes.announceSwitching() || client.player == null) return;

        client.player.sendMessage(Text.literal("called " + colour)
                .formatted(Formatting.GRAY), true);
    }

    /** Opens up the one block being looked at, for punching holes as you go. */
    private static void cutLookedAt(MinecraftClient client) {
        BlockHitResult hit = lookedAt(128.0);
        if (hit == null) return;

        String name = FakeBlocks.buildAt(hit.getBlockPos());
        if (name == null) {
            say(client, "Nothing of yours is standing there.");
            return;
        }
        if (FakeBlocks.cut(hit.getBlockPos(), 0) == 0) {
            say(client, "Already open there.");
            return;
        }
        FakeBlocks.persist();
    }

    /**
     * The master switch.
     *
     * <p>Off puts every real item, dispenser and decoration back at once and takes anything
     * still in the air with it, while forgetting none of the setup. Always says which way it
     * went: this is the one switch where being unsure which state you are in is the whole
     * problem, and a silent one would be worse than no switch at all.
     */
    private static void setPower(MinecraftClient client, boolean on) {
        if (on == SelfFakes.enabled()) return;
        SelfFakes.setEnabled(on);

        if (!on && client.player != null) {
            SelfFakes.apply(client.player);
            ClientDecor.hide();
        }
        say(client, on ? "Mirage on." : "Mirage off. Everything you can see is real.");
    }

    /**
     * Empties the other keys while it is off.
     *
     * <p>Otherwise a handful of presses made in the meantime would all fire at once the
     * moment it came back on.
     */
    private static boolean drainKeys() {
        KeyBinding[] rest = { nextResult, previousResult, armNext, fireNow, refill,
                clearFakes, cycleWinner, winFirst, winSecond, cycleRig, openMenu,
                cutBlock, callFirst, callSecond };

        boolean pressed = false;
        for (KeyBinding key : rest) {
            while (key.wasPressed()) pressed = true;
        }
        return pressed;
    }

    /**
     * Rigs one side of the paper game outright.
     *
     * <p>Stepping through the settings means knowing where it currently is and counting
     * presses. A key per side means pressing it once and being certain, which is the point
     * of having it on a key at all. Silent for the same reason the other switches are.
     */
    private static void setWinner(MinecraftClient client, int index) {
        RigProfile profile = ClientDispensers.active();
        if (!profile.paper) {
            say(client, "Rig '" + profile.name + "' is not the paper game.");
            return;
        }

        List<String> sides = profile.sideNames();
        if (index >= sides.size()) {
            say(client, "Only " + sides.size() + " machine"
                    + (sides.size() == 1 ? " is" : "s are") + " in the game. Watch the other "
                    + "dispenser first.");
            return;
        }

        profile.winner = sides.get(index);
        // Whatever numbers are already drawn belong to the old setting.
        profile.roundTick = Long.MIN_VALUE;
        SelfFakes.save();

        if (!SelfFakes.announceSwitching() || client.player == null) return;
        client.player.sendMessage(Text.literal(profile.winner + " wins")
                .formatted(Formatting.GRAY), true);
    }

    /**
     * Steps who the paper game is rigged for.
     *
     * <p>Silent by default like the other switches, since the whole point is deciding it
     * while everyone is watching the machines rather than your chat.
     */
    private static void stepWinner(MinecraftClient client) {
        RigProfile profile = ClientDispensers.active();
        if (!profile.paper || client.player == null) return;

        String winner = ClientDispensers.cycleWinner();
        if (!SelfFakes.announceSwitching()) return;

        client.player.sendMessage(Text.literal(winner.isEmpty() ? "chance" : winner + " wins")
                .formatted(Formatting.GRAY), true);
    }

    /**
     * Takes every fake back out of the inventory, on one key.
     *
     * <p>Silent unless switching messages are turned on: the point of a key for this is
     * getting the inventory clean before anyone looks at it, which a line of chat undoes.
     * Dispenser layouts are left alone, since they are part of the setup rather than the
     * thing being carried around.
     */
    private static void clearInventoryFakes(MinecraftClient client) {
        if (client.player == null) return;

        int cleared = SelfFakes.all().size();
        SelfFakes.clearAll(client.player);
        if (cleared == 0 || !SelfFakes.announceSwitching()) return;

        client.player.sendMessage(Text.literal("Cleared " + cleared + " fake"
                + (cleared == 1 ? "" : "s") + ".").formatted(Formatting.GRAY), true);
    }

    /** Lays out the dispenser being looked at, or the one just used, on a single key. */
    private static void refillLookedAt(MinecraftClient client) {
        BlockPos pos = null;

        BlockHitResult hit = lookedAt(24.0);
        if (hit != null && client.world != null
                && client.world.getBlockState(hit.getBlockPos()).getBlock() instanceof DispenserBlock) {
            pos = hit.getBlockPos();
        }
        if (pos == null) pos = ClientDispensers.openDispenserPos(client.player);

        if (pos == null) {
            say(client, "Not looking at a dispenser.");
            return;
        }
        if (!ClientDispensers.fill(pos)) {
            say(client, "Rig '" + ClientDispensers.activeName() + "' has nothing to put in.");
            return;
        }

        ClientDispensers.watch(pos);
        ClientDispensers.setOpenDispenser(pos);
        SelfFakes.save();
    }

    /**
     * Says why a key did nothing.
     *
     * <p>Only ever on failure. A key that works in silence is the whole point of having it,
     * but one that fails in silence is what sent the last two evenings sideways.
     */
    private static void say(MinecraftClient client, String message) {
        if (client.player == null) return;
        client.player.sendMessage(Text.literal(message).formatted(Formatting.GRAY), true);
    }

    private static int fillDispenser(CommandContext<FabricClientCommandSource> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        BlockHitResult hit = lookedAt(64.0);

        // Looking at one means that one, watched or not: filling something behind you while
        // saying it worked is worse than doing nothing.
        if (hit != null && client.world != null
                && client.world.getBlockState(hit.getBlockPos()).getBlock() instanceof DispenserBlock) {
            BlockPos pos = hit.getBlockPos();
            if (!ClientDispensers.fill(pos)) {
                return error(context, "Rig '" + ClientDispensers.activeName() + "' has nothing "
                        + "to put in it. Give it something to fire first.");
            }

            ClientDispensers.watch(pos);
            ClientDispensers.setOpenDispenser(pos);
            SelfFakes.save();
            return feedback(context, "That dispenser now shows "
                    + ClientDispensers.describeStock(pos) + ". Open it to check.");
        }

        int filled = ClientDispensers.refillWatched();
        if (filled == 0) {
            return error(context, "You are not looking at a dispenser, and no watched one "
                    + "could be laid out. Look straight at it and try again.");
        }
        SelfFakes.save();
        return feedback(context, "Not looking at a dispenser, so laid out the " + filled
                + " watched one" + (filled == 1 ? "" : "s") + " instead.");
    }

    private static int unfillDispenser(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) return error(context, "Look at the dispenser to empty.");

        if (!ClientDispensers.unfill(hit.getBlockPos())) {
            return error(context, "That dispenser was not laid out in this rig.");
        }
        SelfFakes.save();
        return feedback(context, "That dispenser shows its real contents again.");
    }

    private static int fireDispenser(CommandContext<FabricClientCommandSource> context) {
        BlockHitResult hit = lookedAt(64.0);
        if (hit != null && ClientDispensers.watchedPositions().contains(hit.getBlockPos())) {
            ClientDispensers.fireNow(hit.getBlockPos());
            return feedback(context, "Fired that one.");
        }

        int count = ClientDispensers.fireAllWatched();
        if (count == 0) {
            return error(context, "No dispensers watched. Look at one and run "
                    + "/fake dispenser watch.");
        }
        return feedback(context, "Fired " + count + " watched dispenser"
                + (count == 1 ? "." : "s."));
    }

    private static int dispenserStatus(CommandContext<FabricClientCommandSource> context) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<String> lines = ClientDispensers.status(client.world);

        for (String line : lines) {
            context.getSource().sendFeedback(Text.literal(line).formatted(Formatting.AQUA));
        }
        return lines.size();
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

    /** Builds a fake from the command's item argument, reporting the failure itself. */
    private static FakeSpec specFrom(CommandContext<FabricClientCommandSource> context, int count) {
        return specFrom(context, count, "");
    }

    private static FakeSpec specFrom(CommandContext<FabricClientCommandSource> context, int count,
                                     String displayName) {
        String name = itemName(context);
        FakeSpec spec = SelfFakes.buildSpec(name, count, "", null, displayName);
        if (spec == null) {
            error(context, "No item called '" + name + "'. For a map art use filled_map#<id>.");
        }
        return spec;
    }

    /** The optional trailing name argument, when the command has one. */
    private static String displayNameArg(CommandContext<FabricClientCommandSource> context) {
        try {
            return StringArgumentType.getString(context, "name");
        } catch (IllegalArgumentException absent) {
            return "";
        }
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
