package com.simibubi.create.content.logistics.block.belts.tunnel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

import org.apache.commons.lang3.tuple.Pair;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.contraptions.relays.belt.BeltHelper;
import com.simibubi.create.content.contraptions.relays.belt.BeltTileEntity;
import com.simibubi.create.content.logistics.block.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.block.funnel.BeltFunnelBlock.Shape;
import com.simibubi.create.content.logistics.block.funnel.FunnelBlock;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.tileEntity.TileEntityBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.tileEntity.behaviour.belt.DirectBeltInputBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.filtering.SidedFilteringBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.scrollvalue.INamedIconOptions;
import com.simibubi.create.foundation.tileEntity.behaviour.scrollvalue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.utility.BlockHelper;
import com.simibubi.create.foundation.utility.Components;
import com.simibubi.create.foundation.utility.Couple;
import com.simibubi.create.foundation.utility.Iterate;
import com.simibubi.create.foundation.utility.Lang;
import com.simibubi.create.foundation.utility.NBTHelper;

import io.github.fabricators_of_create.porting_lib.transfer.TransferUtil;
import io.github.fabricators_of_create.porting_lib.transfer.item.ItemHandlerHelper;
import io.github.fabricators_of_create.porting_lib.transfer.item.ItemTransferable;
import io.github.fabricators_of_create.porting_lib.util.NBTSerializer;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class BrassTunnelTileEntity extends BeltTunnelTileEntity implements IHaveGoggleInformation, ItemTransferable {

	SidedFilteringBehaviour filtering;

	boolean connectedLeft;
	boolean connectedRight;

	ItemStack stackToDistribute;
	Direction stackEnteredFrom;

	float distributionProgress;
	int distributionDistanceLeft;
	int distributionDistanceRight;
	int previousOutputIndex;

	// <filtered, non-filtered>
	Couple<List<Pair<BlockPos, Direction>>> distributionTargets;

	private boolean syncedOutputActive;
	private Set<BrassTunnelTileEntity> syncSet;

	protected ScrollOptionBehaviour<SelectionMode> selectionMode;
	private BlockApiCache<Storage<ItemVariant>, Direction> beltCapabilityCache;
	private BrassTunnelItemHandler tunnelCapability;

	public final SnapshotParticipant<Data> snapshotParticipant = new SnapshotParticipant<>() {

		@Override
		protected Data createSnapshot() {
			return new Data(stackToDistribute.copy(), distributionProgress, stackEnteredFrom);
		}

		@Override
		protected void readSnapshot(Data snapshot) {
			stackToDistribute = snapshot.stack;
			distributionProgress = snapshot.progress;
			stackEnteredFrom = snapshot.enteredFrom;
		}
	};

	private record Data(ItemStack stack, float progress, Direction enteredFrom) {
	}

	public BrassTunnelTileEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		distributionTargets = Couple.create(ArrayList::new);
		syncSet = new HashSet<>();
		stackToDistribute = ItemStack.EMPTY;
		stackEnteredFrom = null;
		// fabric: beltCapability moved to cache, initialized on level set
		tunnelCapability = new BrassTunnelItemHandler(this);
		previousOutputIndex = 0;
		syncedOutputActive = false;
	}

	@Override
	public void setLevel(Level level) {
		super.setLevel(level);
		beltCapabilityCache = TransferUtil.getItemCache(level, worldPosition.below());
	}

	@Override
	public void addBehaviours(List<TileEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
		behaviours.add(selectionMode = new ScrollOptionBehaviour<>(SelectionMode.class,
			Lang.translateDirect("logistics.when_multiple_outputs_available"), this,
			new CenteredSideValueBoxTransform((state, d) -> d == Direction.UP)));
		selectionMode.requiresWrench();

		// Propagate settings across connected tunnels
		selectionMode.withCallback(setting -> {
			for (boolean side : Iterate.trueAndFalse) {
				if (!isConnected(side))
					continue;
				BrassTunnelTileEntity adjacent = getAdjacent(side);
				if (adjacent != null)
					adjacent.selectionMode.setValue(setting);
			}
		});
	}

	@Override
	public void tick() {
		super.tick();
		BeltTileEntity beltBelow = BeltHelper.getSegmentTE(level, worldPosition.below());

		if (distributionProgress > 0)
			distributionProgress--;
		if (beltBelow == null || beltBelow.getSpeed() == 0)
			return;
		if (stackToDistribute.isEmpty() && !syncedOutputActive)
			return;
		if (level.isClientSide && !isVirtual())
			return;

		if (distributionProgress == -1) {
			distributionTargets.forEach(List::clear);
			distributionDistanceLeft = 0;
			distributionDistanceRight = 0;

			syncSet.clear();
			List<Pair<BrassTunnelTileEntity, Direction>> validOutputs = gatherValidOutputs();
			if (selectionMode.get() == SelectionMode.SYNCHRONIZE) {
				boolean allEmpty = true;
				boolean allFull = true;
				for (BrassTunnelTileEntity te : syncSet) {
					boolean hasStack = !te.stackToDistribute.isEmpty();
					allEmpty &= !hasStack;
					allFull &= hasStack;
				}
				final boolean notifySyncedOut = !allEmpty;
				if (allFull || allEmpty)
					syncSet.forEach(te -> te.syncedOutputActive = notifySyncedOut);
			}

			if (validOutputs == null)
				return;
			if (stackToDistribute.isEmpty())
				return;

			for (Pair<BrassTunnelTileEntity, Direction> pair : validOutputs) {
				BrassTunnelTileEntity tunnel = pair.getKey();
				Direction output = pair.getValue();
				if (insertIntoTunnel(tunnel, output, stackToDistribute, true) == null)
					continue;
				distributionTargets.get(!tunnel.flapFilterEmpty(output))
					.add(Pair.of(tunnel.worldPosition, output));
				int distance = tunnel.worldPosition.getX() + tunnel.worldPosition.getZ() - worldPosition.getX() - worldPosition.getZ();
				if (distance < 0)
					distributionDistanceLeft = Math.max(distributionDistanceLeft, -distance);
				else
					distributionDistanceRight = Math.max(distributionDistanceRight, distance);
			}

			if (distributionTargets.getFirst()
				.isEmpty()
				&& distributionTargets.getSecond()
					.isEmpty())
				return;

			if (selectionMode.get() != SelectionMode.SYNCHRONIZE || syncedOutputActive) {
				distributionProgress = 10;
				sendData();
			}
			return;
		}

		if (distributionProgress != 0)
			return;

		distributionTargets.forEach(list -> {
			if (stackToDistribute.isEmpty())
				return;
			List<Pair<BrassTunnelTileEntity, Direction>> validTargets = new ArrayList<>();
			for (Pair<BlockPos, Direction> pair : list) {
				BlockPos tunnelPos = pair.getKey();
				Direction output = pair.getValue();
				if (tunnelPos.equals(worldPosition) && output == stackEnteredFrom)
					continue;
				BlockEntity te = level.getBlockEntity(tunnelPos);
				if (!(te instanceof BrassTunnelTileEntity))
					continue;
				validTargets.add(Pair.of((BrassTunnelTileEntity) te, output));
			}
			distribute(validTargets);
			distributionProgress = -1;
		});
	}

	private static Random rand = new Random();
	private static Map<Pair<BrassTunnelTileEntity, Direction>, ItemStack> distributed = new IdentityHashMap<>();
	private static Set<Pair<BrassTunnelTileEntity, Direction>> full = new HashSet<>();

	private void distribute(List<Pair<BrassTunnelTileEntity, Direction>> validTargets) {
		int amountTargets = validTargets.size();
		if (amountTargets == 0)
			return;

		distributed.clear();
		full.clear();

		int indexStart = previousOutputIndex % amountTargets;
		SelectionMode mode = selectionMode.get();
		boolean force = mode == SelectionMode.FORCED_ROUND_ROBIN || mode == SelectionMode.FORCED_SPLIT;
		boolean split = mode == SelectionMode.FORCED_SPLIT || mode == SelectionMode.SPLIT;
		boolean robin = mode == SelectionMode.FORCED_ROUND_ROBIN || mode == SelectionMode.ROUND_ROBIN;

		if (mode == SelectionMode.RANDOMIZE)
			indexStart = rand.nextInt(amountTargets);
		if (mode == SelectionMode.PREFER_NEAREST || mode == SelectionMode.SYNCHRONIZE)
			indexStart = 0;

		ItemStack toDistribute = stackToDistribute.copy();
		for (boolean distributeAgain : Iterate.trueAndFalse) {
			ItemStack toDistributeThisCycle = null;
			int remainingOutputs = amountTargets;
			int leftovers = 0;

			for (boolean simulate : Iterate.trueAndFalse) {
				if (remainingOutputs == 0)
					break;

				leftovers = 0;
				int index = indexStart;
				int stackSize = toDistribute.getCount();
				int splitStackSize = stackSize / remainingOutputs;
				int splitRemainder = stackSize % remainingOutputs;
				int visited = 0;

				toDistributeThisCycle = toDistribute.copy();
				if (!(force || split) && simulate)
					continue;

				while (visited < amountTargets) {
					Pair<BrassTunnelTileEntity, Direction> pair = validTargets.get(index);
					BrassTunnelTileEntity tunnel = pair.getKey();
					Direction side = pair.getValue();
					index = (index + 1) % amountTargets;
					visited++;

					if (full.contains(pair)) {
						if (split && simulate)
							remainingOutputs--;
						continue;
					}

					int count = split ? splitStackSize + (splitRemainder > 0 ? 1 : 0) : stackSize;
					ItemStack toOutput = ItemHandlerHelper.copyStackWithSize(toDistributeThisCycle, count);

					// Grow by 1 to determine if target is full even after a successful transfer
					boolean testWithIncreasedCount = distributed.containsKey(pair);
					int increasedCount = testWithIncreasedCount ? distributed.get(pair)
						.getCount() : 0;
					if (testWithIncreasedCount)
						toOutput.grow(increasedCount);

					ItemStack remainder = insertIntoTunnel(tunnel, side, toOutput, true);

					if (remainder == null || remainder.getCount() == (testWithIncreasedCount ? count + 1 : count)) {
						if (force)
							return;
						if (split && simulate)
							remainingOutputs--;
						if (!simulate)
							full.add(pair);
						if (robin)
							break;
						continue;
					} else if (!remainder.isEmpty() && !simulate) {
						full.add(pair);
					}

					if (!simulate) {
						toOutput.shrink(remainder.getCount());
						distributed.put(pair, toOutput);
					}

					leftovers += remainder.getCount();
					toDistributeThisCycle.shrink(count);
					if (toDistributeThisCycle.isEmpty())
						break;
					splitRemainder--;
					if (!split)
						break;
				}
			}

			toDistribute.setCount(toDistributeThisCycle.getCount() + leftovers);
			if (leftovers == 0 && distributeAgain)
				break;
			if (!split)
				break;
		}

		int failedTransferrals = 0;
		for (Entry<Pair<BrassTunnelTileEntity, Direction>, ItemStack> entry : distributed.entrySet()) {
			Pair<BrassTunnelTileEntity, Direction> pair = entry.getKey();
			failedTransferrals += insertIntoTunnel(pair.getKey(), pair.getValue(), entry.getValue(), false).getCount();
		}

		toDistribute.grow(failedTransferrals);
		stackToDistribute = ItemHandlerHelper.copyStackWithSize(stackToDistribute, toDistribute.getCount());
		if (stackToDistribute.isEmpty())
			stackEnteredFrom = null;
		previousOutputIndex++;
		previousOutputIndex %= amountTargets;
		notifyUpdate();
	}

	public void setStackToDistribute(ItemStack stack, @Nullable Direction enteredFrom, @Nullable TransactionContext ctx) {
		if (ctx != null) {
			snapshotParticipant.updateSnapshots(ctx);
		}
		stackToDistribute = stack;
		stackEnteredFrom = enteredFrom;
		distributionProgress = -1;
	}

	public ItemStack getStackToDistribute() {
		return stackToDistribute;
	}

	public List<ItemStack> grabAllStacksOfGroup(boolean simulate) {
		List<ItemStack> list = new ArrayList<>();

		ItemStack own = getStackToDistribute();
		if (!own.isEmpty()) {
			list.add(own);
			if (!simulate)
				setStackToDistribute(ItemStack.EMPTY, null, null);
		}

		for (boolean left : Iterate.trueAndFalse) {
			BrassTunnelTileEntity adjacent = this;
			while (adjacent != null) {
				if (!level.isLoaded(adjacent.getBlockPos()))
					return null;
				adjacent = adjacent.getAdjacent(left);
				if (adjacent == null)
					continue;
				ItemStack other = adjacent.getStackToDistribute();
				if (other.isEmpty())
					continue;
				list.add(other);
				if (!simulate)
					adjacent.setStackToDistribute(ItemStack.EMPTY, null, null);
			}
		}

		return list;
	}

	@Nullable
	protected ItemStack insertIntoTunnel(BrassTunnelTileEntity tunnel, Direction side, ItemStack stack,
		boolean simulate) {
		if (stack.isEmpty())
			return stack;
		if (!tunnel.testFlapFilter(side, stack))
			return null;

		BeltTileEntity below = BeltHelper.getSegmentTE(level, tunnel.worldPosition.below());
		if (below == null)
			return null;
		BlockPos offset = tunnel.getBlockPos()
			.below()
			.relative(side);
		DirectBeltInputBehaviour sideOutput = TileEntityBehaviour.get(level, offset, DirectBeltInputBehaviour.TYPE);
		if (sideOutput != null) {
			if (!sideOutput.canInsertFromSide(side))
				return null;
			ItemStack result = sideOutput.handleInsertion(stack, side, simulate);
			if (result.isEmpty() && !simulate)
				tunnel.flap(side, false);
			return result;
		}

		Direction movementFacing = below.getMovementFacing();
		if (side == movementFacing)
			if (!BlockHelper.hasBlockSolidSide(level.getBlockState(offset), level, offset, side.getOpposite())) {
				BeltTileEntity controllerTE = below.getControllerTE();
				if (controllerTE == null)
					return null;

				if (!simulate) {
					tunnel.flap(side, true);
					ItemStack ejected = stack;
					float beltMovementSpeed = below.getDirectionAwareBeltMovementSpeed();
					float movementSpeed = Math.max(Math.abs(beltMovementSpeed), 1 / 8f);
					int additionalOffset = beltMovementSpeed > 0 ? 1 : 0;
					Vec3 outPos = BeltHelper.getVectorForOffset(controllerTE, below.index + additionalOffset);
					Vec3 outMotion = Vec3.atLowerCornerOf(side.getNormal())
						.scale(movementSpeed)
						.add(0, 1 / 8f, 0);
					outPos.add(outMotion.normalize());
					ItemEntity entity = new ItemEntity(level, outPos.x, outPos.y + 6 / 16f, outPos.z, ejected);
					entity.setDeltaMovement(outMotion);
					entity.setDefaultPickUpDelay();
					entity.hurtMarked = true;
					level.addFreshEntity(entity);
				}

				return ItemStack.EMPTY;
			}

		return null;
	}

	public boolean testFlapFilter(Direction side, ItemStack stack) {
		if (filtering == null)
			return false;
		if (filtering.get(side) == null) {
			FilteringBehaviour adjacentFilter =
				TileEntityBehaviour.get(level, worldPosition.relative(side), FilteringBehaviour.TYPE);
			if (adjacentFilter == null)
				return true;
			return adjacentFilter.test(stack);
		}
		return filtering.test(side, stack);
	}

	public boolean flapFilterEmpty(Direction side) {
		if (filtering == null)
			return false;
		if (filtering.get(side) == null) {
			FilteringBehaviour adjacentFilter =
				TileEntityBehaviour.get(level, worldPosition.relative(side), FilteringBehaviour.TYPE);
			if (adjacentFilter == null)
				return true;
			return adjacentFilter.getFilter()
				.isEmpty();
		}
		return filtering.getFilter(side)
			.isEmpty();
	}

	@Override
	public void initialize() {
		if (filtering == null) {
			filtering = createSidedFilter();
			attachBehaviourLate(filtering);
		}
		super.initialize();
	}

	public boolean canInsert(Direction side, ItemStack stack) {
		if (filtering != null && !filtering.test(side, stack))
			return false;
		if (!hasDistributionBehaviour())
			return true;
		if (!stackToDistribute.isEmpty())
			return false;
		return true;
	}

	public boolean hasDistributionBehaviour() {
		if (flaps.isEmpty())
			return false;
		if (connectedLeft || connectedRight)
			return true;
		BlockState blockState = getBlockState();
		if (!AllBlocks.BRASS_TUNNEL.has(blockState))
			return false;
		Axis axis = blockState.getValue(BrassTunnelBlock.HORIZONTAL_AXIS);
		for (Direction direction : flaps.keySet())
			if (direction.getAxis() != axis)
				return true;
		return false;
	}

	private List<Pair<BrassTunnelTileEntity, Direction>> gatherValidOutputs() {
		List<Pair<BrassTunnelTileEntity, Direction>> validOutputs = new ArrayList<>();
		boolean synchronize = selectionMode.get() == SelectionMode.SYNCHRONIZE;
		addValidOutputsOf(this, validOutputs);

		for (boolean left : Iterate.trueAndFalse) {
			BrassTunnelTileEntity adjacent = this;
			while (adjacent != null) {
				if (!level.isLoaded(adjacent.getBlockPos()))
					return null;
				adjacent = adjacent.getAdjacent(left);
				if (adjacent == null)
					continue;
				addValidOutputsOf(adjacent, validOutputs);
			}
		}

		if (!syncedOutputActive && synchronize)
			return null;
		return validOutputs;
	}

	private void addValidOutputsOf(BrassTunnelTileEntity tunnelTE,
		List<Pair<BrassTunnelTileEntity, Direction>> validOutputs) {
		syncSet.add(tunnelTE);
		BeltTileEntity below = BeltHelper.getSegmentTE(level, tunnelTE.worldPosition.below());
		if (below == null)
			return;
		Direction movementFacing = below.getMovementFacing();
		BlockState blockState = getBlockState();
		if (!AllBlocks.BRASS_TUNNEL.has(blockState))
			return;

		boolean prioritizeSides = tunnelTE == this;

		for (boolean sidePass : Iterate.trueAndFalse) {
			if (!prioritizeSides && sidePass)
				continue;
			for (Direction direction : Iterate.horizontalDirections) {
				if (direction == movementFacing && below.getSpeed() == 0)
					continue;
				if (prioritizeSides && sidePass == (direction.getAxis() == movementFacing.getAxis()))
					continue;
				if (direction == movementFacing.getOpposite())
					continue;
				if (!tunnelTE.sides.contains(direction))
					continue;

				BlockPos offset = tunnelTE.worldPosition.below()
					.relative(direction);

				BlockState potentialFunnel = level.getBlockState(offset.above());
				if (potentialFunnel.getBlock() instanceof BeltFunnelBlock
					&& potentialFunnel.getValue(BeltFunnelBlock.SHAPE) == Shape.PULLING
					&& FunnelBlock.getFunnelFacing(potentialFunnel) == direction)
					continue;

				DirectBeltInputBehaviour inputBehaviour =
					TileEntityBehaviour.get(level, offset, DirectBeltInputBehaviour.TYPE);
				if (inputBehaviour == null) {
					if (direction == movementFacing)
						if (!BlockHelper.hasBlockSolidSide(level.getBlockState(offset), level, offset,
							direction.getOpposite()))
							validOutputs.add(Pair.of(tunnelTE, direction));
					continue;
				}
				if (inputBehaviour.canInsertFromSide(direction))
					validOutputs.add(Pair.of(tunnelTE, direction));
				continue;
			}
		}
	}

	@Override
	public void addBehavioursDeferred(List<TileEntityBehaviour> behaviours) {
		super.addBehavioursDeferred(behaviours);
		filtering = createSidedFilter();
		behaviours.add(filtering);
	}

	protected SidedFilteringBehaviour createSidedFilter() {
		return new SidedFilteringBehaviour(this, new BrassTunnelFilterSlot(), this::makeFilter,
			this::isValidFaceForFilter);
	}

	private FilteringBehaviour makeFilter(Direction side, FilteringBehaviour filter) {
		return filter;
	}

	private boolean isValidFaceForFilter(Direction side) {
		return sides.contains(side);
	}

	@Override
	public void write(CompoundTag compound, boolean clientPacket) {
		compound.putBoolean("SyncedOutput", syncedOutputActive);
		compound.putBoolean("ConnectedLeft", connectedLeft);
		compound.putBoolean("ConnectedRight", connectedRight);

		compound.put("StackToDistribute", NBTSerializer.serializeNBT(stackToDistribute));
		if (stackEnteredFrom != null)
			NBTHelper.writeEnum(compound, "StackEnteredFrom", stackEnteredFrom);

		compound.putFloat("DistributionProgress", distributionProgress);
		compound.putInt("PreviousIndex", previousOutputIndex);
		compound.putInt("DistanceLeft", distributionDistanceLeft);
		compound.putInt("DistanceRight", distributionDistanceRight);

		for (boolean filtered : Iterate.trueAndFalse) {
			compound.put(filtered ? "FilteredTargets" : "Targets",
				NBTHelper.writeCompoundList(distributionTargets.get(filtered), pair -> {
					CompoundTag nbt = new CompoundTag();
					nbt.put("Pos", NbtUtils.writeBlockPos(pair.getKey()));
					nbt.putInt("Face", pair.getValue()
						.get3DDataValue());
					return nbt;
				}));
		}

		super.write(compound, clientPacket);
	}

	@Override
	protected void read(CompoundTag compound, boolean clientPacket) {
		boolean wasConnectedLeft = connectedLeft;
		boolean wasConnectedRight = connectedRight;

		syncedOutputActive = compound.getBoolean("SyncedOutput");
		connectedLeft = compound.getBoolean("ConnectedLeft");
		connectedRight = compound.getBoolean("ConnectedRight");

		stackToDistribute = ItemStack.of(compound.getCompound("StackToDistribute"));
		stackEnteredFrom =
			compound.contains("StackEnteredFrom") ? NBTHelper.readEnum(compound, "StackEnteredFrom", Direction.class)
				: null;

		distributionProgress = compound.getFloat("DistributionProgress");
		previousOutputIndex = compound.getInt("PreviousIndex");
		distributionDistanceLeft = compound.getInt("DistanceLeft");
		distributionDistanceRight = compound.getInt("DistanceRight");

		for (boolean filtered : Iterate.trueAndFalse) {
			distributionTargets.set(filtered, NBTHelper
				.readCompoundList(compound.getList(filtered ? "FilteredTargets" : "Targets", Tag.TAG_COMPOUND), nbt -> {
					BlockPos pos = NbtUtils.readBlockPos(nbt.getCompound("Pos"));
					Direction face = Direction.from3DDataValue(nbt.getInt("Face"));
					return Pair.of(pos, face);
				}));
		}

		super.read(compound, clientPacket);

		if (!clientPacket)
			return;
		if (wasConnectedLeft != connectedLeft || wasConnectedRight != connectedRight) {
//			requestModelDataUpdate();
			if (hasLevel())
				level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 16);
		}
		filtering.updateFilterPresence();
	}

	public boolean isConnected(boolean leftSide) {
		return leftSide ? connectedLeft : connectedRight;
	}

	@Override
	public void updateTunnelConnections() {
		super.updateTunnelConnections();
		boolean connectivityChanged = false;
		boolean nowConnectedLeft = determineIfConnected(true);
		boolean nowConnectedRight = determineIfConnected(false);

		if (connectedLeft != nowConnectedLeft) {
			connectedLeft = nowConnectedLeft;
			connectivityChanged = true;
			BrassTunnelTileEntity adjacent = getAdjacent(true);
			if (adjacent != null && !level.isClientSide) {
				adjacent.updateTunnelConnections();
				adjacent.selectionMode.setValue(selectionMode.getValue());
			}
		}

		if (connectedRight != nowConnectedRight) {
			connectedRight = nowConnectedRight;
			connectivityChanged = true;
			BrassTunnelTileEntity adjacent = getAdjacent(false);
			if (adjacent != null && !level.isClientSide) {
				adjacent.updateTunnelConnections();
				adjacent.selectionMode.setValue(selectionMode.getValue());
			}
		}

		if (filtering != null)
			filtering.updateFilterPresence();
		if (connectivityChanged)
			sendData();
	}

	protected boolean determineIfConnected(boolean leftSide) {
		if (flaps.isEmpty())
			return false;
		BrassTunnelTileEntity adjacentTunnelTE = getAdjacent(leftSide);
		return adjacentTunnelTE != null && !adjacentTunnelTE.flaps.isEmpty();
	}

	@Nullable
	protected BrassTunnelTileEntity getAdjacent(boolean leftSide) {
		if (!hasLevel())
			return null;

		BlockState blockState = getBlockState();
		if (!AllBlocks.BRASS_TUNNEL.has(blockState))
			return null;

		Axis axis = blockState.getValue(BrassTunnelBlock.HORIZONTAL_AXIS);
		Direction baseDirection = Direction.get(AxisDirection.POSITIVE, axis);
		Direction direction = leftSide ? baseDirection.getCounterClockWise() : baseDirection.getClockWise();
		BlockPos adjacentPos = worldPosition.relative(direction);
		BlockState adjacentBlockState = level.getBlockState(adjacentPos);

		if (!AllBlocks.BRASS_TUNNEL.has(adjacentBlockState))
			return null;
		if (adjacentBlockState.getValue(BrassTunnelBlock.HORIZONTAL_AXIS) != axis)
			return null;
		BlockEntity adjacentTE = level.getBlockEntity(adjacentPos);
		if (adjacentTE.isRemoved())
			return null;
		if (!(adjacentTE instanceof BrassTunnelTileEntity))
			return null;
		return (BrassTunnelTileEntity) adjacentTE;
	}

	@Override
	public void invalidate() {
		super.invalidate();
	}

	@Override
	public void destroy() {
		super.destroy();
		Block.popResource(level, worldPosition, stackToDistribute);
		stackEnteredFrom = null;
	}

	@Override
	public Storage<ItemVariant> getItemStorage(@Nullable Direction face) {
		return tunnelCapability;
	}

	public Storage<ItemVariant> getBeltCapability() {
		return beltCapabilityCache != null ? beltCapabilityCache.find(Direction.UP) : null;
	}

	public enum SelectionMode implements INamedIconOptions {
		SPLIT(AllIcons.I_TUNNEL_SPLIT),
		FORCED_SPLIT(AllIcons.I_TUNNEL_FORCED_SPLIT),
		ROUND_ROBIN(AllIcons.I_TUNNEL_ROUND_ROBIN),
		FORCED_ROUND_ROBIN(AllIcons.I_TUNNEL_FORCED_ROUND_ROBIN),
		PREFER_NEAREST(AllIcons.I_TUNNEL_PREFER_NEAREST),
		RANDOMIZE(AllIcons.I_TUNNEL_RANDOMIZE),
		SYNCHRONIZE(AllIcons.I_TUNNEL_SYNCHRONIZE),

		;

		private final String translationKey;
		private final AllIcons icon;

		SelectionMode(AllIcons icon) {
			this.icon = icon;
			this.translationKey = "tunnel.selection_mode." + Lang.asId(name());
		}

		@Override
		public AllIcons getIcon() {
			return icon;
		}

		@Override
		public String getTranslationKey() {
			return translationKey;
		}
	}

	public boolean canTakeItems() {
		return stackToDistribute.isEmpty() && !syncedOutputActive;
	}

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		List<ItemStack> allStacks = grabAllStacksOfGroup(true);
		if (allStacks.isEmpty())
			return false;

		tooltip.add(componentSpacing.plainCopy()
			.append(Lang.translateDirect("tooltip.brass_tunnel.contains"))
			.withStyle(ChatFormatting.WHITE));
		for (ItemStack item : allStacks) {
			tooltip.add(componentSpacing.plainCopy()
				.append(Lang.translateDirect("tooltip.brass_tunnel.contains_entry", Components.translatable(item.getDescriptionId())
					.getString(), item.getCount()))
				.withStyle(ChatFormatting.GRAY));
		}
		tooltip.add(componentSpacing.plainCopy()
			.append(Lang.translateDirect("tooltip.brass_tunnel.retrieve"))
			.withStyle(ChatFormatting.DARK_GRAY));

		return true;
	}
}
