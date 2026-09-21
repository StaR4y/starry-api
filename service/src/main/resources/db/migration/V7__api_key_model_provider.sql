ALTER TABLE api_keys
    ADD COLUMN model_provider VARCHAR(64) NOT NULL DEFAULT '*';

ALTER TABLE api_keys
    ADD CONSTRAINT ck_api_keys_model_provider_not_blank
    CHECK (btrim(model_provider) <> '');

COMMENT ON COLUMN api_keys.model_provider IS
    'Model catalog provider allowed for this API key; * is retained for legacy keys';
