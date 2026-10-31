# Preserved Go schema: local synthetic backup validation

これはglobal配置のGo比較DB `isureal` 用です。Java geo DBには適用しません。公式seedの互換性は未確認です。

```sh
mkdir -p backups
docker compose exec -T tserver-asia-1 /home/yugabyte/postgres/bin/ysql_dump \
  -h localhost -p 5433 -U yugabyte -d isureal --data-only \
  --exclude-table=locality_probe --exclude-table='locality_probe_*' \
  --exclude-table=local_bootstrap_metadata --exclude-table='initial_*' > backups/data.sql
```

復元は新規の空projectでschemaを先に作り、`SEED_DIR`でこのディレクトリを指定します。

```sh
SEED_DIR="$PWD/backups" docker compose -p isureal-restore \
  -f compose.yaml -f compose.seed.yaml up -d --build
```

同じ公開ポートを使うので、既存projectを止めるかポートoverrideを設定してください。既存DBの初期化markerがある場合は何も入れ替えません。新しいproject名なら永続volumeも分離されます。backupsはgitignore対象です。

2026-10-31に合成データでdata-only SQL→別DBの初期schemaへ投入し、画像日付partitionを含む51tableの件数・row JSON hash一致を確認しました。この小規模試験は公式データや本番backup方式の検証ではありません。稼働中のDBディレクトリ単純コピーは行いません。
