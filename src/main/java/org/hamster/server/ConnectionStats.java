package org.hamster.server;

import java.util.Date;

//src_ip, URI, timestamp,  sent_bytes, received_bytes, speed (bytes/sec)
public class ConnectionStats {
    private String ip;
    private long timestamp;
    private long sentBytes;
    private long receivedBytes;
    private long duration;
    private String uri;

    public ConnectionStats() {
        this.timestamp = System.currentTimeMillis();
    }

    public void setMetrics(Long receivedBytes, Long sentBytes, Long duration) {
        this.sentBytes += sentBytes;
        this.receivedBytes += receivedBytes;
        this.duration += duration;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public Double speed() {
        return (sentBytes + receivedBytes) / (new Long(duration).doubleValue() / 1000);
    }

    public String getAsTableRow() {
        return String.format("<tr><td>%s</td><td>%s</td><td>%s</td><td>%d</td><td>%d</td><td>%f</td></tr>", ip, uri, new Date(timestamp).toString(), sentBytes, receivedBytes, speed());
    }
}
