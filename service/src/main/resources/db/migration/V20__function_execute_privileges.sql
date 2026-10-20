-- Schema-local defaults cannot revoke PostgreSQL's global PUBLIC EXECUTE default.
ALTER DEFAULT PRIVILEGES FOR ROLE starry_api_owner
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- Keep explicit runtime grants while correcting functions created by earlier migrations.
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA starry_api FROM PUBLIC;
