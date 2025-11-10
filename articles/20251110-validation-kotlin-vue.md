---
title: "OpenAPIでKotlinのバリデーションをVue.jsに自動同期する"
emoji: "🔄"
type: "tech"
topics: ["kotlin", "vue", "openapi", "springboot", "zod"]
published: false
---

# TL;DR

Kotlin側でBean Validationを定義し、OpenAPI経由でフロントエンドのTypeScript型とZodスキーマを自動生成する仕組みを構築しました。

**重要な前提**: サーバーサイドはセキュリティとドメインの正当性担保、フロントエンドはUX向上のためのバリデーションを行います。この手法は、UX向上のための基本的なバリデーションをサーバー定義から自動生成することで、二重管理を解消します。複雑なビジネスロジックやセキュリティ検証は、サーバーサイドでのみ実装します。

## 対象読者

この記事は以下の知識を持つ方を対象としています。

- Spring Bootの基本的な使い方（コントローラー、DIなど）
- Vue.js Composition APIの基礎
- TypeScriptの型システムの基本
- npm/Gradleの基本操作

## はじめに

フロントエンドとバックエンドで同じバリデーションルールを書いていませんか？

```typescript
// フロントエンド (Zod)
const userSchema = z.object({
  name: z.string().min(1).max(50),
  email: z.string().email(),
  age: z.number().min(0).max(150).optional()
})
```

```kotlin
// バックエンド (Bean Validation)
data class UserRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 50)
    val name: String,

    @field:Email
    @field:NotBlank
    val email: String,

    @field:Min(0)
    @field:Max(150)
    val age: Int?
)
```

同じルールを二箇所に書くのは、以下の問題があります。

1. 同期漏れのリスク: バックエンドでルールを変更したとき、フロントエンドの更新を忘れる
2. メンテナンスコスト: 仕様変更のたびに両方のコードを修正する必要がある
3. 整合性の保証が困難: 本当に同じルールになっているか確認しづらい

### バリデーションの責務分担

まず、バリデーションの責務を明確にしておく必要があります。

**サーバーサイドのバリデーション**
- **目的**: セキュリティの確保とドメインの正当性担保
- **責任範囲**:
  - 悪意ある入力からシステムを守る
  - ビジネスルールの整合性を保証する
  - データの整合性を担保する
- **重要性**: 必須。フロントエンドのバリデーションは簡単に迂回できるため、サーバーサイドが最終的な権限を持つ

**フロントエンドのバリデーション**
- **目的**: ユーザー体験（UX）の向上
- **責任範囲**:
  - サーバーへの無駄なリクエストを減らす
  - ユーザーに即座にフィードバックを提供する
  - 入力ミスを事前に防ぐ
- **重要性**: 任意。あくまでUX向上のための補助的な役割

この前提のもと、**基本的なフォーマットチェックやフィールド制約については共通化する価値がある**と考えます。複雑なビジネスロジックやセキュリティ上重要な検証は、サーバーサイドでのみ実装すべきです。

そこで、Kotlin側で定義した基本的なバリデーションルールを自動的にフロントエンドに同期させる仕組みを構築します。

## この記事で実現すること

### アーキテクチャ

```
┌─────────────────────┐
│   Kotlin Backend    │
│  (Bean Validation)  │
└──────────┬──────────┘
           │
           │ SpringDoc
           ▼
┌─────────────────────┐
│   openapi.json      │ ← 中間フォーマット
└──────────┬──────────┘
           │
     ┌─────┴─────┐
     │           │
     ▼           ▼
┌──────────┐ ┌──────────┐
│TypeScript│ │   Zod    │
│  Types   │ │ Schemas  │
└────┬─────┘ └────┬─────┘
     │            │
     └──────┬─────┘
            ▼
    ┌──────────────┐
    │   Vue.js     │
    │(vee-validate)│
    └──────────────┘
```

### データフロー

1. 定義: KotlinでBean Validationアノテーションを付与
2. 生成: SpringDocがOpenAPI仕様（JSON）を自動生成
3. 変換: openapi-typescriptがTypeScript型を生成
4. 変換: openapi-zod-clientがZodスキーマを生成
5. 利用: Vue.jsでvee-validateとZodを統合してバリデーション

