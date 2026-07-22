package com.ruskserver.modernvoicechat.network

import com.ruskserver.modernvoicechat.Modernvoicechat
import com.ruskserver.modernvoicechat.client.ClientVoiceManager
import com.ruskserver.modernvoicechat.server.ServerVoiceManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import java.util.UUID

object ModNetwork {
    val S2C_SECRET_TYPE = CustomPacketPayload.Type<S2CVoiceSecretPayload>(ResourceLocation.fromNamespaceAndPath(Modernvoicechat.ID, "s2c_secret"))
    val C2S_SECRET_TYPE = CustomPacketPayload.Type<C2SVoiceSecretPayload>(ResourceLocation.fromNamespaceAndPath(Modernvoicechat.ID, "c2s_secret"))

    data class S2CVoiceSecretPayload(val secretToken: UUID, val voicePort: Int, val voiceHost: String) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = S2C_SECRET_TYPE

        companion object {
            val STREAM_CODEC: StreamCodec<FriendlyByteBuf, S2CVoiceSecretPayload> = StreamCodec.of(
                { buf, valObj ->
                    buf.writeUUID(valObj.secretToken)
                    buf.writeInt(valObj.voicePort)
                    buf.writeUtf(valObj.voiceHost)
                },
                { buf ->
                    S2CVoiceSecretPayload(buf.readUUID(), buf.readInt(), buf.readUtf())
                }
            )
        }
    }

    data class C2SVoiceSecretPayload(val secretToken: UUID) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = C2S_SECRET_TYPE

        companion object {
            val STREAM_CODEC: StreamCodec<FriendlyByteBuf, C2SVoiceSecretPayload> = StreamCodec.of(
                { buf, valObj ->
                    buf.writeUUID(valObj.secretToken)
                },
                { buf ->
                    C2SVoiceSecretPayload(buf.readUUID())
                }
            )
        }
    }

    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar: PayloadRegistrar = event.registrar("1")

        // サーバー -> クライアント ハンドシェイク
        registrar.playToClient(
            S2C_SECRET_TYPE,
            S2CVoiceSecretPayload.STREAM_CODEC
        ) { payload, context ->
            context.enqueueWork {
                Modernvoicechat.LOGGER.info("Received S2C Voice Secret token: ${payload.secretToken}, Port: ${payload.voicePort}, Host: ${payload.voiceHost}")
                ClientVoiceManager.connect(payload.voicePort, payload.voiceHost, payload.secretToken)
            }
        }

        // クライアント -> サーバー 確認応答
        registrar.playToServer(
            C2S_SECRET_TYPE,
            C2SVoiceSecretPayload.STREAM_CODEC
        ) { payload, context ->
            context.enqueueWork {
                val player = context.player()
                if (player != null) {
                    ServerVoiceManager.onClientVoiceConfirmed(player.uuid)
                }
            }
        }
    }
}
