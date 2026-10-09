package com.example.builder;

import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BlueprintStationBlock extends Block {

    private static final Gson GSON = new Gson();
    private static final Map<BlockPos, BlueprintWorkbenchBlock.BlueprintFile> LOADED_BLUEPRINTS = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_NO_PUMPKIN_CONFIRM = new HashMap<>();

    public BlueprintStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /**
     * 玩家挖掘破坏工作站时，清空该坐标的蓝图数据与确认缓存，以便再次放置时可重新选盘
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        LOADED_BLUEPRINTS.remove(pos);
        PENDING_NO_PUMPKIN_CONFIRM.values().removeIf(p -> p.equals(pos));
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            BlueprintWorkbenchBlock.BlueprintFile blueprint = LOADED_BLUEPRINTS.get(pos);

            if (blueprint == null) {
                player.sendSystemMessage(Component.literal("§e[蓝图工作站] 正在打开系统选盘窗口，请选择蓝图 JSON 文件..."));
                openNativeFileDialogAsync(serverLevel, pos, player);
                return InteractionResult.SUCCESS;
            }

            verifyAndBuild(serverLevel, pos, player, blueprint);
        }
        return InteractionResult.SUCCESS;
    }

    private void openNativeFileDialogAsync(ServerLevel level, BlockPos pos, Player player) {
        new Thread(() -> {
            try {
                String psScript = "[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms') | Out-Null; " +
                        "$f = New-Object System.Windows.Forms.OpenFileDialog; " +
                        "$f.Filter = 'Blueprint JSON (*.json)|*.json'; " +
                        "$f.Title = '选择要建造的蓝图文件'; " +
                        "$f.TopMost = $true; " +
                        "if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $f.FileName }";

                ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psScript);
                Process process = pb.start();

                Scanner scanner = new Scanner(process.getInputStream(), "GBK");
                String selectedPath = null;
                if (scanner.hasNextLine()) {
                    selectedPath = scanner.nextLine().trim();
                }
                scanner.close();
                process.waitFor();

                if (selectedPath != null && !selectedPath.isEmpty()) {
                    File file = new File(selectedPath);
                    if (file.exists()) {
                        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                            BlueprintWorkbenchBlock.BlueprintFile loaded = GSON.fromJson(reader, BlueprintWorkbenchBlock.BlueprintFile.class);
                            level.getServer().execute(() -> {
                                LOADED_BLUEPRINTS.put(pos, loaded);
                                player.sendSystemMessage(Component.literal("§a✔ 成功载入蓝图: §f" + file.getName()));
                                if (player.isCreative()) {
                                    player.sendSystemMessage(Component.literal("§d✨ 检测到创造模式，再次右键将免材一键建造！"));
                                } else {
                                    player.sendSystemMessage(Component.literal("§e📦 请在工作站紧邻放置箱子并放入材料，再次右键开始核对建造！"));
                                }
                            });
                            return;
                        }
                    }
                }
                level.getServer().execute(() -> player.sendSystemMessage(Component.literal("§c✘ 取消了蓝图选择。")));
            } catch (Exception e) {
                level.getServer().execute(() -> player.sendSystemMessage(Component.literal("§c✘ 打开文件窗口失败: " + e.getMessage())));
            }
        }).start();
    }

    private void verifyAndBuild(ServerLevel level, BlockPos pos, Player player, BlueprintWorkbenchBlock.BlueprintFile blueprint) {
        BlockPos targetPumpkin = scanForPumpkin(level, pos);
        UUID uuid = player.getUUID();

        if (blueprint.hasAnchor && targetPumpkin == null) {
            BlockPos pendingPos = PENDING_NO_PUMPKIN_CONFIRM.get(uuid);
            if (pendingPos == null || !pendingPos.equals(pos)) {
                PENDING_NO_PUMPKIN_CONFIRM.put(uuid, pos);
                player.sendSystemMessage(Component.literal("§6========================================"));
                player.sendSystemMessage(Component.literal("§e⚠ 未在周围 (前6后6左6右6高3) 检测到定位南瓜！"));
                player.sendSystemMessage(Component.literal("§f该蓝图带有锚点，若现在建造将默认以工作站正上方为基准。"));
                player.sendSystemMessage(Component.literal("§a👉 如确定不放南瓜直接建造，请【再次右键工作站】确认建造！"));
                player.sendSystemMessage(Component.literal("§7(若想精准对齐，请先在合适位置摆放南瓜后再右键)"));
                player.sendSystemMessage(Component.literal("§6========================================"));
                return;
            }
            PENDING_NO_PUMPKIN_CONFIRM.remove(uuid);
        } else {
            PENDING_NO_PUMPKIN_CONFIRM.remove(uuid);
        }

        if (player.isCreative()) {
            player.sendSystemMessage(Component.literal("§6========================================"));
            player.sendSystemMessage(Component.literal("§d✨ 【蓝图工作站】创造模式特权：无需材料与箱子，开始一键生成结构！"));
            player.sendSystemMessage(Component.literal("§6========================================"));
            executeBuild(level, pos, player, blueprint, targetPumpkin);
            player.sendSystemMessage(Component.literal("§a✔ 结构一键建造完成！"));
            return;
        }

        List<Container> containers = getAdjacentContainers(level, pos);
        if (containers.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c✘ 未在蓝图工作站四周找到任何容器（箱子）！"));
            return;
        }

        Map<String, Integer> inventoryCounts = new HashMap<>();
        for (Container container : containers) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                    inventoryCounts.put(itemId, inventoryCounts.getOrDefault(itemId, 0) + stack.getCount());
                }
            }
        }

        boolean allSatisfied = true;
        player.sendSystemMessage(Component.literal("§6========================================"));
        player.sendSystemMessage(Component.literal("§e📥 【蓝图工作站】建造所需材料与箱子库存核对:"));

        for (Map.Entry<String, Integer> req : blueprint.requiredMaterials.entrySet()) {
            String requiredId = req.getKey();
            int neededCount = req.getValue();

            String matchingItemId = requiredId;
            if (requiredId.contains("water")) matchingItemId = "minecraft:water_bucket";
            else if (requiredId.contains("lava")) matchingItemId = "minecraft:lava_bucket";
            else if (!matchingItemId.contains(":")) matchingItemId = "minecraft:" + matchingItemId;

            int available = inventoryCounts.getOrDefault(matchingItemId, 0);

            Identifier itemIdent = Identifier.tryParse(matchingItemId);
            Item item = (itemIdent != null) ? BuiltInRegistries.ITEM.getValue(itemIdent) : Items.AIR;
            Component displayName = (item != Items.AIR) ? new ItemStack(item).getHoverName() : Component.literal(matchingItemId);

            if (available >= neededCount) {
                player.sendSystemMessage(Component.literal(String.format("  §a✔ %s: §f%d / %d", displayName.getString(), available, neededCount)));
            } else {
                allSatisfied = false;
                player.sendSystemMessage(Component.literal(String.format("  §c✘ %s: §e%d / %d §c(缺少 %d)",
                        displayName.getString(), available, neededCount, neededCount - available)));
            }
        }

        if (!allSatisfied) {
            player.sendSystemMessage(Component.literal("§c⚠ 材料不足！请在紧邻箱子中补齐材料后再点击建造。"));
            player.sendSystemMessage(Component.literal("§6========================================"));
            return;
        }

        player.sendSystemMessage(Component.literal("§a✔ 所有材料具备！开始消耗材料并实施自动化建造..."));
        player.sendSystemMessage(Component.literal("§6========================================"));

        consumeMaterials(containers, blueprint.requiredMaterials);
        executeBuild(level, pos, player, blueprint, targetPumpkin);
        player.sendSystemMessage(Component.literal("§a✔ 结构建造完成！"));
    }

    private BlockPos scanForPumpkin(ServerLevel level, BlockPos stationPos) {
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos checkPos = stationPos.offset(dx, dy, dz);
                    String pid = BuiltInRegistries.BLOCK.getKey(level.getBlockState(checkPos).getBlock()).getPath();
                    if (pid.equals("pumpkin") || pid.equals("carved_pumpkin")) {
                        return checkPos;
                    }
                }
            }
        }
        return null;
    }

    private List<Container> getAdjacentContainers(ServerLevel level, BlockPos stationPos) {
        List<Container> list = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();

        for (Direction dir : Direction.values()) {
            BlockPos targetPos = stationPos.relative(dir);
            if (visited.contains(targetPos)) continue;

            BlockState state = level.getBlockState(targetPos);
            if (state.getBlock() instanceof ChestBlock chestBlock) {
                Container chestInv = ChestBlock.getContainer(chestBlock, state, level, targetPos, true);
                if (chestInv != null) {
                    list.add(chestInv);
                    visited.add(targetPos);
                    for (Direction d2 : Direction.Plane.HORIZONTAL) {
                        BlockPos pairPos = targetPos.relative(d2);
                        if (level.getBlockState(pairPos).is(chestBlock)) {
                            visited.add(pairPos);
                        }
                    }
                }
            } else {
                BlockEntity be = level.getBlockEntity(targetPos);
                if (be instanceof Container container) {
                    list.add(container);
                    visited.add(targetPos);
                }
            }
        }
        return list;
    }

    private void consumeMaterials(List<Container> containers, Map<String, Integer> requiredMaterials) {
        for (Map.Entry<String, Integer> req : requiredMaterials.entrySet()) {
            String matchingItemId = req.getKey();
            boolean isWater = matchingItemId.contains("water");
            boolean isLava = matchingItemId.contains("lava");
            if (isWater) matchingItemId = "minecraft:water_bucket";
            if (isLava) matchingItemId = "minecraft:lava_bucket";
            if (!matchingItemId.contains(":")) matchingItemId = "minecraft:" + matchingItemId;

            int toRemove = req.getValue();
            int returnBuckets = 0;

            for (Container container : containers) {
                if (toRemove <= 0) break;
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    if (toRemove <= 0) break;
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        String stackId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        if (stackId.equals(matchingItemId)) {
                            int take = Math.min(toRemove, stack.getCount());
                            stack.shrink(take);
                            toRemove -= take;
                            if (isWater || isLava) returnBuckets += take;
                            if (stack.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
                        }
                    }
                }
            }

            while (returnBuckets > 0) {
                ItemStack bucketStack = new ItemStack(Items.BUCKET, Math.min(returnBuckets, 16));
                returnBuckets -= bucketStack.getCount();
                for (Container container : containers) {
                    for (int slot = 0; slot < container.getContainerSize(); slot++) {
                        ItemStack cur = container.getItem(slot);
                        if (cur.isEmpty()) {
                            container.setItem(slot, bucketStack);
                            bucketStack = ItemStack.EMPTY;
                            break;
                        } else if (cur.is(Items.BUCKET) && cur.getCount() < 16) {
                            int space = 16 - cur.getCount();
                            int add = Math.min(space, bucketStack.getCount());
                            cur.grow(add);
                            bucketStack.shrink(add);
                            if (bucketStack.isEmpty()) break;
                        }
                    }
                    if (bucketStack.isEmpty()) break;
                }
            }
        }
    }

    private void executeBuild(ServerLevel level, BlockPos stationPos, Player player, BlueprintWorkbenchBlock.BlueprintFile blueprint, BlockPos targetPumpkin) {
        BlockPos start = stationPos.above();

        if (blueprint.hasAnchor && targetPumpkin != null) {
            start = targetPumpkin.offset(-blueprint.anchorX, -blueprint.anchorY, -blueprint.anchorZ);
            player.sendSystemMessage(Component.literal("§6🎃 找到定位南瓜！正在以南瓜为基准完美对齐建造..."));
        }

        // 1. 平滑清障
        for (int y = 0; y < blueprint.sizeY; y++) {
            for (int x = 0; x < blueprint.sizeX; x++) {
                for (int z = 0; z < blueprint.sizeZ; z++) {
                    BlockPos p = start.offset(x, y, z);
                    if (!level.getBlockState(p).is(Blocks.BEDROCK)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }

        // 2. 分类
        List<BlueprintWorkbenchBlock.BlockRecord> phase1SolidBlocks = new ArrayList<>();
        List<BlueprintWorkbenchBlock.BlockRecord> phase2AttachedAndFluids = new ArrayList<>();

        for (BlueprintWorkbenchBlock.BlockRecord rec : blueprint.blocks) {
            if (rec.isFluid || isAttachedBlock(rec.blockId)) {
                phase2AttachedAndFluids.add(rec);
            } else {
                phase1SolidBlocks.add(rec);
            }
        }

        // 阶段二排序
        phase2AttachedAndFluids.sort((a, b) -> {
            if (a.relY != b.relY) {
                return Integer.compare(a.relY, b.relY);
            }
            boolean aIsFoot = a.properties != null && "foot".equals(a.properties.get("part"));
            boolean bIsFoot = b.properties != null && "foot".equals(b.properties.get("part"));
            if (aIsFoot && !bIsFoot) return -1;
            if (!aIsFoot && bIsFoot) return 1;

            boolean aIsDoorLower = a.properties != null && "lower".equals(a.properties.get("half"));
            boolean bIsDoorLower = b.properties != null && "lower".equals(b.properties.get("half"));
            if (aIsDoorLower && !bIsDoorLower) return -1;
            if (!aIsDoorLower && bIsDoorLower) return 1;

            return 0;
        });

        int silentPlaceFlag = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

        // 第一阶段放置：实体方块与箱子
        for (BlueprintWorkbenchBlock.BlockRecord rec : phase1SolidBlocks) {
            placeSingleBlock(level, start, rec, silentPlaceFlag);
        }

        // 第二阶段放置：附着物
        for (BlueprintWorkbenchBlock.BlockRecord rec : phase2AttachedAndFluids) {
            placeSingleBlock(level, start, rec, silentPlaceFlag);
        }
    }

    private void placeSingleBlock(ServerLevel level, BlockPos start, BlueprintWorkbenchBlock.BlockRecord rec, int flag) {
        BlockPos targetPos = start.offset(rec.relX, rec.relY, rec.relZ);

        if (rec.isFluid) {
            Identifier fluidIdent = Identifier.tryParse(rec.blockId.contains(":") ? rec.blockId : "minecraft:" + rec.blockId);
            Fluid fluid = (fluidIdent != null) ? BuiltInRegistries.FLUID.getValue(fluidIdent) : Fluids.EMPTY;
            if (fluid != Fluids.EMPTY) {
                level.setBlock(targetPos, fluid.defaultFluidState().createLegacyBlock(), Block.UPDATE_ALL);
            }
        } else {
            Identifier blockIdent = Identifier.tryParse(rec.blockId.contains(":") ? rec.blockId : "minecraft:" + rec.blockId);
            Block block = (blockIdent != null) ? BuiltInRegistries.BLOCK.getValue(blockIdent) : Blocks.AIR;
            if (block != Blocks.AIR) {
                BlockState placeState = block.defaultBlockState();
                if (rec.properties != null && !rec.properties.isEmpty()) {
                    for (Map.Entry<String, String> entry : rec.properties.entrySet()) {
                        Property<?> prop = block.getStateDefinition().getProperty(entry.getKey());
                        if (prop != null) {
                            placeState = setPropertyValue(placeState, prop, entry.getValue());
                        }
                    }
                }
                level.setBlock(targetPos, placeState, flag);
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 2, 0.1, 0.1, 0.1, 0);
            }
        }
    }

    private boolean isAttachedBlock(String blockId) {
        if (blockId == null) return false;
        String id = blockId.toLowerCase();
        return id.contains("lever")
                || id.contains("torch")
                || id.contains("button")
                || id.contains("trapdoor")
                || id.contains("repeater")
                || id.contains("comparator")
                || id.contains("redstone_wire")
                || id.contains("rail")
                || id.contains("door")
                || id.contains("bed")
                || id.contains("carpet")
                || id.contains("ladder")
                || id.contains("tripwire")
                || id.contains("sign")
                || id.contains("pressure_plate");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String valueStr) {
        Optional<T> val = property.getValue(valueStr);
        return val.map(t -> state.setValue(property, t)).orElse(state);
    }
}
