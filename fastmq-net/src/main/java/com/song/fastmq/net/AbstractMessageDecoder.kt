package com.song.fastmq.net


import com.google.common.base.Preconditions.checkArgument
import com.google.protobuf.CodedInputStream
import com.song.fastmq.net.proto.BrokerApi
import com.song.fastmq.net.proto.BrokerApi.*
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.slf4j.LoggerFactory
import java.lang.UnsupportedOperationException

/**
 * @author song
 */
abstract class AbstractMessageDecoder : ChannelInboundHandlerAdapter() {

    @Throws(Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        // Get a buffer that contains the full frame
        val buffer = msg as ByteBuf
        var command: Command?
        var builder: Command.Builder?
        try {
            val writerIndex = buffer.writerIndex()
            builder = Command.newBuilder()
            command = builder.mergeFrom(CodedInputStream.newInstance(buffer.nioBuffer())).build()
            buffer.writerIndex(writerIndex)
            logger.debug("[{}] Received cmd {}", ctx.channel().remoteAddress(), command.type)
            when (command.type) {
                BrokerApi.Command.Type.PRODUCER -> {
                    checkArgument(command.hasProducer())
                    handleProducer(command.producer, buffer)
                }
                BrokerApi.Command.Type.PRODUCER_SUCCESS -> {
                    handleProducerSuccess(command.producerSuccess, buffer)
                    logger.info("Producer success :{}.", command.toString())
                }
                BrokerApi.Command.Type.SEND -> {
                    checkArgument(command.hasSend())
                    handleSend(command.send)
                }
                BrokerApi.Command.Type.SEND_RECEIPT -> {
                    checkArgument(command.hasSendReceipt())
                    handleSendReceipt(command.sendReceipt)
                }
                BrokerApi.Command.Type.SEND_ERROR -> {
                    checkArgument(command.hasSendError())
                    handleSendError(command.sendError)
                }
                else -> throw RuntimeException("Unknown command type :" + command.type)
            }
        } finally {
            buffer.release()
        }
    }

    open fun handleProducer(commandProducer: CommandProducer, payload: ByteBuf) {
        throw UnsupportedOperationException()
    }

    open fun handleProducerSuccess(commandProducerSuccess: CommandProducerSuccess, payload: ByteBuf) {
        throw UnsupportedOperationException()
    }

    open fun handleSend(commandSend: BrokerApi.CommandSend) {
        throw UnsupportedOperationException()
    }

    open fun handleSendReceipt(sendReceipt: CommandSendReceipt) {
        throw UnsupportedOperationException()
    }

    open fun handleSendError(sendError: CommandSendError) {
        throw UnsupportedOperationException()
    }

    companion object {

        private val logger = LoggerFactory.getLogger(AbstractMessageDecoder::class.java)
    }

}
