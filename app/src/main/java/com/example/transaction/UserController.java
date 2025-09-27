package com.example.transaction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/pattern1")
    public Map<String, Object> testPattern1(@RequestParam String name, @RequestParam String email) {
        Map<String, Object> response = new HashMap<>();
        try {
            Long userId = userService.createUserWithTransactionCorrect(name, email);
            response.put("success", true);
            response.put("userId", userId);
            response.put("message", "パターン1: @Transactional + DataSourceUtils.getConnection() 成功");
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("message", "パターン1: エラー発生 - ロールバックされました");
        }
        response.put("userCount", userService.countUsers());
        response.put("orderCount", userService.countOrders());
        return response;
    }

    @PostMapping("/pattern2")
    public Map<String, Object> testPattern2(@RequestParam String name, @RequestParam String email) {
        Map<String, Object> response = new HashMap<>();
        try {
            Long userId = userService.createUserWithTransactionIncorrect(name, email);
            response.put("success", true);
            response.put("userId", userId);
            response.put("message", "パターン2: @Transactional + dataSource.getConnection() 成功");
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("message", "パターン2: エラー発生 - 手動ロールバック実行");
        }
        response.put("userCount", userService.countUsers());
        response.put("orderCount", userService.countOrders());
        return response;
    }

    @PostMapping("/pattern3")
    public Map<String, Object> testPattern3(@RequestParam String name, @RequestParam String email) {
        Map<String, Object> response = new HashMap<>();
        try {
            Long userId = userService.createUserWithoutTransaction(name, email);
            response.put("success", true);
            response.put("userId", userId);
            response.put("message", "パターン3: @Transactionalなし 成功");
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("message", "パターン3: エラー発生 - トランザクションなしのため部分的にデータが残る可能性");
        }
        response.put("userCount", userService.countUsers());
        response.put("orderCount", userService.countOrders());
        return response;
    }

    @GetMapping("/count")
    public Map<String, Integer> getCounts() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("users", userService.countUsers());
        counts.put("orders", userService.countOrders());
        return counts;
    }

    @GetMapping("/users")
    public Map<String, Object> getAllUsers() {
        Map<String, Object> response = new HashMap<>();
        response.put("users", userService.getAllUserNames());
        response.put("count", userService.countUsers());
        return response;
    }
}