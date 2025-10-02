-- Initial Schema for TUBAF Planner
-- Version: 1.0
-- Author: TUBAF Planner System

-- Base Entity Auditing Fields Template (used in all tables)
-- created_at, updated_at, created_by, updated_by, version

-- Semesters
CREATE TABLE semesters (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    short_name VARCHAR(20) NOT NULL UNIQUE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    is_active BOOLEAN DEFAULT false,
    
    -- Audit fields
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version INTEGER DEFAULT 0
);

-- Study Programs
CREATE TABLE study_programs (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    degree_type VARCHAR(50) NOT NULL, -- BACHELOR, MASTER, DIPLOM
    description TEXT,
    
    -- Audit fields
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version INTEGER DEFAULT 0
);

-- Rooms
CREATE TABLE rooms (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    building VARCHAR(100),
    floor_level INTEGER,
    capacity INTEGER,
    room_type VARCHAR(50), -- LECTURE_HALL, SEMINAR_ROOM, LAB, etc.
    equipment TEXT, -- JSON or comma-separated list
    
    -- Audit fields
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version INTEGER DEFAULT 0
);

-- Courses
CREATE TABLE courses (
    id BIGSERIAL PRIMARY KEY,
    course_code VARCHAR(50) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    course_type CHAR(1) NOT NULL, -- V, U, P, S
    credit_hours INTEGER DEFAULT 0,
    instructor VARCHAR(200),
    semester_id BIGINT,
    
    -- Audit fields
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version INTEGER DEFAULT 0,
    
    FOREIGN KEY (semester_id) REFERENCES semesters(id) ON DELETE CASCADE,
    UNIQUE(course_code, semester_id)
);

-- Course-StudyProgram Relationships (Many-to-Many)
CREATE TABLE course_study_programs (
    course_id BIGINT NOT NULL,
    study_program_id BIGINT NOT NULL,
    
    PRIMARY KEY (course_id, study_program_id),
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
    FOREIGN KEY (study_program_id) REFERENCES study_programs(id) ON DELETE CASCADE
);

-- Schedule Entries
CREATE TABLE schedule_entries (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL,
    room_id BIGINT,
    day_of_week INTEGER NOT NULL, -- 1=Monday, 7=Sunday
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    instructor VARCHAR(200),
    notes TEXT,
    
    -- Audit fields
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version INTEGER DEFAULT 0,
    
    FOREIGN KEY (course_id) REFERENCES courses(id) ON DELETE CASCADE,
    FOREIGN KEY (room_id) REFERENCES rooms(id) ON DELETE SET NULL
);

-- Change Log for tracking modifications
CREATE TABLE change_logs (
    id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(100) NOT NULL, -- Course, ScheduleEntry, etc.
    entity_id BIGINT NOT NULL,
    change_type VARCHAR(50) NOT NULL, -- CREATE, UPDATE, DELETE
    old_values TEXT, -- JSON
    new_values TEXT, -- JSON
    changed_by VARCHAR(100),
    change_timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    description TEXT
);

-- Scraping Runs for tracking data collection
CREATE TABLE scraping_runs (
    id BIGSERIAL PRIMARY KEY,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    status VARCHAR(50) NOT NULL, -- RUNNING, SUCCESS, FAILED
    records_processed INTEGER DEFAULT 0,
    records_created INTEGER DEFAULT 0,
    records_updated INTEGER DEFAULT 0,
    records_failed INTEGER DEFAULT 0,
    error_message TEXT,
    log_data TEXT -- JSON with detailed logs
);

-- Indexes for Performance
CREATE INDEX idx_courses_semester ON courses(semester_id);
CREATE INDEX idx_courses_code ON courses(course_code);
CREATE INDEX idx_schedule_course ON schedule_entries(course_id);
CREATE INDEX idx_schedule_room ON schedule_entries(room_id);
CREATE INDEX idx_schedule_day_time ON schedule_entries(day_of_week, start_time);
CREATE INDEX idx_change_logs_entity ON change_logs(entity_type, entity_id);
CREATE INDEX idx_change_logs_timestamp ON change_logs(change_timestamp);
CREATE INDEX idx_scraping_runs_start_time ON scraping_runs(start_time);

-- Insert Sample Data for Development
INSERT INTO semesters (name, short_name, start_date, end_date, is_active) VALUES
('Wintersemester 2024/25', 'WS24', '2024-10-01', '2025-03-31', true),
('Sommersemester 2025', 'SS25', '2025-04-01', '2025-09-30', false);

INSERT INTO study_programs (name, code, degree_type, description) VALUES
('Angewandte Informatik', 'AI', 'BACHELOR', 'Bachelor-Studiengang Angewandte Informatik'),
('Wirtschaftsingenieurwesen', 'WING', 'BACHELOR', 'Bachelor-Studiengang Wirtschaftsingenieurwesen'),
('Informatik', 'INF', 'MASTER', 'Master-Studiengang Informatik');

INSERT INTO rooms (name, building, floor_level, capacity, room_type) VALUES
('HSZ/0001', 'Hörsaalzentrum', 0, 200, 'LECTURE_HALL'),
('HSZ/0002', 'Hörsaalzentrum', 0, 150, 'LECTURE_HALL'),
('CB/SR001', 'Clemens-Winkler-Bau', 1, 30, 'SEMINAR_ROOM'),
('CB/PC-Pool', 'Clemens-Winkler-Bau', 2, 24, 'COMPUTER_LAB');

-- Sample Courses
INSERT INTO courses (course_code, name, description, course_type, credit_hours, instructor, semester_id) VALUES
('CS101', 'Grundlagen der Informatik', 'Einführung in die Informatik', 'V', 4, 'Prof. Dr. Mustermann', 1),
('CS101U', 'Übung Grundlagen der Informatik', 'Übungen zur Vorlesung', 'U', 2, 'Dr. Musterfrau', 1),
('MATH101', 'Mathematik I', 'Grundlagen der Mathematik', 'V', 6, 'Prof. Dr. Zahlen', 1);

-- Link Courses to Study Programs
INSERT INTO course_study_programs (course_id, study_program_id) VALUES
(1, 1), (2, 1), (3, 1), (3, 2);

-- Sample Schedule Entries
INSERT INTO schedule_entries (course_id, room_id, day_of_week, start_time, end_time, instructor) VALUES
(1, 1, 2, '10:30:00', '12:00:00', 'Prof. Dr. Mustermann'), -- Tuesday
(2, 3, 4, '14:00:00', '15:30:00', 'Dr. Musterfrau'), -- Thursday
(3, 2, 1, '08:00:00', '09:30:00', 'Prof. Dr. Zahlen'), -- Monday
(3, 2, 3, '08:00:00', '09:30:00', 'Prof. Dr. Zahlen'); -- Wednesday