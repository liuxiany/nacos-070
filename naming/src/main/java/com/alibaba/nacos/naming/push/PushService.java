/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.naming.push;

import com.alibaba.fastjson.JSON;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.Switch;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.jackson.util.VersionUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * @author nacos
 */
public class PushService {

    public static final long ACK_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final int MAX_RETRY_TIMES = 1;
    private static BlockingQueue<String> QUEUE = new LinkedBlockingDeque<String>();

    private static volatile ConcurrentMap<String, Receiver.AckEntry> ackMap
            = new ConcurrentHashMap<String, Receiver.AckEntry>();

    private static ConcurrentMap<String, ConcurrentMap<String, PushClient>> clientMap
            = new ConcurrentHashMap<String, ConcurrentMap<String, PushClient>>();

    private static volatile ConcurrentHashMap<String, Long> udpSendTimeMap = new ConcurrentHashMap<String, Long>();

    public static volatile ConcurrentHashMap<String, Long> pushCostMap = new ConcurrentHashMap<String, Long>();

    private static int totalPush = 0;

    private static int failedPush = 0;

    private static ConcurrentHashMap<String, Long> lastPushMillisMap = new ConcurrentHashMap<>();

    private static DatagramSocket udpSocket;

    private static Map<String, Future> futureMap = new ConcurrentHashMap<>();
    private static ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("com.alibaba.nacos.naming.push.retransmitter");
            return t;
        }
    });

    private static ScheduledExecutorService udpSender = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("com.alibaba.nacos.naming.push.udpSender");
            return t;
        }
    });


    static {
        try {
            udpSocket = new DatagramSocket();

            Sender sender;
            Receiver receiver;

            sender = new Sender();

            Thread outThread;
            Thread inThread;

            outThread = new Thread(sender);
            outThread.setDaemon(true);
            outThread.setName("com.alibaba.nacos.naming.push.sender");
            outThread.start();

            receiver = new Receiver();

            inThread = new Thread(receiver);
            inThread.setDaemon(true);
            inThread.setName("com.alibaba.nacos.naming.push.receiver");
            inThread.start();

            executorService.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    try {
                        removeClientIfZombie();
                    } catch (Throwable e) {
                        Loggers.PUSH.warn("VIPSRV-PUSH", "failed to remove client zombied");
                    }
                }
            }, 0, 20, TimeUnit.SECONDS);

        } catch (SocketException e) {
            Loggers.SRV_LOG.error("VIPSRV-PUSH", "failed to init push service");
        }
    }

    public static int getTotalPush() {
        return totalPush;
    }

    public static void addClient(String dom,
                                 String clusters,
                                 String agent,
                                 InetSocketAddress socketAddr,
                                 DataSource dataSource,
                                 String tenant,
                                 String app) {

        PushClient client = new PushService.PushClient(dom,
                clusters,
                agent,
                socketAddr,
                dataSource,
                tenant,
                app);
        addClient(client);
    }

    public static void addClient(PushClient client) {
        // client is stored by key 'dom' because notify event is driven by dom change
        ConcurrentMap<String, PushClient> clients = clientMap.get(client.getDom());
        if (clients == null) {
            clientMap.putIfAbsent(client.getDom(), new ConcurrentHashMap<String, PushClient>(1024));
            clients = clientMap.get(client.getDom());
        }

        PushClient oldClient = clients.get(client.toString());
        if (oldClient != null) {
            oldClient.refresh();
        } else {
            PushClient res = clients.putIfAbsent(client.toString(), client);
            if (res != null) {
                Loggers.PUSH.warn("client:" + res.getAddrStr() + " already associated with key " + res.toString());
            }
            Loggers.PUSH.debug("client: " + client.getAddrStr() + " added for dom: " + client.getDom());
        }
    }

    public static void removeClientIfZombie() {

        int size = 0;
        for (Map.Entry<String, ConcurrentMap<String, PushClient>> entry : clientMap.entrySet()) {
            ConcurrentMap<String, PushClient> clientConcurrentMap = entry.getValue();
            for (Map.Entry<String, PushClient> entry1 : clientConcurrentMap.entrySet()) {
                PushClient client = entry1.getValue();
                if (client.zombie()) {
                    clientConcurrentMap.remove(entry1.getKey());
                }
            }

            size += clientConcurrentMap.size();
        }

        Loggers.PUSH.info("VIPSRV-PUSH", "clientMap size: " + size);

    }

    private static Receiver.AckEntry prepareAckEntry(PushClient client, byte[] dataBytes, Map<String, Object> data,
                                                     long lastRefTime) {
        String key = getACKKey(client.getSocketAddr().getAddress().getHostAddress(),
                client.getSocketAddr().getPort(),
                lastRefTime);
        DatagramPacket packet = null;
        try {
            packet = new DatagramPacket(dataBytes, dataBytes.length, client.socketAddr);
            Receiver.AckEntry ackEntry = new Receiver.AckEntry(key, packet);
            ackEntry.data = data;

            // we must store the key be fore send, otherwise there will be a chance the
            // ack returns before we put in
            ackEntry.data = data;

            return ackEntry;
        } catch (Exception e) {
            Loggers.PUSH.error("VIPSRV-PUSH", "failed to prepare data: [" + data + "] to client: [" +
                    client.getSocketAddr() + "]", e);
        }

        return null;
    }

    public static String getPushCacheKey(String dom, String clientIP, String agent) {
        return dom + UtilsAndCommons.CACHE_KEY_SPLITER + agent;
    }

    /**
     * 当节点发生改变时（PUT,POST,DEL）
     * 会触发该事件向客户端发送事件推送
     * @param dom
     */
    public static void domChanged(final String dom) {
        if (futureMap.containsKey(dom)) {
            return;
        }
        Future future = udpSender.schedule(new Runnable() {
            @Override
            public void run() {
                try {//推送更变消息给订阅者 name=com.sam.service.UserService，默认会重新发送2次
                    Loggers.PUSH.info(dom + " is changed, add it to push queue.");
                    ConcurrentMap<String, PushClient> clients = clientMap.get(dom);
                    if (MapUtils.isEmpty(clients)) {
                        return;
                    }

                    Map<String, Object> cache = new HashMap<>(16);
                    long lastRefTime = System.nanoTime();
                    for (PushClient client : clients.values()) {
                        if (client.zombie()) {
                            Loggers.PUSH.debug("client is zombie: " + client.toString());
                            clients.remove(client.toString());
                            Loggers.PUSH.debug("client is zombie: " + client.toString());
                            continue;
                        }

                        Receiver.AckEntry ackEntry;
                        Loggers.PUSH.debug("push dom: " + dom + " to cleint: " + client.toString());
                        String key = getPushCacheKey(dom, client.getIp(), client.getAgent());
                        byte[] compressData = null;
                        Map<String, Object> data = null;
                        if (Switch.getPushCacheMillis() >= 20000 && cache.containsKey(key)) {
                            org.javatuples.Pair pair = (org.javatuples.Pair) cache.get(key);
                            compressData = (byte[]) (pair.getValue0());
                            data = (Map<String, Object>) pair.getValue1();

                            Loggers.PUSH.debug("PUSH-CACHE", "cache hit: " + dom + ":" + client.getAddrStr());
                        }

                        if (compressData != null) {//预发送一次
                            ackEntry = prepareAckEntry(client, compressData, data, lastRefTime);
                        } else {
                            ackEntry = prepareAckEntry(client, prepareHostsData(client), lastRefTime);
                            if (ackEntry != null) {
                                cache.put(key, new org.javatuples.Pair<>(ackEntry.origin.getData(), ackEntry.data));
                            }
                        }

                        Loggers.PUSH.info("dom: " + client.getDom() + " changed, schedule push for: "
                                + client.getAddrStr() + ", agent: " + client.getAgent() + ", key: "
                                + (ackEntry == null ? null : ackEntry.key));

                        udpPush(ackEntry);//发送udp推送消息
                    }
                } catch (Exception e) {
                    Loggers.PUSH.error("VIPSRV-PUSH", "failed to push dom: " + dom + " to cleint", e);

                } finally {
                    futureMap.remove(dom);
                }

            }
        }, 1000, TimeUnit.MILLISECONDS);

        futureMap.put(dom, future);
    }

    public static boolean canEnablePush(String agent) {
        ClientInfo clientInfo = new ClientInfo(agent);

        if (ClientInfo.ClientType.JAVA == clientInfo.type
                && clientInfo.version.compareTo(VersionUtil.parseVersion(Switch.getPushJavaVersion())) >= 0) {
            return true;
        } else if (ClientInfo.ClientType.DNS == clientInfo.type
                && clientInfo.version.compareTo(VersionUtil.parseVersion(Switch.getPushPythonVersion())) >= 0) {
            return true;
        } else if (ClientInfo.ClientType.C == clientInfo.type
                && clientInfo.version.compareTo(VersionUtil.parseVersion(Switch.getPushCVersion())) >= 0) {
            return true;
        } else if (ClientInfo.ClientType.GO == clientInfo.type
                   && clientInfo.version.compareTo(VersionUtil.parseVersion(Switch.getPushGoVersion())) >= 0) {
            return true;
        }

        return false;
    }

    public static List<Receiver.AckEntry> getFailedPushes() {
        return new ArrayList<Receiver.AckEntry>(ackMap.values());
    }

    public static int getFailedPushCount() {
        return ackMap.size() + failedPush;
    }

    public static void resetPushState() {
        ackMap.clear();
    }

    public static class PushClient {
        private String dom;
        private String clusters;
        private String agent;
        private String tenant;
        private String app;
        private InetSocketAddress socketAddr;
        private DataSource dataSource;
        private Map<String, String[]> params;

        public Map<String, String[]> getParams() {
            return params;
        }

        public void setParams(Map<String, String[]> params) {
            this.params = params;
        }

        public long lastRefTime = System.currentTimeMillis();

        public PushClient(String dom
                , String clusters
                , String agent
                , InetSocketAddress socketAddr
                , DataSource dataSource) {
            this.dom = dom;
            this.clusters = clusters;
            this.agent = agent;
            this.socketAddr = socketAddr;
            this.dataSource = dataSource;
        }

        public PushClient(String dom,
                          String clusters,
                          String agent,
                          InetSocketAddress socketAddr,
                          DataSource dataSource,
                          String tenant,
                          String app) {
            this.dom = dom;
            this.clusters = clusters;
            this.agent = agent;
            this.socketAddr = socketAddr;
            this.dataSource = dataSource;
            this.tenant = tenant;
            this.app = app;
        }

        public DataSource getDataSource() {
            return dataSource;
        }

        public PushClient(InetSocketAddress socketAddr) {
            this.socketAddr = socketAddr;
        }

        public boolean zombie() {
            return System.currentTimeMillis() - lastRefTime > Switch.getPushCacheMillis(dom);
        }

        @Override
        public String toString() {
            return "dom: " + dom
                    + ", clusters: " + clusters
                    + ", ip: " + socketAddr.getAddress().getHostAddress()
                    + ", port: " + socketAddr.getPort()
                    + ", agent: " + agent;
        }

        public String getAgent() {
            return agent;
        }

        public String getAddrStr() {
            return socketAddr.getAddress().getHostAddress() + ":" + socketAddr.getPort();
        }

        public String getIp() {
            return socketAddr.getAddress().getHostAddress();
        }

        @Override
        public int hashCode() {
            return Objects.hash(dom, clusters, socketAddr);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof PushClient)) {
                return false;
            }

            PushClient other = (PushClient) obj;

            return dom.equals(other.dom) && clusters.equals(other.clusters) && socketAddr.equals(other.socketAddr);
        }

        public String getClusters() {
            return clusters;
        }

        public void setClusters(String clusters) {
            this.clusters = clusters;
        }

        public String getDom() {
            return dom;
        }

        public void setDom(String dom) {
            this.dom = dom;
        }

        public String getTenant() {
            return tenant;
        }

        public void setTenant(String tenant) {
            this.tenant = tenant;
        }

        public String getApp() {
            return app;
        }

        public void setApp(String app) {
            this.app = app;
        }

        public InetSocketAddress getSocketAddr() {
            return socketAddr;
        }

        public void refresh() {
            lastRefTime = System.currentTimeMillis();
        }
    }

    private static byte[] compressIfNecessary(byte[] dataBytes) throws IOException {
        // enable compression when data is larger than 1KB
        int maxDataSizeUncompress = 1024;
        if (dataBytes.length < maxDataSizeUncompress) {
            return dataBytes;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(out);
        gzip.write(dataBytes);
        gzip.close();

        return out.toByteArray();
    }

    private static Map<String, Object> prepareHostsData(PushClient client) throws Exception {
        Map<String, Object> cmd = new HashMap<String, Object>(2);
        cmd.put("type", "dom");
        cmd.put("data", client.getDataSource().getData(client));

        return cmd;
    }

    private static Receiver.AckEntry prepareAckEntry(PushClient client, Map<String, Object> data, long lastRefTime) {
        if (MapUtils.isEmpty(data)) {
            Loggers.PUSH.error("VIPSRV-PUSH", "pushing empty data for client is not allowed: " + client);
            return null;
        }

        data.put("lastRefTime", lastRefTime);

        // we apply lastRefTime as sequence num for further ack
        String key = getACKKey(client.getSocketAddr().getAddress().getHostAddress(),
                client.getSocketAddr().getPort(),
                lastRefTime);

        String dataStr = JSON.toJSONString(data);

        try {
            byte[] dataBytes = dataStr.getBytes("UTF-8");
            dataBytes = compressIfNecessary(dataBytes);

            DatagramPacket packet = new DatagramPacket(dataBytes, dataBytes.length, client.socketAddr);

            // we must store the key be fore send, otherwise there will be a chance the
            // ack returns before we put in
            Receiver.AckEntry ackEntry = new Receiver.AckEntry(key, packet);
            ackEntry.data = data;

            return ackEntry;
        } catch (Exception e) {
            Loggers.PUSH.error("VIPSRV-PUSH", "failed to prepare data: [" + data + "] to client: [" +
                    client.getSocketAddr() + "]", e);
            return null;
        }
    }

    private static Receiver.AckEntry udpPush(Receiver.AckEntry ackEntry) {
        if (ackEntry == null) {
            Loggers.PUSH.error("VIPSRV-PUSH", "ackEntry is null ");
            return null;
        }

        if (ackEntry.getRetryTimes() > MAX_RETRY_TIMES) {//重发次数超过默认的“1”次后则取消重发,也就是最多会重发2次
            Loggers.PUSH.warn("max re-push times reached, retry times " + ackEntry.retryTimes + ", key: " + ackEntry.key);
            ackMap.remove(ackEntry.key);
            failedPush += 1;
            return ackEntry;
        }

        try {
            if (!ackMap.containsKey(ackEntry.key)) {
                totalPush++;
            }
            ackMap.put(ackEntry.key, ackEntry);
            udpSendTimeMap.put(ackEntry.key, System.currentTimeMillis());

            Loggers.PUSH.info("send udp packet: " + ackEntry.key);
            udpSocket.send(ackEntry.origin);

            ackEntry.increaseRetryTime();

            executorService.schedule(new Retransmitter(ackEntry), TimeUnit.NANOSECONDS.toMillis(ACK_TIMEOUT_NANOS),
                    TimeUnit.MILLISECONDS);//默认会重发

            return ackEntry;
        } catch (Exception e) {
            Loggers.PUSH.error("VIPSRV-PUSH", "failed to push data: [" + ackEntry.data + "] to client: [" +
                    ackEntry.origin.getAddress().getHostAddress() + "]", e);
            ackMap.remove(ackEntry.key);
            failedPush += 1;

            return null;
        }
    }

    private static class Sender implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    String dom;
                    try {
                        dom = QUEUE.take();
                    } catch (InterruptedException e) {
                        continue; //ignore
                    }

                    if (System.currentTimeMillis() - lastPushMillisMap.get(dom) < 1000) {
                        QUEUE.add(dom);
                        continue;
                    }

                    lastPushMillisMap.put(dom, System.currentTimeMillis());

                    ConcurrentMap<String, PushClient> clients = clientMap.get(dom);
                    if (MapUtils.isEmpty(clients)) {
                        continue;
                    }

                    for (PushClient client : clients.values()) {
                        if (client.zombie()) {
                            clients.remove(client.toString());
                            continue;
                        }
                        Loggers.PUSH.debug("push dom: " + dom + " to cleint");
                        Receiver.AckEntry ackEntry = prepareAckEntry(client, prepareHostsData(client), System.nanoTime());
                        Loggers.PUSH.info("sender", "dom: " + client.getDom() + " changed, schedule push for: "
                                + client.getAddrStr() + ", agent: " + client.getAgent() + ", key: " + ackEntry.key);
                        udpPush(ackEntry);
                    }
                } catch (Throwable t) {
                    Loggers.PUSH.error("VIPSRV-PUSH", "failed, caused by: ", t);
                }
            }
        }
    }

    private static String getACKKey(String host, int port, long lastRefTime) {
        return StringUtils.strip(host) + "," + port + "," + lastRefTime;
    }

    public static class Retransmitter implements Runnable {
        Receiver.AckEntry ackEntry;

        public Retransmitter(Receiver.AckEntry ackEntry) {
            this.ackEntry = ackEntry;
        }

        @Override
        public void run() {
            if (ackMap.containsKey(ackEntry.key)) {
                Loggers.PUSH.info("retry to push data, key: " + ackEntry.key);
                udpPush(ackEntry);
            }
        }
    }

    public static class Receiver implements Runnable {
        @Override
        public void run() {
            while (true) {
                byte[] buffer = new byte[1024 * 64];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                try {
                    udpSocket.receive(packet);

                    String json = new String(packet.getData(), 0, packet.getLength(), Charset.forName("UTF-8")).trim();
                    AckPacket ackPacket = JSON.parseObject(json, AckPacket.class);

                    InetSocketAddress socketAddress = (InetSocketAddress) packet.getSocketAddress();
                    String ip = socketAddress.getAddress().getHostAddress();
                    int port = socketAddress.getPort();

                    if (System.nanoTime() - ackPacket.lastRefTime > ACK_TIMEOUT_NANOS) {
                        Loggers.PUSH.warn("ack takes too long from" + packet.getSocketAddress()
                                + " ack json: " + json);
                    }

                    String ackKey = getACKKey(ip, port, ackPacket.lastRefTime);
                    AckEntry ackEntry = ackMap.remove(ackKey);
                    if (ackEntry == null) {
                        throw new IllegalStateException("unable to find ackEntry for key: " + ackKey
                                + ", ack json: " + json);
                    }

                    long pushCost = System.currentTimeMillis() - udpSendTimeMap.get(ackKey);

                    Loggers.PUSH.info("received ack: " + json + " from: " + ip
                            + ":" + port + ", cost: " + pushCost + "ms" + ", unacked: " + ackMap.size() +
                            ",total push: " + totalPush);

                    pushCostMap.put(ackKey, pushCost);

                    udpSendTimeMap.remove(ackKey);

                } catch (Throwable e) {
                    Loggers.PUSH.error("VIPSRV-PUSH", "error while receiving ack data", e);
                }
            }
        }

        public static class AckEntry {

            public AckEntry(String key, DatagramPacket packet) {
                this.key = key;
                this.origin = packet;
            }

            public void increaseRetryTime() {
                retryTimes.incrementAndGet();
            }

            public int getRetryTimes() {
                return retryTimes.get();
            }

            public String key;
            public DatagramPacket origin;
            private AtomicInteger retryTimes = new AtomicInteger(0);
            public Map<String, Object> data;
        }

        public static class AckPacket {
            public String type;
            public long lastRefTime;

            public String data;
        }
    }


}
