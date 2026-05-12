CREATE TABLE departments (
    id          Int32 NOT NULL,
    name        Text  NOT NULL,
    location    Text,
    budget      Double NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE employees (
    id              Int32 NOT NULL,
    first_name      Text  NOT NULL,
    last_name       Text  NOT NULL,
    email           Text  NOT NULL,
    hire_date       Date  NOT NULL,
    salary          Double NOT NULL,
    department_id   Int32,
    is_active       Bool  NOT NULL,
    notes           Text,
    PRIMARY KEY (id)
);

CREATE TABLE projects (
    id          Int32 NOT NULL,
    name        Text  NOT NULL,
    budget      Double,
    start_date  Date  NOT NULL,
    end_date    Date,
    description Text,
    PRIMARY KEY (id)
);

CREATE TABLE employee_projects (
    employee_id     Int32 NOT NULL,
    project_id      Int32 NOT NULL,
    role            Text  NOT NULL,
    assigned_date   Date  NOT NULL,
    PRIMARY KEY (employee_id, project_id)
);
