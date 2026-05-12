INSERT INTO departments (name, location, budget) VALUES
    ('Engineering', 'Building A', 500000.00),
    ('Marketing',   'Building B', 300000.00),
    ('Sales',       'Building C', 250000.00),
    ('HR',          'Building A', 150000.00);

INSERT INTO employees (first_name, last_name, email, hire_date, salary, department_id, is_active, notes) VALUES
    ('Alice', 'Smith',    'alice.smith@example.com',    '2020-01-15', 95000.00,  1, true,  'Senior engineer'),
    ('Bob',   'Johnson',  'bob.johnson@example.com',    '2019-06-01', 105000.00, 1, true,  NULL),
    ('Carol', 'Williams', 'carol.williams@example.com', '2021-03-20', 78000.00,  2, true,  'Marketing lead'),
    ('Dave',  'Brown',    'dave.brown@example.com',     '2018-11-10', 115000.00, 1, true,  'Tech lead'),
    ('Eve',   'Davis',    'eve.davis@example.com',      '2022-07-01', 65000.00,  3, true,  NULL),
    ('Frank', 'Miller',   'frank.miller@example.com',   '2017-02-28', 88000.00,  4, false, 'On leave');

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
