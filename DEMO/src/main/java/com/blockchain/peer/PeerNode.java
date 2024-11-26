package com.blockchain.peer;

import java.nio.charset.StandardCharsets;
import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.web.client.RestTemplate;
import java.util.logging.Logger;

public class PeerNode {
    private static final int DEFAULT_PORT = 0;
    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int THREAD_POOL_SIZE = 10;
    private static final String DIFFICULTY_TARGET = "0000";
    
    private static final Logger logger = Logger.getLogger(PeerNode.class.getName());
    
    private final String host;
    private final int port;
    private final Map<String, PeerInfo> peers;
    private final ServerSocket serverSocket;
    private final ExecutorService executorService;
    private final KeyPair keyPair;
    private final Gson gson;
    private volatile boolean running;
    private final Mining mining;
    private final RestTemplate restTemplate;
    private final BlockService blockService;
    private final PendingService pendingService;

    public static class PeerInfo {
        private String host;
        private int port;
        private String publicKey;

        public PeerInfo() {
            this.host = "";
            this.port = 0;
            this.publicKey = "";
        }

        public PeerInfo(String host, int port, String publicKey) {
            this.host = host;
            this.port = port;
            this.publicKey = publicKey;
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    }

    public static class Block {
        private String blockIndex;
        private String blockHash;
        private String path;

        public String workString() {
            return blockIndex + path;
        }

        public String getBlockHash() {
            return blockHash;
        }

        public void setBlockHash(String blockHash) {
            this.blockHash = blockHash;
        }
    }

    public static class TradeObject {
        private String from;
        private String to;
        private String type;
        private String content;
        private String sign;
        private String hashNo;
        private String jsoncreatetime;
        private String objToString;

        public void setHashNo(String hashNo) {
            this.hashNo = hashNo;
        }
    }

    public static class Mining {
        private volatile boolean isWork;
        private volatile boolean updateComplete;

        public boolean isWork() {
            return isWork;
        }

        public void setWork(boolean work) {
            isWork = work;
        }

        public boolean isUpdateComplete() {
            return updateComplete;
        }

        public void setUpdateComplete(boolean updateComplete) {
            this.updateComplete = updateComplete;
        }
    }

    public static class BlockService {
        public void saveBlock(Block block) {
            // 实现保存区块的逻辑
        }

        public int getCurrentBlockIndex() {
            return 0;
        }
    }

    public static class PendingService {
        public void savePending(TradeObject tradeObject) {
            // 实现保存待处理交易的逻辑
        }
    }

    public PeerNode(String host, int port) throws Exception {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        
        this.host = host;
        this.port = port;
        this.peers = new ConcurrentHashMap<>();
        this.serverSocket = new ServerSocket(port);
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.keyPair = generateKeyPair();
        this.gson = new Gson();
        this.running = true;
        this.mining = new Mining();
        this.restTemplate = new RestTemplate();
        this.blockService = new BlockService();
        this.pendingService = new PendingService();
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    public void start() {
        logger.info("对等节点启动于: " + host + ":" + port);
        
        // 启动服务器监听
        executorService.execute(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executorService.execute(() -> handleClient(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        logger.severe("接受客户端连接失败: " + e.getMessage());
                    }
                }
            }
        });

