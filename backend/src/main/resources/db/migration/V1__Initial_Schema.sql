-- New Baseline Schema aligned with current JPA entities
-- NOTE: This file replaces previous prototype schema. Use only on fresh environments.

CREATE TABLE semesters (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    short_name VARCHAR(10) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    active BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    UNIQUE(short_name)
);

CREATE TABLE course_types (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(10) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE lecturers (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    title VARCHAR(50),
    email VARCHAR(150),
    department VARCHAR(100),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE study_programs (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    degree_type VARCHAR(50),
    description TEXT,
    faculty_id INTEGER,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE rooms (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    building VARCHAR(10) NOT NULL,
    room_number VARCHAR(10) NOT NULL,
    capacity INTEGER,
    room_type VARCHAR(50),
    equipment TEXT,
    location_description VARCHAR(255),
    plan_updated_at DATE,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE courses (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    course_number VARCHAR(20),
    description TEXT,
    semester_id BIGINT NOT NULL REFERENCES semesters(id) ON DELETE CASCADE,
    lecturer_id BIGINT NOT NULL REFERENCES lecturers(id) ON DELETE RESTRICT,
    course_type_id BIGINT NOT NULL REFERENCES course_types(id) ON DELETE RESTRICT,
    sws INTEGER,
    ects_credits DOUBLE PRECISION,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE course_study_programs (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    study_program_id BIGINT NOT NULL REFERENCES study_programs(id) ON DELETE CASCADE,
    semester INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT uk_course_study_program UNIQUE (course_id, study_program_id)
);

CREATE TABLE schedule_entries (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE RESTRICT,
    day_of_week VARCHAR(20) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    week_pattern VARCHAR(20),
    notes TEXT,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE scraping_runs (
    id BIGSERIAL PRIMARY KEY,
    semester_id BIGINT NOT NULL REFERENCES semesters(id) ON DELETE CASCADE,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    status VARCHAR(50) NOT NULL,
    total_entries INTEGER,
    new_entries INTEGER,
    updated_entries INTEGER,
    error_message TEXT,
    source_url VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE TABLE change_log (
    id BIGSERIAL PRIMARY KEY,
    scraping_run_id BIGINT NOT NULL REFERENCES scraping_runs(id) ON DELETE CASCADE,
    entity_type VARCHAR(100) NOT NULL,
    entity_id BIGINT NOT NULL,
    change_type VARCHAR(50) NOT NULL,
    field_name VARCHAR(100),
    old_value TEXT,
    new_value TEXT,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

-- Indices
CREATE INDEX idx_courses_semester ON courses(semester_id);
CREATE INDEX idx_courses_name ON courses(name);
CREATE INDEX idx_schedule_entries_course ON schedule_entries(course_id);
CREATE INDEX idx_schedule_entries_room ON schedule_entries(room_id);
CREATE INDEX idx_scraping_runs_semester ON scraping_runs(semester_id);
CREATE INDEX idx_change_log_entity ON change_log(entity_type, entity_id);

-- Minimal seed data (optional)
-- Seed default course type (code can be up to 10 chars per entity constraint)
-- Use a neutral default course type code that won't clash with tests
INSERT INTO course_types (code, name) VALUES ('SYS','System Default');
INSERT INTO lecturers (name) VALUES ('System');
INSERT INTO semesters (name, short_name, start_date, end_date, active) VALUES ('Baseline Semester','BASE', CURRENT_DATE, CURRENT_DATE + INTERVAL '120 days', TRUE);