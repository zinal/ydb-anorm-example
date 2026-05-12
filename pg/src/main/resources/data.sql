INSERT INTO departments (name, location, budget) VALUES
    ('Engineering', 'Building A', 500000.00),
    ('Marketing',   'Building B', 300000.00),
    ('Sales',       'Building C', 250000.00),
    ('HR',          'Building A', 150000.00);

INSERT INTO employees (first_name, last_name, email, hire_date, salary, department_id, is_active, notes, created_at, rating, bonus_multiplier) VALUES
    ('Alice', 'Smith',    'alice.smith@example.com',    '2020-01-15', 95000.00,  1, true,  'Senior engineer', '2020-01-15 09:00:00', 4.5,  1.15),
    ('Bob',   'Johnson',  'bob.johnson@example.com',    '2019-06-01', 105000.00, 1, true,  NULL,              '2019-06-01 10:30:00', 4.2,  1.10),
    ('Carol', 'Williams', 'carol.williams@example.com', '2021-03-20', 78000.00,  2, true,  'Marketing lead',  '2021-03-20 08:15:00', 3.8,  1.05),
    ('Dave',  'Brown',    'dave.brown@example.com',     '2018-11-10', 115000.00, 1, true,  'Tech lead',       '2018-11-10 14:00:00', 4.9,  1.25),
    ('Eve',   'Davis',    'eve.davis@example.com',      '2022-07-01', 65000.00,  3, true,  NULL,              '2022-07-01 11:45:00', NULL, 1.0),
    ('Frank', 'Miller',   'frank.miller@example.com',   '2017-02-28', 88000.00,  4, false, 'On leave',        '2017-02-28 16:30:00', 3.2,  0.95);

INSERT INTO projects (name, budget, start_date, end_date, description) VALUES
    ('Project Alpha', 100000.00, '2023-01-01', '2023-12-31', 'Main product redesign'),
    ('Project Beta',  50000.00,  '2023-06-01', NULL,         'New feature development'),
    ('Project Gamma', 75000.00,  '2023-03-15', '2023-09-30', 'Infrastructure upgrade');

INSERT INTO employee_projects (employee_id, project_id, role, assigned_date) VALUES
    (1, 1, 'Developer',        '2023-01-15'),
    (1, 2, 'Reviewer',         '2023-06-15'),
    (2, 1, 'Lead Developer',   '2023-01-10'),
    (3, 2, 'Marketing Advisor','2023-06-20'),
    (4, 1, 'Architect',        '2023-01-05'),
    (4, 3, 'Lead',             '2023-03-20'),
    (5, 2, 'Sales Liaison',    '2023-07-01');
