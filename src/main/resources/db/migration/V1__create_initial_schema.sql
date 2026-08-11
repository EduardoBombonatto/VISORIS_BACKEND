-- ==============================================================================
-- ENDOCLOUD VET - DATABASE SCHEMA (POSTGRESQL)
-- ==============================================================================

-- ==============================================================================
-- SNOWFLAKE ID GENERATOR (PL/pgSQL)
-- ==============================================================================
-- Cria uma sequência global para lidar com a concorrência no mesmo milissegundo
CREATE SEQUENCE IF NOT EXISTS global_id_sequence;

-- Função que gera um ‘ID’ Snowflake (BIGINT de 64-bits)
CREATE
OR REPLACE FUNCTION next_id(OUT result bigint) AS $$
DECLARE
our_epoch bigint := 1704067200000; -- Epoch customizado: 01/01/2024
    seq_id
bigint;
    now_millis
bigint;
    shard_id
int := 1; -- ‘ID’ da instância/shard do banco (útil para clusterização)
BEGIN
SELECT nextval('global_id_sequence') % 1024
INTO seq_id;
SELECT FLOOR(EXTRACT(EPOCH FROM clock_timestamp()) * 1000)
INTO now_millis;

result
:= (now_millis - our_epoch) << 23;
    result
:= result | (shard_id << 10);
    result
:= result | (seq_id);
END;
$$
LANGUAGE plpgsql;

-- ==============================================================================
-- ENUMS GLOBAIS (Tipos Fortes para o Scala 3 fazer Pattern Matching)
-- ==============================================================================
CREATE TYPE patient_type_enum AS ENUM ('PET', 'HUMAN');
CREATE TYPE exam_status_enum AS ENUM ('SCHEDULED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED');
CREATE TYPE report_status_enum AS ENUM ('PENDING', 'DRAFT', 'COMPLETED');
CREATE TYPE payment_status_enum AS ENUM ('UNPAID', 'PAID', 'INSURANCE');
CREATE TYPE clinic_invitations_status_enum AS ENUM ('PENDING', 'ACCEPTED', 'CANCELLED');

-- ==============================================================================
-- MÓDULO 1: IDENTIDADE E ACESSO (IAM)
-- ==============================================================================

