# Java用の地域分割スキーマ

`isureal_java` は新しいローカル開発用DBです。Go用 `isureal` には変更を加えません。初期実装の15 entityをすべてregion LIST partitionに配置し、グローバルID・username・関係pair・外部キーを別の制約レジストリで維持します。地域列を単純にUNIQUEへ追加した構成ではありません。

## 配置と制約

- `public.users` など初期SQLの名前は単純な投影viewです。列名・型・defaultを維持します。
- `geo.data_*` が実体テーブルで、内部列 `placement_region` によるAsia / Europe / Americaの3子partitionを持ちます。合計45子テーブルです。
- 子テーブルと57副次indexはそれぞれ `local_asia` / `local_europe` / `local_america` tablespaceに置きます。RF3、対象地域2 voting replicas＋次地域1、対象地域leader優先です。初期化時、子indexも明示的に対応tablespaceへ移動します。
- `geo.registry_*` は各地域1レプリカの `local_global` tablespaceです。グローバル主キー、一意制約、元schemaの外部キー、部分一意インデックスを保持します。`users.username` は削除済みを含めて全地域で一意です。
- 実体の `(ID, placement_region)` はregistryの `(ID, geo_region)` を複合FKで参照します。`image_objects` は元の `(id, created_at)` identityを維持します。同じIDが別地域に重複することを複合主キーだけに任せていません。
- viewのINSTEAD OF triggerがregistryと実体を同じトランザクション内で更新します。物理削除はregistry側の元FK制約を満たす場合のみ可能で、実体へCASCADEします。soft deleteの意味は初期アプリと同じです。

所有地域は次の規則です。

| Entity | 所有地域 |
| --- | --- |
| users | 自身のregion |
| posting_windows | 自身のregion |
| image_objects / login_sessions / notifications | 所有user |
| posts / comments | author |
| friendships | user_low |
| follows / block_relations | follower / blocker |
| friend_requests / realmojis | requester / reactor |
| administrators / ip_bans | 地域列のない制御用データなのでAsia固定 |
| administrator_sessions | administratorと同地域 |

ユーザーのregion変更は、global user registryから所有entityのregistry、さらに実体へ複合FKの `ON UPDATE CASCADE` で伝播します。ユーザー自身を含め11 entityの原子的移動を実DBで確認しました。他ユーザーが所有するコメントや関係行は、その所有者の地域に残ります。過去の投稿窓の地域も変えません。

global registry更新は地域をまたぐ処理です。地域別実体とlocal leaderがあっても、すべてのトランザクションが地域内だけで完結するわけではありません。Mac内3論理地域は実際の耐地域障害を提供しません。local2を置いた地域を失うとそのtabletは過半数を失います。

## アプリSQLとの契約

`isureal_java_app` は通常のLOGIN roleで、public viewsに対するSELECT/INSERT/UPDATE/DELETEだけを許可します。registry・物理表へ直接アクセスできません。triggerは固定search_pathのSECURITY DEFINER関数で、`public` と `geo` への一般CREATE権限を取り除いています。ローカルDockerのtrust認証を使います。本番認証設定を流用するものではありません。

固定版 `yugabytedb/yugabyte:2025.2.8.0-b60` で次を実確認しています。

- 省略列default、INSERT RETURNING、DELETE affected row count。
- `LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE`、`FOR NO KEY UPDATE`、投稿窓の`FOR UPDATE`、joinの`FOR UPDATE OF c/r`。
- 複合FKによる地域partition間CASCADE移動。
- グローバルID/username/pair/active-realmoji一意性、参照先のないINSERT拒否、参照されたuserの削除拒否。

viewのUPDATE/DELETE triggerは基底行をロックし、当初読み取ったOLDと現行値が異なればSQLSTATE `40001` を返します。古いNEW値で他の更新を上書きすることを防ぐためです。アプリは `40001` / `40P01` に対してトランザクション全体を上限付きで再試行する必要があります。任意SQLについて通常テーブルと完全に同じRC再評価を保証する設計ではありません。

`TRUNCATE view`、viewへの`COPY`、主キー列の変更、view上の`ON CONFLICT`はこの入口の対象外です。PKの関数従属性を仮定する `GROUP BY id` はviewで成立しないため、Java SQLでは必要列を明示します。画像は引き続きDBのbyteaに保存しますが、実体は月別partitionから地域partitionへ変更しています。公式seed未入手のため `/initialize` はJava側で503を維持します。

## 起動と検証

リポジトリrootから実行します。

```sh
docker compose -f compose.yaml -f compose.geo.yaml -f compose.java.yaml --profile java up -d --build
# 合成データだけで検証し、SQL側はROLLBACK、並行試験は自分の行を削除する。
docker compose exec -T tserver-asia-1 /home/yugabyte/bin/ysqlsh -X -h localhost -p 5433 -U yugabyte -d isureal_java -v ON_ERROR_STOP=1 < docker/local/geo/verify.sql
python3 docker/local/geo/verify-concurrency.py
docker compose exec -T tserver-asia-1 bash -s < docker/local/geo/verify-placement.sh
```

