package org.hamster.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.nio.NioSocketChannel;

public class StatsHandler extends ChannelDuplexHandler {

    private Long startTime = 0L;
    private Integer receivedBytes = 0;
    private Integer sendBytes = 0;
    private Long endTime = 0L;
    private ConnectionStats connectionStats = new ConnectionStats();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        connectionStats.setTimestamp(System.currentTimeMillis());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        connectionStats.setMetrics(receivedBytes.longValue(), sendBytes.longValue(), endTime - startTime);
        ctx.fireUserEventTriggered(connectionStats);
        super.channelInactive(ctx);
    }


//    - общее количество запросов
//    - количество уникальных запросов (по одному на IP)
//    - счетчик запросов на каждый IP в виде таблицы с колонкам и IP,
//    кол-во запросов, время последнего запроса
//    - количество переадресаций по url'ам  в виде таблицы, с колонками
//    url, кол-во переадресация
//    - количество соединений, открытых в данный момент
//    - в виде таблицы лог из 16 последних обработанных соединений, колонки
//    src_ip, URI, timestamp,  sent_bytes, received_bytes, speed (bytes/sec)



    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;

            if (startTime == 0)
                startTime = System.currentTimeMillis();

            receivedBytes += buf.readableBytes();
        }

        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf)msg;
            sendBytes += buf.readableBytes();
            endTime = System.currentTimeMillis();
        }

        super.write(ctx, msg, promise);
    }

    @Override
    public void close(ChannelHandlerContext ctx, ChannelPromise future) throws Exception {
        super.close(ctx, future);
    }
}