### 実現する価値

- DRY原則の徹底: バリデーションルールを一箇所で管理
- 型安全性: APIのリクエスト・レスポンスが完全に型安全に
- 変更への追従: バックエンドの変更が自動的にフロントエンドに反映
- セキュリティ: サーバーサイドが常に最終的な検証を実施

### できること・できないこと

この手法で共通化できるのは、**UX向上のための基本的なバリデーション**のみです。

**共通化できるもの（フロント・サーバー両方で実装する価値がある）**
- 基本的なフォーマットチェック（NotBlank, Size, Email, Min, Max等）
- 正規表現による形式検証（電話番号、郵便番号等）
- 型定義の自動生成
- 必須/任意フィールドの判定

**共通化すべきでないもの（サーバーサイドのみで実装）**
- データベースへの問い合わせが必要な検証（重複チェック、存在確認等）
- 複雑なビジネスルール（例: 「管理者のみ実行可能」「特定条件下でのみ許可」）
- 複数フィールドにまたがる複雑なバリデーション
- セキュリティ上重要な検証（認証・認可、権限チェック等）
- 動的に変わるバリデーションルール

**重要**: フロントエンドのバリデーションを通過しても、必ずサーバーサイドで再検証されます。フロントエンドのバリデーションはあくまでユーザーへの早期フィードバックのためです。

## 技術選定

### なぜOpenAPIを使うのか

他の選択肢と比較した結果、OpenAPIを選択しました。

| 手法 | メリット | デメリット |
|------|----------|------------|
| OpenAPI | ・REST APIの標準仕様<br>・エコシステムが充実<br>・既存のSpring Bootプロジェクトに導入しやすい | ・JSON Schemaよりも表現力が若干低い<br>・アプリケーション起動が必要 |
| JSON Schema | ・バリデーション表現力が高い<br>・言語非依存 | ・Spring Bootとの統合が弱い<br>・ツールチェインが未成熟 |
| gRPC/Protocol Buffers | ・型安全性が非常に高い<br>・パフォーマンスが良い | ・既存のREST APIから移行が困難<br>・学習コストが高い |
| GraphQL | ・スキーマファーストな設計<br>・型システムが強力 | ・バリデーションルールの表現が限定的<br>・アーキテクチャの大幅な変更が必要 |

OpenAPIは、REST APIベースのプロジェクトで最も導入しやすく、ツールも充実しています。

### 使用するライブラリ

バックエンド
- springdoc-openapi: Spring BootからOpenAPI仕様を自動生成（2.7.0）
- Spring Boot標準のBean Validationをそのまま使用

フロントエンド
- openapi-typescript: OpenAPIからTypeScript型定義を生成（7.0.0）
- openapi-zod-client: OpenAPIからZodスキーマを生成（1.18.0）
- vee-validate: Vue.jsのフォームバリデーション（4.12.0）
- zod: TypeScript向けスキーマバリデーション（3.22.0）

## 全体の仕組み

### バリデーションルールの変換

Bean ValidationアノテーションがどのようにOpenAPI、そしてZodに変換されるかを示します。

| Bean Validation | OpenAPI | Zod |
|----------------|---------|-----|
| `@NotBlank` | `required: ["name"]` + `minLength: 1` | `z.string().min(1)` |
| `@Size(min=1, max=50)` | `minLength: 1, maxLength: 50` | `.min(1).max(50)` |
| `@Email` | `format: "email"` | `.email()` |
| `@Min(0)` | `minimum: 0` | `.gte(0)` |
| `@Max(150)` | `maximum: 150` | `.lte(150)` |
| `@Pattern(regexp=...)` | `pattern: "..."` | `.regex(/.../)` |
| 省略可能 (`?`) | requiredに含まれない | `.optional()` |

### 各ツールの役割

SpringDoc
- Spring Bootアプリケーションの実装を解析
- Bean Validationアノテーションを読み取る
- OpenAPI 3.0形式のJSON/YAMLを出力

openapi-typescript
- OpenAPI仕様からTypeScript型定義を生成
- `components.schemas`を型として利用可能に

