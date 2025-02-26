package com.simibubi.create.content.logistics.block.display;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.logistics.block.display.target.DisplayTarget;
import com.simibubi.create.foundation.config.AllConfigs;
import com.simibubi.create.foundation.utility.Lang;

import io.github.fabricators_of_create.porting_lib.item.BlockUseBypassingItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

public class DisplayLinkBlockItem extends BlockItem implements BlockUseBypassingItem {

	public DisplayLinkBlockItem(Block pBlock, Properties pProperties) {
		super(pBlock, pProperties);
	}

	// fabric: handled by BlockUseBypassingItem
//	public static InteractionResult gathererItemAlwaysPlacesWhenUsed(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
//		ItemStack usedItem = player.getItemInHand(hand);
//		if (usedItem.getItem() instanceof DisplayLinkBlockItem) {
//			if (AllBlocks.DISPLAY_LINK.has(level
//				.getBlockState(hitResult.getBlockPos())))
//				return InteractionResult.PASS;
//			return InteractionResult.FAIL;
//		}
//		return InteractionResult.PASS;
//	}

	@Override
	public boolean shouldBypass(BlockState state, BlockPos pos, Level level, Player player, InteractionHand hand) {
		ItemStack usedItem = player.getItemInHand(hand);
		if (usedItem.getItem() instanceof DisplayLinkBlockItem) {
			if (!AllBlocks.DISPLAY_LINK.has(state))
				return true;
		}
		return false;
	}

	@Override
	public InteractionResult useOn(UseOnContext pContext) {
		ItemStack stack = pContext.getItemInHand();
		BlockPos pos = pContext.getClickedPos();
		Level level = pContext.getLevel();
		BlockState state = level.getBlockState(pos);
		Player player = pContext.getPlayer();

		if (player == null)
			return InteractionResult.FAIL;

		if (player.isSteppingCarefully() && stack.hasTag()) {
			if (level.isClientSide)
				return InteractionResult.SUCCESS;
			player.displayClientMessage(Lang.translateDirect("display_link.clear"), true);
			stack.setTag(null);
			return InteractionResult.SUCCESS;
		}

		if (!stack.hasTag()) {
			if (level.isClientSide)
				return InteractionResult.SUCCESS;
			CompoundTag stackTag = stack.getOrCreateTag();
			stackTag.put("SelectedPos", NbtUtils.writeBlockPos(pos));
			player.displayClientMessage(Lang.translateDirect("display_link.set"), true);
			stack.setTag(stackTag);
			return InteractionResult.SUCCESS;
		}

		CompoundTag tag = stack.getTag();
		CompoundTag teTag = new CompoundTag();

		BlockPos selectedPos = NbtUtils.readBlockPos(tag.getCompound("SelectedPos"));
		BlockPos placedPos = pos.relative(pContext.getClickedFace(), state.getMaterial()
			.isReplaceable() ? 0 : 1);

		if (!selectedPos.closerThan(placedPos, AllConfigs.SERVER.logistics.displayLinkRange.get())) {
			player.displayClientMessage(Lang.translateDirect("display_link.too_far")
				.withStyle(ChatFormatting.RED), true);
			return InteractionResult.FAIL;
		}

		teTag.put("TargetOffset", NbtUtils.writeBlockPos(selectedPos.subtract(placedPos)));
		tag.put("BlockEntityTag", teTag);

		InteractionResult useOn = super.useOn(pContext);
		if (level.isClientSide || useOn == InteractionResult.FAIL)
			return useOn;

		ItemStack itemInHand = player.getItemInHand(pContext.getHand());
		if (!itemInHand.isEmpty())
			itemInHand.setTag(null);
		player.displayClientMessage(Lang.translateDirect("display_link.success")
			.withStyle(ChatFormatting.GREEN), true);
		return useOn;
	}

	private static BlockPos lastShownPos = null;
	private static AABB lastShownAABB = null;

	@Environment(EnvType.CLIENT)
	public static void clientTick() {
		Player player = Minecraft.getInstance().player;
		if (player == null)
			return;
		ItemStack heldItemMainhand = player.getMainHandItem();
		if (!(heldItemMainhand.getItem() instanceof DisplayLinkBlockItem))
			return;
		if (!heldItemMainhand.hasTag())
			return;
		CompoundTag stackTag = heldItemMainhand.getOrCreateTag();
		if (!stackTag.contains("SelectedPos"))
			return;

		BlockPos selectedPos = NbtUtils.readBlockPos(stackTag.getCompound("SelectedPos"));

		if (!selectedPos.equals(lastShownPos)) {
			lastShownAABB = getBounds(selectedPos);
			lastShownPos = selectedPos;
		}

		CreateClient.OUTLINER.showAABB("target", lastShownAABB)
			.colored(0xffcb74)
			.lineWidth(1 / 16f);
	}

	@Environment(EnvType.CLIENT)
	private static AABB getBounds(BlockPos pos) {
		Level world = Minecraft.getInstance().level;
		DisplayTarget target = AllDisplayBehaviours.targetOf(world, pos);

		if (target != null)
			return target.getMultiblockBounds(world, pos);

		BlockState state = world.getBlockState(pos);
		VoxelShape shape = state.getShape(world, pos);
		return shape.isEmpty() ? new AABB(BlockPos.ZERO)
			: shape.bounds()
				.move(pos);
	}

}