CREATE TABLE users
(
    id                    BIGINT PRIMARY KEY       DEFAULT next_id(),
    email                 VARCHAR(255) UNIQUE NOT NULL,
    password_hash         VARCHAR(255)        NOT NULL,
    full_name             VARCHAR(255)        NOT NULL,
    professional_document VARCHAR(50) UNIQUE  NOT NULL,
    digital_signature_url VARCHAR(1024),
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

COMMENT
ON TABLE users IS 'Armazena os veterinários/médicos (Tenants principais atuais).';

CREATE TABLE refresh_tokens
(
    id          BIGINT PRIMARY KEY                DEFAULT next_id(),
    user_id     BIGINT                   NOT NULL,
    clinic_id   BIGINT,
    role        VARCHAR(50),
    token       VARCHAR(512) UNIQUE      NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    is_revoked  BOOLEAN                  NOT NULL DEFAULT FALSE,
    device_info VARCHAR(255),
    ip_address  VARCHAR(45),
    created_at  TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Índice para acelerar a busca do ‘token’ durante o processo de renovação (refresh)
CREATE INDEX idx_refresh_tokens_token ON refresh_tokens (token);
-- Índice para buscar rapidamente todas as sessões ativas de um utilizador
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

COMMENT
ON TABLE refresh_tokens IS 'Armazena as sessões ativas e tokens de renovação dos usuários para controle de segurança.';

-- ==============================================================================
-- MÓDULO 2: GESTÃO DE PARCEIROS (CLÍNICAS)
-- ==============================================================================

CREATE TABLE clinics
(
    id               BIGINT PRIMARY KEY       DEFAULT next_id(),
    name             VARCHAR(255) NOT NULL,
    cnpj             VARCHAR(20),
    phone            VARCHAR(20),
    address          VARCHAR(500),
    max_active_seats INTEGER      NOT NULL    DEFAULT 1,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE refresh_tokens
    ADD CONSTRAINT fk_refresh_tokens_clinic
        FOREIGN KEY (clinic_id) REFERENCES clinics (id) ON DELETE CASCADE;

-- Tabela de Junção (Mitigação para Escala B2B)
CREATE TABLE doctor_clinics
(
    user_id    BIGINT REFERENCES users (id) ON DELETE CASCADE,
    clinic_id  BIGINT REFERENCES clinics (id) ON DELETE CASCADE,
    role       VARCHAR(50) NOT NULL     DEFAULT 'DOCTOR',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, clinic_id)
);

CREATE INDEX idx_doctor_clinics_user ON doctor_clinics (user_id);
COMMENT
ON TABLE doctor_clinics IS 'Permite que um vet atenda em várias clínicas, e uma clínica tenha vários vets no futuro.';

CREATE TABLE clinic_invitations
(
    id           BIGINT PRIMARY KEY                      DEFAULT next_id(),
    clinic_id    BIGINT                         NOT NULL REFERENCES clinics (id) ON DELETE CASCADE,
    email        VARCHAR(255)                   NOT NULL,
    role         VARCHAR(50)                    NOT NULL DEFAULT 'DOCTOR',
    invite_token VARCHAR(128) UNIQUE            NOT NULL,
    status       clinic_invitations_status_enum NOT NULL DEFAULT 'PENDING', -- PENDING, ACCEPTED, CANCELLED
    expires_at   TIMESTAMP WITH TIME ZONE       NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE                DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_invitations_token ON clinic_invitations (invite_token);
COMMENT
ON TABLE clinic_invitations IS 'Gerencia convites enviados para médicos participarem de uma clínica.';
COMMENT
ON COLUMN clinic_invitations.status IS 'PENDING, ACCEPTED, CANCELLED';

-- ==============================================================================
-- MÓDULO 3: PACIENTES E TUTORES
-- ==============================================================================

-- O Responsável Legal / Financeiro
CREATE TABLE clients
(
    id               BIGINT PRIMARY KEY       DEFAULT next_id(),
    clinic_id        BIGINT       NOT NULL REFERENCES clinics (id) ON DELETE CASCADE,
    full_name        VARCHAR(255) NOT NULL,
    document_cpf     VARCHAR(20),
    email            VARCHAR(255),
    phone            VARCHAR(20),
    max_active_seats INTEGER      NOT NULL    DEFAULT 1,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_clients_clinic ON clients (clinic_id);

-- A Entidade Biológica (Padrão ‘Single’ Table Inheritance via JSONB)
CREATE TABLE patients
(
    id                 BIGINT PRIMARY KEY         DEFAULT next_id(),
    client_id          BIGINT REFERENCES clients (id) ON DELETE RESTRICT,
    name               VARCHAR(255)      NOT NULL,
    patient_type       patient_type_enum NOT NULL,
    birth_date         DATE,
    biological_details JSONB             NOT NULL DEFAULT '{}'::jsonb,
    created_at         TIMESTAMP WITH TIME ZONE   DEFAULT CURRENT_TIMESTAMP
);

COMMENT
ON COLUMN patients.biological_details IS 'JSON Flexível para não encher a tabela de colunas nulas entre Humanos e Pets.';

-- ==============================================================================
-- MÓDULO 4: AGENDA E FINANCEIRO
-- ==============================================================================

CREATE TABLE appointments
(
    id             BIGINT PRIMARY KEY                DEFAULT next_id(),
    user_id        BIGINT REFERENCES users (id) ON DELETE CASCADE,
    clinic_id      BIGINT REFERENCES clinics (id) ON DELETE RESTRICT,
    patient_id     BIGINT REFERENCES patients (id) ON DELETE RESTRICT,

    scheduled_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    procedure_name VARCHAR(255)             NOT NULL,

    exam_status    exam_status_enum         NOT NULL DEFAULT 'SCHEDULED',
    report_status  report_status_enum       NOT NULL DEFAULT 'PENDING',

    payment_status payment_status_enum      NOT NULL DEFAULT 'UNPAID',
    price          DECIMAL(10, 2),

    created_at     TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE          DEFAULT CURRENT_TIMESTAMP
);

-- Índices para acelerar a busca da agenda diária
CREATE INDEX idx_appointments_date_user ON appointments (user_id, scheduled_at);

-- ==============================================================================
-- MÓDULO 5: CONHECIMENTO (TEMPLATES)
-- ==============================================================================

CREATE TABLE templates
(
    id         BIGINT PRIMARY KEY       DEFAULT next_id(),
    user_id    BIGINT REFERENCES users (id) ON DELETE CASCADE,
    title      VARCHAR(255) NOT NULL,
    content    TEXT         NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==============================================================================
-- MÓDULO 6: LAUDOS E EMISSÃO (CORE)
-- ==============================================================================

CREATE TABLE reports
(
    id                       BIGINT PRIMARY KEY       DEFAULT next_id(),
    appointment_id           BIGINT UNIQUE REFERENCES appointments (id) ON DELETE RESTRICT,
    user_id                  BIGINT REFERENCES users (id) ON DELETE CASCADE,
    attendance_location_name VARCHAR(255),
    patient_snapshot         JSONB,
    clinic_snapshot          JSONB,
    content                  TEXT NOT NULL,
    pdf_url                  VARCHAR(1024),
    signed_at                TIMESTAMP WITH TIME ZONE,
    created_at               TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Imagens anexadas a um laudo específico
CREATE TABLE report_images
(
    id            BIGINT PRIMARY KEY       DEFAULT next_id(),
    report_id     BIGINT REFERENCES reports (id) ON DELETE CASCADE,
    image_url     VARCHAR(1024) NOT NULL,
    caption       VARCHAR(500),
    display_order INTEGER       NOT NULL   DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- ==============================================================================
-- TRIGGERS E FUNÇÕES AUXILIARES
-- ==============================================================================

-- Função para atualizar o updated_at automaticamente
CREATE
OR REPLACE FUNCTION update_modified_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at
= now();
RETURN NEW;
END;
$$
language 'plpgsql';

-- Aplicando a trigger nas tabelas que precisam
CREATE TRIGGER update_users_modtime
    BEFORE UPDATE
    ON users
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
CREATE TRIGGER update_appointments_modtime
    BEFORE UPDATE
    ON appointments
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
CREATE TRIGGER update_templates_modtime
    BEFORE UPDATE
    ON templates
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();
CREATE TRIGGER update_reports_modtime
    BEFORE UPDATE
    ON reports
    FOR EACH ROW EXECUTE PROCEDURE update_modified_column();