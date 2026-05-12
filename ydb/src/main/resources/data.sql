UPSERT INTO departments (id, name, location, budget) VALUES
    (1, 'Engineering', 'Building A', Decimal("500000.00", 15, 2)),
    (2, 'Marketing',   'Building B', Decimal("300000.00", 15, 2)),
    (3, 'Sales',       'Building C', Decimal("250000.00", 15, 2)),
    (4, 'HR',          'Building A', Decimal("150000.00", 15, 2));

UPSERT INTO employees (id, first_name, last_name, email, hire_date, salary, department_id, is_active, notes, created_at, rating, bonus_multiplier) VALUES
    (1, 'Alice', 'Smith',    'alice.smith@example.com',    Date32('2020-01-15'), Decimal("95000.00", 12, 2),  1, true,  'Senior engineer', Timestamp64('2020-01-15T09:00:00Z'), 4.5,  1.15),
    (2, 'Bob',   'Johnson',  'bob.johnson@example.com',    Date32('2019-06-01'), Decimal("105000.00", 12, 2), 1, true,  NULL,              Timestamp64('2019-06-01T10:30:00Z'), 4.2,  1.10),
    (3, 'Carol', 'Williams', 'carol.williams@example.com', Date32('2021-03-20'), Decimal("78000.00", 12, 2),  2, true,  'Marketing lead',  Timestamp64('2021-03-20T08:15:00Z'), 3.8,  1.05),
    (4, 'Dave',  'Brown',    'dave.brown@example.com',     Date32('2018-11-10'), Decimal("115000.00", 12, 2), 1, true,  'Tech lead',       Timestamp64('2018-11-10T14:00:00Z'), 4.9,  1.25),
    (5, 'Eve',   'Davis',    'eve.davis@example.com',      Date32('2022-07-01'), Decimal("65000.00", 12, 2),  3, true,  NULL,              Timestamp64('2022-07-01T11:45:00Z'), NULL, 1.0),
    (6, 'Frank', 'Miller',   'frank.miller@example.com',   Date32('2017-02-28'), Decimal("88000.00", 12, 2),  4, false, 'On leave',        Timestamp64('2017-02-28T16:30:00Z'), 3.2,  0.95);

UPSERT INTO projects (id, name, budget, start_date, end_date, description) VALUES
    (1, 'Project Alpha', Decimal("100000.00", 15, 2), Date32('2023-01-01'), Date32('2023-12-31'), 'Main product redesign'),
    (2, 'Project Beta',  Decimal("50000.00", 15, 2),  Date32('2023-06-01'), NULL,               'New feature development'),
    (3, 'Project Gamma', Decimal("75000.00", 15, 2),  Date32('2023-03-15'), Date32('2023-09-30'), 'Infrastructure upgrade');

UPSERT INTO employee_projects (employee_id, project_id, role, assigned_date) VALUES
    (1, 1, 'Developer',         Date32('2023-01-15')),
    (1, 2, 'Reviewer',          Date32('2023-06-15')),
    (2, 1, 'Lead Developer',    Date32('2023-01-10')),
    (3, 2, 'Marketing Advisor', Date32('2023-06-20')),
    (4, 1, 'Architect',         Date32('2023-01-05')),
    (4, 3, 'Lead',              Date32('2023-03-20')),
    (5, 2, 'Sales Liaison',     Date32('2023-07-01'));
