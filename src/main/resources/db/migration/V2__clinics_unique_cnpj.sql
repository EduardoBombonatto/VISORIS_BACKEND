CREATE UNIQUE INDEX uq_clinics_cnpj ON clinics (cnpj) WHERE cnpj IS NOT NULL;
