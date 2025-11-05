
---
title: "フロントエンドとサーバーでのバリデーション責務分解"
emoji: "🐱"
type: "tech"
topics: ["validation"]
published: false
---

## はじめに

先日、Xでこんな投稿が話題になっていました。

https://x.com/okd61807313/status/1984917649386615251

実際のサイトを見ていないため詳細は不明ですが、事象としてはフロントエンドのJavaScriptだけでバリデーションを実装し、サーバーサイドに同等以上のバリデーションがなかったケースです。
DevToolsで回避できるということは、悪意あるユーザーが不正なデータを送信できてしまいます。

サーバーサイドではすべてのバリデーションを実装するべきです。セキュリティの観点でも、ドメインの正当性を保つ意味でも、サーバーは最後の砦となります。

一方で、フロントエンドのバリデーションについては、どこまで実装すべきか判断が難しいところです。本記事では、この点について考えを整理してみます。

## バリデーションの責務を整理する

前提を決めておきます。

### サーバーサイドバリデーションは絶対

サーバーサイドのバリデーションは必須です。これは絶対に省略できません。

理由は2つあります。

1つ目はセキュリティです。ブラウザ上で動作するJavaScriptは、ユーザーが自由に改変できます。DevToolsを開けば、変数を書き換えたり、関数の実行を止めたり、何でもできます。冒頭のX(Twitter)事例のように、disabled属性を消すだけでボタンが押せてしまう。これがフロントエンドの限界です。

2つ目はドメインの正当性です。ビジネスルールやドメインモデルの不変条件は、サーバーで守る必要があります。

例えば「在庫数を超える注文はできない」というルールがあったとして、これをフロントだけで実装していたらどうなるでしょうか。在庫数は刻一刻と変わりますし、複数のユーザーが同時にアクセスする可能性もあります。

サーバーでトランザクション管理をして、最終的な整合性を保証する必要があります。

### フロントエンドバリデーションは入力支援

一方で、フロントエンドのバリデーションは「あると嬉しい」ものです。

目的はユーザー体験の向上です。セキュリティや整合性の担保ではありません。

例えば、メールアドレスの形式が間違っていたら、送信ボタンを押す前に「メールアドレスの形式が正しくありません」と教えてあげる。これがフロントエンドバリデーションの役割です。

サーバーに送信して、エラーが返ってきて、またフォームに戻って修正する、という手間を省けます。

ここで重要なのは、フロントエンドでバリデーションを実装する場合でも、サーバーサイドのバリデーションは必ず実装することです。フロントを信頼してサーバーのチェックを省略することは、絶対にしません。

この前提のもと、どこまでフロントでバリデーションを実装すべきか判断していきます。

## グレーゾーンの判断基準

「フロントでも実装すべきか？」という判断を、具体的なケースで見ていきます。大きく3つのパターンに分けて整理しました。

### パターン1: フロント+サーバー両方で実装

#### 自明なケース：形式チェック・必須チェック

これは間違いなくフロント実装を推奨します。

具体例として、次のようなチェックが該当します。
- メールアドレスの形式チェック
- 必須項目の入力チェック
- 文字数制限（最小・最大）
- 数値の範囲チェック

実装例（TypeScript + Zod v3.22以降）
```typescript
import { z } from "zod"

const schema = z.object({
  email: z.string()
    .min(1, "メールアドレスを入力してください")
    .email("メールアドレスの形式が正しくありません"),
  
  password: z.string()
    .min(8, "パスワードは8文字以上で入力してください")
    .max(128, "パスワードは128文字以内で入力してください"),
})
```

実装例（Kotlin + Spring Boot 3.x, Bean Validation 3.0）
```kotlin
data class UserRegistrationRequest(
    @field:NotBlank(message = "メールアドレスを入力してください")
    @field:Email(message = "メールアドレスの形式が正しくありません")
    val email: String,

    @field:Size(min = 8, max = 128, message = "パスワードは8文字以上128文字以内で入力してください")
    val password: String
)
```

