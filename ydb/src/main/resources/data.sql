UPSERT INTO departments (id, name, location, budget) VALUES
    (1, 'Engineering', 'Building A', 500000.0),
    (2, 'Marketing',   'Building B', 300000.0),
    (3, 'Sales',       'Building C', 250000.0),
    (4, 'HR',          'Building A', 150000.0);

UPSERT INTO employees (id, first_name, last_name, email, hire_date, salary, department_id, is_active, notes) VALUES
    (1, 'Alice', 'Smith',    'alice.smith@example.com',    Date('2020-01-15'), 95000.0,  1, true,  'Senior engineer'),
    (2, 'Bob',   'Johnson',  'bob.johnson@example.com',    Date('2019-06-01'), 105000.0, 1, true,  NULL),
    (3, 'Carol', 'Williams', 'carol.williams@example.com', Date('2021-03-20'), 78000.0,  2, true,  'Marketing lead'),
    (4, 'Dave',  'Brown',    'dave.brown@example.com',     Date('2018-11-10'), 115000.0, 1, true,  'Tech lead'),
    (5, 'Eve',   'Davis',    'eve.davis@example.com',      Date('2022-07-01'), 65000.0,  3, true,  NULL),
    (6, 'Frank', 'Miller',   'frank.miller@example.com',   Date('2017-02-28'), 88000.0,  4, false, 'On leave');

UPSERT INTO projects (id, name, budget, start_date, end_date, description) VALUES
    (1, 'Project Alpha', 100000.0, Date('2023-01-01'), Date('2023-12-31'), 'Main product redesign'),
    (2, 'Project Beta',  50000.0,  Date('2023-06-01'), NULL,               'New feature development'),
    (3, 'Project Gamma', 75000.0,  Date('2023-03-15'), Date('2023-09-30'), 'Infrastructure upgrade');

UPSERT INTO employee_projects (employee_id, project_id, role, assigned_date) VALUES
    (1, 1, 'Developer',         Date('2023-01-15')),
    (1, 2, 'Reviewer',          Date('2023-06-15')),
    (2, 1, 'Lead Developer',    Date('2023-01-10')),
    (3, 2, 'Marketing Advisor', Date('2023-06-20')),
    (4, 1, 'Architect',         Date('2023-01-05')),
    (4, 3, 'Lead',              Date('2023-03-20')),
    (5, 2, 'Sales Liaison',     Date('2023-07-01'));
