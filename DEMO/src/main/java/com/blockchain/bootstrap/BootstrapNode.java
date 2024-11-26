package com.blockchain.bootstrap;

import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;

public class BootstrapNode {
    private static final int MAX_PORT_RETRY = 10;
    private static final int PORT_RETRY_INTERVAL = 1000; // 毫秒
    
    private final int port;
    private final Map<String, PeerInfo> peers;
    private ServerSocket serverSocket;
    private final ExecutorService executorService;
    private final Gson gson;
    private volatile boolean running;

    // 添加配置读取
    private static final Properties config = new Properties();
    static {
        try (InputStream input = BootstrapNode.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input != null) {
                config.load(input);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static class PeerInfo {
        public String host;
        public int port;
        public String publicKey;

        public PeerInfo(String host, int port, String publicKey) {
            this.host = host;
            this.port = port;
            this.publicKey = publicKey;
        }
    }

    public BootstrapNode(String host, int port) throws IOException {
        this.port = port;
        this.peers = new ConcurrentHashMap<>();
        this.gson = new Gson();
        this.running = true;
        
        this.executorService = Executors.newFixedThreadPool(10);
        
        initializeServerSocket(host, port);
    }

    private void initializeServerSocket(String host, int port) throws IOException {
        int retryCount = 0;
        while (retryCount < MAX_PORT_RETRY) {
            try {
                this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName(host));
                System.out.println("成功绑定端口: " + port);
                break;
            } catch (BindException e) {
                retryCount++;
                if (retryCount >= MAX_PORT_RETRY) {
                    throw new IOException("无法绑定端口 " + port + "，已重试 " + MAX_PORT_RETRY + " 次", e);
                }
                System.out.println("端口 " + port + " 被占用，等待重试... (" + retryCount + "/" + MAX_PORT_RETRY + ")");
                try {
                    Thread.sleep(PORT_RETRY_INTERVAL);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("端口绑定被中断", ie);
                }
            }
        }
    }

    public void start() {
        System.out.println("引导节点启动于端口: " + port);
        
        while (running && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                executorService.execute(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true)) {
            
            String message = in.readLine();
            if (message == null) return;
            
            Map<String, Object> request = gson.fromJson(message, Map.class);
            if (request == null || request.get("type") == null) {
                System.err.println("无效的请求格式");
                return;
            }
            
            if ("register".equals(request.get("type"))) {
                String peerHost = clientSocket.getInetAddress().getHostAddress();
                double portDouble = (double) request.get("port");
                int peerPort = (int) portDouble;
                String publicKey = (String) request.get("publicKey");
                
                PeerInfo peerInfo = new PeerInfo(peerHost, peerPort, publicKey);
                peers.put(peerHost + ":" + peerPort, peerInfo);
                
                Map<String, Object> response = new HashMap<>();
                response.put("type", "peers_list");
                response.put("peers", peers);
                
                out.println(gson.toJson(response));
                System.out.println("新节点注册: " + peerHost + ":" + peerPort);
            }
        } catch (IOException e) {
            System.err.println("处理客户端请求失败: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
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

    public static void main(String[] args) {
        String host = "0.0.0.0";
        int port = 5000;
        
        // 解析命令行参数
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--host=")) {
                host = args[i].substring(7);
            } else if (args[i].startsWith("--port=")) {
                port = Integer.parseInt(args[i].substring(7));
            }
        }

        try {
            // 检查端口是否可用
            if (!isPortAvailable(port)) {
                System.err.println("警告: 端口 " + port + " 已被占用");
                // 可以选择使用随机可用端口
                port = findAvailablePort(5000, 6000);
                System.out.println("使用新端口: " + port);
            }
            
            BootstrapNode bootstrapNode = new BootstrapNode(host, port);
            
            // 添加关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("正在关闭引导节点...");
                bootstrapNode.stop();
            }));
            
            bootstrapNode.start();
        } catch (IOException e) {
            System.err.println("引导节点启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // 检查端口是否可用
    private static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // 查找可用端口
    private static int findAvailablePort(int minPort, int maxPort) {
        for (int port = minPort; port <= maxPort; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        throw new RuntimeException("在范围 " + minPort + "-" + maxPort + " 内没有可用端口");
    }

    // 确保资源正确释放
    @Override
    protected void finalize() throws Throwable {
        try {
            stop();
        } finally {
            super.finalize();
        }
    }
} 