`generate.py` が保存済み初期schemaを読み、`schema.sql` と `entities.json` を生成します。生成結果はコミット対象です。`python3 docker/local/geo/generate.py` で再生成できます。

## seedとバックアップ入口

`JAVA_SEED_MODE=smoke` は既定で、合成userと投稿期間を3地域各1件作ります。`empty` はアプリデータなしです。新DB作成時だけ適用され、`geo.bootstrap_metadata` 完了マーカーがある既存DBは保持します。初期化途中で失敗したschemaを自動上書きしません。

`external` は信頼できる **親table名に正規化された、data-only INSERT SQL** を `/seed/data.sql` からアプリroleで単一トランザクションとして投入します。公式データは同梱されていません。通常のPG dumpは、`image_objects_YYYYMM` など子table名へINSERTする場合があり、そのまま使えません。COPY、DDL、所有者変更、schema復元、内部geo表・registryへの直接INSERTは対象外です。INSERTは `users` / `administrators` → 画像・session・関係 → 投稿窓 → 投稿 → reaction/comment/notification のFK順にします。

任意の既存smoke DBへ追記して置換しません。空の新規ローカルcluster/volumeで次のoverrideを使います。

```sh
# GEO_SEED_FILEは内容確認済みの絶対パス。既存DBは変更されない。
GEO_SEED_FILE=/absolute/path/parent-inserts.sql docker compose \
  -f compose.yaml -f compose.geo.yaml -f compose.java.yaml \
  -f docker/local/geo/compose.seed.yaml --profile java up -d --build
```

`export.py` はpublic viewsを単一のread-only REPEATABLE READ snapshotで読み、FK順の親名INSERTを出力する専用ツールです。内部registryを二重投入せず、画像bytea・NULL・timestampもSQL literal化します。既存出力ファイルを上書きしません。

2026-10-31、ユーザー承認のもとローカル合成データだけをexportし、別DBへの復元を確認しました。15 entity・206行の全列（画像bytea・NULL・timestampを含む）と地域別件数が一致しました。公式seedの復元検証ではありません。

再現時は書き込み元アプリを停止してから比較します。以下のDB名・ファイルは未使用のものを指定してください。既存DBを上書きしません。

```sh
docker compose -f compose.yaml -f compose.geo.yaml -f compose.java.yaml --profile java stop app-java-asia app-java-europe app-java-america
python3 docker/local/geo/export.py --output /tmp/geo-synthetic.sql
docker compose exec -T tserver-asia-1 /home/yugabyte/bin/ysqlsh -X -h localhost -p 5433 -U yugabyte -d yugabyte -v ON_ERROR_STOP=1 -c "CREATE DATABASE isureal_geo_restore_test"
docker compose exec -T tserver-asia-1 /home/yugabyte/bin/ysqlsh -X -h localhost -p 5433 -U yugabyte -d isureal_geo_restore_test -v ON_ERROR_STOP=1 < docker/local/geo/schema.sql
docker compose exec -T tserver-asia-1 /home/yugabyte/bin/ysqlsh -X -1 -h localhost -p 5433 -U isureal_java_app -d isureal_geo_restore_test -v ON_ERROR_STOP=1 < /tmp/geo-synthetic.sql
python3 docker/local/geo/compare-restore.py isureal_java isureal_geo_restore_test
docker compose -f compose.yaml -f compose.geo.yaml -f compose.java.yaml --profile java start app-java-asia app-java-europe app-java-america
```

照合toolは両DBをread-only snapshotで読み、行を主キー順に比較します。全列の等価性とentity別SHA256、地域別件数を確認し、行データは表示しません。照合後に不要な一時DBとexportファイルを削除してください。

## 検証記録

2026-10-31、上記固定YB版、既存6 TServer / 3 MasterのMac Docker環境で確認。

- `verify.sql`: 15 entity / 45 partition、SQL互換・制約・所有地域移動・限定role: PASS。
- `verify-concurrency.py`: RC statement snapshot、地域間table lock、移動vs子INSERT、同時username衝突、並行更新lost-update防止: 5/5 PASS。
- `verify-placement.sh`: 45実体table + 57副次index、全102 tabletについて実Raft replicaがlocal2＋remote1、leaderがlocal地域: PASS。
- Bash/Python構文、生成再現性、Compose結合config、diff whitespace: PASS。
- geo logical export/restore: 別DBへ限定roleで単一transaction復元、15 entity / 206行・地域件数一致: PASS。一時復元DBは照合後に削除。
- 外部公式seed: 未入手・未実行。

資料: [YugabyteDB triggers](https://docs.yugabyte.com/stable/explore/ysql-language-features/advanced-features/triggers/)、[PostgreSQL CREATE VIEW](https://www.postgresql.org/docs/current/sql-createview.html)、[YugabyteDB geo-local tables](https://docs.yugabyte.com/stable/develop/build-global-apps/real-world-scenarios/globally-local/)。文書だけで互換を判定せず、固定イメージの実行結果を優先しています。
