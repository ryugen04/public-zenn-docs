package com.example.transaction;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TransactionVerificationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass")
            .withInitScript("schema.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private UserService userService;

    @Test
    @Order(1)
    @DisplayName("パターン1: @Transactional + DataSourceUtils - 正常ケース")
    void testCorrectTransactionSuccess() {
        int initialUserCount = userService.countUsers();
        int initialOrderCount = userService.countOrders();

        Long userId = userService.createUserWithTransactionCorrect("TestUser1", "test1@example.com");

        assertThat(userId).isNotNull().isPositive();
        assertThat(userService.countUsers()).isEqualTo(initialUserCount + 1);
        assertThat(userService.countOrders()).isEqualTo(initialOrderCount + 1);
    }

    @Test
    @Order(2)
    @DisplayName("パターン1: @Transactional + DataSourceUtils - エラー時のロールバック")
    void testCorrectTransactionRollback() {
        int initialUserCount = userService.countUsers();
        int initialOrderCount = userService.countOrders();

        assertThatThrownBy(() -> {
            userService.createUserWithTransactionCorrect("ERROR", "error@example.com");
        }).isInstanceOf(RuntimeException.class)
          .hasMessage("Intentional error for testing");

        // ロールバックされているので、データは変更されていない
        assertThat(userService.countUsers()).isEqualTo(initialUserCount);
        assertThat(userService.countOrders()).isEqualTo(initialOrderCount);
    }

    @Test
    @Order(3)
    @DisplayName("パターン3: @Transactionalなし - エラー時の動作")
    void testNoTransactionError() {
        int initialUserCount = userService.countUsers();
        int initialOrderCount = userService.countOrders();

        assertThatThrownBy(() -> {
            userService.createUserWithoutTransaction("ERROR", "error@example.com");
        }).isInstanceOf(RuntimeException.class)
          .hasMessage("Intentional error for testing");

        // トランザクションがないので、ユーザーは挿入されるが注文は挿入されない
        assertThat(userService.countUsers()).isEqualTo(initialUserCount + 1);
        assertThat(userService.countOrders()).isEqualTo(initialOrderCount);
    }

    @Test
    @Order(4)
    @DisplayName("プロセス強制終了シミュレーション")
    void testProcessTermination() throws InterruptedException {
        // 別スレッドでトランザクション実行
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                userService.createUserWithLongRunningTransaction("SlowUser", "slow@example.com");
            } catch (Exception e) {
                System.out.println("Transaction interrupted: " + e.getMessage());
            }
        });

        // 少し待ってからプロセス終了をシミュレート
        Thread.sleep(1000);
        
        // 実際の環境では System.exit(1) でプロセス終了
        // テストでは例外を投げてシミュレート
        future.cancel(true);
        
        // データベースの状態確認
        // 未コミットのデータは残らない
        Thread.sleep(2000);
        assertThat(userService.getAllUserNames()).doesNotContain("SlowUser");
    }
}