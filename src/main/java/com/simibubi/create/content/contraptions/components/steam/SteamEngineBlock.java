package com.simibubi.create.content.contraptions.components.steam;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllShapes;
import com.simibubi.create.AllTileEntities;
import com.simibubi.create.content.contraptions.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.contraptions.fluids.tank.FluidTankConnectivityHandler;
import com.simibubi.create.content.contraptions.fluids.tank.FluidTankTileEntity;
import com.simibubi.create.content.contraptions.relays.elementary.ShaftBlock;
import com.simibubi.create.content.contraptions.wrench.IWrenchable;
import com.simibubi.create.foundation.block.ITE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SteamEngineBlock extends FaceAttachedHorizontalDirectionalBlock
	implements SimpleWaterloggedBlock, IWrenchable, ITE<SteamEngineTileEntity> {

	public SteamEngineBlock(Properties p_53182_) {
		super(p_53182_);
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> pBuilder) {
		super.createBlockStateDefinition(pBuilder.add(FACE, FACING, WATERLOGGED));
	}

	@Override
	public boolean canSurvive(BlockState pState, LevelReader pLevel, BlockPos pPos) {
		return canAttach(pLevel, pPos, getConnectedDirection(pState).getOpposite());
	}

	public static boolean canAttach(LevelReader pReader, BlockPos pPos, Direction pDirection) {
		BlockPos blockpos = pPos.relative(pDirection);
		return pReader.getBlockState(blockpos)
			.getBlock() instanceof FluidTankBlock;
	}

	@Override
	public FluidState getFluidState(BlockState state) {
		return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : Fluids.EMPTY.defaultFluidState();
	}

	@Override
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighbourState, LevelAccessor world,
		BlockPos pos, BlockPos neighbourPos) {
		if (state.getValue(WATERLOGGED))
			world.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(world));
		return state;
	}

	@Override
	public void onPlace(BlockState pState, Level pLevel, BlockPos pPos, BlockState pOldState, boolean pIsMoving) {
		updateAttachedTank(pState, pLevel, pPos);
		BlockPos shaftPos = getShaftPos(pState, pPos);
		BlockState shaftState = pLevel.getBlockState(shaftPos);
		if (isShaftValid(pState, shaftState))
			pLevel.setBlock(shaftPos, PoweredShaftBlock.getEquivalent(shaftState), 3);
	}

	@Override
	public void onRemove(BlockState pState, Level pLevel, BlockPos pPos, BlockState pNewState, boolean pIsMoving) {
		if (pState.hasBlockEntity() && (!pState.is(pNewState.getBlock()) || !pNewState.hasBlockEntity()))
			pLevel.removeBlockEntity(pPos);
		updateAttachedTank(pState, pLevel, pPos);

		BlockPos shaftPos = getShaftPos(pState, pPos);
		BlockState shaftState = pLevel.getBlockState(shaftPos);
		if (AllBlocks.POWERED_SHAFT.has(shaftState))
			pLevel.scheduleTick(shaftPos, shaftState.getBlock(), 1);
	}

	private void updateAttachedTank(BlockState pState, Level pLevel, BlockPos pPos) {
		BlockPos tankPos = pPos.relative(getFacing(pState).getOpposite());
		BlockState tankState = pLevel.getBlockState(tankPos);
		if (!FluidTankBlock.isTank(tankState))
			return;
		FluidTankTileEntity tankTE = FluidTankConnectivityHandler.anyTankAt(pLevel, tankPos);
		if (tankTE == null)
			return;
		FluidTankTileEntity controllerTE = tankTE.getControllerTE();
		if (controllerTE == null)
			return;
		controllerTE.updateBoilerState();
	}

	@Override
	public VoxelShape getShape(BlockState pState, BlockGetter pLevel, BlockPos pPos, CollisionContext pContext) {
		AttachFace face = pState.getValue(FACE);
		Direction direction = pState.getValue(FACING);
		return face == AttachFace.CEILING ? AllShapes.STEAM_ENGINE_CEILING.get(direction.getAxis())
			: face == AttachFace.FLOOR ? AllShapes.STEAM_ENGINE.get(direction.getAxis())
				: AllShapes.STEAM_ENGINE_WALL.get(direction);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		FluidState ifluidstate = level.getFluidState(pos);
		BlockState state = super.getStateForPlacement(context);
		if (state == null)
			return null;
		return state.setValue(WATERLOGGED, Boolean.valueOf(ifluidstate.getType() == Fluids.WATER));
	}

	@Override
	public boolean isPathfindable(BlockState state, BlockGetter reader, BlockPos pos, PathComputationType type) {
		return false;
	}

	public static Direction getFacing(BlockState sideState) {
		return getConnectedDirection(sideState);
	}

	public static BlockPos getShaftPos(BlockState sideState, BlockPos pos) {
		return pos.relative(getConnectedDirection(sideState), 2);
	}

	public static boolean isShaftValid(BlockState state, BlockState shaft) {
		return AllBlocks.SHAFT.has(shaft) && shaft.getValue(ShaftBlock.AXIS) != getFacing(state).getAxis();
	}

	@Override
	public Class<SteamEngineTileEntity> getTileEntityClass() {
		return SteamEngineTileEntity.class;
	}

	@Override
	public BlockEntityType<? extends SteamEngineTileEntity> getTileEntityType() {
		return AllTileEntities.STEAM_ENGINE.get();
	}

}
