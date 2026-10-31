# ローカルスキーマの範囲

この構成は段階1です。`webapp/sql/001-schema.sql` の初期アプリのテーブル、主キー、外部キー、画像の月別パーティションを維持して、YSQL上に作成します。初期実装は `users(id)` を多くのテーブルが参照し、`posting_windows(id)` も `posts` と `notifications` が参照します。`region` をこれらの主キーや一意制約に足すだけの変更は行っていません。

## 実装済みの配置

| 対象 | レプリカ配置 | リーダー優先 |
| --- | --- | --- |
| 初期アプリのテーブルとインデックス | `local_global`: Asia / Europe / America 各1、RF3 | 地域指定なし |
| `locality_probe_asia` | Asia 2、Europe 1 | Asia、次にEurope |
| `locality_probe_europe` | Europe 2、America 1 | Europe、次にAmerica |
| `locality_probe_america` | America 2、Asia 1 | America、次にAsia |

`locality_probe` は `region` の LIST partition と対応 tablespace を動作確認する専用の合成テーブルです。アプリのデータが地域別に配置されたことを表すものではありません。各地域の2レプリカは別TServer identityですが、同じMac上で動くため物理的な地域障害対策にはなりません。また、local 2 + remote 1はlocal地域全体を失うと当該tabletの過半数を失う配置です。

`users.username` にグローバルな一意インデックスを追加しました。初期登録APIは全ユーザーを対象に同名判定し、削除済みユーザーも除外しないため、その仕様を維持します。`region` の更新でIDやusernameの一意性は変わらず、既存FKもそのままです。登録APIの `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE` は別途実動作検証が必要です。

## データの選択

- `SEED_MODE=smoke`（既定）: 3地域各1名の合成ユーザーと、各地域1件の投稿期間を作成します。画像や実ユーザーのデータはありません。ローカル動作確認用のユーザー名は `smoke_asia` / `smoke_europe` / `smoke_america`、共通secretは `local-smoke-secret` です。
- `SEED_MODE=empty`: アプリデータは空。配置確認用の `locality_probe` だけを残します。
- `SEED_MODE=external`: `/seed/data.sql` にマウントした信頼できる data-only SQL を読み込みます。公式データはまだ同梱されていません。未完成のdumpを使わないでください。

external SQLはYSQLクライアントで実行するプログラムです。自分で確認した公式ファイルのみを指定してください。DDL、tablespace設定、所有者変更、接続切替、resetスクリプトを含むフルdumpはこの入口の対象ではありません。配布形式が判明したら、初期schemaとの列・型の対応、COPY対象、YSQL対応を先に確認します。新しい空のCompose project/volumeで `external` を選んで初期化し、既存smoke DBへの追記で置換を代用しないでください。

選択したデータ投入後に `003-initial-snapshot.sql` で初期状態を保存します。初期化が完了すると `local_bootstrap_metadata` に完了マーカーを保存し、その後の起動は既存データを保持します。途中失敗して既存DBが残っている場合、自動DROPやschema再適用はせず終了します。ログを確認して、新しい空volumeでの再試行または明示的なローカル復旧を行ってください。モード変更は既存DBには適用されません。

## 段階2で必要な変更（未実装）

アプリデータの完全地域化には、ID/usernameのグローバルレジストリを保持しながら、地域ごとの実体テーブルとFKを設計する必要があります。関連・セッション・投稿・画像・通知の所有地域、地域をまたぐ参照、地域移動時の関連行移動、登録との競合、初期化と公式seedの変換まで一括で扱う必要があります。特に、プロフィールのregion変更だけをpartition key更新に置き換えると、そのユーザーに属する他テーブルは移動しません。

段階1の動作確認は、全地域化や最適化版と同じレイテンシーを保証しません。

## 参照した公式資料

- [Global and geo-local tables](https://docs.yugabyte.com/stable/develop/build-global-apps/real-world-scenarios/globally-local/): LIST partition とtablespaceの指定。
- [Read Committed isolation](https://docs.yugabyte.com/stable/architecture/transactions/read-committed/): 手動起動では `yb_enable_read_committed_isolation=true` が必要。`SHOW transaction_isolation` だけでは実装有効化の証拠にならないため、2接続でstatement snapshotを検証します。

資料のstableページは更新されるため、固定イメージでの実行結果を最終判断にします。
