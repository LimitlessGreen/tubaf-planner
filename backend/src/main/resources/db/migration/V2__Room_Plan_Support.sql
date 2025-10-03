-- Add metadata fields for room plans
ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS location_description VARCHAR(255);

ALTER TABLE rooms
    ADD COLUMN IF NOT EXISTS plan_updated_at DATE;

-- Table for scraped room plan slots
CREATE TABLE IF NOT EXISTS room_plan_slots (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    semester_id BIGINT NOT NULL REFERENCES semesters(id) ON DELETE CASCADE,
    day_of_week VARCHAR(20) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    course_title VARCHAR(255) NOT NULL,
    course_type VARCHAR(100),
    lecturers TEXT,
    week_pattern VARCHAR(50),
    info_id VARCHAR(20),
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_room_plan_slot
    ON room_plan_slots (room_id, semester_id, day_of_week, start_time, end_time, course_title);

CREATE INDEX IF NOT EXISTS idx_room_plan_slots_room
    ON room_plan_slots (room_id);

CREATE INDEX IF NOT EXISTS idx_room_plan_slots_semester
    ON room_plan_slots (semester_id);
