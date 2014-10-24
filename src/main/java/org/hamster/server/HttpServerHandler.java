package org.hamster.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpHeaders.Names.*;
import static io.netty.handler.codec.http.HttpHeaders.Values;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpServerHandler extends ChannelDuplexHandler {
    static final AtomicLong totalRequests = new AtomicLong();
    static final ConcurrentHashMap<String, AtomicLong> requestsPerIP = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Long> lastRequestTimePerIP = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, AtomicLong> redirectsPerUrl = new ConcurrentHashMap<>();
    static final AtomicLong openedConnections = new AtomicLong();
    static final ConcurrentLinkedQueue<ConnectionStats> stats = new ConcurrentLinkedQueue<>();
    static final AtomicInteger statsSize = new AtomicInteger(0);

    private String remoteHostName;
    private String uri;

    private final int timeout = 10000;

//    @Override
//    public void channelReadComplete(ChannelHandlerContext ctx) {
//        ctx.flush();
//    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            totalRequests.incrementAndGet();
            remoteHostName = ((NioSocketChannel) ctx.channel()).remoteAddress().getHostName();

            AtomicLong requestsCounter = requestsPerIP.putIfAbsent(remoteHostName, new AtomicLong(1L));
            if (requestsCounter != null) requestsCounter.incrementAndGet();

            lastRequestTimePerIP.put(remoteHostName, System.currentTimeMillis());

            HttpRequest req = (HttpRequest) msg;
            uri = req.getUri();
            QueryStringDecoder decoder = new QueryStringDecoder(req.getUri());
            HttpMethod method = req.getMethod();

            if (method.equals(HttpMethod.GET) && decoder.path().equals("/hello")) {
                Thread.sleep(timeout);

                if (HttpHeaders.is100ContinueExpected(req)) {
                    ctx.write(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE));
                }
                boolean keepAlive = HttpHeaders.isKeepAlive(req);
                FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer("Hello World".getBytes()));
                response.headers().set(CONTENT_TYPE, "text/plain");
                response.headers().set(CONTENT_LENGTH, response.content().readableBytes());

                if (!keepAlive) {
                    ctx.write(response).addListener(ChannelFutureListener.CLOSE);
                } else {
                    response.headers().set(CONNECTION, Values.KEEP_ALIVE);
                    //ctx.write(response);
                }
            } else if (method.equals(HttpMethod.GET) && decoder.path().equals("/redirect") && decoder.parameters().containsKey("url")) {
                String redirectTo = decoder.parameters().get("url").get(0);

                AtomicLong redirectCounter = redirectsPerUrl.putIfAbsent(redirectTo, new AtomicLong(1L));
                if (redirectCounter != null)
                    redirectCounter.incrementAndGet();

                System.out.println("redirect to " + redirectTo);

                FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.FOUND);
                response.headers().set(HttpHeaders.Names.LOCATION, redirectTo);

                // Close the connection as soon as the error message is sent.
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            } else if (method.equals(HttpMethod.GET) && decoder.path().equals("/status")) {
                sendStats(ctx);
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        openedConnections.incrementAndGet();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        openedConnections.decrementAndGet();
        super.channelInactive(ctx);
    }

    private void addStats(ConnectionStats connectionStats) {
        connectionStats.setIp(remoteHostName);
        connectionStats.setUri(uri);
        stats.add(connectionStats);
        if (statsSize.incrementAndGet() > 16) {
            stats.remove();
            statsSize.decrementAndGet();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof ConnectionStats) {
            addStats((ConnectionStats) evt);
        }

        super.userEventTriggered(ctx, evt);
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

    private static void sendStats(ChannelHandlerContext ctx) {
        Long totalRequestsCount = totalRequests.get();
        Integer uniqueRequestsCount = requestsPerIP.size();

        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        response.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8");

        StringBuilder buf = new StringBuilder();
        String eol = "\r\n";

        buf.append("<!DOCTYPE html>" + eol);
        buf.append("<html><head><title>");
        buf.append("Stats");
        buf.append("</title></head><body>" + eol);
        buf.append("Total requests =" + totalRequestsCount + " <br>" + eol);
        buf.append("Unique requests= " + uniqueRequestsCount + "<br>" + eol);
        buf.append("Opened connections = " + openedConnections.get() + eol);
        IPCounters(buf);
        redirectCounters(buf);
        log16(buf);
        buf.append("</body></html>");
        ByteBuf buffer = Unpooled.copiedBuffer(buf, CharsetUtil.UTF_8);
        response.content().writeBytes(buffer);
        response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
        buffer.release();

        // Close the connection as soon as the error message is sent.
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    //    src_ip, URI, timestamp,  sent_bytes, received_bytes, speed (bytes/sec)
    private static void log16(final StringBuilder buf) {
        buf.append("<TABLE border=\"1\">");
        buf.append("<caption>Last Connections log</caption><br>");
        buf.append("<TR align=\"center\"><td>IP</td> <td>URL</td><td>Timestamp</td><td>Sent bytes</td><td>Received bytes</td><td>Speed (bytes/sec)</td></TR><br>");

        stats.iterator().forEachRemaining(new Consumer<ConnectionStats>() {
            @Override
            public void accept(ConnectionStats connectionStats) {
                buf.append(connectionStats.getAsTableRow());
            }
        });
    }

    //- счетчик запросов на каждый IP в виде таблицы с колонкам и IP,
    //    кол-во запросов, время последнего запроса
    static void IPCounters(StringBuilder buf) {
//        buf.append("Requests per IP:<br>");
        buf.append("<TABLE border=\"1\">");
        buf.append("<caption>Requests per IP info table</caption><br>");
        buf.append("<TR align=\"center\"><td align=\"center\">IP</td> <td align=\"center\">Count </td><td>Time</td></TR><br>");
        Enumeration<String> enumeration = requestsPerIP.keys();

        while (enumeration.hasMoreElements()) {
            String key = enumeration.nextElement();
            Long requests = requestsPerIP.get(key).get();
            String lastRequestTime = new Date(lastRequestTimePerIP.get(key)).toString();
            buf.append(String.format("<tr><td>%s</td> <td align=\"center\">%d </td> <td>%s</td></tr> <br>", key, requests, lastRequestTime));
        }
    }

    static void redirectCounters(StringBuilder buf) {
        if (redirectsPerUrl.size() == 0)
            return;

        buf.append("<TABLE border=\"1\">");
        buf.append("<caption>Redirects info table</caption><br>");
        buf.append("<TR><td align=\"center\">URL</td> <td align=\"center\">Count </td></TR><br>");

        for (Enumeration<String> e = redirectsPerUrl.keys(); e.hasMoreElements(); ) {
            String url = e.nextElement();
            buf.append(String.format("<tr><td>%s</td> <td align=\"center\">%d </td></tr><br>", url, redirectsPerUrl.get(url).get()));
        }
        buf.append("</TABLE>");
    }
}