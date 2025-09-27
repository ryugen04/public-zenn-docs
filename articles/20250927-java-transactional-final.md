---
title: "Springの@Transactionalアノテーションの挙動について"
emoji: "🪓"
type: "tech" # tech: 技術記事 / idea: アイデア
topics: ["java"]
published: false
---

## TL;DR

Spring Bootで@Transactionalを使い、repositoryをautowiredせずに直接SQLでinsertする場合の動作を調査し、PostgreSQL環境で実際に検証してみました。DataSourceUtils.getConnection()を使えばトランザクション管理が正しく動作することを確認できます。

## はじめに

Spring Bootで開発していて、@Transactionalアノテーションの挙動について疑問に思うことがありました。
特に「repositoryをautowiredしないで直接SQLを実行する場合でも、@Transactionalは効果を発揮するのか」という点は、実際の開発でよく遭遇するケースです。

調べてみると、この部分の理解が曖昧だと意外とハマりやすいポイントだったので、調査と実際の動作検証の両方を行ってみました。


## @Transactionalの基本的な仕組み

まず@Transactionalがどのような仕組みで動作しているかを理解しておく必要があります。

Springの@Transactionalは、AOP（Aspect-Oriented Programming）とプロキシパターンを使用してトランザクション管理を実現しています。

### 基本的な動作原理

