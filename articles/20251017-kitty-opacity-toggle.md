---
title: "Kittyターミナルで透明度をトグルするカスタムkittenの実装"
emoji: "🐱"
type: "tech"
topics: ["kitty", "python", "terminal"]
published: false
---

## TL;DR

Kittyターミナルで背景の透明度を1キーでトグルする機能を、カスタムkittenとして実装しました。`kitty.fast_data_types`モジュールを使用して現在の透明度を取得し、0.6と1.0の間で切り替えます。

## はじめに

Kittyターミナルエミュレータを使っていて、背景の透明度を簡単に切り替えたいと思ったことはありませんか？

例えば、コーディング中は背景を透明にして後ろの資料を見ながら作業し、プレゼンテーション中は不透明にして見やすくする、といった使い方です。

Kittyには`set_background_opacity`というビルトインアクションがありますが、これは絶対値を指定する必要があり、トグル動作はできません。そこで、カスタムkittenを使って透明度をトグルする機能を実装してみました。

## Kittyのカスタムkittenとは

Kittyのkittenは、ターミナルの機能を拡張するためのPythonスクリプトです。Kittyの内部APIにアクセスでき、ウィンドウやタブの操作、設定の変更などが可能です。

カスタムkittenは、`~/.config/kitty/kittens/`ディレクトリに配置し、以下の2つの関数を定義します。

```python
from kitty.boss import Boss

def main(args: list[str]) -> str:
    # ユーザー入力などの処理
    return result

def handle_result(args: list[str], answer: str,
                  target_window_id: int, boss: Boss) -> None:
    # kittyへの操作を実行
    pass
```

## 実装の試行錯誤

### 最初の失敗: subprocessを使った実装

最初は、`subprocess`を使って`kitten @ set-background-opacity`コマンドを呼び出す実装を試みました。

```python
import subprocess

def handle_result(args, answer, target_window_id, boss):
    new_opacity = 1.0 if _is_transparent else 0.6
    subprocess.run(['kitten', '@', 'set-background-opacity', str(new_opacity)])
```

しかし、この方法ではKittyアプリケーションがフリーズする問題が発生しました。Kittyのイベントループをブロックしてしまうためです。

### 第二の試み: Boss APIの直接使用

次に、`boss.set_background_opacity()`を直接呼び出す方法を試しました。

```python
def handle_result(args, answer, target_window_id, boss):
    os_window_id = boss.active_window.os_window_id
    boss.set_background_opacity(str(new_opacity), os_window_id)
```

しかし、以下のエラーが発生しました。

```
Boss.set_background_opacity() takes 2 positional arguments but 3 were given
```

また、現在の透明度を取得する方法も見つかりませんでした（`boss.get_background_opacity()`は存在しない）。

### 解決策: kitty.fast_data_typesを使用

[GitHubのissue #6691](https://github.com/kovidgoyal/kitty/issues/6691)で、Kittyの開発者が推奨している方法を見つけました。`kitty.fast_data_types`モジュールを使用する方法です。

## 最終的な実装

### ディレクトリ構成

```
~/.config/kitty/
├── kitty.conf
└── kittens/
    └── toggle_opacity.py
```

### toggle_opacity.py

```python
#!/usr/bin/env python3
"""透明度を切り替えるkitten"""

from typing import List
from kitty.boss import Boss


def main(args: List[str]) -> str:
    return ""


def handle_result(args: List[str], answer: str, target_window_id: int, boss: Boss) -> None:
    """透明度を切り替える"""
    import kitty.fast_data_types as f

    # 透明度の値を設定
    transparent = 0.6
    opaque = 1.0

    # 現在フォーカスされているOSウィンドウIDを取得
    os_window_id = f.current_focused_os_window_id()

    # 現在の透明度を取得
    current_opacity = f.background_opacity_of(os_window_id)

    # 透明度を切り替え
    if current_opacity < 0.8:
        new_opacity = opaque
    else:
        new_opacity = transparent

    # 透明度を設定
    boss.set_background_opacity(str(new_opacity))


handle_result.no_ui = True
```

### kitty.confの設定

```conf
# 動的な透明度変更を有効化
dynamic_background_opacity yes

# リモートコントロールを有効化
allow_remote_control yes

# 透明度切り替え: Ctrl+a → g
map ctrl+a>g kitten kittens/toggle_opacity.py
```

## 実装のポイント

### 1. kitty.fast_data_typesモジュールの使用

`kitty.fast_data_types`は、KittyのC拡張モジュールで、高速な操作が可能です。

```python
import kitty.fast_data_types as f

# 現在フォーカスされているウィンドウIDを取得
os_window_id = f.current_focused_os_window_id()

# 現在の透明度を取得
current_opacity = f.background_opacity_of(os_window_id)
```

このモジュールを使うことで、現在の透明度を正確に取得できます。グローバル変数で状態を管理する必要がなく、より堅牢な実装になります。

### 2. dynamic_background_opacityの設定

`dynamic_background_opacity yes`を設定しないと、実行時に以下のエラーが発生します。

```
Cannot change background opacity
You must set the dynamic_background_opacity option in kitty.conf
```

この設定により、実行時に透明度を動的に変更できるようになります。

### 3. boss.set_background_opacityの引数

`boss.set_background_opacity()`は、透明度の値（文字列）のみを引数として受け取ります。ウィンドウIDは指定せず、現在フォーカスされているウィンドウに自動的に適用されます。

```python
# 正しい使い方
boss.set_background_opacity(str(new_opacity))

# 間違った使い方（エラーになる）
boss.set_background_opacity(str(new_opacity), os_window_id)
```

### 4. handle_result.no_ui = True

```python
handle_result.no_ui = True
```

この設定により、kittenがUIを表示せずに直接`handle_result`関数を実行します。透明度の切り替えのような単純な操作では、ユーザー入力は不要なため、この設定が適しています。

## 動作確認

実装後、以下のように動作します。

1. Kittyを起動
2. `Ctrl+a`を押してから`g`を押す
3. 透明度が0.6（透明）と1.0（不透明）の間で切り替わる

現在の透明度を取得してから判断するため、Kittyを再起動しても正しく動作します。

## まとめ

Kittyのカスタムkittenを使って、透明度をトグルする機能を実装しました。

実装のポイント:
- `kitty.fast_data_types`モジュールで現在の透明度を取得
- `boss.set_background_opacity()`で透明度を設定
- `dynamic_background_opacity yes`の設定が必須
- subprocessの使用は避ける（アプリがフリーズする）

この実装は、GitHubのissueで開発者が推奨している方法に基づいており、安定して動作します。

カスタムkittenは、Kittyの機能を柔軟に拡張できる強力な仕組みです。他にも様々な機能を追加できるので、ぜひ試してみてください。

## 参考資料

- [Kitty - Custom kittens](https://sw.kovidgoyal.net/kitty/kittens/custom/)
- [Kitty - Remote control](https://sw.kovidgoyal.net/kitty/remote-control/)
- [GitHub Issue #6691 - Toggle background opacity](https://github.com/kovidgoyal/kitty/issues/6691)
- [Kitty - Actions](https://sw.kovidgoyal.net/kitty/actions/)