        // 定期打印节点列表
        executorService.execute(() -> {
            while (running) {
                try {
                    Thread.sleep(5000); // 每5秒打印一次
                    logger.info("当前节点列表 (" + peers.size() + " 个节点):");
                    for (Map.Entry<String, PeerInfo> entry : peers.entrySet()) {
                        PeerInfo peer = entry.getValue();
                        logger.info(String.format("节点: %s:%d", peer.getHost(), peer.getPort()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    public void connectToBootstrap(String bootstrapHost, int bootstrapPort) {
        int retryCount = 0;
        int maxRetries = 3;
        
        while (retryCount < maxRetries) {
            try (Socket socket = new Socket(bootstrapHost, bootstrapPort)) {
                // 准备注册消息
                Map<String, Object> registration = new HashMap<>();
                registration.put("type", "register");
                registration.put("port", this.port);
                registration.put("publicKey", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
                
                // 发送注册请求
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                out.println(gson.toJson(registration));
                logger.info("发送注册请求到引导节点: " + bootstrapHost + ":" + bootstrapPort);
                
                // 接收响应
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String response = in.readLine();
                if (response != null) {
                    Map<String, Object> responseMap = gson.fromJson(response, Map.class);
                    if ("peers_list".equals(responseMap.get("type"))) {
                        // 更新节点列表
                        updatePeersList(responseMap);
                        logger.info("成功注册并获取节点列表，当前共有 " + peers.size() + " 个节点");
                        return; // 成功连接后返回
                    }
                }
                
            } catch (IOException e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    logger.severe("连接引导节点失败，已重试 " + maxRetries + " 次");
                    break;
                }
                try {
                    Thread.sleep(1000 * retryCount);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void updatePeersList(Map<String, Object> response) {
        try {
            Map<String, Object> peersMap = (Map<String, Object>) response.get("peers");
            if (peersMap != null) {
                peers.clear();
                for (Map.Entry<String, Object> entry : peersMap.entrySet()) {
                    Map<String, Object> peerData = (Map<String, Object>) entry.getValue();
                    PeerInfo peerInfo = new PeerInfo();
                    peerInfo.setHost((String) peerData.get("host"));
                    peerInfo.setPort(((Double) peerData.get("port")).intValue());
                    peerInfo.setPublicKey((String) peerData.get("publicKey"));
                    peers.put(entry.getKey(), peerInfo);
                }
            }
        } catch (Exception e) {
            logger.severe("更新节点列表失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void listenForPeers() {
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                executorService.execute(() -> handlePeerConnection(clientSocket));
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handlePeerConnection(Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
            
            String message = in.readLine();
            if (message != null) {
                // 处理来自其他对等节点的消息
                System.out.println("收到消息: " + message);
            }
            
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void stop() {
        running = false;
        executorService.shutdown();
        try {
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void startMining() {
        if (mining.isUpdateComplete()) {
            mining.setWork(true);
            executorService.execute(this::miningWork);
        } else {
            System.out.println("等待区块更新");
        }
    }

    public void processTrade(TradeObject tradeObject) {
        // 验证交易签名
        if (!verifyTradeSignature(tradeObject)) {
            System.out.println("交易签名验证失败");
            return;
        }

        // 生成交易编号
        String tradeNo = generateTradeNo(tradeObject);
        tradeObject.setHashNo(tradeNo);

        // 广播交易到其他节点
        broadcastTrade(tradeObject);
        
        // 保存到待处理交易池
        pendingService.savePending(tradeObject);
    }

    public boolean verifyBlock(Block block) {
        String blockHash = calculateBlockHash(block.workString());
        return blockHash.equals(block.getBlockHash()) && 
               blockHash.startsWith(getDifficultyTarget());
    }

    private void broadcastTrade(TradeObject tradeObject) {
        for (Map.Entry<String, PeerInfo> entry : peers.entrySet()) {
            PeerInfo peer = entry.getValue();
            try {
                // 发送交易到其他节点
                String url = String.format("http://%s:%d/trade", peer.getHost(), peer.getPort());
                restTemplate.postForEntity(url, tradeObject, String.class);
            } catch (Exception e) {
                System.out.println("广播交易到节点失败: " + peer.getHost() + ":" + peer.getPort());
            }
        }
    }

    private void miningWork() {
        while (mining.isWork()) {
            // 实现挖矿逻辑
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean verifyTradeSignature(TradeObject tradeObject) {
        // 实现交易签名验证逻辑
        return true;
    }

    private String generateTradeNo(TradeObject tradeObject) {
        // 生成交易编号
        return UUID.randomUUID().toString();
    }

    private String calculateBlockHash(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String getDifficultyTarget() {
        return "0000";
    }

    private void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
            
            String message = in.readLine();
            if (message == null) return;
            
            Map<String, Object> request = gson.fromJson(message, Map.class);
            String type = (String) request.get("type");
            
            switch (type) {
                case "register":
                    handleRegister(request, out);
                    break;
                case "trade":
                    handleTrade(request, out);
                    break;
                case "block":
                    handleBlock(request, out);
                    break;
                default:
                    System.out.println("未知的消息类型: " + type);
            }
            
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleRegister(Map<String, Object> request, PrintWriter out) {
        try {
            String peerKey = host + ":" + request.get("port");
            if (peers.containsKey(peerKey)) {
                Map<String, Object> response = new HashMap<>();
                response.put("type", "error");
                response.put("message", "节点已注册");
                out.println(gson.toJson(response));
                return;
            }
            double portDouble = (double) request.get("port");
            int peerPort = (int) portDouble;
            String publicKey = (String) request.get("publicKey");
            
            PeerInfo peerInfo = new PeerInfo();
            peerInfo.setHost(host);
            peerInfo.setPort(peerPort);
            peerInfo.setPublicKey(publicKey);
            
            peers.put(peerKey, peerInfo);
            
            Map<String, Object> response = new HashMap<>();
            response.put("type", "peers_list");
            response.put("peers", peers);
            
            out.println(gson.toJson(response));
            logger.info("新节点注册: " + peerKey);
            logger.info("当前共有 " + peers.size() + " 个节点");
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("type", "error");
            response.put("message", "注册失败: " + e.getMessage());
            out.println(gson.toJson(response));
        }
    }

    private void handleTrade(Map<String, Object> request, PrintWriter out) {
        try {
            TradeObject trade = gson.fromJson(gson.toJson(request.get("trade")), TradeObject.class);
            if (verifyTradeSignature(trade)) {
                String tradeNo = generateTradeNo(trade);
                trade.setHashNo(tradeNo);
                pendingService.savePending(trade);
                
                Map<String, Object> response = new HashMap<>();
                response.put("type", "trade_response");
                response.put("status", "success");
                response.put("tradeNo", tradeNo);
                
                out.println(gson.toJson(response));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleBlock(Map<String, Object> request, PrintWriter out) {
        try {
            Block block = gson.fromJson(gson.toJson(request.get("block")), Block.class);
            if (verifyBlock(block)) {
                blockService.saveBlock(block);
                
                Map<String, Object> response = new HashMap<>();
                response.put("type", "block_response");
                response.put("status", "success");
                
                out.println(gson.toJson(response));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        String peerHost = DEFAULT_HOST;
        int peerPort = DEFAULT_PORT;
        String bootstrapHost = DEFAULT_HOST;
        int bootstrapPort = 5000;
        
        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--peer-host=")) {
                peerHost = args[i].substring(12);
            } else if (args[i].startsWith("--peer-port=")) {
                peerPort = Integer.parseInt(args[i].substring(12));
            } else if (args[i].startsWith("--bootstrap-host=")) {
                bootstrapHost = args[i].substring(17);
            } else if (args[i].startsWith("--bootstrap-port=")) {
                bootstrapPort = Integer.parseInt(args[i].substring(17));
            }
        }

        try {
            PeerNode peerNode = new PeerNode(peerHost, peerPort);
            
            // 添加关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("正在关闭对��节点...");
                peerNode.stop();
            }));
            
            peerNode.start();
            peerNode.connectToBootstrap(bootstrapHost, bootstrapPort);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 添加心跳检测
    private void startHeartbeat() {
        executorService.execute(() -> {
            while (running) {
                try {
                    Thread.sleep(30000); // 每30秒检查一次
                    checkPeersStatus();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private void checkPeersStatus() {
        List<String> deadPeers = new ArrayList<>();
        for (Map.Entry<String, PeerInfo> entry : peers.entrySet()) {
            PeerInfo peer = entry.getValue();
            if (!isPeerAlive(peer)) {
                deadPeers.add(entry.getKey());
            }
        }
        // 移除死亡节点
        deadPeers.forEach(peers::remove);
    }

    private boolean isPeerAlive(PeerInfo peer) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peer.getHost(), peer.getPort()), 3000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
} 