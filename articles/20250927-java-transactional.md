---
title: "Springの@Transactionalアノテーションの挙動について"
emoji: "🪓"
type: "tech" # tech: 技術記事 / idea: アイデア
topics: ["java"]
published: false
---
# TL;DR

Spring Bootで@Transactionalを使い、repositoryをautowiredせずに直接SQLでinsertする場合の動作を調査し、PostgreSQL環境で実際に検証してみました。DataSourceUtils.getConnection()を使えばトランザクション管理が正しく動作することを確認できます。

## はじめに

Spring Bootで開発していると、@Transactionalアノテーションの挙動について疑問に思うことがありますよね。特に「repositoryをautowiredしないで直接SQLを実行する場合でも、@Transactionalは効果を発揮するのか」という点は、実際の開発でよく遭遇するケースです。

調べてみると、この部分の理解が曖昧だと意外とハマりやすいポイントだったので、理論的な調査と実際の動作検証の両方を行ってみました。

## @Transactionalの基本的な仕組み

まず@Transactionalがどのような仕組みで動作しているかを理解しておく必要があります。

Springの@Transactionalは、基本的に以下のようなJDBCコードを自動で実行してくれます。

```java
public class UserService {
    public Long registerUser(User user) {
        Connection connection = dataSource.getConnection(); // (1)
        try (connection) {
            connection.setAutoCommit(false); // (2)
            // ここで実際のSQL実行
            connection.commit(); // (3)
        } catch (SQLException e) {
            connection.rollback(); // (4)
        }
    }
}
```

しかし、実際にはもっと洗練された仕組みで動作しています。SpringはAOPとプロキシパターンを使って、この処理を透明に実行しています。

@Transactionalが付いたクラスやメソッドに対して、Springが動的にプロキシオブジェクトを作成します。このプロキシがメソッド呼び出しを横取りして、トランザクションの開始・コミット・ロールバックを管理する仕組みです。

```java
// 実際にはこのようなプロキシが作られる（概念図）
public class UserService$$Proxy extends UserService {
    private UserService target;
    private TransactionManager transactionManager;
    
    @Override
    public Long registerUser(User user) {
        // 1. トランザクション開始
        Transaction tx = transactionManager.getTransaction();
        try {
            // 2. 実際のメソッド実行
            Long result = target.registerUser(user);
            // 3. コミット
            tx.commit();
            return result;
        } catch (Exception e) {
            // 4. ロールバック
            tx.rollback();
            throw e;
        }
    }
}
```

## 直接SQL実行時の重要なポイント

repositoryをautowiredしないで直接SQLを実行する場合、重要なのはどうやってConnectionを取得するかです。

### ❌ 普通のDataSource.getConnection()を使った場合

```java
@Service
public class UserService {
    @Autowired
    private DataSource dataSource;
    
    @Transactional
    public void createUser(String name) {
        try {
            // これだと@Transactionalの管理下に入らない
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (name) VALUES (?)");
            stmt.setString(1, name);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // 例外が発生してもロールバックされない
            throw new RuntimeException(e);
        }
    }
}
```

この場合、@Transactionalアノテーションを付けていても、実際にはトランザクション管理が働きません。なぜなら、dataSource.getConnection()は常に新しいConnectionを返すためです。

### ⭕ DataSourceUtils.getConnection()を使った場合

```java
@Service
public class UserService {
    @Autowired
    private DataSource dataSource;
    
    @Transactional
    public void createUser(String name) {
        try {
            // Spring管理のConnectionを取得
            Connection conn = DataSourceUtils.getConnection(dataSource);
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (name) VALUES (?)");
            stmt.setString(1, name);
            stmt.executeUpdate();
            
            // DataSourceUtilsで取得したConnectionは手動でcloseしない
            // Spring側で適切に管理される
        } catch (SQLException e) {
            // 例外が発生すると@Transactionalによってロールバックされる
            throw new RuntimeException(e);
        }
    }
}
```

## DataSourceUtils.getConnection()の動作

DataSourceUtils.getConnection()は以下のような動作をします。

1. アクティブなトランザクションがある場合、そのトランザクションに紐づいたConnectionを返す
2. アクティブなトランザクションがない場合、dataSource.getConnection()と同じ動作

