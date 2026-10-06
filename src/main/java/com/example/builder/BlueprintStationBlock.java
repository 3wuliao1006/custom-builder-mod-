package com.example.builder;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlueprintStationBlock extends Block {
    private static final Gson GSON = new Gson();
    private static final Map<BlockPos, BlueprintWorkbenchBlock.BlueprintFile> LOADED_BLUEPRINTS = new ConcurrentHashMap<>();

    public BlueprintStationBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        // 阶段 1：未载入蓝图时，唤起 Windows 系统原生文件选择器（自由切换 C/D 盘）
        if (!LOADED_BLUEPRINTS.containsKey(pos)) {
            if (level.isClientSide()) {
                player.sendSystemMessage(Component.literal("\u00a7e[\u84dd\u56fe\u5de5\u4f5c\u7ad9] \u6b63\u5728\u6253\u5f00 Windows \u6587\u4ef6\u9009\u62e9\u7a97\u53e3\uff0c\u8bf7\u7a0d\u5019..."));

                // 在独立后台线程中唤起 Windows 系统的 OpenFileDialog，不受 Java AWT 限制且不卡游戏
                new Thread(() -> {
                    try {
                        Path defaultDir = FabricLoader.getInstance().getGameDir().resolve("blueprints");
                        File dir = defaultDir.toFile();
                        if (!dir.exists()) {
                            dir.mkdirs();
                        }

                        // 借助系统底层直接唤出置顶的 OpenFileDialog 对话框
                        String initialPath = dir.getAbsolutePath().replace("'", "''");
                        String psCommand = "[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms') | Out-Null; "
                                + "$f = New-Object System.Windows.Forms.OpenFileDialog; "
                                + "$f.InitialDirectory = '" + initialPath + "'; "
                                + "$f.Filter = 'Blueprint JSON (*.json)|*.json|All files (*.*)|*.*'; "
                                + "$f.Title = '请选择要建造的装置蓝图文件'; "
                                + "$topForm = New-Object System.Windows.Forms.Form; "
                                + "$topForm.TopMost = $true; "
                                + "if ($f.ShowDialog($topForm) -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $f.FileName }";

                        Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand).start();

                        String chosenPath = null;
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.trim().isEmpty()) {
                                    chosenPath = line.trim();
                                }
                            }
                        }
                        process.waitFor();

                        if (chosenPath != null && !chosenPath.isEmpty()) {
                            File selectedFile = new File(chosenPath);
                            if (selectedFile.exists()) {
                                try (FileReader fr = new FileReader(selectedFile, StandardCharsets.UTF_8)) {
                                    BlueprintWorkbenchBlock.BlueprintFile bp = GSON.fromJson(fr, BlueprintWorkbenchBlock.BlueprintFile.class);
                                    if (bp != null && bp.requiredMaterials != null) {
                                        LOADED_BLUEPRINTS.put(pos, bp);
                                        player.sendSystemMessage(Component.literal("\u00a7a\u2714 \u84dd\u56fe\u8f7d\u5165\u6210\u529f: \u00a7f" + selectedFile.getName()));
                                        player.sendSystemMessage(Component.literal(String.format("\u00a7d\u25b6 \u88c5\u7f6e\u5c3a\u5bf8: \u00a7f%d \u00d7 %d \u00d7 %d \u00a77(\u653e\u7f6e\u5728 XYZ \u6b63\u8c61\u9650)", bp.sizeX, bp.sizeY, bp.sizeZ)));
                                        player.sendSystemMessage(Component.literal("\u00a7e[\u63d0\u793a] \u5728\u5de5\u4f5c\u7ad9\u65c1\u8fb9\u653e\u7f6e\u7bb1\u5b50\u5e76\u653e\u5165\u6750\u6599\uff0c\u518d\u6b21\u53f3\u952e\u5de5\u4f5c\u7ad9\u6838\u5bf9\u6750\u6599\u5e76\u5f00\u5de5\uff01"));
                                    }
                                } catch (Exception e) {
                                    player.sendSystemMessage(Component.literal("\u00a7c\u2718 \u6587\u4ef6\u89e3\u6790\u5931\u8d25: 不是有效的蓝图 JSON 文件！"));
                                }
                            }
                        } else {
                            player.sendSystemMessage(Component.literal("\u00a77[\u63d0\u793a] \u5df2\u53d6\u6d88\u9009\u62e9\u6587\u4ef6"));
                        }
                    } catch (Exception ex) {
                        player.sendSystemMessage(Component.literal("\u00a7c\u2718 \u5524\u8d77\u7cfb\u7edf\u7a97\u53e3\u5f02\u5e38: " + ex.getMessage()));
                    }
                }, "BlueprintStation-NativeDialog").start();
            }
            return InteractionResult.SUCCESS;
        }

        // 阶段 2：已装载蓝图，右键核查四周箱子材料与自动建造
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            BlueprintWorkbenchBlock.BlueprintFile bp = LOADED_BLUEPRINTS.get(pos);
            if (bp == null) return InteractionResult.SUCCESS;

            player.sendSystemMessage(Component.literal("\u00a76========================================"));
            player.sendSystemMessage(Component.literal("\u00a7e\ud83d\udccb [\u84dd\u56fe\u5de5\u4f5c\u7ad9] \u5efa\u9020\u6240\u9700\u6750\u6599\u4e0e\u8eab\u8fb9\u7bb1\u5b50\u5e93\u5b58\u6838\u5bf9:"));

            boolean allReady = true;

            for (Map.Entry<String, Integer> entry : bp.requiredMaterials.entrySet()) {
                String matId = entry.getKey();
                int needed = entry.getValue();

                Item targetItem;
                if (matId.contains("water")) {
                    targetItem = Items.WATER_BUCKET;
                } else if (matId.contains("lava")) {
                    targetItem = Items.LAVA_BUCKET;
                } else {
                    Identifier id = Identifier.tryParse(matId);
                    Block b = id != null ? BuiltInRegistries.BLOCK.get(id).map(Holder::value).orElse(Blocks.AIR) : Blocks.AIR;
                    targetItem = b.asItem();
                }

                int currentCount = countAdjacentContainerItems(serverLevel, pos, targetItem);
                String itemName = targetItem.getName(targetItem.getDefaultInstance()).getString();

                if (currentCount >= needed) {
                    player.sendSystemMessage(Component.literal(String.format("  \u00a7a\u2714 %s: %d / %d", itemName, currentCount, needed)));
                } else {
                    player.sendSystemMessage(Component.literal(String.format("  \u00a7c\u2718 %s: %d / %d \u00a7e(\u7f3a\u5c11 %d)", itemName, currentCount, needed, needed - currentCount)));
                    allReady = false;
                }
            }

            if (allReady) {
                player.sendSystemMessage(Component.literal("\u00a7a\u26a1 \u6750\u6599\u5df2\u5168\u90e8\u9f50\u5168\uff01\u6b63\u5728\u6e05\u7406\u969c\u788d\u5e76\u81ea\u52a8\u5efa\u9020..."));
                executeBuild(serverLevel, pos, bp);
                LOADED_BLUEPRINTS.remove(pos); // 建造完毕后清空，允许下次放入新图纸
            } else {
                player.sendSystemMessage(Component.literal("\u00a7c\u26a0 \u6750\u6599\u4e0d\u8db3\uff01\u8bf7\u5728\u7d27\u90bb\u7bb1\u5b50\u4e2d\u8865\u9f50\u6750\u6599\u540e\u518d\u6b21\u53f3\u952e\u3002"));
            }
            player.sendSystemMessage(Component.literal("\u00a76========================================"));
        }

        return InteractionResult.SUCCESS;
    }

    private int countAdjacentContainerItems(ServerLevel level, BlockPos stationPos, Item item) {
        int total = 0;
        for (Direction dir : Direction.values()) {
            if (level.getBlockEntity(stationPos.relative(dir)) instanceof Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.is(item)) {
                        total += stack.getCount();
                    }
                }
            }
        }
        return total;
    }

    private void executeBuild(ServerLevel level, BlockPos stationPos, BlueprintWorkbenchBlock.BlueprintFile bp) {
        BlockPos origin = stationPos.offset(1, 0, 1);

        // 1. 逐层清理非基岩障碍物
        for (int y = 0; y < bp.sizeY; y++) {
            for (int x = 0; x < bp.sizeX; x++) {
                for (int z = 0; z < bp.sizeZ; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    BlockState bs = level.getBlockState(p);
                    if (!bs.isAir() && !bs.is(Blocks.BEDROCK)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        // 2. 扣除材料并建造实体方块
        for (BlueprintWorkbenchBlock.BlockRecord rec : bp.blocks) {
            if (!rec.isFluid) {
                Identifier id = Identifier.tryParse(rec.blockId);
                Block targetBlock = id != null ? BuiltInRegistries.BLOCK.get(id).map(Holder::value).orElse(Blocks.AIR) : Blocks.AIR;
                if (consumeAdjacentItem(level, stationPos, targetBlock.asItem(), 1)) {
                    level.setBlock(origin.offset(rec.relX, rec.relY, rec.relZ), targetBlock.defaultBlockState(), 3);
                }
            }
        }

        // 3. 注入流体并返还空桶
        for (BlueprintWorkbenchBlock.BlockRecord rec : bp.blocks) {
            if (rec.isFluid) {
                BlockPos fPos = origin.offset(rec.relX, rec.relY, rec.relZ);
                if (rec.blockId.contains("water") && consumeBucket(level, stationPos, Items.WATER_BUCKET)) {
                    level.setBlock(fPos, Blocks.WATER.defaultBlockState(), 3);
                } else if (rec.blockId.contains("lava") && consumeBucket(level, stationPos, Items.LAVA_BUCKET)) {
                    level.setBlock(fPos, Blocks.LAVA.defaultBlockState(), 3);
                }
            }
        }

        level.playSound(null, stationPos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0f, 1.0f);
    }

    private boolean consumeAdjacentItem(ServerLevel level, BlockPos stationPos, Item item, int count) {
        int left = count;
        for (Direction dir : Direction.values()) {
            if (level.getBlockEntity(stationPos.relative(dir)) instanceof Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.is(item)) {
                        int take = Math.min(left, stack.getCount());
                        stack.shrink(take);
                        left -= take;
                        container.setChanged();
                        if (left <= 0) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean consumeBucket(ServerLevel level, BlockPos stationPos, Item bucketItem) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockEntity(stationPos.relative(dir)) instanceof Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (stack.is(bucketItem)) {
                        stack.shrink(1);
                        container.setItem(i, stack.isEmpty() ? new ItemStack(Items.BUCKET) : stack);
                        container.setChanged();
                        return true;
                    }
                }
            }
        }
        return false;
    }
}