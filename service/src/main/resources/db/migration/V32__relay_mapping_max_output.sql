-- The most output tokens the relay's upstream writes for a mapped model (design 7.10, D15). The gateway does not know it: a
-- relay is sent 4,096 for a Responses request that names no output limit, the limit it was sent before the gateway began to
-- give such requests more (D11, D14), unless the mapping states a maximum, and is then sent that, as far as the default for
-- the request goes. NULL says the mapping states none. Nullable and without a default, so that the mappings that exist keep
-- the 4,096 they were sent.
ALTER TABLE source_tier_member_models
    ADD COLUMN max_output_tokens INTEGER,
    ADD CONSTRAINT ck_source_tier_member_models_max_output
        CHECK (max_output_tokens IS NULL OR max_output_tokens BETWEEN 1 AND 1000000);
