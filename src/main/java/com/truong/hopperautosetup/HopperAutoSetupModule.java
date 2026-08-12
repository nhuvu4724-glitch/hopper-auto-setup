package com.truong.hopperautosetup;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.IPathManager;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HopperAutoSetupModule extends Module {
    private final SettingGroup region = settings.createGroup("Region");
    private final SettingGroup behavior = settings.createGroup("Behavior");

    private final Setting<Integer> x1 = region.add(new IntSetting.Builder().name("x1").description("First corner X.").defaultValue(0).build());
    private final Setting<Integer> y1 = region.add(new IntSetting.Builder().name("y1").description("First corner Y.").defaultValue(0).build());
    private final Setting<Integer> z1 = region.add(new IntSetting.Builder().name("z1").description("First corner Z.").defaultValue(0).build());
    private final Setting<Integer> x2 = region.add(new IntSetting.Builder().name("x2").description("Second corner X.").defaultValue(0).build());
    private final Setting<Integer> y2 = region.add(new IntSetting.Builder().name("y2").description("Second corner Y.").defaultValue(0).build());
    private final Setting<Integer> z2 = region.add(new IntSetting.Builder().name("z2").description("Second corner Z.").defaultValue(0).build());

    private final Setting<Item> item = behavior.add(new ItemSetting.Builder()
        .name("item")
        .description("Item to put one-by-one into hopper slots 1-4.")
        .defaultValue(Items.GLASS)
        .build());
    private final Setting<Integer> scanDelay = behavior.add(new IntSetting.Builder()
        .name("scan-delay")
        .description("Ticks between scans of the configured region.")
        .range(5, 200)
        .sliderRange(5, 60)
        .defaultValue(20)
        .build());
    private final Setting<Integer> openDelay = behavior.add(new IntSetting.Builder()
        .name("open-delay")
        .description("Ticks to wait after clicking a hopper before processing its GUI.")
        .range(2, 40)
        .sliderRange(2, 20)
        .defaultValue(10)
        .build());
    private final Setting<Integer> clickDelay = behavior.add(new IntSetting.Builder()
        .name("click-delay")
        .description("Ticks between individual inventory placements. Raise this if the server is laggy and items get skipped or duplicated.")
        .range(1, 20)
        .sliderRange(1, 10)
        .defaultValue(4)
        .build());
    private final Setting<Integer> reach = behavior.add(new IntSetting.Builder()
        .name("reach")
        .description("Distance from the hopper at which it is clicked.")
        .range(2, 5)
        .sliderRange(2, 5)
        .defaultValue(4)
        .build());
    private final Setting<Integer> sweepSpacing = behavior.add(new IntSetting.Builder()
        .name("sweep-spacing")
        .description("Grid spacing (blocks) used to walk the region and load chunks when no known hopper is left nearby.")
        .range(8, 128)
        .sliderRange(16, 64)
        .defaultValue(32)
        .build());

    private final Set<BlockPos> completed = new HashSet<>();
    private final Set<BlockPos> found = new HashSet<>();

    private List<BlockPos> waypoints;
    private int waypointIndex;

    private BlockPos current;
    private int tick;
    private int waitTicks;
    private int placeStep;
    private int collectAttempts;
    private boolean clicked;
    private boolean processing;

    public HopperAutoSetupModule() {
        super(HopperAutoSetup.CATEGORY, "hopper-auto-setup", "Finds hoppers in a configured region, walks to them (sweeping the region to load new chunks if needed), empties anything already inside back into your inventory, then fills hopper slots 1-4 with one selected item each.");
    }

    @Override
    public void onActivate() {
        completed.clear();
        found.clear();
        waypoints = null;
        waypointIndex = 0;
        current = null;
        tick = 0;
        waitTicks = 0;
        placeStep = 0;
        collectAttempts = 0;
        clicked = false;
        processing = false;
        scan();
        info("Started. Found %d hopper(s).", found.size());
    }

    @Override
    public void onDeactivate() {
        stopPathing();
        closeIfOpen();
        current = null;
        clicked = false;
        processing = false;
    }

    @Override
    public String getInfoString() {
        if (current == null) return found.size() + " hopper(s)";
        return current.getX() + " " + current.getY() + " " + current.getZ();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        tick++;

        if (mc.currentScreen instanceof HopperScreen) {
            if (!processing && current != null && clicked) {
                if (waitTicks > 0) {
                    waitTicks--;
                    return;
                }
                processing = true;
                placeStep = 0;
                collectAttempts = 0;
            }
            if (processing) {
                handleContainer();
                return;
            }
        } else if (processing) {
            processing = false;
            clicked = false;
            current = null;
        }

        if (tick % scanDelay.get() == 0) scan();

        if (current == null) {
            current = findNearestUncompleted();
            if (current == null) {
                sweep();
                return;
            }
            clicked = false;
            waitTicks = 0;
        }

        double distanceSq = squaredDistanceTo(current);
        double reachSq = reach.get() * reach.get();

        if (distanceSq > reachSq) {
            IPathManager path = PathManagers.get();
            if (!path.isPathing()) path.moveTo(current, false);
            return;
        }

        stopPathing();

        if (!clicked) {
            if (!clickHopper(current)) {
                warning("Could not click hopper at %s.", current.toShortString());
                completed.add(current);
                found.remove(current);
                current = null;
                return;
            }
            clicked = true;
            waitTicks = openDelay.get();
        }
    }

    private void scan() {
        if (mc.world == null) return;

        int minX = Math.min(x1.get(), x2.get());
        int maxX = Math.max(x1.get(), x2.get());
        int minY = Math.min(y1.get(), y2.get());
        int maxY = Math.max(y1.get(), y2.get());
        int minZ = Math.min(z1.get(), z2.get());
        int maxZ = Math.max(z1.get(), z2.get());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mc.world.getBlockState(pos).isOf(Blocks.HOPPER)
                        && !completed.contains(pos)
                        && !found.contains(pos)) {
                        found.add(pos.toImmutable());
                    }
                }
            }
        }
    }

    // Walks a grid of waypoints across the configured region so unloaded chunks get loaded
    // and re-scanned. Only runs while there is no already-known hopper to go handle.
    private void sweep() {
        if (waypoints == null) buildWaypoints();
        if (waypoints.isEmpty() || waypointIndex >= waypoints.size()) return;
        if (mc.player == null) return;

        BlockPos raw = waypoints.get(waypointIndex);
        BlockPos target = new BlockPos(raw.getX(), (int) mc.player.getY(), raw.getZ());

        double dx = mc.player.getX() - (target.getX() + 0.5);
        double dz = mc.player.getZ() - (target.getZ() + 0.5);
        double distSqXZ = dx * dx + dz * dz;

        if (distSqXZ < 12 * 12) {
            waypointIndex++;
            return;
        }

        IPathManager path = PathManagers.get();
        if (!path.isPathing()) path.moveTo(target, false);
    }

    private void buildWaypoints() {
        waypoints = new ArrayList<>();

        int minX = Math.min(x1.get(), x2.get());
        int maxX = Math.max(x1.get(), x2.get());
        int minZ = Math.min(z1.get(), z2.get());
        int maxZ = Math.max(z1.get(), z2.get());
        int step = Math.max(1, sweepSpacing.get());

        boolean reverse = false;
        for (int x = minX; x <= maxX; x += step) {
            if (!reverse) {
                for (int z = minZ; z <= maxZ; z += step) waypoints.add(new BlockPos(x, 0, z));
            } else {
                for (int z = maxZ; z >= minZ; z -= step) waypoints.add(new BlockPos(x, 0, z));
            }
            reverse = !reverse;
        }

        waypointIndex = 0;
    }

    private BlockPos findNearestUncompleted() {
        if (mc.player == null) return null;
        return found.stream()
            .filter(pos -> !completed.contains(pos))
            .min(Comparator.comparingDouble(this::squaredDistanceTo))
            .orElse(null);
    }

    private double squaredDistanceTo(BlockPos pos) {
        if (mc.player == null || pos == null) return Double.MAX_VALUE;
        double dx = mc.player.getX() - (pos.getX() + 0.5);
        double dy = mc.player.getY() - (pos.getY() + 0.5);
        double dz = mc.player.getZ() - (pos.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean clickHopper(BlockPos pos) {
        if (mc.interactionManager == null || mc.player == null || mc.world == null) return false;
        if (!mc.world.getBlockState(pos).isOf(Blocks.HOPPER)) return false;

        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(pos).add(0, 0.45, 0),
            Direction.UP,
            pos,
            false
        );

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private void handleContainer() {
        if (!(mc.currentScreen instanceof HopperScreen)) {
            processing = false;
            clicked = false;
            return;
        }
        if (mc.player.currentScreenHandler == null || mc.interactionManager == null) return;
        if (mc.player.currentScreenHandler.slots.size() < 41) return;

        if (placeStep >= 4) {
            finishCurrent();
            return;
        }

        int targetSlot = placeStep;
        ItemStack hopperStack = mc.player.currentScreenHandler.getSlot(targetSlot).getStack();

        if (!hopperStack.isEmpty()) {
            if (collectAttempts >= 3) {
                warning("Slot %d at %s stayed occupied, skipping it.", targetSlot, current.toShortString());
                placeStep++;
                collectAttempts = 0;
                waitTicks = clickDelay.get();
                return;
            }

            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                targetSlot,
                0,
                SlotActionType.QUICK_MOVE,
                mc.player
            );
            collectAttempts++;
            waitTicks = clickDelay.get();
            return;
        }

        collectAttempts = 0;

        Item wanted = item.get();
        int inventoryIndex = findInventorySlot(wanted);
        if (inventoryIndex < 0) {
            error("No %s in inventory. Stopping.", wanted);
            disable();
            return;
        }

        int screenSlot = inventoryIndexToScreenSlot(inventoryIndex);

        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            screenSlot,
            0,
            SlotActionType.PICKUP,
            mc.player
        );
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            targetSlot,
            1,
            SlotActionType.PICKUP,
            mc.player
        );
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            screenSlot,
            0,
            SlotActionType.PICKUP,
            mc.player
        );

        placeStep++;
        waitTicks = clickDelay.get();
    }

    private void finishCurrent() {
        completed.add(current);
        found.remove(current);
        current = null;
        clicked = false;
        processing = false;
        placeStep = 0;
        collectAttempts = 0;
        stopPathing();
        closeIfOpen();
    }

    private int findInventorySlot(Item wanted) {
        if (mc.player == null) return -1;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isOf(wanted)) return i;
        }
        return -1;
    }

    private int inventoryIndexToScreenSlot(int inventoryIndex) {
        return inventoryIndex < 9 ? 32 + inventoryIndex : 5 + (inventoryIndex - 9);
    }

    private void stopPathing() {
        try {
            PathManagers.get().stop();
        } catch (Throwable ignored) {
        }
    }

    private void closeIfOpen() {
        if (mc.player != null && mc.currentScreen instanceof HopperScreen) {
            mc.player.closeHandledScreen();
        }
    }
}
