-- ==============================================================================
-- ENDOCLOUD VET - REVOKE METADATA FOR REFRESH TOKENS (V2)
-- ==============================================================================
-- Rastrea quando e por que um refresh token foi revogado. Permite ao backend
-- distinguir revogação por rotação (single-use, benigna) de logout/roubo,
-- para aplicar janela de reuso apenas no primeiro caso.
ALTER TABLE refresh_tokens
    ADD COLUMN revoked_at     TIMESTAMP WITH TIME ZONE,
    ADD COLUMN revoked_reason VARCHAR(20);

COMMENT
ON COLUMN refresh_tokens.revoked_reason IS 'ROTATION, LOGOUT ou THEFT';

CREATE INDEX idx_refresh_tokens_revoked
    ON refresh_tokens (user_id, is_revoked);
