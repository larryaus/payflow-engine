INSERT INTO account (account_id, account_name, available_balance, frozen_balance, currency, status)
VALUES
    ('acc001', 'Alice',   1000000, 0, 'CNY', 'ACTIVE'),
    ('acc002', 'Bob',      500000, 0, 'CNY', 'ACTIVE'),
    ('acc003', 'Charlie',  750000, 0, 'CNY', 'ACTIVE'),
    ('acc004', 'Diana',    250000, 0, 'CNY', 'ACTIVE')
ON CONFLICT (account_id) DO NOTHING;