参考: [Zod公式ドキュメント](https://zod.dev/)、[Bean Validation仕様](https://beanvalidation.org/)

なぜフロントでも実装すべきでしょうか。

UX向上効果が大きい点が挙げられます。入力欄から離れた瞬間にエラーが表示されるので、ユーザーはすぐに修正できます。サーバーに送信してエラーが返ってくるまで待つ必要がありません。

実装コストも低い点も魅力です。形式チェックや必須チェックは、ルールがシンプルで実装しやすく、正規表現や文字数のチェックは、フロントでもサーバーでもほぼ同じように書けます。

冒頭のX(Twitter)事例も、サーバーサイドにバリデーションがあれば、disabled属性を消されても不正なデータは弾けました。フロントのバリデーションは、あくまでUX向上のためのものです。

このレベルのバリデーションはフロントで実装しないと効果が薄いでしょう。ユーザーが入力している最中に「ここが間違っています」と教えられるのは、フロントだけです。

#### 悩ましいケース：メールアドレス重複チェック


ユーザー登録画面で、既存ユーザーと重複するメールアドレスが入力された場合の処理を考えます。

1つ目のパターンは、フロントで非同期チェックする方法です。

```typescript
const checkEmailAvailability = async (email: string) => {
  try {
    const response = await fetch(`/api/users/check-email?email=${email}`)
    const data = await response.json()
    return data.available
  } catch (error) {
    // エラー時はサーバー検証に任せる
    return true
  }
}

// onBlurで実行
<input
  type="email"
  onBlur={(e) => checkEmailAvailability(e.target.value)}
/>
```

2つ目のパターンは、サーバーのみでチェックする方法です。

```kotlin
@Service
class UserService(
    private val userRepository: UserRepository
) {
    fun registerUser(request: UserRegistrationRequest) {
        // サーバーで最終チェック
        if (userRepository.existsByEmail(request.email)) {
            throw EmailDuplicateException("このメールアドレスは既に使用されています")
        }
        // 登録処理
    }
}
```

どちらを選ぶべきでしょうか。

フロント実装を検討する要素として、UX向上効果があります。ユーザー登録は頻度が低いので、即座のフィードバックは嬉しいでしょう。API呼び出しコストも、そこまで頻繁ではないので許容できます。

一方で、フロント実装を避けたい要素もあります。チェック時と登録時でタイムラグがあるため、レースコンディションが発生します。API作成、エラーハンドリング、デバウンス処理など、実装コストもかかります。メールアドレスの存在チェックAPIを公開することになるので、セキュリティ的な配慮も必要です。

もし実装するなら、次の点に注意が必要です。

認証が必要なAPIにすること。誰でもアクセスできるAPIにすると、既存ユーザーのメールアドレスを列挙される可能性があります。

フロントのチェックは「ヒント」程度に考えること。「このメールアドレスは使えないかもしれません」くらいの軽い通知にして、最終判定はサーバーに任せます。

サーバーでの検証は必須。フロントでOKが出ても、サーバーでエラーになる可能性があることを前提に実装します。

逆に、実装コストやセキュリティリスクを考えると、サーバーのみでも十分だと感じることもあります。送信ボタンを押してエラーが返ってくるまで数秒なら、それほどUXを損なわないという判断もあります。

明確な正解はないグレーゾーンですが、実装する場合は「あくまで補助的なもの」として位置づけて、サーバーでの最終検証は絶対に省略しないことが重要です。

### パターン2: サーバーのみで実装

#### 自明なケース：ビジネスルール・状態依存チェック

これはサーバーのみで実装すべきケースです。

具体例として、次のようなチェックが該当します。
- 在庫数チェック（商品の在庫を超える注文はできない）
- 権限チェック（管理者のみ実行可能な操作）
- ステータスチェック（受付中の注文のみキャンセル可能）

実装例（Kotlin + Spring Boot 3.x）
```kotlin
@Service
class OrderService(
    private val inventoryService: InventoryService,
    private val orderRepository: OrderRepository
) {
    @Transactional
    fun createOrder(request: CreateOrderRequest) {
        // 在庫数チェック
        val available = inventoryService.getAvailableStock(request.productId)
        if (request.quantity > available) {
            throw InsufficientStockException("在庫が不足しています（在庫数: $available）")
        }
        
        // 注文処理
        val order = Order(
            productId = request.productId,
            quantity = request.quantity
        )
        orderRepository.save(order)
        
        // 在庫更新
        inventoryService.decreaseStock(request.productId, request.quantity)
    }
}
```

なぜフロントで実装する意味がないのでしょうか。

データが常に変動するからです。在庫数は刻一刻と変わります。フロントで在庫数を取得してチェックしても、送信するまでの間に在庫が変わっている可能性が高いです。

複数ユーザーの同時アクセスも考慮する必要があります。在庫が残り1個の商品を、複数のユーザーが同時に注文しようとした場合、フロントのチェックでは対応できません。サーバーでトランザクション管理をして、排他制御をする必要があります。

セキュリティリスクもあります。権限チェックをフロントで実装しても、簡単に回避されてしまいます。管理者機能へのアクセス制限は、サーバーで確実に行う必要があります。

では、フロントエンドではどう対応すべきでしょうか。

フロントでは、ユーザーに情報を提示する程度にとどめます。

例えば在庫数の場合、「残り3個」「在庫わずか」といった表示をして、ユーザーに注意を促します。ただし、これはあくまで参考情報であって、バリデーションではありません。

最終的なチェックはサーバーで行い、在庫不足の場合はエラーを返します。フロントはそのエラーを適切に表示して、ユーザーに伝えます。

#### 悩ましいケース：複雑な相関チェック


複数フィールドの組み合わせによるバリデーションを考えます。

```
例：法人登録フォーム
- 「法人」を選択した場合、会社名が必須
- 「個人」を選択した場合、会社名は不要
```

実装パターンA（フロントでも実装）
```typescript
const schema = z.object({
  userType: z.enum(["individual", "corporate"]),
  companyName: z.string().optional()
}).refine(
  (data) => {
    if (data.userType === "corporate") {
      return data.companyName && data.companyName.length > 0
    }
    return true
  },
  {
    message: "法人の場合は会社名を入力してください",
    path: ["companyName"]
  }
)
```

実装パターンB（サーバーのみ）
```kotlin
@Service
class UserService {
    fun validateRegistration(request: UserRegistrationRequest) {
        if (request.userType == UserType.CORPORATE) {
            if (request.companyName.isNullOrBlank()) {
                throw ValidationException("法人の場合は会社名を入力してください")
            }
        }
    }
}
```

どう判断すべきでしょうか。

フロント実装を検討する要素として、UX向上効果があります。ユーザーが「法人」を選択した瞬間に会社名欄が必須になることを明示できます。

一方で、フロント実装を避けたい要素もあります。ビジネスルールが複雑になるほど、フロントとサーバーで二重管理のコストが高くなります。ルール変更時に両方を修正する必要があり、ズレが発生するリスクもあります。

実装すべきかどうかは、ルールの複雑さによって決まります。

ルールがシンプルな場合（2〜3個のフィールドの組み合わせ程度）なら、フロントでも実装する価値があります。ユーザーが入力している最中にリアルタイムでガイドできるのは便利です。

ルールが複雑になる場合（5個以上のフィールドが関係する、条件分岐が多いなど）は、サーバーのみにする方が現実的です。フロントとサーバーでロジックを完全に同期させるのは難しく、メンテナンスコストが高くなります。

いずれの場合も、サーバーでの検証は必須です。フロントはあくまで入力支援であり、最終判定はサーバーが行います。

### パターン3: フロント主体で実装

#### 自明なケース：パスワード強度表示

これはフロントで実装すべきケースです。

パスワード入力欄で、強度メーターを表示する機能を考えます。

実装例（TypeScript）
```typescript
const calculatePasswordStrength = (password: string) => {
  let strength = 0
  
  if (password.length >= 8) strength++
  if (password.length >= 12) strength++
  if (/[a-z]/.test(password)) strength++
  if (/[A-Z]/.test(password)) strength++
  if (/[0-9]/.test(password)) strength++
  if (/[!@#$%^&*]/.test(password)) strength++
  
  if (strength <= 2) return { level: "weak", label: "弱い" }
  if (strength <= 4) return { level: "medium", label: "普通" }
  return { level: "strong", label: "強い" }
}

// リアルタイムで表示更新
<input 
  type="password"
  onChange={(e) => {
    const strength = calculatePasswordStrength(e.target.value)
    setPasswordStrength(strength)
  }}
/>
<PasswordStrengthMeter strength={passwordStrength} />
```

実装例（Kotlin + Spring Boot 3.x）
```kotlin
// サーバーでは最低限のチェックのみ
data class UserRegistrationRequest(
    @field:Size(min = 8, message = "パスワードは8文字以上で入力してください")
    @field:Pattern(
        regexp = "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#\$%^&*]).*\$",
        message = "パスワードには数字、小文字、大文字、記号を含めてください"
    )
    val password: String
)
```

なぜフロント主体で実装すべきでしょうか。

リアルタイムフィードバックが本質だからです。ユーザーが1文字入力するたびに強度メーターが変化することで、「あと少し工夫すれば強いパスワードになる」というガイドができます。

サーバーでは、最低限の要件（8文字以上、記号を含むなど）をチェックするだけで十分です。強度の細かい段階まで検証する必要はありません。

この機能はフロントで実装しないと効果が薄いでしょう。送信してから「パスワードが弱いです」と言われても、ユーザーは困ってしまいます。

#### 悩ましいケース：入力中のリアルタイムAPI呼び出し


ユーザー名の使用可能チェックを、入力中に逐次行う機能を考えます。

次のような実装が考えられます。

```typescript
import { debounce } from "lodash"

const checkUsernameAvailability = debounce(async (username: string) => {
  if (username.length < 3) return
  
  try {
    const response = await fetch(`/api/users/check-username?username=${username}`)
    const data = await response.json()
    setUsernameAvailable(data.available)
  } catch (error) {
    console.error("Failed to check username", error)
  }
}, 500) // 500ms待ってからAPI呼び出し

<input 
  type="text"
  onChange={(e) => checkUsernameAvailability(e.target.value)}
/>
```

どう判断すべきでしょうか。

フロント実装を検討する要素として、UX向上効果は確かにあります。入力中にすぐに「このユーザー名は使えません」と分かるのは便利です。

一方で、サーバー負荷が大きな問題になります。ユーザーが文字を入力するたびにAPI呼び出しが発生します（デバウンスしても頻度は高い）。複雑な実装が必要になります。デバウンス処理、エラーハンドリング、ローディング状態の管理など、考慮すべきことが多いです。

このケースでは、本当に必要かを問うべきです。送信時にチェックするだけでは、なぜダメなのでしょうか。

ユーザー名の入力は、ユーザー登録時に1回だけです。入力中にリアルタイムでチェックしなくても、送信時にエラーが返ってきて、別のユーザー名を入力し直す、という流れでも許容できるでしょう。

実装するなら、サーバー負荷を考慮して、レートリミットやキャッシュを適切に設定する必要があります。また、この機能はあくまでオプショナルなものとして、APIが失敗しても送信時の検証でカバーできるようにしておくべきです。

コストに対して得られるUX向上効果が見合わないことが多いと感じています。よほど入力頻度が高い画面でない限り、送信時のチェックで十分です。

## まとめ

フロントエンドとサーバーのバリデーション責務について整理しました。

大前提として、サーバーサイドのバリデーションは絶対に必要です。フロントエンドは改ざん可能なので、セキュリティやドメインの整合性はサーバーで守ります。

フロントエンドのバリデーションは、入力支援として位置づけます。ユーザー体験向上のための機能であり、実装すべきかは費用対効果で判断します。

判断基準として、3つのパターンで整理しました。

フロント+サーバー両方で実装するパターン
- 形式チェックや必須チェックは実装すべき
- メールアドレス重複チェックはUXとコストのバランスで判断

サーバーのみで実装するパターン
- ビジネスルールや状態依存チェックはサーバーのみ
- 複雑な相関チェックはルールの複雑さで判断

フロント主体で実装するパターン
- パスワード強度表示はフロント主体で実装
- リアルタイムAPI呼び出しは本当に必要か問うべき

実務では、プロジェクトの性質、チームのリソース、メンテナンスコストなども考慮して、最適なバランスを見つけることが重要です。

「サーバーは絶対、フロントは補助」という原則を忘れずに、適切な責務分解を心がけたいところです。

## 参考文献

- https://fintan.jp/page/421/
- https://qiita.com/isaaac/items/4ca28057a45dddb14a64
- https://zenn.dev/mikann_mikann/scraps/579dacab05d17a
- https://tomoima525.hatenablog.com/entry/2019/01/06/075644
- https://zenn.dev/sutamac/articles/7e864fb9e30d70
- https://www.ogis-ri.co.jp/otc/hiroba/technical/DDDEssence/chap2.html
- https://reflectoring.io/bean-validation-with-spring-boot/
