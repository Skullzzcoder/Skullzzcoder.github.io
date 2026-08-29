package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

/**
 * The whole client-side setup in one screen: what your inventory shows, what containers show,
 * and how each game is rigged.
 *
 * <p>Widgets for every tab are built once and shown or hidden, rather than rebuilt on each
 * switch, so nothing has to be torn down and re-laid-out mid-interaction.
 */
public class FakeItemsScreen extends Screen {
    private enum Tab { INVENTORY, DISPENSER, ENDER, RIGS }

    private static final int CELL = 22;
    private static final int BUTTON = 20;
    private static final int CONTENT_TOP = 106;
    /** Fixed pools, shown or hidden as the state needs; more than anyone will set up. */
    private static final int MAX_RIG_BUTTONS = 8;
    private static final int MAX_SHOT_BUTTONS = 12;

    private final List<ButtonWidget> inventoryButtons = new ArrayList<>();
    private final List<ButtonWidget> dispenserButtons = new ArrayList<>();
    private final List<ButtonWidget> enderButtons = new ArrayList<>();
    private final List<ClickableWidget> dispenserExtras = new ArrayList<>();
    private final List<ClickableWidget> rigWidgets = new ArrayList<>();

    private final ButtonWidget[] rigButtons = new ButtonWidget[MAX_RIG_BUTTONS];
    private final ButtonWidget[] shotButtons = new ButtonWidget[MAX_SHOT_BUTTONS];
    private ButtonWidget rouletteToggle;
    private ButtonWidget collectToggle;
    private ButtonWidget fewerChambers;
    private ButtonWidget moreChambers;

    private TextFieldWidget itemField;
    private TextFieldWidget countField;
    private TextFieldWidget nameField;
    private TextFieldWidget priceField;
    private TextFieldWidget enchantField;

    private Text status = Text.empty();
    private Tab tab = Tab.INVENTORY;

    private int fieldsLeft;

    public FakeItemsScreen() {
        super(Text.literal("Mirage"));
    }

    @Override
    protected void init() {
        this.inventoryButtons.clear();
        this.dispenserButtons.clear();
        this.enderButtons.clear();
        this.dispenserExtras.clear();
        this.rigWidgets.clear();

        buildTabs();
        buildFields();
        buildInventoryGrid();
        buildEnderGrid();
        buildDispenserTab();
        buildRigsTab();
        buildFooter();

        refresh();
    }

    // ------------------------------------------------------------------ layout

    private void buildTabs() {
        int left = (this.width - 4 * 88) / 2;
        addTab("Inventory", Tab.INVENTORY, left);
        addTab("Dispenser", Tab.DISPENSER, left + 88);
        addTab("Ender chest", Tab.ENDER, left + 176);
        addTab("Rigs", Tab.RIGS, left + 264);
    }

