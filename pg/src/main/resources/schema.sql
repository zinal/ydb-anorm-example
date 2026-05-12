CREATE TABLE departments (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    location    VARCHAR(100),
    budget      NUMERIC(15,2) NOT NULL DEFAULT 0
);

CREATE TABLE employees (
    id                SERIAL PRIMARY KEY,
    first_name        VARCHAR(50) NOT NULL,
    last_name         VARCHAR(50) NOT NULL,
    email             VARCHAR(100) NOT NULL UNIQUE,
    hire_date         DATE NOT NULL,
    salary            NUMERIC(12,2) NOT NULL,
    department_id     INTEGER REFERENCES departments(id),
    is_active         BOOLEAN NOT NULL DEFAULT true,
    notes             TEXT,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    rating            DOUBLE PRECISION,
    bonus_multiplier  DOUBLE PRECISION NOT NULL DEFAULT 1.0
);

CREATE TABLE projects (
    id          SERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    budget      NUMERIC(15,2),
    start_date  DATE NOT NULL,
    end_date    DATE,
    description TEXT
);

CREATE TABLE employee_projects (
    employee_id     INTEGER NOT NULL REFERENCES employees(id),
    project_id      INTEGER NOT NULL REFERENCES projects(id),
    role            VARCHAR(50) NOT NULL,
    assigned_date   DATE NOT NULL DEFAULT CURRENT_DATE,
    PRIMARY KEY (employee_id, project_id)
);
