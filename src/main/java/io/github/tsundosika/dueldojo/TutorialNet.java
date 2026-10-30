package io.github.tsundosika.dueldojo;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The messages between the server's side of a drill tutorial and its window and on-screen prompts on the client. */
public final class TutorialNet {
	private TutorialNet() {
	}

	/** Server to client: show this drill's tutorial window. */
	public record Open(int drill) implements CustomPacketPayload {
		public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelDojo.MOD_ID, "tutorial_open"));
		static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Open::drill, Open::new);

		@Override
		public Type<Open> type() {
			return TYPE;
		}
	}

	/** Client to server: the window was closed; skip = straight to the drill, otherwise "try it" comes first. */
	public record Closed(boolean skip) implements CustomPacketPayload {
		public static final Type<Closed> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelDojo.MOD_ID, "tutorial_closed"));
		static final StreamCodec<RegistryFriendlyByteBuf, Closed> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, Closed::skip, Closed::new);

		@Override
		public Type<Closed> type() {
			return TYPE;
		}
	}

	/**
	 * Server to client: what "try it" shows right now.
	 *
	 * @param drill the drill, or -1 to hide it all
	 * @param hint index into its guide's hints
	 * @param skip how far holding the skip key has got, 0 to 100
	 */
	public record Hud(int drill, int done, int hint, int skip) implements CustomPacketPayload {
		public static final Type<Hud> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(DuelDojo.MOD_ID, "tutorial_hud"));
		public static final Hud HIDDEN = new Hud(-1, 0, 0, 0);
		static final StreamCodec<RegistryFriendlyByteBuf, Hud> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, Hud::drill, ByteBufCodecs.VAR_INT, Hud::done,
				ByteBufCodecs.VAR_INT, Hud::hint, ByteBufCodecs.VAR_INT, Hud::skip, Hud::new);

		@Override
		public Type<Hud> type() {
			return TYPE;
		}
	}

	static void register() {
		PayloadTypeRegistry.playS2C().register(Open.TYPE, Open.CODEC);
		PayloadTypeRegistry.playS2C().register(Hud.TYPE, Hud.CODEC);
		PayloadTypeRegistry.playC2S().register(Closed.TYPE, Closed.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Closed.TYPE, (payload, context) -> DuelManager.tutorialClosed(context.player(), payload.skip()));
	}
}
