-- ==============================================================================
-- V2: PERFORMANCE INDEXES, REDUNDANT INDEX CLEANUP & MISSING UPDATED_AT TRIGGERS
-- ==============================================================================

-- 1. Functional index for case-insensitive email lookup (UserRepository.findByEmail)
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower
    ON users (LOWER(email));

-- 2. Missing FK and composite query indexes across bounded contexts
CREATE INDEX IF NOT EXISTS idx_patients_client_name
    ON patients (client_id, name);

CREATE INDEX IF NOT EXISTS idx_appointments_patient_id
    ON appointments (patient_id);

CREATE INDEX IF NOT EXISTS idx_appointments_user_clinic_date
    ON appointments (user_id, clinic_id, scheduled_at);

CREATE INDEX IF NOT EXISTS idx_templates_user_created
    ON templates (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_report_images_report_order
    ON report_images (report_id, display_order, id);

-- 3. Remove redundant indexes on refresh_tokens and add expiry index for cleanup
DROP INDEX IF EXISTS idx_refresh_tokens_token;
DROP INDEX IF EXISTS idx_refresh_tokens_user_id;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at
    ON refresh_tokens (expires_at);

-- 4. Missing updated_at triggers on clinics and clients
DROP TRIGGER IF EXISTS update_clinics_modtime ON clinics;
CREATE TRIGGER update_clinics_modtime
    BEFORE UPDATE ON clinics
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();

DROP TRIGGER IF EXISTS update_clients_modtime ON clients;
CREATE TRIGGER update_clients_modtime
    BEFORE UPDATE ON clients
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