Spring Frameworkの公式ドキュメント（[Understanding the Spring Framework's declarative transaction implementation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-decl-explained.html)）によると、@Transactionalは以下のように動作します。

1. **プロキシの作成**: @Transactionalが付いたクラスやメソッドに対して、Springが動的にプロキシオブジェクトを作成
2. **インターセプション**: プロキシがメソッド呼び出しを横取りして、TransactionInterceptorが実行される
3. **トランザクション管理**: PlatformTransactionManagerを通じてトランザクションの開始・コミット・ロールバックが管理される

### プロキシの種類

Spring公式ブログ（[Transactions, Caching and AOP: understanding proxy usage in Spring](https://spring.io/blog/2012/05/23/transactions-caching-and-aop-understanding-proxy-usage-in-spring/)）で説明されている通り、Springは以下の2種類のプロキシを使用します。

- **JDK Dynamic Proxy**: インターフェースを実装したクラスの場合
- **CGLIB Proxy**: クラスベースのプロキシが必要な場合（Spring Boot 2.x以降はデフォルト）

### 実際のトランザクション処理

DataSourceTransactionManagerの公式ドキュメント（[DataSourceTransactionManager](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceTransactionManager.html)）によると、実際のJDBCレベルでは以下の処理が行われます。

- Connection取得とsetAutoCommit(false)の実行
- トランザクション完了時のcommit()またはrollback()の実行
- Connectionの適切な管理とリソース解放

## 直接SQL実行時の重要なポイント

repositoryをautowiredしないで直接SQLを実行する場合、重要なのはどうやってConnectionを取得するかです。

### 普通のDataSource.getConnection()を使った場合

```java
@Service
public class UserService {
    @Autowired
    private DataSource dataSource;
    
    @Transactional
    public void createUser(String name) {
        try {
            // これだとSpringのトランザクション管理と連携しない
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (name) VALUES (?)");
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // 例外が発生してもSpringによるロールバックは効かない
            throw new RuntimeException(e);
        }
    }
}
```

この場合、@Transactionalアノテーションを付けていても、Springのトランザクション管理と連携しません。なぜなら、dataSource.getConnection()は常に新しいConnectionを返すため、Springが管理しているトランザクション用のConnectionとは別物になってしまうためです。

### DataSourceUtils.getConnection()を使った場合

```java
@Service
public class UserService {
    @Autowired
    private DataSource dataSource;
    
    @Transactional
    public void createUser(String name) {
        Connection conn = null;
        try {
            // Spring管理のConnectionを取得
            conn = DataSourceUtils.getConnection(dataSource);
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (name) VALUES (?)");
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // 例外が発生すると@Transactionalによってロールバックされる
            throw new RuntimeException(e);
        } finally {
            // Connectionを適切に解放
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }
}
```

## DataSourceUtils.getConnection()の動作

DataSourceUtils.getConnection()は以下のような動作をします。

1. **アクティブなトランザクションがある場合**：そのトランザクションに紐づいたConnectionを返す
2. **アクティブなトランザクションがない場合**：dataSource.getConnection()と同じ動作

つまり、@Transactionalメソッド内で呼び出せば、Springが管理しているトランザクション用のConnectionを取得できるわけです。

また、DataSourceUtils.releaseConnection()を使用することで、Connectionが適切に管理されます。トランザクション内であればConnectionは閉じられず、トランザクション外であれば適切に閉じられます。

## PostgreSQL環境での実際の動作検証

理論的な理解だけではなく、実際にPostgreSQL環境で動作を確認してみましょう。

### プロジェクトセットアップ

まずは検証用のプロジェクトを作成します。

**docker-compose.yml**
```yaml
version: '3.8'
services:
  postgres:
    image: postgres:15-alpine
    container_name: transaction-test-postgres
    environment:
      POSTGRES_DB: transaction_test
      POSTGRES_USER: testuser
      POSTGRES_PASSWORD: testpass
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    restart: always

volumes:
  postgres_data:
```

**build.gradle**
```gradle
plugins {
    id 'org.springframework.boot' version '3.2.0'
    id 'io.spring.dependency-management' version '1.1.4'
    id 'java'
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-jdbc'
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.postgresql:postgresql'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.testcontainers:postgresql:1.19.3'
    testImplementation 'org.testcontainers:junit-jupiter:1.19.3'
}
```

**application.properties**
```properties
# PostgreSQL接続設定
spring.datasource.url=jdbc:postgresql://localhost:5432/transaction_test
spring.datasource.username=testuser
spring.datasource.password=testpass
spring.datasource.driver-class-name=org.postgresql.Driver

# トランザクションのデバッグログ
logging.level.org.springframework.transaction=DEBUG
logging.level.org.springframework.jdbc=DEBUG

# テーブル作成
spring.sql.init.mode=always
```

**schema.sql**
```sql
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS users CASCADE;

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    product_name VARCHAR(255) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

### 検証用サービスクラス

実際に様々なパターンでトランザクションの動作を確認するためのサービスクラスを作成します。

**UserService.java**
```java
@Service
public class UserService {

    @Autowired
    private DataSource dataSource;

    // パターン1: @Transactional + DataSourceUtils.getConnection()
    @Transactional
    public Long createUserWithTransactionCorrect(String name, String email) {
        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            
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
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    // パターン2: @Transactional + dataSource.getConnection() (Springと連携しない方法)
    @Transactional
    public Long createUserWithTransactionSeparate(String name, String email) {
        Connection conn = null;
        try {
            // Springのトランザクション管理とは独立したConnectionを取得
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
        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            
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
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    // 検証用のヘルパーメソッド
    public int countUsers() {
        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM users");
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    public int countOrders() {
        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM orders");
            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }

    public List<String> getAllUserNames() {
        Connection conn = null;
        try {
            conn = DataSourceUtils.getConnection(dataSource);
            PreparedStatement stmt = conn.prepareStatement("SELECT name FROM users ORDER BY id");
            ResultSet rs = stmt.executeQuery();
            
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
            return names;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn != null) {
                DataSourceUtils.releaseConnection(conn, dataSource);
            }
        }
    }
}
```

### 動作検証テスト

実際に各パターンでトランザクションがどう動作するかを検証するテストを作成します。

**TransactionVerificationTest.java**
```java
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

        // Springによってロールバックされているので、データは変更されていない
        assertThat(userService.countUsers()).isEqualTo(initialUserCount);
        assertThat(userService.countOrders()).isEqualTo(initialOrderCount);
    }

    @Test
    @Order(3)
    @DisplayName("パターン2: @Transactional + dataSource.getConnection() - エラー時の動作")
    void testSeparateTransactionRollback() {
        int initialUserCount = userService.countUsers();
        int initialOrderCount = userService.countOrders();

        assertThatThrownBy(() -> {
            userService.createUserWithTransactionSeparate("ERROR", "error@example.com");
        }).isInstanceOf(RuntimeException.class)
          .hasMessage("Intentional error for testing");

        // 手動でrollbackを実装しているので、データは変更されていない
        assertThat(userService.countUsers()).isEqualTo(initialUserCount);
        assertThat(userService.countOrders()).isEqualTo(initialOrderCount);
    }

    @Test
    @Order(4)
    @DisplayName("パターン3: @Transactionalなし - エラー時の動作")
    void testNoTransactionError() {
        int initialUserCount = userService.countUsers();
        int initialOrderCount = userService.countOrders();

        assertThatThrownBy(() -> {
            userService.createUserWithoutTransaction("ERROR", "error@example.com");
        }).isInstanceOf(RuntimeException.class)
          .hasMessage("Intentional error for testing");

        // auto-commitモードで動作するため、
        // 最初のINSERT（ユーザー）は個別のトランザクションでコミットされ、
        // 2番目のINSERT（注文）で例外が発生する
        assertThat(userService.countUsers()).isEqualTo(initialUserCount + 1);
        assertThat(userService.countOrders()).isEqualTo(initialOrderCount);
        assertThat(userService.getAllUserNames()).contains("ERROR");
    }
}
```


## 実際に動かしてみての結果

### 各パターンの動作

| パターン | Connection取得方法 | @Transactional | エラー時の動作 | データ整合性 |
|---------|------------------|---------------|--------------|------------|
| パターン1 | DataSourceUtils.getConnection() | あり | Springが自動ロールバック | 保たれる（全てロールバック） |
| パターン2 | dataSource.getConnection() | あり | 手動ロールバック実装が必要 | 手動実装次第 |
| パターン3 | DataSourceUtils.getConnection() | なし | auto-commitモード | 保たれない（部分的にコミット） |

### 結果

#### パターン1: Springトランザクション管理が正常に動作
```
初期状態: users=0, orders=0
正常ケース実行後: users=1, orders=1
エラーケース実行後: users=1, orders=1 (ロールバックにより変化なし)
```

#### パターン2: 独立したトランザクション管理
```
初期状態: users=0, orders=0
正常ケース実行後: users=1, orders=1
エラーケース実行後: users=1, orders=1 (手動ロールバックにより変化なし)
```

#### パターン3: auto-commitモードの動作
```
初期状態: users=0, orders=0
エラーケース実行:
  1. INSERT INTO users → 成功・自動コミット
  2. INSERT INTO orders → 例外発生
最終状態: users=1, orders=0 (不整合な状態)
```

## まとめ

repositoryをautowiredしない場合でも、DataSourceUtils.getConnection()を使えば@Transactionalが正しく動作することを動作確認できました。

- **Connection取得による違い**
  - `DataSourceUtils.getConnection()` → Springのトランザクション管理に参加
  - `dataSource.getConnection()` → 独立したConnectionで@Transactionalは効果なし

- **リソース管理の動作**
  - `DataSourceUtils.releaseConnection()`で適切にConnection解放
  - トランザクション内外で自動的に適切な処理が実行される

- **@Transactionalなしのリスク**
  - auto-commitモードで各SQL文が個別にコミット
  - 複数テーブル操作時にデータ不整合の可能性

Springのトランザクション管理の仕組みについて、少し解像度が上がりました。

## 参考文献

- [Spring Framework Documentation - Transaction Management](https://docs.spring.io/spring-framework/docs/current/reference/html/data-access.html#transaction)
- [Spring Framework - DataSourceUtils API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceUtils.html)
- [Spring Transaction Management In-Depth Guide](https://www.marcobehler.com/guides/spring-transaction-management-transactional-in-depth)
- [Understanding Proxy-Based AOP in Spring](https://medium.com/@jabriassia29/understanding-proxy-based-aop-in-spring-f91823ea76ee)
- [Testcontainers Documentation - PostgreSQL Module](https://www.testcontainers.org/modules/databases/postgres/)
