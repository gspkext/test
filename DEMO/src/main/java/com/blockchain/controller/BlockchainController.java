package com.blockchain.controller;

import com.google.gson.Gson;
import com.blockchain.bootstrap.BootstrapNode;
import com.blockchain.peer.PeerNode;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BlockchainController {

    private final Gson gson = new Gson();
    private final BootstrapNode bootstrapNode;

    public BlockchainController(BootstrapNode bootstrapNode) {
        this.bootstrapNode = bootstrapNode;
    }

    @PostMapping("/register")
    public String registerNode(@RequestBody Map<String, Object> request) {
        String peerHost = (String) request.get("host");
        int peerPort = ((Double) request.get("port")).intValue();
        String publicKey = (String) request.get("publicKey");

        // 处理节点注册逻辑
        Map<String, Object> response = new HashMap<>();
        try {
            bootstrapNode.handleRegister(request, null); // 直接调用注册方法
            response.put("status", "success");
            response.put("message", "节点注册成功");
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "注册失败: " + e.getMessage());
        }
        return gson.toJson(response);
    }

    @PostMapping("/trade")
    public String handleTrade(@RequestBody Map<String, Object> request) {
        // 处理交易逻辑
        Map<String, Object> response = new HashMap<>();
        try {
            // 这里可以调用 PeerNode 的交易处理方法
            // peerNode.handleTrade(request, out);
            response.put("status", "success");
            response.put("message", "交易处理成功");
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "交易处理失败: " + e.getMessage());
        }
        return gson.toJson(response);
    }
} 