## はじめに

Web周りの開発だと、フロントエンドサーバー、バックエンドサーバーみたいに複数のサーバーを同時に起動して、かつログも出力し続けたいことが多いです。
特に、マルチリポジトリ構成や、サーバーが多い場合にはなおさらです。

毎回手動で新しいタブを開いて、分割して、それぞれのディレクトリに移動して、コマンド実行してという作業を繰り返すのは正直めんどくさいです。

kittyのリモートコントロール機能を使えば、この一連の流れを1つのショートカットキーやスクリプトで自動化できます。
この記事では、まずkittyの「kitten」という仕組みを理解した上で、実際の設定方法を見ていきます。

## kittyとkittensとは

https://sw.kovidgoyal.net/kitty/

kittyは高速でGPUベースのターミナルエミュレータです。この記事では詳細は割愛しますが、簡単に言うとターミナルエミュレータとして必要な機能が揃っている上に、拡張性が高いのが特徴です。WezTermやAlacrittyを使っていたこともありますが、結局kittyに落ち着きました。

### kittensの仕組み

kittensは、kittyの機能を拡張するための小さなターミナルプログラムです。kittyには以下のようなビルトインkittensが用意されています。

- icat: ターミナルで画像を表示
- diff: シンタックスハイライト付きのdiff表示
- unicode_input: Unicode文字の入力
- themes: カラーテーマの切り替え
- hints: URL、ファイルパス、単語などを選択して操作

これらは`kitty +kitten <kitten名>`という形式で実行できます。例えば、画像を表示したい場合は以下のようになります。
```bash
kitty +kitten icat image.png
```

### kitten @の特殊な役割

kittensの中でも特に重要なのが`kitten @`です。これはkittyをリモートコントロールするための専用コマンドで、kittyの動作を外部から制御できます。

`@`記号が付いている理由は、これがリモートコントロール専用の特別なkittenだからです。通常のkittensとは異なり、kittyインスタンス自体を操作するためのインターフェースとして機能します。
```bash
# 新しいウィンドウを開く
kitten @ launch --type=window vim

# タブのタイトルを変更
kitten @ set-tab-title "Development"

# 特定のウィンドウにテキストを送信
kitten @ send-text --match title:terminal "echo hello"
```

`kitten @`を使うには、`kitty.conf`で`allow_remote_control`を有効にする必要があります。

## 新しいタブで複数サーバーを起動する方法

それでは実際に、新しいタブを作成して複数のサーバーを分割ペインで起動する設定を見ていきます。

まず、`~/.config/kitty/kitty.conf`でリモートコントロールを有効化します。
```conf
# リモートコントロールを有効化
allow_remote_control yes

# splitsレイアウトを有効化
enabled_layouts splits

# F1キーにバインド
map ctrl+shift+s launch --type=background sh -c '~/.config/kitty/start-servers.sh'
```

続いて、サーバー起動用のスクリプトを作成します。
```bash
#!/bin/bash
# ~/.config/kitty/start-servers.sh

# 最初のウィンドウ(新しいタブで操作用)
kitten @ launch --type=tab --tab-title "Dev Environment" \
    --cwd=/path/to/project bash -c 'echo "Dev Environment"; exec bash'

sleep 0.2

# 右側に垂直分割でフロントエンド
kitten @ launch --type=window --location=vsplit \
    --cwd=/path/to/frontend bash -c 'npm run dev; exec bash'

sleep 0.2

# フロントエンドの下に水平分割でバックエンド
kitten @ launch --type=window --location=hsplit \
    --cwd=/path/to/backend bash -c 'npm start; exec bash'

sleep 0.2

# バックエンドの下に水平分割でAPI
kitten @ launch --type=window --location=hsplit \
    --cwd=/path/to/api bash -c 'python3 server.py; exec bash'

sleep 0.2

# 最初のウィンドウにメッセージを送信
kitten @ send-text --match num:0 'echo "[サーバーの起動が完了しました]"\n'
```

実行権限を付与します。
```bash
chmod +x ~/.config/kitty/start-servers.sh
```

これで、Ctrl+Shift+Sを押すだけで新しいタブが開き、複数のサーバーが分割ペインで起動するようになります。

このスクリプトでは以下のようなレイアウトになります:
- 左側: 操作用のペイン
- 右側上: フロントエンドサーバー
- 右側中: バックエンドサーバー
- 右側下: APIサーバー

各サーバー起動コマンドには`; exec bash`を付けているため、エラーが発生してもウィンドウは閉じずに残ります。エラー内容を確認して、手動で修正や再実行が可能です。

最後の`kitten @ send-text`は、操作用ペインに起動完了メッセージを送信しています。


### kittenのオプション解説

スクリプトで使用しているオプションについて詳しく説明します。

#### `kitten @ launch`の主要オプション

**`--type`**: 新しいウィンドウの作成場所を指定します。
- `window`: 現在のタブ内に新しいウィンドウを作成
- `tab`: 新しいタブを作成
- `os-window`: 新しいOSウィンドウを開く
- `background`: ウィンドウなしでバックグラウンド実行

**`--location`**: 既存ウィンドウ内での新規ウィンドウの配置位置を指定します。
- `vsplit`: 垂直分割(左右に並べる)
- `hsplit`: 水平分割(上下に並べる)
- `split`: 自動分割(ウィンドウサイズに応じて決定)
- `after`: アクティブウィンドウの後ろに配置
- `before`: アクティブウィンドウの前に配置

**`--cwd`**: 新規プロセスの作業ディレクトリを指定します。
- 絶対パスで指定: `/path/to/directory`
- `current`: ソースウィンドウの作業ディレクトリを使用
- `last_reported`: シェル統合で報告された最後のディレクトリ
- `root`: ウィンドウ作成時に開始されたプロセスのディレクトリ

**`--tab-title`**: タブのタイトルを設定します。

**`--keep-focus`**: 現在のウィンドウにフォーカスを保持します。

#### `kitten @ focus-window`

指定されたウィンドウにキーボードフォーカスを移動させます。

**`--match`**: ウィンドウを識別するためのパターンを指定します。
- `title:タイトル`: ウィンドウタイトルで検索
- `num:番号`: ウィンドウ番号で検索(0から開始)
- `id:ID`: ウィンドウIDで検索
- `pid:プロセスID`: プロセスIDで検索

例: `--match num:0`は最初のウィンドウを指定します。


## まとめ

kittyの`kitten @`を使えば、新しいタブで複数のサーバーを分割ペインで自動起動できます。開発開始時の定型作業を自動化できるので、効率化につながります。

kittensという仕組み自体も面白くて、カスタムkittenを作れば独自の機能も追加できますのでおすすめです。

## 参考文献

https://sw.kovidgoyal.net/kitty/
https://sw.kovidgoyal.net/kitty/kittens_intro/
https://sw.kovidgoyal.net/kitty/remote-control/
https://sw.kovidgoyal.net/kitty/launch/
https://sw.kovidgoyal.net/kitty/layouts/
https://github.com/kovidgoyal/kitty/discussions/5108
