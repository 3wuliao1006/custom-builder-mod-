package com.example.builder;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Custom_builder implements ModInitializer {
	public static final String MOD_ID = "custom_builder";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// 1. 蓝图工作台 (Blueprint Workbench)
	public static Block BLUEPRINT_WORKBENCH;
	public static Item BLUEPRINT_WORKBENCH_ITEM;

	// 2. 蓝图工作站 (Blueprint Station)
	public static Block BLUEPRINT_STATION;
	public static Item BLUEPRINT_STATION_ITEM;

	@Override
	public void onInitialize() {
		// ----------------- 注册蓝图工作台 -----------------
		Identifier wbId = Identifier.fromNamespaceAndPath(MOD_ID, "blueprint_workbench");
		ResourceKey<Block> wbBlockKey = ResourceKey.create(Registries.BLOCK, wbId);
		ResourceKey<Item> wbItemKey = ResourceKey.create(Registries.ITEM, wbId);

		BLUEPRINT_WORKBENCH = Registry.register(
				BuiltInRegistries.BLOCK,
				wbBlockKey,
				new BlueprintWorkbenchBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.CRAFTING_TABLE)
						.requiresCorrectToolForDrops()
						.setId(wbBlockKey))
		);
		BLUEPRINT_WORKBENCH_ITEM = Registry.register(
				BuiltInRegistries.ITEM,
				wbItemKey,
				new BlockItem(BLUEPRINT_WORKBENCH, new Item.Properties().useBlockDescriptionPrefix().setId(wbItemKey))
		);

		// ----------------- 注册蓝图工作站 -----------------
		Identifier stationId = Identifier.fromNamespaceAndPath(MOD_ID, "blueprint_station");
		ResourceKey<Block> stBlockKey = ResourceKey.create(Registries.BLOCK, stationId);
		ResourceKey<Item> stItemKey = ResourceKey.create(Registries.ITEM, stationId);

		BLUEPRINT_STATION = Registry.register(
				BuiltInRegistries.BLOCK,
				stBlockKey,
				new BlueprintStationBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
						.requiresCorrectToolForDrops()
						.setId(stBlockKey))
		);
		BLUEPRINT_STATION_ITEM = Registry.register(
				BuiltInRegistries.ITEM,
				stItemKey,
				new BlockItem(BLUEPRINT_STATION, new Item.Properties().useBlockDescriptionPrefix().setId(stItemKey))
		);

		LOGGER.info("Custom Builder Mod 核心系统初始化完毕！");
	}
}