openapi-zod-client
- OpenAPI仕様からZodスキーマを生成
- バリデーションルールをZodのAPIに変換

## 実装（バックエンド）

### プロジェクトセットアップ

`build.gradle.kts`に依存関係を追加します。

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}
```

### Bean Validationの実装

リクエストDTOにバリデーションアノテーションを付与します。

```kotlin
package com.example.validation.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.*

data class UserRequest(
    @field:NotBlank(message = "名前は必須です")
    @field:Size(min = 1, max = 50, message = "名前は1文字以上50文字以内で入力してください")
    @Schema(description = "ユーザー名", example = "山田太郎")
    val name: String,

    @field:NotBlank(message = "メールアドレスは必須です")
    @field:Email(message = "有効なメールアドレスを入力してください")
    @Schema(description = "メールアドレス", example = "yamada@example.com")
    val email: String,

    @field:Min(value = 0, message = "年齢は0以上である必要があります")
    @field:Max(value = 150, message = "年齢は150以下である必要があります")
    @Schema(description = "年齢", example = "30")
    val age: Int? = null,

    @field:Pattern(regexp = "^0\\d{9,10}$", message = "日本の電話番号形式ではありません")
    @Schema(
        description = "電話番号（日本形式：0から始まる10桁または11桁）",
        pattern = "^0\\d{9,10}$",
        example = "09012345678"
    )
    val phone: String? = null
)
```

重要ポイント
- `@field:`を必ず付ける（Kotlinのプロパティではなくフィールドに適用するため）
- `@Schema`アノテーションで説明やexampleを追加すると、OpenAPI仕様がリッチになる
- カスタムバリデーションの場合、`@Schema`の`pattern`属性を併記することでフロントエンドに反映される

### コントローラーの実装

```kotlin
package com.example.validation.controller

import com.example.validation.dto.UserRequest
import com.example.validation.dto.UserResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/api/users")
@Tag(name = "User", description = "ユーザー管理API")
class UserController {

    @PostMapping
    @Operation(
        summary = "ユーザー作成",
        description = "新しいユーザーを作成します。リクエストボディはBean Validationによって検証されます。"
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "ユーザー作成成功"),
            ApiResponse(responseCode = "400", description = "バリデーションエラー")
        ]
    )
    fun createUser(
        @Valid @RequestBody request: UserRequest
    ): ResponseEntity<UserResponse> {
        // 実際のビジネスロジック
        val response = UserResponse(
            id = UUID.randomUUID().toString(),
            name = request.name,
            email = request.email,
            age = request.age,
            phone = request.phone
        )
        return ResponseEntity.ok(response)
    }
}
```

重要ポイント
- `@Valid`を付けることでBean Validationが実行される
- `@Operation`, `@ApiResponses`でAPI仕様を充実させる

### エラーハンドリング

バリデーションエラー時に構造化されたレスポンスを返すため、グローバル例外ハンドラーを実装します。

```kotlin
package com.example.validation.controller

