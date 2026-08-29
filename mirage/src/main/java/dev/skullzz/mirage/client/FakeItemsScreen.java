package dev.skullzz.mirage.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/**
 * Point-and-click front end for the client-side fakes. One tab per place a fake can live;
 * type an item, click the slots it should appear in, empty field clears.
 */
public class FakeItemsScreen extends Screen {
    private enum Tab { INVENTORY, DISPENSER, ENDER }

    private static final int CELL = 22;
    private static final int BUTTON = 20;

    private final List<ButtonWidget> inventoryButtons = new ArrayList<>();
    private final List<ButtonWidget> dispenserButtons = new ArrayList<>();
    private final List<ButtonWidget> enderButtons = new ArrayList<>();
    private final List<ClickableWidget> dispenserExtras = new ArrayList<>();

    private TextFieldWidget itemField;
    private TextFieldWidget countField;
    private TextFieldWidget priceField;
    private TextFieldWidget enchantField;
    private Text status = Text.empty();
    private Tab tab = Tab.INVENTORY;

    public FakeItemsScreen() {
        super(Text.literal("Mirage - Fake Items"));
    }

    @Override
    protected void init() {
        this.inventoryButtons.clear();
        this.dispenserButtons.clear();
        this.enderButtons.clear();
        this.dispenserExtras.clear();

        int tabsLeft = (this.width - 3 * 92) / 2;
        addTab("Inventory", Tab.INVENTORY, tabsLeft);
        addTab("Dispenser", Tab.DISPENSER, tabsLeft + 92);
        addTab("Ender chest", Tab.ENDER, tabsLeft + 184);

        int fieldsLeft = (this.width - 360) / 2;
        this.itemField = new TextFieldWidget(this.textRenderer, fieldsLeft, 68, 130, 20, Text.literal("item"));
        this.itemField.setMaxLength(64);
        this.addDrawableChild(this.itemField);

        this.countField = new TextFieldWidget(this.textRenderer, fieldsLeft + 136, 68, 34, 20, Text.literal("count"));
        this.countField.setMaxLength(3);
        this.countField.setText("1");
        this.addDrawableChild(this.countField);

        this.priceField = new TextFieldWidget(this.textRenderer, fieldsLeft + 176, 68, 60, 20, Text.literal("price"));
        this.priceField.setMaxLength(16);
        this.addDrawableChild(this.priceField);

        this.enchantField = new TextFieldWidget(this.textRenderer, fieldsLeft + 242, 68, 118, 20,
                Text.literal("enchants"));
        this.enchantField.setMaxLength(128);
        this.addDrawableChild(this.enchantField);

        int wideLeft = (this.width - (9 * CELL - (CELL - BUTTON))) / 2;
        int top = 110;

        // Inventory: main storage above, hotbar below, as on the real screen.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.inventoryButtons.add(addSlot(9 + row * 9 + column,
                        wideLeft + column * CELL, top + row * CELL, Tab.INVENTORY));
            }
        }
        int hotbarY = top + 3 * CELL + 8;
        for (int column = 0; column < 9; column++) {
            this.inventoryButtons.add(addSlot(column, wideLeft + column * CELL, hotbarY, Tab.INVENTORY));
        }

        // Ender chest: the same nine-wide grid, three rows.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                this.enderButtons.add(addSlot(row * 9 + column,
                        wideLeft + column * CELL, top + row * CELL, Tab.ENDER));
            }
        }

        // Dispenser: three by three, centred.
        int narrowLeft = (this.width - (3 * CELL - (CELL - BUTTON))) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                this.dispenserButtons.add(addSlot(row * 3 + column,
                        narrowLeft + column * CELL, top + row * CELL, Tab.DISPENSER));
            }
        }

        ButtonWidget output = ButtonWidget.builder(Text.literal("Set what it fires"),
                button -> setDispenserOutput()).dimensions(this.width / 2 - 152, top + 74, 150, 20).build();
        ButtonWidget watch = ButtonWidget.builder(Text.literal("Watch block I'm facing"),
                button -> watchLookedAt()).dimensions(this.width / 2 + 2, top + 74, 150, 20).build();
        ButtonWidget arrow = ButtonWidget.builder(Text.literal("Arrow lands where I face"),
                button -> setArrowTarget()).dimensions(this.width / 2 - 152, top + 96, 150, 20).build();
        ButtonWidget noArrow = ButtonWidget.builder(Text.literal("No fake arrow"),
                button -> {
                    ClientDispensers.setArrowTarget(null);
                    SelfFakes.save();
                    this.status = Text.literal("Fake arrows turned off.").formatted(Formatting.GREEN);
                }).dimensions(this.width / 2 + 2, top + 96, 150, 20).build();

        this.dispenserExtras.add(output);
        this.dispenserExtras.add(watch);
        this.dispenserExtras.add(arrow);
        this.dispenserExtras.add(noArrow);
        this.addDrawableChild(output);
        this.addDrawableChild(watch);
        this.addDrawableChild(arrow);
        this.addDrawableChild(noArrow);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Clear all"), button -> {
            ClientPlayerEntity player = this.client == null ? null : this.client.player;
            SelfFakes.clearAll(player);
            SelfFakes.clearAllContainers(player);
            this.status = Text.literal("Cleared every fake.").formatted(Formatting.GREEN);
        }).dimensions(this.width / 2 - 152, hotbarY + 34, 150, 20).build());

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> this.close())
                .dimensions(this.width / 2 + 2, hotbarY + 34, 150, 20).build());

        applyTabVisibility();
    }

    private void addTab(String label, Tab target, int x) {
        this.addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> {
            this.tab = target;
            applyTabVisibility();
        }).dimensions(x, 42, 90, 20).build());
    }

    private ButtonWidget addSlot(int slot, int x, int y, Tab owner) {
        ButtonWidget button = ButtonWidget.builder(Text.empty(), ignored -> onSlotClicked(slot, owner))
                .dimensions(x, y, BUTTON, BUTTON)
                .build();
        this.addDrawableChild(button);
        return button;
    }

    /** Only the active tab's widgets are shown; the rest stay built but hidden. */
    private void applyTabVisibility() {
        setVisible(this.inventoryButtons, this.tab == Tab.INVENTORY);
        setVisible(this.dispenserButtons, this.tab == Tab.DISPENSER);
        setVisible(this.enderButtons, this.tab == Tab.ENDER);
        for (ClickableWidget widget : this.dispenserExtras) {
            widget.visible = this.tab == Tab.DISPENSER;
            widget.active = this.tab == Tab.DISPENSER;
        }
    }

    private void setVisible(List<ButtonWidget> buttons, boolean visible) {
        for (ButtonWidget button : buttons) {
            button.visible = visible;
            button.active = visible;
        }
    }

    // ------------------------------------------------------------------ actions

    private FakeSpec buildFromFields() {
        Item item = SelfFakes.lookupItem(this.itemField.getText().trim());
        if (item == null) return null;

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
        return new FakeSpec(item, count, this.enchantField.getText(), price);
    }

    private void onSlotClicked(int slot, Tab owner) {
        ClientPlayerEntity player = this.client == null ? null : this.client.player;
        String typed = this.itemField.getText().trim();

        if (typed.isEmpty()) {
            switch (owner) {
                case INVENTORY -> SelfFakes.clear(slot, player);
                case DISPENSER -> SelfFakes.clearContainer(SelfFakes.DISPENSER, slot, player);
                case ENDER -> SelfFakes.clearContainer(SelfFakes.ENDER_CHEST, slot, player);
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
        }
        String priced = spec.price != null ? " at $" + FakeLore.preview(spec.price)
                : (FakeLore.hasPriceFor(spec.stack()) ? " (priced from file)" : " (no price)");
        this.status = Text.literal(spec.count + "x " + typed + priced).formatted(Formatting.GREEN);
    }

    private void setDispenserOutput() {
        FakeSpec spec = buildFromFields();
        if (spec == null) {
            this.status = Text.literal("Type an item first.").formatted(Formatting.RED);
            return;
        }
        ClientDispensers.setResult(spec);
        SelfFakes.save();
        this.status = Text.literal("Watched dispensers will appear to fire that.")
                .formatted(Formatting.GREEN);
    }

    private void setArrowTarget() {
        BlockHitResult hit = lookedAt(128.0);
        if (hit == null) {
            this.status = Text.literal("Nothing in front of you within 128 blocks.")
                    .formatted(Formatting.RED);
            return;
        }

        // The precise point on the block face, so it lands on the pad rather than its middle.
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

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Item, count, price, enchants (sharpness:5). Empty item clears a slot.")
                        .formatted(Formatting.GRAY),
                this.width / 2, 26, 0xFFAAAAAA);

        switch (this.tab) {
            case INVENTORY -> drawIcons(context, this.inventoryButtons, slot -> SelfFakes.get(indexFor(slot)));
            case DISPENSER -> drawIcons(context, this.dispenserButtons,
                    slot -> SelfFakes.getContainer(SelfFakes.DISPENSER, slot));
            case ENDER -> drawIcons(context, this.enderButtons,
                    slot -> SelfFakes.getContainer(SelfFakes.ENDER_CHEST, slot));
        }

        if (!this.status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, this.status,
                    this.width / 2, this.height - 22, 0xFFFFFFFF);
        }
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
