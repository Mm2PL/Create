package com.simibubi.create.content.contraptions.components.structureMovement;

import java.util.function.Supplier;

import com.simibubi.create.foundation.networking.SimplePacketBase;

import io.github.fabricators_of_create.porting_lib.util.EnvExecutor;
import net.fabricmc.api.EnvType;
import net.minecraft.network.FriendlyByteBuf;

public class ContraptionRelocationPacket extends SimplePacketBase {

	int entityID;

	public ContraptionRelocationPacket(int entityID) {
		this.entityID = entityID;
	}

	public ContraptionRelocationPacket(FriendlyByteBuf buffer) {
		entityID = buffer.readInt();
	}

	@Override
	public void write(FriendlyByteBuf buffer) {
		buffer.writeInt(entityID);
	}

	@Override
	public void handle(Supplier<Context> context) {
		context.get()
			.enqueueWork(() -> EnvExecutor.runWhenOn(EnvType.CLIENT,
				() -> () -> OrientedContraptionEntity.handleRelocationPacket(this)));
		context.get()
			.setPacketHandled(true);
	}

}
