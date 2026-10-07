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

    public BlueprintStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            BlueprintWorkbenchBlock.BlueprintFile blueprint = LOADED_BLUEPRINTS.get(pos);

            if (blueprint == null) {
                player.sendSystemMessage(Component.literal("\u00a7e[\u84dd\u56fe\u5de5\u4f5c\u7ad9] \u6b63\u5728\u6253\u5f00\u7cfb\u7edf\u9009\u76d8\u7a97\u53e3\uff0c\u8bf7\u9009\u62e9\u84dd\u56fe JSON \u6587\u4ef6..."));
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
                                player.sendSystemMessage(Component.literal("\u00a7a\u2714 \u6210\u529f\u8f7d\u5165\u84dd\u56fe: \u00a7f" + file.getName()));
                                player.sendSystemMessage(Component.literal("\u00a7e\ud83d\udce6 \u8bf7\u5728\u5de5\u4f5c\u7ad9\u7d27\u90bb\u653e\u7f6e\u7bb1\u5b50\u5e76\u653e\u5165\u6750\u6599\uff0c\u518d\u6b21\u53f3\u952e\u5f00\u59cb\u6838\u5bf9\u5efa\u9020\uff01"));
                            });
                            return;
                        }
                    }
                }
                level.getServer().execute(() -> player.sendSystemMessage(Component.literal("\u00a7c\u2718 \u53d6\u6d88\u4e86\u84dd\u56fe\u9009\u62e9\u3002")));
            } catch (Exception e) {
                level.getServer().execute(() -> player.sendSystemMessage(Component.literal("\u00a7c\u2718 \u6253\u5f00\u6587\u4ef6\u7a97\u53e3\u5931\u8d25: " + e.getMessage())));
            }
        }).start();
    }

    private void verifyAndBuild(ServerLevel level, BlockPos pos, Player player, BlueprintWorkbenchBlock.BlueprintFile blueprint) {
        List<Container> containers = getAdjacentContainers(level, pos);
        if (containers.isEmpty()) {
            player.sendSystemMessage(Component.literal("\u00a7c\u2718 \u672a\u5728\u84dd\u56fe\u5de5\u4f5c\u7ad9\u56db\u5468\u627e\u5230\u4efb\u4f55\u5bb9\u5668\uff08\u7bb1\u5b50\uff09\uff01"));
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
        player.sendSystemMessage(Component.literal("\u00a76========================================"));
        player.sendSystemMessage(Component.literal("\u00a7e\ud83d\udce5 \u3010\u84dd\u56fe\u5de5\u4f5c\u7ad9\u3011\u5efa\u9020\u6240\u9700\u6750\u6599\u4e0e\u7bb1\u5b50\u5e93\u5b58\u6838\u5bf9:"));

        for (Map.Entry<String, Integer> req : blueprint.requiredMaterials.entrySet()) {
            String requiredId = req.getKey();
            int neededCount = req.getValue();

            String matchingItemId = requiredId;
            if (requiredId.contains("water")) matchingItemId = "minecraft:water_bucket";
            else if (requiredId.contains("lava")) matchingItemId = "minecraft:lava_bucket";

            int available = inventoryCounts.getOrDefault(matchingItemId, 0);

            // 1.21.4 修复：从注册表取物品并正确获取其显示名称
            Identifier itemIdent = Identifier.tryParse(matchingItemId);
            Item item = (itemIdent != null) ? BuiltInRegistries.ITEM.getValue(itemIdent) : Items.AIR;
            Component displayName = (item != Items.AIR) ? new ItemStack(item).getHoverName() : Component.literal(matchingItemId);

            if (available >= neededCount) {
                player.sendSystemMessage(Component.literal(String.format("  \u00a7a\u2714 %s: \u00a7f%d / %d", displayName.getString(), available, neededCount)));
            } else {
                allSatisfied = false;
                player.sendSystemMessage(Component.literal(String.format("  \u00a7c\u2718 %s: \u00a7e%d / %d \u00a7c(\u7f3a\u5c11 %d)",
                        displayName.getString(), available, neededCount, neededCount - available)));
            }
        }

        if (!allSatisfied) {
            player.sendSystemMessage(Component.literal("\u00a7c\u26a0 \u6750\u6599\u4e0d\u8db3\uff01\u8bf7\u5728\u7d27\u90bb\u7bb1\u5b50\u4e2d\u8865\u9f50\u6750\u6599\u540e\u518d\u6b21\u53f3\u952e\u3002"));
            player.sendSystemMessage(Component.literal("\u00a76========================================"));
            return;
        }

        player.sendSystemMessage(Component.literal("\u00a7a\u2714 \u6240\u6709\u6750\u6599\u5177\u5907\uff01\u5f00\u59cb\u6d88\u8017\u6750\u6599\u5e76\u5b9e\u65bd\u81ea\u52a8\u5316\u5efa\u9020..."));
        player.sendSystemMessage(Component.literal("\u00a76========================================"));

        consumeMaterials(containers, blueprint.requiredMaterials);
        executeBuild(level, pos, blueprint);
        player.sendSystemMessage(Component.literal("\u00a7a\u2714 \u7ed3\u6784\u5efa\u9020\u5b8c\u6210\uff01"));
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

    private void executeBuild(ServerLevel level, BlockPos stationPos, BlueprintWorkbenchBlock.BlueprintFile blueprint) {
        BlockPos start = stationPos.above();

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

        // 2. 放置实体方块与流体
        for (BlueprintWorkbenchBlock.BlockRecord rec : blueprint.blocks) {
            BlockPos targetPos = start.offset(rec.relX, rec.relY, rec.relZ);

            if (rec.isFluid) {
                Identifier fluidIdent = Identifier.tryParse(rec.blockId);
                Fluid fluid = (fluidIdent != null) ? BuiltInRegistries.FLUID.getValue(fluidIdent) : Fluids.EMPTY;
                if (fluid != Fluids.EMPTY) {
                    level.setBlock(targetPos, fluid.defaultFluidState().createLegacyBlock(), Block.UPDATE_ALL);
                }
            } else {
                Identifier blockIdent = Identifier.tryParse(rec.blockId);
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
                    level.setBlock(targetPos, placeState, Block.UPDATE_ALL);
                    level.sendParticles(ParticleTypes.HAPPY_VILLAGER, targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property, String valueStr) {
        Optional<T> val = property.getValue(valueStr);
        return val.map(t -> state.setValue(property, t)).orElse(state);
    }
}