REVOKE ALL ON TABLE
    announcements,
    announcement_recipients
FROM PUBLIC;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    announcements,
    announcement_recipients
TO starry_api_runtime;

GRANT USAGE, SELECT ON SEQUENCE
    announcements_id_seq,
    announcement_recipients_id_seq
TO starry_api_runtime;
