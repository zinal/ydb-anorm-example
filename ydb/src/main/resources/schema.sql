CREATE TABLE departments (
    id          Int32 NOT NULL,
    name        Text  NOT NULL,
    location    Text,
    budget      Decimal(15,2) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE employees (
    id                Int32 NOT NULL,
    first_name        Text  NOT NULL,
    last_name         Text  NOT NULL,
    email             Text  NOT NULL,
    hire_date         Date32 NOT NULL,
    salary            Decimal(12,2) NOT NULL,
    department_id     Int32,
    is_active         Bool  NOT NULL,
    notes             Text,
    created_at        Timestamp64 NOT NULL,
    rating            Double,
    bonus_multiplier  Double NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE projects (
    id          Int32 NOT NULL,
    name        Text  NOT NULL,
    budget      Decimal(15,2),
    start_date  Date32 NOT NULL,
    end_date    Date32,
    description Text,
    PRIMARY KEY (id)
);

CREATE TABLE employee_projects (
    employee_id     Int32 NOT NULL,
    project_id      Int32 NOT NULL,
    role            Text  NOT NULL,
    assigned_date   Date32 NOT NULL,
    PRIMARY KEY (employee_id, project_id)
);

CREATE TABLE operations (
    operation_id   Uuid NOT NULL,
    operation_type Text NOT NULL,
    applied_at     Timestamp64 NOT NULL,
    PRIMARY KEY (operation_id)
);