import com.example.validation.dto.ErrorResponse
import com.example.validation.dto.FieldError
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.LocalDateTime

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        ex: MethodArgumentNotValidException
    ): ResponseEntity<ErrorResponse> {
        val errors = ex.bindingResult.fieldErrors.map { error ->
            FieldError(
                field = error.field,
                message = error.defaultMessage ?: "バリデーションエラー"
            )
        }

        val errorResponse = ErrorResponse(
            timestamp = LocalDateTime.now(),
            status = HttpStatus.BAD_REQUEST.value(),
            error = HttpStatus.BAD_REQUEST.reasonPhrase,
            message = "バリデーションエラーが発生しました",
            path = "/api/users",
            errors = errors
        )

        return ResponseEntity.badRequest().body(errorResponse)
    }
}
```

実際のエラーレスポンス例：

```json
{
  "timestamp": "2025-11-10T20:55:16.313301207",
  "status": 400,
  "error": "Bad Request",
  "message": "バリデーションエラーが発生しました",
  "path": "/api/users",
  "errors": [
    {
      "field": "name",
      "message": "名前は必須です"
    },
    {
      "field": "email",
      "message": "有効なメールアドレスを入力してください"
    }
  ]
}
```

### OpenAPI仕様の確認

アプリケーションを起動して、OpenAPI仕様を確認します。

```bash
./gradlew bootRun
```

以下のURLにアクセスすると、生成されたOpenAPI仕様を確認できます。

- **OpenAPI JSON**: http://localhost:8080/v3/api-docs
- **Swagger UI**: http://localhost:8080/swagger-ui.html

生成されるOpenAPI仕様の例：

```json
{
  "components": {
    "schemas": {
      "UserRequest": {
        "required": ["email", "name"],
        "type": "object",
        "properties": {
          "name": {
            "maxLength": 50,
            "minLength": 1,
            "type": "string",
            "description": "ユーザー名",
            "example": "山田太郎"
          },
          "email": {
            "type": "string",
            "description": "メールアドレス",
            "format": "email",
            "example": "yamada@example.com"
          },
          "age": {
            "maximum": 150,
            "minimum": 0,
            "type": "integer",
            "description": "年齢",
            "format": "int32",
            "example": 30
          },
          "phone": {
            "pattern": "^0\\d{9,10}$",
            "type": "string",
            "description": "電話番号（日本形式：0から始まる10桁または11桁）",
            "example": "09012345678"
          }
        }
      }
    }
  }
}
```

Bean Validationアノテーションが正しくOpenAPI仕様に反映されていることが確認できます。

### OpenAPI仕様のエクスポート

開発中は手動で取得できますが、CI/CDで自動化するために設定を追加します。

```kotlin
// build.gradle.kts
plugins {
    id("org.springdoc.openapi-gradle-plugin") version "1.8.0"
}

openApi {
    apiDocsUrl.set("http://localhost:8080/v3/api-docs")
    outputDir.set(file("$projectDir/openapi"))
    outputFileName.set("openapi.json")
    waitTimeInSeconds.set(60)
}
```

または、シンプルにcurlで取得する方法もあります。

```bash
mkdir -p openapi
curl http://localhost:8080/v3/api-docs > openapi/openapi.json
```

## 実装（フロントエンド）

### プロジェクトセットアップ

`package.json`に必要なライブラリを追加します。

```json
{
  "name": "validation-frontend",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc && vite build",
    "generate:types": "openapi-typescript ../backend/openapi/openapi.json -o src/types/api.ts",
    "generate:zod": "openapi-zod-client ../backend/openapi/openapi.json -o src/schemas/api.ts",
    "generate": "npm run generate:types && npm run generate:zod"
  },
  "dependencies": {
    "vue": "^3.4.0",
    "vee-validate": "^4.12.0",
    "zod": "^3.22.0",
    "@vee-validate/zod": "^4.12.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "typescript": "^5.3.0",
    "vite": "^5.0.0",
    "vue-tsc": "^1.8.0",
    "openapi-typescript": "^7.0.0",
    "openapi-zod-client": "^1.18.0"
  }
}
```

### 型とスキーマの生成

バックエンドのOpenAPI仕様から、TypeScript型とZodスキーマを生成します。

```bash
npm install
npm run generate
```

#### 生成されるTypeScript型

`src/types/api.ts`（自動生成）:

```typescript
export interface components {
  schemas: {
    UserRequest: {
      name: string;
      email: string;
      age?: number;
      phone?: string;
    };
    UserResponse: {
      id: string;
      name: string;
      email: string;
      age?: number;
      phone?: string;
    };
    // ... その他のスキーマ
  };
}
```

#### 生成されるZodスキーマ

`src/schemas/api.ts`（自動生成）:

```typescript
import { z } from 'zod';

const UserRequest = z.object({
  name: z.string().min(1).max(50),
  email: z.string(),
  age: z.number().int().gte(0).lte(150).optional(),
  phone: z.string().regex(/^0\d{9,10}$/).optional()
}).passthrough();

export const schemas = {
  UserRequest,
};
```

Kotlin側で定義したバリデーションルールが、そのままZodスキーマに変換されています。

### Vue.jsでの使用（簡潔な例）

vee-validateとZodを統合した最小限の実装例です。

```vue
<script setup lang="ts">
import { useForm, useField } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { schemas } from '@/schemas/api'

