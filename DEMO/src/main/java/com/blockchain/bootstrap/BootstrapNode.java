package com.blockchain.bootstrap;

import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.Map;
import java.util.HashMap;

public class BootstrapNode {
    private final int port;
    private final Map<String, PeerInfo> peers;
    private final ServerSocket serverSocket;
    private final ExecutorService executorService;
    private final Gson gson;
    private volatile boolean running;

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
        this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName(host));
        this.executorService = Executors.newFixedThreadPool(10);
        this.gson = new Gson();
        this.running = true;
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
        try {
            String host = "10.39.183.141";  // 改为你的实际IP
            int port = 5000;
            
            BootstrapNode bootstrapNode = new BootstrapNode(host, port);
            bootstrapNode.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
} 