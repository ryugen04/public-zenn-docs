package com.example.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
class Pattern2Test {

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
    @DisplayName("パターン2: @Transactional + dataSource.getConnection() - エラー時の動作")
    void testIncorrectTransactionMethod() {
        int initialUserCount = userService.countUsers();
        int initialOrderCount = userService.countOrders();

        // エラー発生時の動作を確認
        assertThatThrownBy(() -> {
            userService.createUserWithTransactionIncorrect("ERROR", "error@example.com");
        }).isInstanceOf(RuntimeException.class);

        // パターン2では手動でロールバックしているが、
        // Springのトランザクション管理とは別なので、実際の動作を確認
        int afterUserCount = userService.countUsers();
        int afterOrderCount = userService.countOrders();
        
        System.out.println("初期ユーザー数: " + initialUserCount);
        System.out.println("エラー後ユーザー数: " + afterUserCount);
        System.out.println("初期注文数: " + initialOrderCount);
        System.out.println("エラー後注文数: " + afterOrderCount);
        
        // 手動ロールバックは効いているはず
        assertThat(afterUserCount).isEqualTo(initialUserCount);
        assertThat(afterOrderCount).isEqualTo(initialOrderCount);
    }
}