// Zodスキーマをvee-validateに統合
const { handleSubmit, errors } = useForm({
  validationSchema: toTypedSchema(schemas.UserRequest)
})

// フィールドのバインディング
const { value: name } = useField<string>('name')
const { value: email } = useField<string>('email')

const onSubmit = handleSubmit(async (values) => {
  // API呼び出し
  const response = await fetch('/api/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(values)
  })

  if (response.ok) {
    console.log('登録成功')
  }
})
</script>

<template>
  <form @submit="onSubmit">
    <div>
      <input v-model="name" placeholder="名前" />
      <span v-if="errors.name">{{ errors.name }}</span>
    </div>

    <div>
      <input v-model="email" type="email" placeholder="メール" />
      <span v-if="errors.email">{{ errors.email }}</span>
    </div>

    <button type="submit">登録</button>
  </form>
</template>
```

重要ポイント
- `toTypedSchema`でZodスキーマをvee-validateに変換
- `useField`でフィールドごとにバインディングとエラー管理
- バリデーションは入力時に自動実行される

<details>
<summary>完全な実装例（エラーハンドリング含む）</summary>

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { useForm, useField } from 'vee-validate'
import { toTypedSchema } from '@vee-validate/zod'
import { schemas } from '@/schemas/api'
import type { components } from '@/types/api'

type UserRequest = components['schemas']['UserRequest']
type UserResponse = components['schemas']['UserResponse']

const successMessage = ref<string | null>(null)
const errorMessage = ref<string | null>(null)

const { handleSubmit, errors, isSubmitting } = useForm({
  validationSchema: toTypedSchema(schemas.UserRequest)
})

const { value: name } = useField<string>('name')
const { value: email } = useField<string>('email')
const { value: age } = useField<number | undefined>('age')
const { value: phone } = useField<string | undefined>('phone')

const onSubmit = handleSubmit(async (values: UserRequest) => {
  try {
    successMessage.value = null
    errorMessage.value = null

    const response = await fetch('/api/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(values)
    })

    if (!response.ok) {
      const error = await response.json()
      errorMessage.value = error.message || 'サーバーエラーが発生しました'

      // サーバー側のフィールドエラーを表示
      if (error.errors) {
        console.error('フィールドエラー:', error.errors)
      }
      return
    }

    const result: UserResponse = await response.json()
    successMessage.value = `登録完了（ID: ${result.id}）`

    // フォームをリセット
    name.value = ''
    email.value = ''
    age.value = undefined
    phone.value = ''
  } catch (e) {
    errorMessage.value = '通信エラーが発生しました'
    console.error(e)
  }
})
</script>

<template>
  <div class="form-container">
    <h1>ユーザー登録フォーム</h1>

    <div v-if="successMessage" class="success-message">
      {{ successMessage }}
    </div>

    <div v-if="errorMessage" class="error-message">
      {{ errorMessage }}
    </div>

    <form @submit="onSubmit">
      <div class="form-group">
        <label for="name">名前 *</label>
        <input
          id="name"
          v-model="name"
          type="text"
          :class="{ error: errors.name }"
          placeholder="山田太郎"
        />
        <span v-if="errors.name" class="error-text">{{ errors.name }}</span>
      </div>

      <div class="form-group">
        <label for="email">メールアドレス *</label>
        <input
          id="email"
          v-model="email"
          type="email"
          :class="{ error: errors.email }"
          placeholder="yamada@example.com"
        />
        <span v-if="errors.email" class="error-text">{{ errors.email }}</span>
      </div>

      <div class="form-group">
        <label for="age">年齢</label>
        <input
          id="age"
          v-model.number="age"
          type="number"
          :class="{ error: errors.age }"
          placeholder="30"
        />
        <span v-if="errors.age" class="error-text">{{ errors.age }}</span>
      </div>

      <div class="form-group">
        <label for="phone">電話番号</label>
        <input
          id="phone"
          v-model="phone"
          type="tel"
          :class="{ error: errors.phone }"
          placeholder="09012345678"
        />
        <span v-if="errors.phone" class="error-text">{{ errors.phone }}</span>
      </div>

      <button type="submit" :disabled="isSubmitting">
        {{ isSubmitting ? '送信中...' : '登録' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.form-container {
  max-width: 600px;
  margin: 0 auto;
  padding: 20px;
}

.form-group {
  margin-bottom: 20px;
}

label {
  display: block;
  margin-bottom: 5px;
  font-weight: 500;
}

input {
  width: 100%;
  padding: 10px;
  border: 1px solid #ddd;
  border-radius: 4px;
}

input.error {
  border-color: #f44336;
}

.error-text {
  color: #f44336;
  font-size: 12px;
  margin-top: 5px;
  display: block;
}

.success-message {
  background-color: #d4edda;
  border: 1px solid #c3e6cb;
  color: #155724;
  padding: 12px;
  border-radius: 4px;
  margin-bottom: 20px;
}

.error-message {
  background-color: #f8d7da;
  border: 1px solid #f5c6cb;
  color: #721c24;
  padding: 12px;
  border-radius: 4px;
  margin-bottom: 20px;
}

button {
  background-color: #4CAF50;
  color: white;
  padding: 12px 24px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  width: 100%;
}

button:hover {
  background-color: #45a049;
}

button:disabled {
  background-color: #ccc;
  cursor: not-allowed;
}
</style>
```

