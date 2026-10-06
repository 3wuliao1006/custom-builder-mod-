package com.example.builder;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class BlueprintWorkbenchBlock extends Block {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public BlueprintWorkbenchBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    public static class BlockRecord {
        public int relX, relY, relZ;
        public String blockId;
        public boolean isFluid;

        public BlockRecord(int x, int y, int z, String id, boolean fluid) {
            this.relX = x;
            this.relY = y;
            this.relZ = z;
            this.blockId = id;
            this.isFluid = fluid;
        }
    }

    public static class BlueprintFile {
        public String name;
        public String author;
        public String createTime;
        public int sizeX, sizeY, sizeZ;
        public Map<String, Integer> requiredMaterials;
        public List<BlockRecord> blocks;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            player.sendSystemMessage(Component.literal("\u00a7e[\u84dd\u56fe\u5de5\u4f5c\u53f0] \u6b63\u5728\u68c0\u6d4b 8 \u4e2a\u9876\u70b9\u5de5\u4f5c\u53f0..."));

            BlockPos otherCorner = findOppositeCorner(serverLevel, pos);
            if (otherCorner == null) {
                player.sendSystemMessage(Component.literal("\u00a7c\u2718 \u672a\u80fd\u68c0\u6d4b\u5230 8 \u4e2a\u84dd\u56fe\u5de5\u4f5c\u53f0\u95ed\u5408\u7684\u957f\u65b9\u4f53\uff01"));
                player.sendSystemMessage(Component.literal("\u00a77- \u8bf7\u786e\u4fdd\u957f\u65b9\u4f53\u7684 8 \u4e2a\u9876\u70b9\u5747\u653e\u7f6e\u4e86\u84dd\u56fe\u5de5\u4f5c\u53f0\u3002"));
                return InteractionResult.SUCCESS;
            }

            int minX = Math.min(pos.getX(), otherCorner.getX());
            int minY = Math.min(pos.getY(), otherCorner.getY());
            int minZ = Math.min(pos.getZ(), otherCorner.getZ());
            int maxX = Math.max(pos.getX(), otherCorner.getX());
            int maxY = Math.max(pos.getY(), otherCorner.getY());
            int maxZ = Math.max(pos.getZ(), otherCorner.getZ());

            int sizeX = maxX - minX + 1;
            int sizeY = maxY - minY + 1;
            int sizeZ = maxZ - minZ + 1;

            spawnLaserBox(serverLevel, minX, minY, minZ, maxX, maxY, maxZ);
            player.sendSystemMessage(Component.literal(String.format("\u00a7a\u2714 8 \u9876\u70b9\u95ed\u5408\u6210\u529f\uff01\u5c3a\u5bf8: \u00a7e%d \u00d7 %d \u00d7 %d", sizeX, sizeY, sizeZ)));

            exportBlueprintData(serverLevel, player, minX, minY, minZ, maxX, maxY, maxZ, sizeX, sizeY, sizeZ);
        }
        return InteractionResult.SUCCESS;
    }

    private BlockPos findOppositeCorner(ServerLevel level, BlockPos origin) {
        List<Integer> xs = scanAxis(level, origin, 'X');
        List<Integer> ys = scanAxis(level, origin, 'Y');
        List<Integer> zs = scanAxis(level, origin, 'Z');

        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    BlockPos p2 = new BlockPos(x, y, z);
                    int minX = Math.min(origin.getX(), p2.getX());
                    int minY = Math.min(origin.getY(), p2.getY());
                    int minZ = Math.min(origin.getZ(), p2.getZ());
                    int maxX = Math.max(origin.getX(), p2.getX());
                    int maxY = Math.max(origin.getY(), p2.getY());
                    int maxZ = Math.max(origin.getZ(), p2.getZ());

                    int sx = maxX - minX + 1;
                    int sy = maxY - minY + 1;
                    int sz = maxZ - minZ + 1;

                    if (sx < 2 || sy < 2 || sz < 2 || sx > 256 || sy > 256 || sz > 256) continue;

                    boolean allCornersValid = true;
                    int[] cx = {minX, maxX};
                    int[] cy = {minY, maxY};
                    int[] cz = {minZ, maxZ};

                    for (int ix : cx) {
                        for (int iy : cy) {
                            for (int iz : cz) {
                                if (!level.getBlockState(new BlockPos(ix, iy, iz)).is(this)) {
                                    allCornersValid = false;
                                    break;
                                }
                            }
                            if (!allCornersValid) break;
                        }
                        if (!allCornersValid) break;
                    }

                    if (allCornersValid) {
                        return p2;
                    }
                }
            }
        }
        return null;
    }

    private List<Integer> scanAxis(ServerLevel level, BlockPos start, char axis) {
        List<Integer> list = new ArrayList<>();
        int[] dirs = {-1, 1};
        for (int dir : dirs) {
            for (int step = 1; step <= 256; step++) {
                BlockPos target = switch (axis) {
                    case 'X' -> start.offset(step * dir, 0, 0);
                    case 'Y' -> start.offset(0, step * dir, 0);
                    case 'Z' -> start.offset(0, 0, step * dir);
                    default -> start;
                };
                if (level.getBlockState(target).is(this)) {
                    int coord = switch (axis) {
                        case 'X' -> target.getX();
                        case 'Y' -> target.getY();
                        case 'Z' -> target.getZ();
                        default -> 0;
                    };
                    list.add(coord);
                    break;
                }
            }
        }
        return list;
    }

    private void spawnLaserBox(ServerLevel level, int x1, int y1, int z1, int x2, int y2, int z2) {
        spawnLine(level, x1, y1, z1, x2, y1, z1);
        spawnLine(level, x1, y2, z1, x2, y2, z1);
        spawnLine(level, x1, y1, z2, x2, y1, z2);
        spawnLine(level, x1, y2, z2, x2, y2, z2);

        spawnLine(level, x1, y1, z1, x1, y2, z1);
        spawnLine(level, x2, y1, z1, x2, y2, z1);
        spawnLine(level, x1, y1, z2, x1, y2, z2);
        spawnLine(level, x2, y1, z2, x2, y2, z2);

        spawnLine(level, x1, y1, z1, x1, y1, z2);
        spawnLine(level, x2, y1, z1, x2, y1, z2);
        spawnLine(level, x1, y2, z1, x1, y2, z2);
        spawnLine(level, x2, y2, z1, x2, y2, z2);
    }

    private void spawnLine(ServerLevel level, double x1, double y1, double z1, double x2, double y2, double z2) {
        double dist = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2) + Math.pow(z2 - z1, 2));
        int steps = Math.max(1, (int) (dist * 2));
        double dx = (x2 - x1) / steps;
        double dy = (y2 - y1) / steps;
        double dz = (z2 - z1) / steps;

        for (int i = 0; i <= steps; i++) {
            level.sendParticles(ParticleTypes.END_ROD, x1 + dx * i + 0.5, y1 + dy * i + 0.5, z1 + dz * i + 0.5, 1, 0, 0, 0, 0);
        }
    }

    private void exportBlueprintData(ServerLevel level, Player player, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int sizeX, int sizeY, int sizeZ) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String name = "blueprint_" + timestamp;

        File folder = FabricLoader.getInstance().getGameDir().resolve("blueprints").toFile();
        if (!folder.exists()) folder.mkdirs();

        BlueprintFile fileData = new BlueprintFile();
        fileData.name = name;
        fileData.author = player.getName().getString();
        fileData.createTime = timestamp;
        fileData.sizeX = sizeX;
        fileData.sizeY = sizeY;
        fileData.sizeZ = sizeZ;
        fileData.requiredMaterials = new LinkedHashMap<>();
        fileData.blocks = new ArrayList<>();

        int imgW = Math.max(64, Math.min(256, sizeX * 4));
        int imgH = Math.max(64, Math.min(256, sizeZ * 4));
        BufferedImage thumb = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = thumb.createGraphics();
        g.setColor(new Color(25, 40, 65));
        g.fillRect(0, 0, imgW, imgH);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean isCorner = (x == minX || x == maxX) && (y == minY || y == maxY) && (z == minZ || z == maxZ);
                    if (isCorner) continue;

                    BlockPos curPos = new BlockPos(x, y, z);
                    BlockState bState = level.getBlockState(curPos);
                    FluidState fState = level.getFluidState(curPos);

                    int relX = x - minX;
                    int relY = y - minY;
                    int relZ = z - minZ;

                    // 1. 严格判断流体：必须是非空且严格是源头 (isSource)，完全忽略流动水/流动岩浆
                    if (!fState.isEmpty()) {
                        if (fState.isSource()) {
                            String fluidId = BuiltInRegistries.FLUID.getKey(fState.getType()).toString();
                            fileData.blocks.add(new BlockRecord(relX, relY, relZ, fluidId, true));
                            fileData.requiredMaterials.put(fluidId, fileData.requiredMaterials.getOrDefault(fluidId, 0) + 1);

                            int px = (int) ((double) relX / sizeX * imgW);
                            int pz = (int) ((double) relZ / sizeZ * imgH);
                            g.setColor(fluidId.contains("water") ? new Color(30, 144, 255) : new Color(255, 69, 0));
                            g.fillRect(px, pz, Math.max(2, imgW / sizeX), Math.max(2, imgH / sizeZ));
                        }
                        // 流动的流体直接跳过，不计入实体方块，也不计入材料
                        continue;
                    }

                    // 2. 实体方块（排除空气）
                    if (!bState.isAir()) {
                        Identifier bId = BuiltInRegistries.BLOCK.getKey(bState.getBlock());
                        String idStr = bId.toString();
                        fileData.blocks.add(new BlockRecord(relX, relY, relZ, idStr, false));
                        fileData.requiredMaterials.put(idStr, fileData.requiredMaterials.getOrDefault(idStr, 0) + 1);

                        int px = (int) ((double) relX / sizeX * imgW);
                        int pz = (int) ((double) relZ / sizeZ * imgH);
                        g.setColor(new Color(220, 235, 255));
                        g.fillRect(px, pz, Math.max(2, imgW / sizeX), Math.max(2, imgH / sizeZ));
                    }
                }
            }
        }

        g.dispose();

        File json = new File(folder, name + ".json");
        File png = new File(folder, name + ".png");

        try (FileWriter writer = new FileWriter(json, StandardCharsets.UTF_8)) {
            GSON.toJson(fileData, writer);
            ImageIO.write(thumb, "png", png);

            player.sendSystemMessage(Component.literal("\u00a7a\u2714 \u84dd\u56fe\u5df2\u6210\u529f\u63d0\u53d6\u5e76\u5bfc\u51fa\uff01"));
            player.sendSystemMessage(Component.literal("\u00a7b\ud83d\udcc4 \u6587\u4ef6: \u00a7f" + json.getName()));
            player.sendSystemMessage(Component.literal(String.format("\u00a7e\ud83d\udcca \u5171\u8bb0\u5f55 \u00a7f%d \u00a7e\u4e2a\u65b9\u5757/\u6e90\u5934\uff0c\u6750\u6599\u79cd\u7c7b: \u00a7f%d",
                    fileData.blocks.size(), fileData.requiredMaterials.size())));
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("\u00a7c\u2718 \u5bfc\u51fa\u6587\u4ef6\u5931\u8d25: " + e.getMessage()));
        }
    }
}