-- Plain HTTP CONNECT and SOCKS5 nodes are reached directly by this application; every other protocol goes through a
-- Mihomo listener. The route is decided when a node is imported (see ClashNodes) and stored so that SQL, the
-- Mihomo configuration and the transport cannot disagree about it.
ALTER TABLE proxy_channels
    ADD COLUMN route VARCHAR(16) NOT NULL DEFAULT 'MIHOMO',
    ADD CONSTRAINT ck_proxy_channel_route CHECK (route IN ('DIRECT_PROXY', 'MIHOMO'));

-- Existing plain nodes: only type, server, port and credentials (udp is irrelevant to a tunnel), and for an HTTP
-- proxy TLS to the proxy itself (tls: true). Anything else is an option only Mihomo honours.
UPDATE proxy_channels SET route = 'DIRECT_PROXY'
WHERE protocol IN ('http', 'socks5')
  AND jsonb_typeof(node_config) = 'object'
  AND (node_config - 'type' - 'server' - 'port' - 'username' - 'password' - 'udp'
       - CASE WHEN node_config -> 'tls' = 'false'::jsonb OR (protocol = 'http' AND node_config -> 'tls' = 'true'::jsonb)
              THEN 'tls' ELSE '' END) = '{}'::jsonb;

-- Listener ports used to come from an IDENTITY (20000..29999) that every inserted row consumed for good. A
-- subscription refresh inserts a new row whenever a node fingerprint changes, so the range ran out after a few
-- refreshes and every later import failed. A port now belongs to a node only while that node needs a Mihomo
-- listener (route MIHOMO, present in its source, without a configuration issue); it is released when the node stops
-- needing one and the lowest free port is assigned under an advisory lock whenever a node starts needing one.
ALTER TABLE proxy_channels
    ALTER COLUMN listener_port DROP IDENTITY IF EXISTS,
    ALTER COLUMN listener_port DROP NOT NULL;

-- Release the ports nobody needs (without touching updated_at).
ALTER TABLE proxy_channels DISABLE TRIGGER trg_proxy_channels_updated_at;
UPDATE proxy_channels SET listener_port = NULL
WHERE route <> 'MIHOMO' OR NOT source_present OR config_issue IS NOT NULL;
ALTER TABLE proxy_channels ENABLE TRIGGER trg_proxy_channels_updated_at;

ALTER TABLE proxy_channels
    ADD CONSTRAINT ck_proxy_channel_listener_port
        CHECK (listener_port IS NULL OR listener_port BETWEEN 20000 AND 29999),
    ADD CONSTRAINT ck_proxy_channel_routable_port
        CHECK (listener_port IS NOT NULL OR route <> 'MIHOMO' OR NOT source_present OR config_issue IS NOT NULL);

CREATE FUNCTION starry_api.assign_proxy_channel_listener_port()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    assigned INTEGER;
BEGIN
    IF NEW.route <> 'MIHOMO' OR NOT NEW.source_present OR NEW.config_issue IS NOT NULL THEN
        NEW.listener_port := NULL;
        RETURN NEW;
    END IF;
    IF NEW.listener_port IS NOT NULL THEN
        RETURN NEW;
    END IF;
    -- Held until commit, so a concurrent allocation sees every port this transaction took (or released).
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('starry_api:proxy_channel_listener_port', 0));
    -- The lowest free port is either the start of the range or directly after a used port.
    SELECT candidate.port INTO assigned
    FROM (
        SELECT 20000 AS port
        UNION ALL
        SELECT used.listener_port + 1 FROM starry_api.proxy_channels AS used
        WHERE used.listener_port BETWEEN 20000 AND 29998
    ) AS candidate
    WHERE NOT EXISTS (
        SELECT 1 FROM starry_api.proxy_channels AS taken WHERE taken.listener_port = candidate.port
    )
    ORDER BY candidate.port
    LIMIT 1;
    IF assigned IS NULL THEN
        RAISE EXCEPTION 'No free proxy listener port in the range 20000-29999'
            USING ERRCODE = '53400';
    END IF;
    NEW.listener_port := assigned;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_proxy_channels_listener_port
    BEFORE INSERT OR UPDATE OF route, source_present, config_issue, listener_port ON proxy_channels
    FOR EACH ROW EXECUTE FUNCTION starry_api.assign_proxy_channel_listener_port();

REVOKE ALL ON FUNCTION starry_api.assign_proxy_channel_listener_port() FROM PUBLIC, starry_api_runtime;

-- An administrator's rename must survive the next subscription refresh, and the last probe is kept per node.
ALTER TABLE proxy_channels
    ADD COLUMN name_customized BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_test_exit_ip VARCHAR(45),
    ADD COLUMN last_test_latency_ms INTEGER,
    ADD COLUMN last_test_error_code VARCHAR(48),
    ADD CONSTRAINT ck_proxy_channel_last_test_result
        CHECK (last_test_result IS NULL OR last_test_result IN ('SUCCESS', 'FAILED')),
    ADD CONSTRAINT ck_proxy_channel_last_test_latency
        CHECK (last_test_latency_ms IS NULL OR last_test_latency_ms >= 0);
