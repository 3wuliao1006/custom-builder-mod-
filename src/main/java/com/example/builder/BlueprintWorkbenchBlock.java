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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class BlueprintWorkbenchBlock extends Block {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<UUID, BlockPos[]> SELECTIONS = new HashMap<>();

    public BlueprintWorkbenchBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    public static class BlockRecord {
        public int relX, relY, relZ;
        public String blockId;
        public boolean isFluid;
        public Map<String, String> properties;

        public BlockRecord(int x, int y, int z, String id, boolean fluid, Map<String, String> properties) {
            this.relX = x;
            this.relY = y;
            this.relZ = z;
            this.blockId = id;
            this.isFluid = fluid;
            this.properties = properties;
        }
    }

    public static class BlueprintFile {
        public String name;
        public String author;
        public String createTime;
        public int sizeX, sizeY, sizeZ;
        public int anchorX = 0;
        public int anchorY = 0;
        public int anchorZ = 0;
        public boolean hasAnchor = false;
        public Map<String, Integer> requiredMaterials;
        public List<BlockRecord> blocks;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && level instanceof ServerLevel serverLevel) {
            UUID uuid = player.getUUID();
            BlockPos[] points = SELECTIONS.computeIfAbsent(uuid, k -> new BlockPos[2]);

            if (points[0] == null) {
                points[0] = pos;
                player.sendSystemMessage(Component.literal("§a[蓝图工作台] 已标记起点 (点1): " + pos.toShortString() + "，请右键对角工作台作为终点。"));
                return InteractionResult.SUCCESS;
            } else if (points[1] == null) {
                if (points[0].equals(pos)) {
                    player.sendSystemMessage(Component.literal("§e[蓝图工作台] 当前方块已经是起点，请右键另一个工作台作为终点。"));
                    return InteractionResult.SUCCESS;
                }
                points[1] = pos;
                player.sendSystemMessage(Component.literal("§a[蓝图工作台] 已标记终点 (点2): " + pos.toShortString() + "。两点已闭合！再次右键即可导出蓝图。"));

                spawnLaserBox(serverLevel, points[0], points[1]);
                return InteractionResult.SUCCESS;
            } else {
                exportBlueprintData(serverLevel, player, points[0], points[1]);
                SELECTIONS.remove(uuid);
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.SUCCESS;
    }

    private void spawnLaserBox(ServerLevel level, BlockPos p1, BlockPos p2) {
        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX()) + 1;
        int maxY = Math.max(p1.getY(), p2.getY()) + 1;
        int maxZ = Math.max(p1.getZ(), p2.getZ()) + 1;

        spawnLine(level, minX, minY, minZ, maxX, minY, minZ);
        spawnLine(level, minX, minY, maxZ, maxX, minY, maxZ);
        spawnLine(level, minX, minY, minZ, minX, minY, maxZ);
        spawnLine(level, maxX, minY, minZ, maxX, minY, maxZ);

        spawnLine(level, minX, maxY, minZ, maxX, maxY, minZ);
        spawnLine(level, minX, maxY, maxZ, maxX, maxY, maxZ);
        spawnLine(level, minX, maxY, minZ, minX, maxY, maxZ);
        spawnLine(level, maxX, maxY, minZ, maxX, maxY, maxZ);

        spawnLine(level, minX, minY, minZ, minX, maxY, minZ);
        spawnLine(level, maxX, minY, minZ, maxX, maxY, minZ);
        spawnLine(level, minX, minY, maxZ, minX, maxY, maxZ);
        spawnLine(level, maxX, minY, maxZ, maxX, maxY, maxZ);
    }

    private void spawnLine(ServerLevel level, double x1, double y1, double z1, double x2, double y2, double z2) {
        double dist = Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2) + Math.pow(z2 - z1, 2));
        int steps = Math.max(1, (int) (dist * 2));
        double dx = (x2 - x1) / steps;
        double dy = (y2 - y1) / steps;
        double dz = (z2 - z1) / steps;

        for (int i = 0; i <= steps; i++) {
            level.sendParticles(ParticleTypes.END_ROD, x1 + dx * i, y1 + dy * i, z1 + dz * i, 1, 0, 0, 0, 0);
        }
    }

    private void exportBlueprintData(ServerLevel level, Player player, BlockPos p1, BlockPos p2) {
        int minX = Math.min(p1.getX(), p2.getX());
        int minY = Math.min(p1.getY(), p2.getY());
        int minZ = Math.min(p1.getZ(), p2.getZ());
        int maxX = Math.max(p1.getX(), p2.getX());
        int maxY = Math.max(p1.getY(), p2.getY());
        int maxZ = Math.max(p1.getZ(), p2.getZ());

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

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

        // 检索南瓜定位锚点
        BlockPos pumpkinAnchor = null;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos cp = new BlockPos(x, y, z);
                    String pid = BuiltInRegistries.BLOCK.getKey(level.getBlockState(cp).getBlock()).getPath();
                    if (pid.equals("pumpkin") || pid.equals("carved_pumpkin")) {
                        pumpkinAnchor = cp;
                        break;
                    }
                }
                if (pumpkinAnchor != null) break;
            }
            if (pumpkinAnchor != null) break;
        }

        if (pumpkinAnchor != null) {
            fileData.hasAnchor = true;
            fileData.anchorX = pumpkinAnchor.getX() - minX;
            fileData.anchorY = pumpkinAnchor.getY() - minY;
            fileData.anchorZ = pumpkinAnchor.getZ() - minZ;
            player.sendSystemMessage(Component.literal("§6🎃 检测到定位南瓜锚点！相对位置: [" + fileData.anchorX + ", " + fileData.anchorY + ", " + fileData.anchorZ + "]（南瓜不计入材料清单）"));
        }

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos curPos = new BlockPos(x, y, z);
                    BlockState bState = level.getBlockState(curPos);
                    FluidState fState = level.getFluidState(curPos);

                    if (bState.getBlock() == this) continue;

                    int relX = x - minX;
                    int relY = y - minY;
                    int relZ = z - minZ;

                    // 1. 流体识别
                    if (!fState.isEmpty()) {
                        if (fState.isSource()) {
                            String fluidId = BuiltInRegistries.FLUID.getKey(fState.getType()).toString();
                            fileData.blocks.add(new BlockRecord(relX, relY, relZ, fluidId, true, Collections.emptyMap()));

                            String bucketId = fState.is(Fluids.WATER) ? "minecraft:water_bucket" : (fState.is(Fluids.LAVA) ? "minecraft:lava_bucket" : fluidId);
                            fileData.requiredMaterials.put(bucketId, fileData.requiredMaterials.getOrDefault(bucketId, 0) + 1);
                        }
                        continue;
                    }

                    // 2. 实体方块识别
                    if (!bState.isAir()) {
                        Identifier bId = BuiltInRegistries.BLOCK.getKey(bState.getBlock());
                        String path = bId.getPath();

                        if (path.equals("pumpkin") || path.equals("carved_pumpkin")) {
                            continue;
                        }

                        if (isWildFoliage(path)) {
                            continue;
                        }

                        // 记录原始方块建造信息（建造端保留精确方块形态与属性）
                        String blockIdStr = bId.toString();
                        Map<String, String> props = new HashMap<>();
                        for (Property<?> prop : bState.getProperties()) {
                            props.put(prop.getName(), getPropValueString(bState, prop));
                        }
                        fileData.blocks.add(new BlockRecord(relX, relY, relZ, blockIdStr, false, props));

                        // 过滤床和门的多格重复计数
                        boolean skipCount = false;
                        if (bState.hasProperty(BedBlock.PART) && bState.getValue(BedBlock.PART) == BedPart.HEAD) {
                            skipCount = true;
                        }
                        if (bState.hasProperty(DoorBlock.HALF) && bState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
                            skipCount = true;
                        }

                        if (!skipCount) {
                            // 【核心优化】：对材料进行全套同类归并简化！
                            String normalizedMaterial = normalizeMaterialId(path, bState.getBlock().asItem());
                            fileData.requiredMaterials.put(normalizedMaterial, fileData.requiredMaterials.getOrDefault(normalizedMaterial, 0) + 1);
                        }
                    }
                }
            }
        }

        File json = new File(folder, name + ".json");
        File png = new File(folder, name + ".png");

        try (FileWriter writer = new FileWriter(json, StandardCharsets.UTF_8)) {
            GSON.toJson(fileData, writer);

            // 生成高清、带真实贴图与颜色的材料清单长图
            generateMaterialListImage(fileData, png);

            player.sendSystemMessage(Component.literal("§a✔ 蓝图已成功提取并导出！"));
            player.sendSystemMessage(Component.literal("§b📄 数据: §f" + json.getName()));
            player.sendSystemMessage(Component.literal("§d🖼 清单大图: §f" + png.getName()));
            player.sendSystemMessage(Component.literal(String.format("§e📊 共记录 §f%d §e个方块，材料种类: §f%d",
                    fileData.blocks.size(), fileData.requiredMaterials.size())));
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("§c✘ 导出文件失败: " + e.getMessage()));
        }
    }

    /**
     * 材料同类家族兼容与归并逻辑
     */
    private String normalizeMaterialId(String path, Item item) {
        // 1. 泥土家族：草方块、土径、耕地、粗泥、灰化土全部统一为【泥土】
        if (path.equals("grass_block") || path.equals("dirt_path") || path.equals("farmland")
                || path.equals("coarse_dirt") || path.equals("rooted_dirt") || path.equals("podzol") || path.equals("dirt")) {
            return "minecraft:dirt";
        }

        // 2. 石头台阶家族：石头台阶、平滑石台阶全部统一为【圆石台阶】
        if (path.equals("stone_slab") || path.equals("smooth_stone_slab") || path.equals("cobblestone_slab")) {
            return "minecraft:cobblestone_slab";
        }

        // 3. 整块石头家族：石头、平滑石头统一为【圆石】
        if (path.equals("stone") || path.equals("smooth_stone") || path.equals("cobblestone")) {
            return "minecraft:cobblestone";
        }

        // 4. 木质活板门家族：所有种类的木质活板门统一为【橡木活板门】
        if (path.endsWith("_trapdoor") && !path.contains("iron")) {
            return "minecraft:oak_trapdoor";
        }

        // 5. 木门家族：所有种类的木门统一为【橡木门】
        if (path.endsWith("_door") && !path.contains("iron")) {
            return "minecraft:oak_door";
        }

        // 6. 木栅栏家族：所有种类的木栅栏统一为【橡木栅栏】
        if (path.endsWith("_fence") && !path.contains("nether_brick")) {
            return "minecraft:oak_fence";
        }

        // 7. 木栅栏门家族：所有种类的木栅栏门统一为【橡木栅栏门】
        if (path.endsWith("_fence_gate")) {
            return "minecraft:oak_fence_gate";
        }

        // 8. 玻璃家族：所有染色玻璃统一为普通【玻璃】
        if (path.endsWith("_stained_glass")) {
            return "minecraft:glass";
        }
        if (path.endsWith("_stained_glass_pane")) {
            return "minecraft:glass_pane";
        }

        // 默认获取物品 Registry ID
        if (item != Items.AIR) {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
        return "minecraft:" + path;
    }

    /**
     * 自动绘制高清材料清单大图
     */
    private void generateMaterialListImage(BlueprintFile fileData, File targetFile) {
        try {
            int itemCount = fileData.requiredMaterials.size();
            int columns = 3;
            int rows = (int) Math.ceil((double) itemCount / columns);
            rows = Math.max(1, rows);

            int cardWidth = 280;
            int cardHeight = 64;
            int padding = 20;
            int headerHeight = 100;

            int imgWidth = padding * 2 + columns * cardWidth + (columns - 1) * 15;
            int imgHeight = headerHeight + rows * cardHeight + (rows - 1) * 10 + padding * 2;

            BufferedImage image = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = image.createGraphics();

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 1. 深色精致背景
            g2d.setColor(new Color(24, 26, 32));
            g2d.fillRect(0, 0, imgWidth, imgHeight);

            // 2. 顶部 Header
            g2d.setColor(new Color(32, 35, 45));
            g2d.fillRoundRect(padding, padding, imgWidth - padding * 2, headerHeight - 15, 12, 12);

            g2d.setColor(new Color(255, 204, 0));
            g2d.setFont(new Font("Microsoft YaHei", Font.BOLD, 22));
            g2d.drawString("📦 蓝图建筑材料清单", padding + 20, padding + 36);

            g2d.setColor(new Color(180, 190, 205));
            g2d.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            String infoText = String.format("蓝图名称: %s  |  尺寸: %d × %d × %d  |  方块总数: %d  |  材料种数: %d",
                    fileData.name, fileData.sizeX, fileData.sizeY, fileData.sizeZ, fileData.blocks.size(), itemCount);
            g2d.drawString(infoText, padding + 20, padding + 64);

            // 3. 绘制材料卡片
            int idx = 0;
            int startY = headerHeight + padding;

            for (Map.Entry<String, Integer> entry : fileData.requiredMaterials.entrySet()) {
                String matId = entry.getKey();
                int totalCount = entry.getValue();

                int c = idx % columns;
                int r = idx / columns;

                int cardX = padding + c * (cardWidth + 15);
                int cardY = startY + r * (cardHeight + 10);

                g2d.setColor(new Color(38, 42, 54));
                g2d.fillRoundRect(cardX, cardY, cardWidth, cardHeight, 10, 10);
                g2d.setColor(new Color(55, 60, 75));
                g2d.drawRoundRect(cardX, cardY, cardWidth, cardHeight, 10, 10);

                Identifier id = Identifier.tryParse(matId);
                Item item = (id != null) ? BuiltInRegistries.ITEM.getValue(id) : Items.AIR;
                String displayName = (item != Items.AIR) ? new ItemStack(item).getHoverName().getString() : matId;

                // 加载方块贴图（带特殊实体与台阶兼容适配）
                BufferedImage icon = loadItemTexture(id);
                if (icon != null) {
                    g2d.drawImage(icon, cardX + 12, cardY + 12, 40, 40, null);
                } else {
                    g2d.setColor(new Color(60, 100, 160));
                    g2d.fillRoundRect(cardX + 12, cardY + 12, 40, 40, 6, 6);
                    g2d.setColor(Color.WHITE);
                    g2d.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
                    g2d.drawString(displayName.substring(0, Math.min(1, displayName.length())), cardX + 25, cardY + 36);
                }

                // 绘制名称
                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
                g2d.drawString(displayName, cardX + 60, cardY + 28);

                // 绘制数量与组数
                int stacks = totalCount / 64;
                int remains = totalCount % 64;
                String countText = totalCount + " 个";
                String stackDetail = (stacks > 0) ? String.format("(%d组 %d个)", stacks, remains) : "(散件)";
                if (matId.contains("bucket")) {
                    stackDetail = "(单件/不可叠)";
                }

                g2d.setColor(new Color(255, 204, 0));
                g2d.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
                g2d.drawString(countText, cardX + 60, cardY + 48);

                g2d.setColor(new Color(150, 160, 175));
                g2d.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
                int countW = g2d.getFontMetrics(new Font("Microsoft YaHei", Font.BOLD, 14)).stringWidth(countText);
                g2d.drawString(stackDetail, cardX + 65 + countW, cardY + 48);

                idx++;
            }

            g2d.dispose();
            ImageIO.write(image, "png", targetFile);
        } catch (Exception ignored) {
        }
    }

    /**
     * 智能贴图解析器：完美适配箱子实体、台阶纹理借用以及草方块着色
     */
    private BufferedImage loadItemTexture(Identifier id) {
        if (id == null) return null;
        String path = id.getPath();

        // 1. 箱子特殊处理（从实体纹理中截取箱子锁扣与正立面）
        if (path.contains("chest") && !path.contains("plate")) {
            try (InputStream in = getClass().getResourceAsStream("/assets/minecraft/textures/entity/chest/normal.png")) {
                if (in != null) {
                    BufferedImage chestSheet = ImageIO.read(in);
                    // 裁剪箱子正面 14x14 像素并缩放
                    BufferedImage chestFace = chestSheet.getSubimage(14, 29, 14, 14);
                    BufferedImage output = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = output.createGraphics();
                    g.drawImage(chestFace, 1, 1, 14, 14, null);
                    g.dispose();
                    return output;
                }
            } catch (Exception ignored) {
            }
        }

        // 2. 台阶类特殊处理：剥离 _slab 后缀，借用母体方块贴图
        String lookupPath = path;
        if (path.endsWith("_slab")) {
            lookupPath = path.replace("_slab", "");
        }

        // 3. 通用方块与物品贴图路径扫描
        List<String> possiblePaths = new ArrayList<>();
        possiblePaths.add("/assets/minecraft/textures/item/" + lookupPath + ".png");
        possiblePaths.add("/assets/minecraft/textures/block/" + lookupPath + ".png");
        possiblePaths.add("/assets/minecraft/textures/block/" + lookupPath + "_top.png");
        possiblePaths.add("/assets/minecraft/textures/block/" + lookupPath + "_front.png");
        possiblePaths.add("/assets/minecraft/textures/block/" + lookupPath + "_side.png");

        for (String p : possiblePaths) {
            try (InputStream in = getClass().getResourceAsStream(p)) {
                if (in != null) {
                    BufferedImage original = ImageIO.read(in);
                    // 如果是草方块顶部，为其染上生机盎然的 Minecraft 标准草绿色
                    if (path.contains("grass_block")) {
                        return tintGreen(original);
                    }
                    return original;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 为黑白灰度草方块材质着原版草绿色
     */
    private BufferedImage tintGreen(BufferedImage src) {
        BufferedImage tinted = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Color grassGreen = new Color(124, 189, 78);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int rgb = src.getRGB(x, y);
                int alpha = (rgb >> 24) & 0xFF;
                if (alpha == 0) continue;
                int gray = (rgb >> 16) & 0xFF; // 取灰度分量
                int r = (gray * grassGreen.getRed()) / 255;
                int g = (gray * grassGreen.getGreen()) / 255;
                int b = (gray * grassGreen.getBlue()) / 255;
                tinted.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return tinted;
    }

    private boolean isWildFoliage(String path) {
        return path.equals("short_grass")
                || path.equals("grass")
                || path.equals("tall_grass")
                || path.equals("fern")
                || path.equals("large_fern")
                || path.equals("dead_bush")
                || path.equals("seagrass")
                || path.equals("tall_seagrass");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getPropValueString(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
