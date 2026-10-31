-- SYNTHETIC LOCAL CONTRACT FIXTURE ONLY. Apply independently to isureal and isureal_java.
-- No production credentials or imported data. Known password: local-contract-admin.
INSERT INTO administrators(id, username, password_hash)
SELECT 'ca000000-0000-4000-8000-000000000001', 'contract_admin', '18ffb82e85f4bd93363bcc9a8b5dd9755387b689c428e8b023283e5fe208bb1c'
WHERE NOT EXISTS (SELECT 1 FROM administrators WHERE id = 'ca000000-0000-4000-8000-000000000001');
