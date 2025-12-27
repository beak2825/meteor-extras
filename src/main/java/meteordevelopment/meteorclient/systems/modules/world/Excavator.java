/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.BetterBlockPos;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.item.ItemStack;
import org.lwjgl.glfw.GLFW;

public class Excavator extends Module {
    private final IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRendering = settings.createGroup("Rendering");

    // Keybindings
    private final Setting<Keybind> selectionBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("selection-bind")
        .description("Bind to draw selection.")
        .defaultValue(Keybind.fromButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT))
        .build()
    );

    // Logging
    private final Setting<Boolean> logSelection = sgGeneral.add(new BoolSetting.Builder()
        .name("log-selection")
        .description("Logs the selection coordinates to the chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> keepActive = sgGeneral.add(new BoolSetting.Builder()
        .name("keep-active")
        .description("Keep the module active after finishing the excavation.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> disableLowDura = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-low-dura")
        .description("Disable the module when your main hand tool has < 100 durability to prevent it from breaking.")
        .defaultValue(false)
        .build()
    );

    // Rendering
    private final Setting<ShapeMode> shapeMode = sgRendering.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRendering.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color.")
        .defaultValue(new SettingColor(255, 255, 255, 50))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRendering.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private enum Status {
        SEL_START,
        SEL_END,
		READY_TO_WORK, // new state
        WORKING
    }

    private Status status = Status.SEL_START;
    private BetterBlockPos start, end;

    public Excavator() {
        super(Categories.World, "excavator", "Excavate a selection area.");
    }

    @Override
    public void onDeactivate() {
        baritone.getSelectionManager().removeSelection(baritone.getSelectionManager().getLastSelection());
        if (baritone.getBuilderProcess().isActive()) baritone.getCommandManager().execute("stop");
        status = Status.SEL_START;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (disableLowDura.get()) {
            if (isToolLowDurability()) {
                info("Tool durability is below 100, stopping Excavator to prevent breaking.");
                toggle();
                return;
            }
        }

        if (status == Status.READY_TO_WORK && !baritone.getBuilderProcess().isActive()) {
            baritone.getBuilderProcess().clearArea(start, end);
            status = Status.WORKING;
        }
    }

    private boolean isToolLowDurability() {
        ItemStack mainHandStack = mc.player.getMainHandStack();
        if (mainHandStack.isEmpty() || !mainHandStack.isDamageable()) {
            return false;
        }
        return mainHandStack.getMaxDamage() - mainHandStack.getDamage() < 100;
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action != KeyAction.Press || !selectionBind.get().isPressed() || mc.currentScreen != null) {
            return;
        }
        selectCorners();
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action != KeyAction.Press || !selectionBind.get().isPressed() || mc.currentScreen != null) {
            return;
        }
        selectCorners();
    }

    private void selectCorners() {
        if (!(mc.crosshairTarget instanceof BlockHitResult result)) return;

        if (status == Status.SEL_START) {
            start = BetterBlockPos.from(result.getBlockPos());
            status = Status.SEL_END;
            if (logSelection.get()) {
                info("Start corner set: (%d, %d, %d)".formatted(start.getX(), start.getY(), start.getZ()));
            }
        } else if (status == Status.SEL_END) {
            end = BetterBlockPos.from(result.getBlockPos());
            status = Status.READY_TO_WORK;
            if (logSelection.get()) {
                info("End corner set: (%d, %d, %d)".formatted(end.getX(), end.getY(), end.getZ()));
            }
            baritone.getSelectionManager().addSelection(start, end);
        }
    }

	@EventHandler
	private void onRender3D(Render3DEvent event) {
		// Highlight selection points while selecting
		if (status == Status.SEL_START || status == Status.SEL_END) {
			if (mc.crosshairTarget instanceof BlockHitResult result) {
				event.renderer.box(
					result.getBlockPos(),
					sideColor.get(),
					lineColor.get(),
					shapeMode.get(),
					0
				);
			}
			return;
		}

		// Only handle WORKING state logic below this point
		if (status != Status.WORKING) return;

		// Check if Baritone has finished building
		if (!baritone.getBuilderProcess().isActive()) {
			// Remove the selection that was being built
			baritone.getSelectionManager().removeSelection(
				baritone.getSelectionManager().getLastSelection()
			);

			// Decide what to do next
			if (keepActive.get()) {
				status = Status.SEL_START;  // Go back to selecting new area
			} else {
				toggle();  // Turn off the module entirely
			}
		}
	}

}
