package com.example.transaction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserService {

    @Autowired
    private DataSource dataSource;

    // パターン1: @Transactional + DataSourceUtils.getConnection()
    @Transactional
    public Long createUserWithTransactionCorrect(String name, String email) {
        try {
            Connection conn = DataSourceUtils.getConnection(dataSource);
            
            // ユーザー作成
            PreparedStatement userStmt = conn.prepareStatement(
                "INSERT INTO users (name, email) VALUES (?, ?) RETURNING id"
            );
            userStmt.setString(1, name);
            userStmt.setString(2, email);
            
            ResultSet rs = userStmt.executeQuery();
            rs.next();
            long userId = rs.getLong(1);
            
            // 注文も作成
            PreparedStatement orderStmt = conn.prepareStatement(
                "INSERT INTO orders (user_id, product_name, amount) VALUES (?, ?, ?)"
            );
            orderStmt.setLong(1, userId);
            orderStmt.setString(2, "Test Product");
            orderStmt.setBigDecimal(3, new BigDecimal("100.00"));
            orderStmt.executeUpdate();
            
            // 意図的にエラーを発生させる
            if (name.equals("ERROR")) {
                throw new RuntimeException("Intentional error for testing");
            }
            
            return userId;
            
        } catch (SQLException e) {
            throw new RuntimeException("Database error", e);
        }
    }

    // パターン2: @Transactional + dataSource.getConnection() (間違った方法)
    @Transactional
    public Long createUserWithTransactionIncorrect(String name, String email) {
        Connection conn = null;
        try {
            // これは間違った方法 - 新しいConnectionが作られる
            conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            
            PreparedStatement userStmt = conn.prepareStatement(
                "INSERT INTO users (name, email) VALUES (?, ?) RETURNING id"
            );
            userStmt.setString(1, name);
            userStmt.setString(2, email);
            
            ResultSet rs = userStmt.executeQuery();
            rs.next();
            long userId = rs.getLong(1);
            
            PreparedStatement orderStmt = conn.prepareStatement(
                "INSERT INTO orders (user_id, product_name, amount) VALUES (?, ?, ?)"
            );
            orderStmt.setLong(1, userId);
            orderStmt.setString(2, "Test Product");
            orderStmt.setBigDecimal(3, new BigDecimal("100.00"));
            orderStmt.executeUpdate();
            
            if (name.equals("ERROR")) {
                throw new RuntimeException("Intentional error for testing");
            }
            
            conn.commit();
            return userId;
            
        } catch (Exception e) {
            try {
                if (conn != null) {
                    conn.rollback();
                }
            } catch (SQLException rollbackEx) {
                System.err.println("Rollback failed: " + rollbackEx.getMessage());
            }
            throw new RuntimeException("Database error", e);
        } finally {
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (SQLException closeEx) {
                System.err.println("Connection close failed: " + closeEx.getMessage());
            }
        }
    }

    // パターン3: @Transactionalなし + DataSourceUtils.getConnection()
    public Long createUserWithoutTransaction(String name, String email) {
        try {
            Connection conn = DataSourceUtils.getConnection(dataSource);
            
            PreparedStatement userStmt = conn.prepareStatement(
                "INSERT INTO users (name, email) VALUES (?, ?) RETURNING id"
            );
            userStmt.setString(1, name);
            userStmt.setString(2, email);
            
            ResultSet rs = userStmt.executeQuery();
            rs.next();
            long userId = rs.getLong(1);
            
            PreparedStatement orderStmt = conn.prepareStatement(
                "INSERT INTO orders (user_id, product_name, amount) VALUES (?, ?, ?)"
            );
            orderStmt.setLong(1, userId);
            orderStmt.setString(2, "Test Product");
            orderStmt.setBigDecimal(3, new BigDecimal("100.00"));
            orderStmt.executeUpdate();
            
            if (name.equals("ERROR")) {
                throw new RuntimeException("Intentional error for testing");
            }
            
            return userId;
            
        } catch (SQLException e) {
            throw new RuntimeException("Database error", e);
        }
    }

    // 長時間実行されるトランザクションをシミュレート
    @Transactional
    public void createUserWithLongRunningTransaction(String name, String email) {
        try {
            Connection conn = DataSourceUtils.getConnection(dataSource);
            
            PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO users (name, email) VALUES (?, ?)"
            );
            stmt.setString(1, name);
            stmt.setString(2, email);
            stmt.executeUpdate();
            
            // 長時間の処理をシミュレート
            Thread.sleep(5000);
            
        } catch (SQLException | InterruptedException e) {
            throw new RuntimeException("Transaction error", e);
        }
    }

    // 検証用のヘルパーメソッド
    public int countUsers() {
        try {
            Connection conn = DataSourceUtils.getConnection(dataSource);
            PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM users");
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public int countOrders() {
        try {
            Connection conn = DataSourceUtils.getConnection(dataSource);
            PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM orders");
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getAllUserNames() {
        List<String> names = new ArrayList<>();
        try {
            Connection conn = DataSourceUtils.getConnection(dataSource);
            PreparedStatement stmt = conn.prepareStatement("SELECT name FROM users");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
            return names;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}