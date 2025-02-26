package com.simibubi.create.content.curiosities.girder;

import java.util.Arrays;
import java.util.Random;
import java.util.function.Supplier;

import com.simibubi.create.AllBlockPartials;
import com.simibubi.create.foundation.block.connected.CTModel;
import com.simibubi.create.foundation.utility.Iterate;

import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

public class ConnectedGirderModel extends CTModel {

	public ConnectedGirderModel(BakedModel originalModel) {
		super(originalModel, new GirderCTBehaviour());
	}

	@Override
	public void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos, Supplier<Random> randomSupplier, RenderContext context) {
		ConnectionData data = new ConnectionData();
		for (Direction d : Iterate.horizontalDirections)
			data.setConnected(d, GirderBlock.isConnected(blockView, pos, state, d));

		super.emitBlockQuads(blockView, state, pos, randomSupplier, context);

		for (Direction d : Iterate.horizontalDirections)
			if (data.isConnected(d))
				((FabricBakedModel) AllBlockPartials.METAL_GIRDER_BRACKETS.get(d)
					.get())
					.emitBlockQuads(blockView, state, pos, randomSupplier, context);
	}

	private class ConnectionData {
		boolean[] connectedFaces;

		public ConnectionData() {
			connectedFaces = new boolean[4];
			Arrays.fill(connectedFaces, false);
		}

		void setConnected(Direction face, boolean connected) {
			connectedFaces[face.get2DDataValue()] = connected;
		}

		boolean isConnected(Direction face) {
			return connectedFaces[face.get2DDataValue()];
		}
	}

}