    private void addTab(String label, Tab target, int x) {
        this.addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
            this.tab = target;
            this.status = Text.empty();
            refresh();
        }).dimensions(x, 38, 86, 20).build());
    }

    private void buildFields() {
        this.fieldsLeft = (this.width - 416) / 2;
        int x = this.fieldsLeft;

        this.itemField = field(x, 120, "item");
        x += 126;
        this.countField = field(x, 32, "count");
        this.countField.setText("1");
        x += 38;
        this.nameField = field(x, 96, "name");
        x += 102;
        this.priceField = field(x, 56, "price");
        x += 62;
        this.enchantField = field(x, 88, "enchants");
    }

    private TextFieldWidget field(int x, int width, String label) {
        TextFieldWidget widget = new TextFieldWidget(this.textRenderer, x, 74, width, 20,
                Text.literal(label));
        widget.setMaxLength(128);
        this.addDrawableChild(widget);
        return widget;
    }

    private void buildInventoryGrid() {
        int left = wideLeft();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.inventoryButtons.add(addSlot(9 + row * 9 + column,
                        left + column * CELL, CONTENT_TOP + row * CELL, Tab.INVENTORY));
            }
        }
        for (int column = 0; column < 9; column++) {
            this.inventoryButtons.add(addSlot(column, left + column * CELL, hotbarY(), Tab.INVENTORY));
        }
    }

    private void buildEnderGrid() {
        int left = wideLeft();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.enderButtons.add(addSlot(row * 9 + column,
                        left + column * CELL, CONTENT_TOP + row * CELL, Tab.ENDER));
            }
        }
    }

    private void buildDispenserTab() {
        int left = (this.width - (3 * CELL - (CELL - BUTTON))) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                this.dispenserButtons.add(addSlot(row * 3 + column,
                        left + column * CELL, CONTENT_TOP + row * CELL, Tab.DISPENSER));
            }
        }

        int y = CONTENT_TOP + 3 * CELL + 12;
        addExtra(this.dispenserExtras, "Set what it fires", this.width / 2 - 152, y, 150,
                button -> setDispenserOutput());
        addExtra(this.dispenserExtras, "Watch block I'm facing", this.width / 2 + 2, y, 150,
                button -> watchLookedAt());
        addExtra(this.dispenserExtras, "Arrow lands where I face", this.width / 2 - 152, y + 24, 150,
                button -> setArrowTarget());
        addExtra(this.dispenserExtras, "No fake arrow", this.width / 2 + 2, y + 24, 150, button -> {
            ClientDispensers.setArrowTarget(null);
            SelfFakes.save();
            this.status = Text.literal("Fake arrows off for this rig.").formatted(Formatting.GREEN);
        });

        this.collectToggle = ButtonWidget.builder(Text.empty(), button -> {
            SelfFakes.setAutoCollect(!SelfFakes.autoCollect());
            refresh();
        }).dimensions(this.width / 2 - 152, y + 48, 304, 20).build();
        this.dispenserExtras.add(this.collectToggle);
        this.addDrawableChild(this.collectToggle);
    }

    private void buildRigsTab() {
        int left = (this.width - 4 * 88) / 2;
        for (int index = 0; index < MAX_RIG_BUTTONS; index++) {
            int slot = index;
            ButtonWidget button = ButtonWidget.builder(Text.empty(), ignored -> selectRig(slot))
                    .dimensions(left + (index % 4) * 88, CONTENT_TOP + (index / 4) * 24, 86, 20)
                    .build();
            this.rigButtons[index] = button;
            this.rigWidgets.add(button);
            this.addDrawableChild(button);
        }

        int togglesY = CONTENT_TOP + 62;
        this.rouletteToggle = ButtonWidget.builder(Text.empty(), button -> {
            RigProfile profile = ClientDispensers.active();
            profile.roulette = !profile.roulette;
            profile.tidyRoulette();
            SelfFakes.save();
            refresh();
        }).dimensions(this.width / 2 - 152, togglesY, 150, 20).build();
        this.rigWidgets.add(this.rouletteToggle);
        this.addDrawableChild(this.rouletteToggle);

        addExtra(this.rigWidgets, "Reset chamber", this.width / 2 + 2, togglesY, 150, button -> {
            ClientDispensers.active().resetShots();
            SelfFakes.save();
            this.status = Text.literal("Chamber count back to zero.").formatted(Formatting.GREEN);
            refresh();
        });

        // Shot picker: one button per chamber, so the loaded shot is a single click.
        int shotsLeft = (this.width - MAX_SHOT_BUTTONS * 26) / 2;
        for (int index = 0; index < MAX_SHOT_BUTTONS; index++) {
            int shot = index + 1;
            ButtonWidget button = ButtonWidget.builder(Text.literal(String.valueOf(shot)),
                            ignored -> setBulletShot(shot))
                    .dimensions(shotsLeft + index * 26, togglesY + 46, 24, 20)
                    .build();
            this.shotButtons[index] = button;
            this.rigWidgets.add(button);
            this.addDrawableChild(button);
        }

        this.fewerChambers = ButtonWidget.builder(Text.literal("- chamber"),
                        button -> changeChambers(-1))
                .dimensions(this.width / 2 - 152, togglesY + 70, 150, 20).build();
        this.moreChambers = ButtonWidget.builder(Text.literal("+ chamber"),
                        button -> changeChambers(1))
                .dimensions(this.width / 2 + 2, togglesY + 70, 150, 20).build();
        this.rigWidgets.add(this.fewerChambers);
        this.rigWidgets.add(this.moreChambers);
        this.addDrawableChild(this.fewerChambers);
        this.addDrawableChild(this.moreChambers);
    }

    private void buildFooter() {
        int y = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), button -> {
            ClientPlayerEntity player = this.client == null ? null : this.client.player;
            SelfFakes.clearAll(player);
            SelfFakes.clearAllContainers(player);
            this.status = Text.literal("Cleared every fake item.").formatted(Formatting.GREEN);
        }).dimensions(this.width / 2 - 152, y, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close())
                .dimensions(this.width / 2 + 2, y, 150, 20).build());
    }

    private void addExtra(List<ClickableWidget> group, String label, int x, int y, int width,
                          ButtonWidget.PressAction action) {
        ButtonWidget button = ButtonWidget.builder(Text.literal(label), action)
                .dimensions(x, y, width, 20).build();
        group.add(button);
        this.addDrawableChild(button);
    }

    private ButtonWidget addSlot(int slot, int x, int y, Tab owner) {
        ButtonWidget button = ButtonWidget.builder(Text.empty(), ignored -> onSlotClicked(slot, owner))
                .dimensions(x, y, BUTTON, BUTTON)
                .build();
        this.addDrawableChild(button);
        return button;
    }

    private int wideLeft() {
        return (this.width - (9 * CELL - (CELL - BUTTON))) / 2;
    }

    private int hotbarY() {
        return CONTENT_TOP + 3 * CELL + 8;
    }

    // ----------------------------------------------------------------- visibility

    /** Shows only what the active tab needs, and relabels anything that reads state. */
    private void refresh() {
        setVisible(this.inventoryButtons, this.tab == Tab.INVENTORY);
        setVisible(this.dispenserButtons, this.tab == Tab.DISPENSER);
        setVisible(this.enderButtons, this.tab == Tab.ENDER);
        for (ClickableWidget widget : this.dispenserExtras) show(widget, this.tab == Tab.DISPENSER);

        RigProfile profile = ClientDispensers.active();
        List<String> names = new ArrayList<>(ClientDispensers.profiles().keySet());

        for (int index = 0; index < this.rigButtons.length; index++) {
            ButtonWidget button = this.rigButtons[index];
            boolean used = index < names.size();
            show(button, this.tab == Tab.RIGS && used);
            if (used) {
                String name = names.get(index);
                button.setMessage(Text.literal(name.equals(profile.name) ? "> " + name : name));
            }
        }

        this.collectToggle.setMessage(Text.literal(SelfFakes.autoCollect()
                ? "Fired fakes land in your inventory" : "Fired fakes just vanish"));

        show(this.rouletteToggle, this.tab == Tab.RIGS);
        this.rouletteToggle.setMessage(Text.literal(
                profile.roulette ? "Roulette: on" : "Roulette: off"));

        boolean showChambers = this.tab == Tab.RIGS && profile.roulette;
        for (int index = 0; index < this.shotButtons.length; index++) {
            ButtonWidget button = this.shotButtons[index];
            boolean used = index < profile.chambers;
            show(button, showChambers && used);
            if (used) {
                int shot = index + 1;
                button.setMessage(Text.literal(shot == profile.bulletAt
                        ? "[" + shot + "]" : String.valueOf(shot)));
            }
        }
        show(this.fewerChambers, showChambers);
        show(this.moreChambers, showChambers);

        for (ClickableWidget widget : this.rigWidgets) {
            if (this.tab != Tab.RIGS) show(widget, false);
        }
    }

    private void setVisible(List<ButtonWidget> buttons, boolean visible) {
        for (ButtonWidget button : buttons) show(button, visible);
    }

    private void show(ClickableWidget widget, boolean visible) {
        if (widget == null) return;
        widget.visible = visible;
        widget.active = visible;
    }

    // ------------------------------------------------------------------ actions

    private FakeSpec buildFromFields() {
        int count = 1;
        try {
            count = Integer.parseInt(this.countField.getText().trim());
        } catch (NumberFormatException ignored) {
            // an unparseable count just means one
        }

        // An empty price falls back to the price file; a typed one overrides it for this fake.
        Double price = null;
        String typedPrice = this.priceField.getText().trim().replace(",", "");
        if (!typedPrice.isEmpty()) {
            try {
                price = Double.parseDouble(typedPrice);
            } catch (NumberFormatException ignored) {
                // leave it null and fall back to the file
            }
        }

        return SelfFakes.buildSpec(this.itemField.getText(), count,
                this.enchantField.getText(), price, this.nameField.getText());
    }

    private void onSlotClicked(int slot, Tab owner) {
        ClientPlayerEntity player = this.client == null ? null : this.client.player;
        String typed = this.itemField.getText().trim();

        if (typed.isEmpty()) {
            switch (owner) {
                case INVENTORY -> SelfFakes.clear(slot, player);
                case DISPENSER -> SelfFakes.clearContainer(SelfFakes.DISPENSER, slot, player);
                case ENDER -> SelfFakes.clearContainer(SelfFakes.ENDER_CHEST, slot, player);
                default -> { }
            }
            this.status = Text.literal("Cleared that slot.").formatted(Formatting.GREEN);
            return;
        }

        FakeSpec spec = buildFromFields();
        if (spec == null) {
            this.status = Text.literal("No item called '" + typed + "'.").formatted(Formatting.RED);
            return;
        }

        switch (owner) {
            case INVENTORY -> SelfFakes.set(slot, spec);
            case DISPENSER -> SelfFakes.setContainer(SelfFakes.DISPENSER, slot, spec);
            case ENDER -> SelfFakes.setContainer(SelfFakes.ENDER_CHEST, slot, spec);
            default -> { }
        }

        String priced = spec.price != null ? " at $" + FakeLore.preview(spec.price)
                : (FakeLore.hasPriceFor(spec.stack()) ? " (priced from file)" : "");
        this.status = Text.literal(spec.count + "x " + spec.label() + priced)
                .formatted(Formatting.GREEN);
    }

    private void selectRig(int index) {
        List<String> names = new ArrayList<>(ClientDispensers.profiles().keySet());
        if (index >= names.size()) return;

        ClientDispensers.use(names.get(index));
        SelfFakes.save();
        this.status = Text.literal("Using rig '" + names.get(index) + "'.").formatted(Formatting.GREEN);
        refresh();
    }

    private void setBulletShot(int shot) {
        RigProfile profile = ClientDispensers.active();
        profile.bulletAt = shot;
        profile.tidyRoulette();
        SelfFakes.save();
        this.status = Text.literal("Loaded shot is number " + profile.bulletAt + ".")
                .formatted(Formatting.GREEN);
        refresh();
    }

    private void changeChambers(int delta) {
        RigProfile profile = ClientDispensers.active();
        profile.chambers += delta;
        profile.tidyRoulette();
        SelfFakes.save();
        refresh();
    }

    private void setDispenserOutput() {
        FakeSpec spec = buildFromFields();
        if (spec == null) {
            this.status = Text.literal("Type an item first.").formatted(Formatting.RED);
            return;
        }

        ClientDispensers.setResult(spec);
        SelfFakes.save();
        this.status = Text.literal("This rig will fire " + spec.label() + ".")
                .formatted(Formatting.GREEN);
    }

    private void watchLookedAt() {
        BlockHitResult hit = lookedAt(64.0);
        if (hit == null) {
            this.status = Text.literal("Nothing in front of you within 64 blocks.")
                    .formatted(Formatting.RED);
            return;
        }

        ClientDispensers.watch(hit.getBlockPos());
        SelfFakes.save();
        this.status = Text.literal("Watching that block (" + ClientDispensers.watchedCount()
                + " total).").formatted(Formatting.GREEN);
    }

    private void setArrowTarget() {
        BlockHitResult hit = lookedAt(128.0);
        if (hit == null) {
            this.status = Text.literal("Nothing in front of you within 128 blocks.")
                    .formatted(Formatting.RED);
            return;
        }

        ClientDispensers.setArrowTarget(hit.getPos());
        SelfFakes.save();
        this.status = Text.literal(String.format("Arrows will land at %.2f %.2f %.2f",
                hit.getPos().x, hit.getPos().y, hit.getPos().z)).formatted(Formatting.GREEN);
    }

    /**
     * Not client.crosshairTarget: that only reaches as far as you can touch, and past it
     * returns a miss a few blocks in front of your face rather than what you aimed at.
     */
    private BlockHitResult lookedAt(double distance) {
        if (this.client == null || this.client.player == null) return null;

        HitResult hit = this.client.player.raycast(distance, 0.0F, false);
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            return block;
        }
        return null;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Only you see any of this").formatted(Formatting.DARK_GRAY),
                this.width / 2, 24, 0xFF6E7076);

        drawFieldLabels(context);

        switch (this.tab) {
            case INVENTORY -> {
                caption(context, "Main inventory", this.fieldsLeft, CONTENT_TOP - 12);
                caption(context, "Hotbar", this.fieldsLeft, hotbarY() - 12);
                drawIcons(context, this.inventoryButtons, slot -> SelfFakes.get(indexFor(slot)));
            }
            case DISPENSER -> {
                caption(context, "What a dispenser shows when opened", this.fieldsLeft, CONTENT_TOP - 12);
                drawIcons(context, this.dispenserButtons,
                        slot -> SelfFakes.getContainer(SelfFakes.DISPENSER, slot));
            }
            case ENDER -> {
                caption(context, "What an ender chest shows when opened", this.fieldsLeft, CONTENT_TOP - 12);
                drawIcons(context, this.enderButtons,
                        slot -> SelfFakes.getContainer(SelfFakes.ENDER_CHEST, slot));
            }
            case RIGS -> drawRigs(context);
        }

        if (!this.status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, this.status,
                    this.width / 2, this.height - 44, 0xFFFFFFFF);
        }
    }

    private void drawFieldLabels(DrawContext context) {
        int x = this.fieldsLeft;
        caption(context, "item", x, 64);
        caption(context, "count", x + 126, 64);
        caption(context, "name", x + 164, 64);
        caption(context, "price", x + 266, 64);
        caption(context, "enchants", x + 328, 64);
    }

    private void drawRigs(DrawContext context) {
        RigProfile profile = ClientDispensers.active();
        int left = (this.width - 4 * 88) / 2;
        caption(context, "Rigs", left, CONTENT_TOP - 12);

        int y = CONTENT_TOP + 62;
        if (profile.roulette) {
            String line = "Shot " + profile.shot + " of " + profile.chambers
                    + "   loaded on " + profile.bulletAt;
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(line).formatted(Formatting.GRAY), this.width / 2, y + 30, 0xFFAAAAAA);

            String bullet = profile.bullet == null ? "nothing" : profile.bullet.label();
            String blank = profile.blank == null ? "nothing" : profile.blank.label();
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("loaded fires " + bullet + " - others fire " + blank)
                            .formatted(Formatting.DARK_GRAY),
                    this.width / 2, y + 94, 0xFF6E7076);
        } else {
            FakeSpec selected = profile.selected();
            String line = selected == null
                    ? "No items in this rig. Type one above, then Set what it fires."
                    : "Cycles to " + selected.label() + "   ( ] and [ )";
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(line).formatted(Formatting.GRAY), this.width / 2, y + 30, 0xFFAAAAAA);

            int row = 0;
            for (Map.Entry<BlockPos, FakeSpec> entry : profile.perDispenser.entrySet()) {
                if (row >= 4) break;
                BlockPos pos = entry.getKey();
                context.drawCenteredTextWithShadow(this.textRenderer,
                        Text.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()
                                + "  fires  " + entry.getValue().label()).formatted(Formatting.DARK_GRAY),
                        this.width / 2, y + 50 + row * 11, 0xFF6E7076);
                row++;
            }
        }
    }

    private void caption(DrawContext context, String text, int x, int y) {
        context.drawTextWithShadow(this.textRenderer,
                Text.literal(text).formatted(Formatting.GRAY), x, y, 0xFF9AA0A6);
    }

    /** The inventory tab lists storage first, then the hotbar, so map position to slot. */
    private int indexFor(int position) {
        return position < 27 ? 9 + position : position - 27;
    }

    private void drawIcons(DrawContext context, List<ButtonWidget> buttons,
                           java.util.function.IntFunction<ItemStack> lookup) {
        for (int position = 0; position < buttons.size(); position++) {
            ButtonWidget button = buttons.get(position);
            if (!button.visible) continue;

            ItemStack stack = lookup.apply(position);
            if (stack.isEmpty()) continue;
            context.drawItem(stack, button.getX() + 2, button.getY() + 2);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