</details>

## 動作確認

実際にバリデーションが動作することを確認します。

### 正常ケース

```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "山田太郎",
    "email": "yamada@example.com",
    "age": 30,
    "phone": "09012345678"
  }'
```

レスポンス:
```json
{
  "id": "e13f95a2-4904-48af-8fb3-e82e9612b1a4",
  "name": "山田太郎",
  "email": "yamada@example.com",
  "age": 30,
  "phone": "09012345678"
}
```

### バリデーションエラーケース

```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "",
    "email": "invalid-email",
    "age": 200
  }'
```

レスポンス:
```json
{
  "timestamp": "2025-11-10T20:55:16.313301207",
  "status": 400,
  "error": "Bad Request",
  "message": "バリデーションエラーが発生しました",
  "path": "/api/users",
  "errors": [
    {
      "field": "name",
      "message": "名前は必須です"
    },
    {
      "field": "name",
      "message": "名前は1文字以上50文字以内で入力してください"
    },
    {
      "field": "age",
      "message": "年齢は150以下である必要があります"
    },
    {
      "field": "email",
      "message": "有効なメールアドレスを入力してください"
    }
  ]
}
```

### カスタムバリデーションの確認

```bash
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "テスト",
    "email": "test@example.com",
    "phone": "1234567890"
  }'
```

レスポンス:
```json
{
  "timestamp": "2025-11-10T20:55:23.825507633",
  "status": 400,
  "error": "Bad Request",
  "message": "バリデーションエラーが発生しました",
  "path": "/api/users",
  "errors": [
    {
      "field": "phone",
      "message": "日本の電話番号形式ではありません"
    }
  ]
}
```

電話番号のカスタムバリデーション（正規表現）が正しく機能しています。

## 開発ワークフロー

### 日常的な開発での使い方

バックエンド開発者がバリデーションを変更したとき

1. KotlinのDTOを修正
2. アプリケーションを起動してOpenAPI仕様を更新
3. フロントエンドリポジトリに変更を通知（または自動化）

フロントエンド開発者がすること

1. バックエンドの変更を取得
2. `npm run generate`を実行
3. 型エラーがあれば修正
4. バリデーションが自動的に反映される

### チーム開発での運用

推奨フロー

1. バックエンドの変更をPR
   - DTOの変更をプルリクエストとして提出
   - レビュー後にマージ

2. OpenAPI仕様を自動更新
   - CI/CDでOpenAPI仕様を自動生成
   - フロントエンドリポジトリに自動コミット

3. フロントエンドの型を自動更新
   - OpenAPI仕様の変更を検知
   - 型とスキーマを自動生成
   - PRとして自動作成

4. フロントエンド開発者が確認
   - 自動生成されたPRをレビュー
   - 必要に応じてUI/UXを調整

### CI/CDへの組み込み

#### パターン1: Gradle Pluginを使用

`build.gradle.kts`:

