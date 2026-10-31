FROM yugabytedb/yugabyte:2025.2.8.0-b60
COPY docker/local/geo/schema.sql /geo/schema.sql
COPY docker/local/geo/init.sh /geo/init.sh
COPY docker/local/sql/030-smoke.sql /geo/smoke.sql
ENTRYPOINT ["bash", "/geo/init.sh"]
