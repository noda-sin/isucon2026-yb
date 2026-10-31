# ISUReal local YugabyteDB lab

初期Go実装を動かす段階1です。**全アプリデータの地域partition化とJava移植は未完了**です。3地域の接続と、独立した配置probeでlocal 2 + remote 1を検証します。アプリ本体は元のPK/FKを保持し、各地域1レプリカのglobal RF3です。

## 起動

Docker Desktop (Compose v2以降) を起動してください。検証環境はApple Silicon、Docker VM 8 CPU / 約8 GiB、空き75 GiB。9 DBプロセスに加えて3アプリを動かすため、これは小規模smoke用途です。公式データ全量に十分なメモリ/ディスク容量とは限りません。

```sh
docker compose up -d --build
docker compose ps
```

| 地域 | UI / API | YSQL (host) |
|---|---|---|
| Asia | http://localhost:18080 | localhost:15433 |
| Europe | http://localhost:18081 | localhost:15434 |
| America | http://localhost:18082 | localhost:15435 |

Master UI: http://localhost:17000。すべての公開ポートは127.0.0.1に限定。DBはローカル開発用trust認証であり、外部公開しないでください。

合成smokeアカウントは `smoke_asia`, `smoke_europe`, `smoke_america`、共通の合成secretは `local-smoke-secret`。公式データではありません。地域に対応するUIから利用します。初期アプリの `/api/initialize` は、この構成では意図せぬresetを防ぐため無効です。

## 正確なトポロジー

「3インスタンス」は3つの論理地域として表します。プロセス監視と永続volumeを分離するため、各地域Master 1コンテナ + TServer 2コンテナ、計3 Master / 6 TServerです。アプリとnginxも地域ごとに1つずつです。3 TServerだけの構成ではlocal voting 2を満たせません。

`local_asia`はAsia 2 + Europe 1、`local_europe`はEurope 2 + America 1、`local_america`はAmerica 2 + Asia 1。leader preferenceは対象地域が最優先です。`locality_probe`だけがregion LIST partitionでこれらのtablespaceに配置されます。`users`、`posts`等のアプリテーブルはglobal RF3のままです。詳しくは [SCHEMA.md](docker/local/SCHEMA.md)。

全コンテナは同じMac/VM/ストレージ上です。地域間遅延や実際の障害独立性は再現しません。local 2 + remote 1自体も対象地域全損で2/3を失うため、書込quorumを維持できません。

Goは標準pgxなのでsmart driverの `topology_keys` を渡さず、各アプリを同じ地域のTServerへ接続します。地域内2台目への自動failoverは未実装。Read Committedサーバflagとwait queuesを有効にし、接続時のdefault_transaction_isolationも明示します。

## 検証

実行結果と未対応範囲は [VALIDATION.md](docker/local/VALIDATION.md)。

```sh
docker compose --profile test run --rm --build verify
docker compose exec -T tserver-asia-1 bash < docker/local/tests/verify-placement.sh
python3 docker/local/smoke-app.py
```

verifyはテスト専用データを作成/除去し、実RC snapshot、row/advisory lock、global username、FK、region更新、6TServerを検証します。API smokeは一意な名前の合成ユーザーを作成します。placementはprobe tabletの全replica/leaderを実測します。成功を全API互換や全データ地域化の証明にはしません。

## 停止・再起動・保持

```sh
docker compose stop
docker compose start
# コンテナを削除しても名前付きvolumeは保持
docker compose down
docker compose up -d
```

`down -v`はデータを削除するため通常使いません。db-initは完了マーカーがあれば何も変更しません。途中失敗したDBを見つけた場合は自動dropせず停止します。原因を直した後、必要なら新しいCompose project名 (`-p isureal-clean`) と別ポートのoverrideを使って空の環境を作成します。

## 公式データ後投入

公式seed未入手。回収中止したpartial dumpは使用しません。

1. 現在の環境を止め、必要なら下記data-only backupを保存。
2. 新規の空のCompose project/volumeを用意（同時起動するならポートoverrideが必要）。
3. 信頼する公式データをschemaを含まないSQLとして `data.sql` に準備。元データに `COPY`/INSERT以外の所有者、拡張、tablespace、DDL、resetが含まれないか確認。
4. `SEED_DIR=/absolute/seed-dir docker compose -f compose.yaml -f compose.seed.yaml -p isureal-official up -d --build`

通常起動はホストbind mount不要です。外部seedだけ `compose.seed.yaml` により指定ディレクトリを読み取り専用mountします（Docker Desktopのファイル共有許可が必要な場合があります）。新規initだけがseedを読込みます。既存環境でSEED_MODEを変えても追加入力/置換しません。公式スキーマとの差分や重複usernameがあれば失敗して止まります。公式seedの実互換性は入手後に検証してください。空のアプリデータは `SEED_MODE=empty`。

## データbackupと復元

稼働中のDBディレクトリを単純コピーしません。小規模ローカル用data-only SQL backup:

```sh
mkdir -p backups
docker compose exec -T tserver-asia-1 /home/yugabyte/postgres/bin/ysql_dump \
  -h localhost -p 5433 -U yugabyte -d isureal --data-only \
  --exclude-table=locality_probe --exclude-table='locality_probe_*' \
  --exclude-table=local_bootstrap_metadata --exclude-table='initial_*' > backups/data.sql
```

このファイルを新しい空projectの `SEED_MODE=external` 入力に渡します。アプリschema/tablespacesとprobeはinit側で作り、初期snapshotはデータ投入後に作り直します。DB本体の完全backup・継続的バックアップではありません。公式データ全量の復元試験は未実施。`backups/`とseed SQLはgitignore対象です。

## 出典と範囲

元の初期実装838544aから保存されたsnapshot bdaaccfを基点とし、最適化ブランチは取り込んでいません。Goの変更は接続設定・health・local initialize保護のみです。元のローカルrepoと本番サーバは変更していません。公開版にはサーバ設定、接続inventory、DBdump、実データを含めません。原本にLICENSEが確認できないため、独自のライセンス付与はしていません。

Javaの差分と必要な互換試験は [JAVA-MIGRATION.md](docker/local/JAVA-MIGRATION.md)。

公式参照: [Read Committed](https://docs.yugabyte.com/stable/architecture/transactions/read-committed/)、[tablespace](https://docs.yugabyte.com/stable/api/ysql/the-sql-language/statements/ddl_create_tablespace/)、[manual deployment](https://docs.yugabyte.com/stable/deploy/manual-deployment/start-masters/)、[2025.2 release](https://docs.yugabyte.com/stable/releases/ybdb-releases/v2025.2/)。固定image: `yugabytedb/yugabyte:2025.2.8.0-b60` (arm64/amd64)。
