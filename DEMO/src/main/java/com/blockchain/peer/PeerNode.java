package com.blockchain.peer;

import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class PeerNode {
    private final String host;
    private final int port;
    private final Map<String, PeerInfo> peers;
    private final ServerSocket serverSocket;
    private final ExecutorService executorService;
    private final KeyPair keyPair;
    private final Gson gson;
    private volatile boolean running;

    public static class PeerInfo {
        public String host;
        public int port;
        public String publicKey;
    }

    public PeerNode(String host, int port) throws Exception {
        this.host = host;
        this.port = port;
        this.peers = new ConcurrentHashMap<>();
        this.serverSocket = new ServerSocket(port);
        this.executorService = Executors.newFixedThreadPool(10);
        this.keyPair = generateKeyPair();
        this.gson = new Gson();
        this.running = true;
    }

    private KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        return keyGen.generateKeyPair();
    }

    public void start() {
        System.out.println("对等节点启动于: " + host + ":" + port);
        executorService.execute(this::listenForPeers);
    }

    public void connectToBootstrap(String bootstrapHost, int bootstrapPort) {
        try (Socket socket = new Socket(bootstrapHost, bootstrapPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            
            Map<String, Object> registration = new HashMap<>();
            registration.put("type", "register");
            registration.put("port", port);
            registration.put("publicKey", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
            
            out.println(gson.toJson(registration));
            
            String response = in.readLine();
            if (response != null) {
                Map<String, Object> peersList = gson.fromJson(response, Map.class);
                
                if ("peers_list".equals(peersList.get("type"))) {
                    updatePeersList((Map<String, PeerInfo>) peersList.get("peers"));
                }
            }
            
        } catch (IOException e) {
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

    private void updatePeersList(Map<String, PeerInfo> newPeers) {
        peers.clear();
        peers.putAll(newPeers);
        System.out.println("更新节点列表，当前共有 " + peers.size() + " 个节点");
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
            // 设置对等节点IP和端口
            String peerHost = "10.39.183.141";  // 改为对等节点机器的实际IP
            int peerPort = 0;  // 0表示自动分配端口
            
            // 设置引导节点的IP和端口
            String bootstrapHost = "10.39.183.141";  // 改为引导节点的实际IP
            int bootstrapPort = 5000;
            
            PeerNode peerNode = new PeerNode(peerHost, peerPort);
            peerNode.start();
            peerNode.connectToBootstrap(bootstrapHost, bootstrapPort);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
} 