```kotlin
plugins {
    id("org.springdoc.openapi-gradle-plugin") version "1.8.0"
}

openApi {
    apiDocsUrl.set("http://localhost:8080/v3/api-docs")
    outputDir.set(file("$projectDir/openapi"))
    outputFileName.set("openapi.json")
    waitTimeInSeconds.set(60)
}

tasks.named("build") {
    dependsOn("generateOpenApiDocs")
}
```

#### パターン2: GitHub Actionsで自動化

`.github/workflows/generate-types.yml`:

```yaml
name: Generate Frontend Types

on:
  push:
    branches: [main, develop]
    paths:
      - 'backend/src/**/*.kt'

jobs:
  generate:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v3

      - name: Setup Java
        uses: actions/setup-java@v3
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: Build and Start Spring Boot
        run: |
          cd backend
          ./gradlew build
          java -jar build/libs/*.jar &

          # アプリケーション起動待機
          for i in {1..30}; do
            if curl -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
              echo "Application is ready"
              break
            fi
            echo "Waiting for application to start..."
            sleep 2
          done

      - name: Download OpenAPI spec
        run: |
          mkdir -p backend/openapi
          curl http://localhost:8080/v3/api-docs > backend/openapi/openapi.json

      - name: Setup Node.js
        uses: actions/setup-node@v3
        with:
          node-version: '20'

      - name: Generate TypeScript types
        run: |
          cd frontend
          npm ci
          npm run generate

      - name: Commit and push if changed
        run: |
          git config --local user.email "github-actions[bot]@users.noreply.github.com"
          git config --local user.name "github-actions[bot]"
          git add backend/openapi/openapi.json frontend/src/types frontend/src/schemas
          git diff --quiet && git diff --staged --quiet || \
            (git commit -m "chore: update generated types [skip ci]" && git push)
```

重要ポイント
- `[skip ci]`を付けて無限ループを防ぐ
- Kotlin側の変更を検知して自動実行
- 生成された型を自動コミット

## トラブルシューティング

### よくある問題と解決方法

#### Q1: emailのバリデーションがZodに反映されない

症状
```typescript
// 期待: z.string().email()
// 実際: z.string()
```

原因
openapi-zod-clientのバージョンによっては`format: "email"`が反映されない場合があります。

解決方法
1. openapi-zod-clientを最新版に更新
2. または、`@Pattern`で正規表現を明示的に指定

```kotlin
@field:Pattern(
    regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$",
    message = "有効なメールアドレスを入力してください"
)
@Schema(pattern = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
val email: String
```

#### Q2: 生成された型が期待と異なる

症状
必須フィールドが`optional()`になっている、または逆のケース。

原因
- Kotlinの`?`（nullable）とBean Validationの`@NotNull`/@NotBlankの組み合わせが正しくない
- OpenAPI仕様の`required`配列に含まれていない

解決方法
```kotlin
// 必須フィールド
@field:NotBlank
val name: String  // nullable にしない

// 任意フィールド
val age: Int? = null  // nullable にする（デフォルト値推奨）
```

#### Q3: カスタムバリデーションが反映されない

症状
独自のバリデーションアノテーションがOpenAPIに出力されない。

原因
`@Schema`アノテーションでパターンを明示していない。

解決方法
```kotlin
@field:JapanesePhone
@Schema(
    description = "電話番号",
    pattern = "^0\\d{9,10}$",  // これを必ず追加
    example = "09012345678"
)
val phone: String?
```

#### Q4: 型生成が失敗する

症状
```
Error: Cannot read properties of undefined (reading 'schemas')
```

原因
- OpenAPI仕様が正しく生成されていない
- OpenAPI仕様のパスが間違っている

解決方法
1. OpenAPI仕様が生成されているか確認
   ```bash
   curl http://localhost:8080/v3/api-docs
   ```

2. ファイルパスを確認
   ```json
   {
     "scripts": {
       "generate:types": "openapi-typescript ../backend/openapi/openapi.json -o src/types/api.ts"
     }
   }
   ```

#### Q5: vee-validateでバリデーションが効かない

症状
フォーム送信時にZodのバリデーションが実行されない。

原因
- `toTypedSchema`でラップしていない
- `useForm`の`validationSchema`に渡していない