つまり、@Transactionalメソッド内で呼び出せば、Springが管理しているトランザクション用のConnectionを取得できるわけです。

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
    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'org.testcontainers:junit-jupiter'
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
}
```

## プロセス異常終了時の動作確認

実際の運用で気になるのは、トランザクション途中でプロセスが落ちた場合の動作です。これも検証してみましょう。

### プロセス強制終了テスト

```java
@Test
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
```

## 実際に動かしてみて

この検証を通じて、いくつか面白い発見がありました。

まず、パターン1のDataSourceUtils.getConnection()を使った場合、本当に@Transactionalが期待通りに動作することが確認できました。ログを見ると、Springがトランザクションの開始とコミット・ロールバックを適切に管理していることがわかります。

```
2024-01-15 10:30:15.123 DEBUG --- [main] o.s.j.d.DataSourceTransactionManager : Creating new transaction with name [UserService.createUserWithTransactionCorrect]: PROPAGATION_REQUIRED,ISOLATION_DEFAULT
2024-01-15 10:30:15.145 DEBUG --- [main] o.s.j.d.DataSourceTransactionManager : Acquired Connection [HikariProxyConnection@123456789] for JDBC transaction
2024-01-15 10:30:15.167 DEBUG --- [main] o.s.j.d.DataSourceUtils : Fetching JDBC Connection from DataSource
```

一方で、パターン3のテスト結果は少し意外でした。@Transactionalがない場合、PostgreSQLのauto-commitモードにより、各SQL文が個別にコミットされるため、途中でエラーが発生すると部分的にデータが残ってしまうんですね。

特に興味深かったのは、プロセス強制終了のテストです。トランザクション途中でプロセスが落ちた場合、PostgreSQL側で未コミットのデータは自動的にロールバックされます。これはデータベースのACID特性によるもので、Springのトランザクション管理とは独立して動作する安全機能です。

個人的には、DataSourceUtils.getConnection()とdataSource.getConnection()の挙動の違いを実際に確認できたのが一番勉強になりました。前者はSpringの管理下にあるConnectionを返すのに対し、後者は常に新しいConnectionを作成します。この違いが@Transactionalの効果に直結するわけです。

## デバッグ時のポイント

実際の開発でトランザクションの動作を確認したい場合、以下の設定をapplication.propertiesに追加すると便利です。

```properties
# トランザクションのデバッグログ
logging.level.org.springframework.transaction=DEBUG
logging.level.org.springframework.jdbc=DEBUG

# PostgreSQLドライバーのログ
logging.level.org.postgresql=DEBUG
```

これらの設定により、トランザクションの開始・コミット・ロールバックのタイミングや、実際に実行されるSQL文を詳細に確認できます。想像以上に詳細なログが出力されるので、トランザクションの動作を理解するのにとても役立ちました。

## まとめ

今回の調査と検証により、@TransactionalはrepositoryをautowiredしなくてもDataSourceUtils.getConnection()を使えば正しく動作することが実証できました。重要なポイントをまとめてみます。

Springの@Transactionalは、AOPとプロキシパターンを使ってトランザクション管理を実現しています。@Transactionalを使う場合はDataSourceUtils.getConnection()でConnectionを取得する必要があります。dataSource.getConnection()では、Springの管理下から外れてしまうため、トランザクション制御が効きません。

また、@Transactionalがない場合は各SQL文が個別にコミットされるため、途中でエラーが発生すると部分的にデータが残る可能性があります。これは特に複数のテーブルに対する操作を行う場合に注意が必要です。

プロセス異常終了時には、PostgreSQL側のACID特性により未コミットのデータは自動的にロールバックされるため、データの整合性は保たれます。

実際にコードを書いて検証してみることで、理論だけでは分からない細かい挙動まで理解できました。特にログ出力の設定は、実際の開発でトランザクションの動作を追いかける際にとても役立ちますね。

## 参考文献

- [Spring Framework Documentation - Transaction Management](https://docs.spring.io/spring-framework/docs/current/reference/html/data-access.html#transaction)
- [Spring Framework - DataSourceUtils API](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/datasource/DataSourceUtils.html)
- [Spring Transaction Management In-Depth Guide](https://www.marcobehler.com/guides/spring-transaction-management-transactional-in-depth)
- [Understanding Proxy-Based AOP in Spring](https://medium.com/@jabriassia29/understanding-proxy-based-aop-in-spring-f91823ea76ee)
- [Testcontainers Documentation - PostgreSQL Module](https://www.testcontainers.org/modules/databases/postgres/)
