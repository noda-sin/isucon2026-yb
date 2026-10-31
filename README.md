# ISUReal: Java + three-region YugabyteDB local lab

初期Go実装を保存したまま、42 APIをJavaへ移植し、アプリの15 entityを地域partitionへ配置したローカル開発環境です。公式seedは未入手で、既定では合成smokeデータだけを使います。本番サーバへ接続する設定やdumpは含めません。

## 起動

Docker Desktopを起動し、リポジトリrootで実行してください。

```sh
docker compose -f compose.yaml -f compose.geo.yaml -f compose.java.yaml \
  --profile java up -d --build
```

| 地域 | Java UI / API | 比較用Go UI / API | YSQL |
|---|---|---|---|
| Asia | http://localhost:28080 | http://localhost:18080 | localhost:15433 |
| Europe | http://localhost:28081 | http://localhost:18081 | localhost:15434 |
| America | http://localhost:28082 | http://localhost:18082 | localhost:15435 |

Master UI: http://localhost:17000。公開ポートはすべて127.0.0.1限定です。DBはローカルtrust認証なので外部公開しないでください。

合成ユーザーは `smoke_asia` / `smoke_europe` / `smoke_america`、共通の合成secretは `local-smoke-secret`。対応地域のUIで利用します。Java DBは`isureal_java`、比較用Go DBは`isureal`。どちらも `/api/initialize` は503で、既存データを勝手にresetしません。

検証環境はApple Silicon、Docker VM8CPU／約8GiBです。公式YB imageは `yugabytedb/yugabyte:2025.2.8.0-b60`、Java21／smart JDBC `42.7.3-yb-7`。小規模合成テスト向け設定であり、公式データ全量や本番負荷に足りる資源量を示すものではありません。

Go版だけの起動は `docker compose up -d --build`。既にJavaを起動している場合、このコマンドだけでJavaを停止することはありません。

## 正確な構成

3つの論理地域それぞれにMaster1＋TServer2、合計3 Master／6 TServerです。プロセス監視と永続volumeを分離したため、DBコンテナは9個です。3 TServerだけではlocal voting2を満たせません。Go／Javaとnginxも地域ごとに配置します。

Javaの実体45partitionと副次index57個は対象地域2 voting replicas＋次地域1、RF3、local leaderです。Asia→Europe、Europe→America、America→Asiaの順にremote replicaを置きます。グローバルID・username・関係pair・FKは別のglobal registry（各地域1 replica）で維持し、単純にregionをUNIQUEへ足して一意性を弱めていません。所有userのregion変更は11種類の関連データへ同一transactionで伝播します。

Java smart driverは同地域の2 TServerへ接続します。Go比較版は初期schemaを保ったglobal RF3、固定の同地域TServer接続です。両者を同じ地域化実装とは扱いません。[詳細schema](docker/local/geo/README.md)と[互換差](webapp/java/README.md)を参照してください。

すべて同じMac／VM／ストレージ上です。実際の障害独立性はなく、local2を置いた地域全損ではquorumを失います。global registryへの書込みを含むため、全transactionが地域内で完結するわけでもありません。

## 検証

```sh
# 合成admin fixture（両DB、実データは使わない）
for db in isureal isureal_java; do
  docker compose exec -T tserver-asia-1 /home/yugabyte/bin/ysqlsh \
    -h localhost -p 5433 -U yugabyte -d "$db" -v ON_ERROR_STOP=1 \
    < docker/local/tests/contract-admin.sql
done
python3 docker/local/tests/contract-java.py --output /tmp/isureal-contract

# 地域実体・副次indexの全tablet配置
docker compose exec -T tserver-asia-1 bash < docker/local/geo/verify-placement.sh
```

契約テストは合成ユーザー、投稿、関係を作り、管理APIで投稿期間を進めます。開発中の手作業データがある場合は専用の新規projectで実施してください。Go／Java各216ケース、42 API、署名・画像bytes・ZIP・SSE・地域移動まで比較しました。無効化したinitializeと認定取消しは意図したエラー経路です。

[Go検証記録](docker/local/VALIDATION.md)／[Java・geo検証記録](docker/local/VALIDATION-JAVA.md)／[契約試験の説明](docker/local/tests/contract-README.md)。完全な任意入力互換や公式競技ベンチマークの合格を意味しません。

## 停止・再起動・データ保持

Javaを含む全構成では同じComposeファイルを指定します。

```sh
docker compose -f compose.yaml -f compose.geo.yaml -f compose.java.yaml --profile java stop
docker compose -f compose.yaml -f compose.geo.yaml -f compose.java.yaml --profile java start
# コンテナを作り直しても名前付きvolumeは保持
docker compose -f compose.yaml -f compose.geo.yaml -f compose.java.yaml --profile java down
docker compose -f compose.yaml -f compose.geo.yaml -f compose.java.yaml --profile java up -d
```

通常は `down -v` を使いません（データ削除）。初期化は完了マーカーを検出するとskipし、途中失敗したDBは自動上書きせず停止します。新規環境は別project名 (`-p isureal-clean`) と、同時起動するならポートoverrideを用意してください。

## 後日の公式seedとbackup

Javaは親table名に正規化したdata-only **INSERT形式**を新規DBへ投入します。viewへのCOPYや、元の画像月別partition名へのINSERTは使えません。入口・制限は [geo seed手順](docker/local/geo/README.md)。Goのdata-only SQL入口は `SEED_DIR=/absolute/seed-dir docker compose -f compose.yaml -f compose.seed.yaml up -d --build`（新規DBのみ）です。

Java geo専用exportツールは用意していますが、実行検証が未完了のため、**geo backup/restoreは未検証**です。公式seedの互換性も入手後に確認してください。取得を中止したpartial dumpは使用しません。[Go版の合成data-only backup／別DB復元](docker/local/BACKUP-GO.md)は51tableの件数・内容hash一致まで確認しています。

## 遅延シミュレーション

通常構成は遅延を注入しません。netem overrideの実装と必要権限は [netem手順](docker/local/netem/README.md)。既定RTT100ms（片道50ms）はシミュレーション仮定で、実測した本番値ではありません。NET_ADMIN付きsidecarとDocker内network再作成は明示承認後にのみ実行します。

## 原本

[PROVENANCE.md](PROVENANCE.md)。初期Go原本はcommit `b2d46a9`、Javaは別ディレクトリです。サーバ設定・inventory・DBdump・実データは公開履歴に含めません。原本にLICENSEが確認できず、新しいライセンスは付与していません。