解決方法
```typescript
import { toTypedSchema } from '@vee-validate/zod'
import { schemas } from '@/schemas/api'

const { handleSubmit, errors } = useForm({
  validationSchema: toTypedSchema(schemas.UserRequest) // toTypedSchemaを使用
})
```

### パフォーマンスに関する注意

型生成のコスト
- openapi-typescript: 数百ms（OpenAPI仕様のサイズに依存）
- openapi-zod-client: 1-2秒（スキーマが多い場合）

推奨事項
- 型生成はビルド時またはCI/CDで実行
- 開発中は変更があったときのみ手動実行
- `watch`モードは不要（バックエンドの変更頻度は低いため）

## 実際に使ってみて

### メリット

1. 開発体験の向上

バリデーションルールの変更がフロントエンドに自動反映されるため、「バックエンドでルール変えたから、フロント側も直しておいてね」というコミュニケーションが不要になります。

2. 型安全性の向上

APIのリクエスト・レスポンスが完全に型安全になるため、間違ったプロパティ名を使うとコンパイルエラーになります。実行前にミスに気付けます。

3. ドキュメント不要

OpenAPI仕様自体がドキュメントとして機能するため、別途APIドキュメントを書く必要がありません。

4. セキュリティの適切な実装

サーバーサイドが常に最終的な検証を行う構造になっているため、セキュリティが担保されます。フロントエンドのバリデーションを迂回されても、サーバー側で必ず検証が実行されます。

**注意**: フロントエンドのバリデーションはあくまでUX向上のためのものです。セキュリティやビジネスロジックの正当性は、必ずサーバーサイドで担保してください。

### デメリットと制約

1. 表現力の限界

Bean Validationで表現できないような複雑なバリデーション（例: データベースへの重複チェック、他のAPIを呼ぶ処理）は同期されません。

2. 複数フィールドにまたがるバリデーション

Cross-field validation（例: 「開始日は終了日より前でなければならない」）は同期できません。

3. 動的なバリデーション

実行時に条件によって変わるバリデーションルールは表現できません。

4. セットアップの初期コスト

環境構築には時間がかかります。小規模なプロジェクトでは過剰な仕組みになります。

### この手法が適している場面

- REST APIベースのWebアプリケーション
- バックエンドとフロントエンドが別リポジトリ
- チーム開発（特に分業体制）
- 長期運用が想定されるプロジェクト
- APIの変更頻度が高い

### この手法が適していない場面

- 小規模な個人プロジェクト
- プロトタイピング段階
- REST API以外（GraphQL、gRPC等）をメインに使用
- バックエンドとフロントエンドが密結合（モノリポ）

## まとめ

Kotlin側でBean Validationを定義し、OpenAPI経由でフロントエンドに自動同期する仕組みを構築しました。

実現したこと
- バリデーションルールの一元管理（DRY原則の徹底）
- 型安全なAPI呼び出し
- 変更への自動追従
- セキュリティの向上

重要なポイント
- サーバーサイドが真実の源として機能（セキュリティとドメイン正当性の担保）
- フロントエンドのバリデーションはUX向上のための補助的な役割
- 共通化できるのは基本的なフォーマットチェックのみ
- 複雑なビジネスロジックやセキュリティ検証はサーバー側のみで実装

**責務の明確化**

この手法は「バリデーションの共通化」ではなく、「UX向上のためのフロントエンドバリデーションを、サーバーサイドの定義から自動生成する」という位置づけです。

- サーバーサイド: セキュリティとドメインの正当性を保証（必須）
- フロントエンド: ユーザーへの早期フィードバック（任意・補助的）

この責務分担を守りつつ、基本的なバリデーションルールの二重管理から解放されることで、開発体験が向上します。

## 参考文献

- [SpringDoc OpenAPI - 公式ドキュメント](https://springdoc.org/)
- [openapi-typescript - GitHub](https://openapi-ts.pages.dev/)
- [openapi-zod-client - GitHub](https://github.com/astahmer/openapi-zod-client)
- [vee-validate - 公式ドキュメント](https://vee-validate.logaretm.com/v4/)
- [Zod - 公式ドキュメント](https://zod.dev/)
- [Bean Validation - Spring公式ドキュメント](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html)
