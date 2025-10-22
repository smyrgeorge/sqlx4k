-- ============================================================================
-- Notification Throttling System for PGMQ
-- ============================================================================
-- This file implements a PostgreSQL NOTIFY-based notification system for
-- PGMQ queues with configurable throttling to prevent notification flooding
-- under high message insertion rates.
-- ============================================================================

-- Table: notify_insert_throttle
-- Purpose: Tracks notification throttling configuration and state for each queue
DROP TABLE IF EXISTS pgmq.notify_insert_throttle;
CREATE TABLE IF NOT EXISTS pgmq.notify_insert_throttle
(
    queue_name           VARCHAR UNIQUE NOT NULL,           -- Queue name (without 'q_' prefix)
    throttle_interval_ms INTEGER        NOT NULL DEFAULT 0, -- Min milliseconds between notifications (0 = no throttling)
    last_notified_at     TIMESTAMP WITH TIME ZONE           -- Timestamp of last sent notification (NULL if never notified)
);

-- ============================================================================
-- Performance Optimizations
-- ============================================================================

-- Optimization 1: Partial Index for Throttled Queues
-- Creates an index only for queues with active throttling (throttle_interval_ms > 0)
-- Includes last_notified_at for index-only scans in the hot path
-- Smaller index = faster updates and better cache hit ratio
CREATE INDEX IF NOT EXISTS idx_notify_throttle_active
    ON pgmq.notify_insert_throttle (queue_name, last_notified_at)
    WHERE throttle_interval_ms > 0;

-- Optimization 2: FILLFACTOR for HOT Updates
-- Set fillfactor to 70 to leave 30% free space for HOT (Heap-Only Tuple) updates
-- This is critical since last_notified_at is updated on every notification
-- HOT updates avoid index maintenance overhead and reduce table bloat
-- Expected improvement: 20-40% faster updates under high concurrency
ALTER TABLE pgmq.notify_insert_throttle
    SET (fillfactor = 70);

-- Optimization 3: Autovacuum Tuning for High-Update Table
-- Tune autovacuum to run more frequently on this high-update table
-- Prevents bloat and keeps statistics fresh for optimal query planning
ALTER TABLE pgmq.notify_insert_throttle
    SET (
        autovacuum_vacuum_scale_factor = 0.05, -- VACUUM more frequently (default: 0.2)
        autovacuum_analyze_scale_factor = 0.02 -- ANALYZE more frequently (default: 0.1)
        );

-- Add column comments for documentation
COMMENT ON COLUMN pgmq.notify_insert_throttle.queue_name IS
    'Name of the queue (matches the queue table name without the q_ prefix). '
        'This is the logical queue name, not the physical table name.';

COMMENT ON COLUMN pgmq.notify_insert_throttle.throttle_interval_ms IS
    'Minimum milliseconds between notifications. '
        'Set to 0 for immediate notifications on every insert (no throttling). '
        'Set to >0 to limit notification frequency, e.g., 1000 = max 1 notification per second. '
        'Prevents notification flooding under high message insertion rates.';

COMMENT ON COLUMN pgmq.notify_insert_throttle.last_notified_at IS
    'Timestamp of the last sent notification using clock_timestamp() for real-time precision. '
        'NULL if no notification has ever been sent for this queue. '
        'Updated atomically by notify_queue_listeners() to prevent race conditions.';

-- ============================================================================
-- Notify Queue Listeners Trigger Function
-- ============================================================================
-- Sends PostgreSQL NOTIFY events when messages are inserted into queues,
-- respecting throttle intervals to prevent notification flooding.
-- Designed to be concurrency-safe using atomic UPDATE operations.
-- ============================================================================

CREATE OR REPLACE FUNCTION pgmq.notify_queue_listeners()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    queue_name_extracted TEXT; -- Queue name extracted from trigger table name
    updated_count        INTEGER; -- Number of rows updated (0 or 1)
BEGIN
    -- ========================================================================
    -- Extract Queue Name
    -- ========================================================================
    -- Extract queue name from table name (assumes format: q_<queue_name>)
    -- For example: q_my_queue -> my_queue
    -- This relies on the PGMQ naming convention where queue tables are prefixed with 'q_'

    queue_name_extracted := substring(TG_TABLE_NAME from 3);

    -- ========================================================================
    -- Atomic Throttle Check and Update
    -- ========================================================================
    -- Atomically check if notification should be sent and update timestamp
    -- This prevents race conditions under high concurrency by combining
    -- the read-check-write operations into a single atomic UPDATE.
    -- Only one transaction will succeed in updating within the throttle window.
    -- Uses optimized interval comparison to avoid string concatenation

    UPDATE pgmq.notify_insert_throttle
    SET last_notified_at = clock_timestamp()
    WHERE queue_name = queue_name_extracted
      AND (
        throttle_interval_ms = 0 -- No throttling configured
            OR last_notified_at IS NULL -- Never notified before
            OR clock_timestamp() - last_notified_at >=
               (throttle_interval_ms * INTERVAL '1 millisecond') -- Throttle interval has elapsed
        );

    -- Check how many rows were updated (will be 0 or 1)
    GET DIAGNOSTICS updated_count = ROW_COUNT;

    -- ========================================================================
    -- Send Notification
    -- ========================================================================
    -- If we successfully updated a row, send the NOTIFY event
    -- Notification format: 'pgmq.<table_name>.<operation>'
    -- Example: 'pgmq.q_my_queue.INSERT'

    IF updated_count > 0 THEN
        PERFORM PG_NOTIFY('pgmq.' || TG_TABLE_NAME || '.' || TG_OP, NULL);
    END IF;

    -- Return NEW as required by AFTER INSERT triggers
    RETURN NEW;
END;
$$;

-- Add comment for the trigger function
COMMENT ON FUNCTION pgmq.notify_queue_listeners() IS
    'Trigger function that sends PostgreSQL NOTIFY events for message insertions with throttling support. '
        'Uses atomic UPDATE to prevent race conditions under high concurrency. '
        'Notification format: pgmq.<table_name>.<operation> (e.g., pgmq.q_my_queue.INSERT). '
        'Only sends notifications if throttle interval has elapsed or no throttling is configured. '
        'Designed to be attached as an AFTER INSERT trigger on queue tables.';

-- ============================================================================
-- Enable Notification Function
-- ============================================================================
-- Enables PostgreSQL NOTIFY events for message insertions on a specific queue
-- with optional throttling configuration
-- ============================================================================
DROP FUNCTION IF EXISTS pgmq.enable_notify_insert(text, integer);
CREATE OR REPLACE FUNCTION pgmq.enable_notify_insert(queue_name TEXT, throttle_interval_ms INTEGER)
    RETURNS void
    LANGUAGE plpgsql
AS
$$
DECLARE
    qtable                 TEXT    := pgmq.format_table_name(queue_name, 'q'); -- Formatted queue table name
    v_queue_name           TEXT    := queue_name; -- Local copy of queue_name parameter
    v_throttle_interval_ms INTEGER := throttle_interval_ms; -- Local copy of throttle parameter
BEGIN
    -- ========================================================================
    -- Input Validation
    -- ========================================================================

    -- Validate that throttle_interval_ms is non-negative
    IF v_throttle_interval_ms < 0 THEN
        RAISE EXCEPTION 'throttle_interval_ms must be non-negative, got: %', v_throttle_interval_ms;
    END IF;

    -- Validate that the queue table exists
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.tables
                   WHERE table_schema = 'pgmq'
                     AND table_name = qtable) THEN
        RAISE EXCEPTION 'Queue "%" does not exist. Create it first using pgmq.create()', v_queue_name;
    END IF;

    -- ========================================================================
    -- Remove Existing Notification Setup (Idempotent)
    -- ========================================================================
    -- First disable any existing notification setup for this queue
    -- This makes the function idempotent and allows updating throttle settings

    PERFORM pgmq.disable_notify_insert(v_queue_name);

    -- ========================================================================
    -- Register Throttle Configuration
    -- ========================================================================
    -- Insert or update the throttle configuration in notify_insert_throttle table
    -- Reset last_notified_at to NULL so first notification is sent immediately

    INSERT INTO pgmq.notify_insert_throttle (queue_name, throttle_interval_ms, last_notified_at)
    VALUES (v_queue_name, v_throttle_interval_ms, NULL)
    ON CONFLICT ON CONSTRAINT notify_insert_throttle_queue_name_key DO UPDATE
        SET throttle_interval_ms = EXCLUDED.throttle_interval_ms,
            last_notified_at     = NULL;

    -- ========================================================================
    -- Create Trigger
    -- ========================================================================
    -- Create AFTER INSERT trigger on the queue table
    -- Fires immediately per-row (not deferred to transaction commit)
    -- Calls notify_queue_listeners() which handles throttling logic

    EXECUTE FORMAT(
            $QUERY$
            CREATE TRIGGER trigger_notify_queue_insert_listeners
            AFTER INSERT ON pgmq.%I
            FOR EACH ROW
            EXECUTE FUNCTION pgmq.notify_queue_listeners()
            $QUERY$,
            qtable
            );
END;
$$;

-- Add comment for the enable function
COMMENT ON FUNCTION pgmq.enable_notify_insert(text, integer) IS
    'Enables PostgreSQL NOTIFY events for message insertions on a specific queue with optional throttling. '
        'Parameters: queue_name (queue to enable), throttle_interval_ms (min ms between notifications, default 0). '
        'Creates an AFTER INSERT trigger that sends notifications respecting the throttle interval. '
        'Idempotent - safe to call multiple times. Updates throttle settings if already enabled. '
        'Usage: SELECT pgmq.enable_notify_insert(''my_queue''); '
        'Usage: SELECT pgmq.enable_notify_insert(''my_queue'', 1000); -- Max 1 notification per second';

-- ============================================================================
-- Enable Notification Function Overload (No Throttling)
-- ============================================================================
-- Convenience overload that enables notifications without throttling
-- ============================================================================

CREATE OR REPLACE FUNCTION pgmq.enable_notify_insert(queue_name TEXT)
    RETURNS void
    LANGUAGE plpgsql
AS
$$
DECLARE
    v_queue_name TEXT := queue_name; -- Local variable to avoid parameter/column ambiguity
BEGIN
    -- Delegate to the main enable_notify_insert function with default throttle (0 = no throttling)
    PERFORM pgmq.enable_notify_insert(v_queue_name, 0);
END;
$$;

-- Add comment for the overload function
COMMENT ON FUNCTION pgmq.enable_notify_insert(text) IS
    'Convenience overload for enabling notifications without throttling (throttle_interval_ms = 0). '
        'Delegates to pgmq.enable_notify_insert(queue_name, 0). '
        'Usage: SELECT pgmq.enable_notify_insert(''my_queue'');';

-- ============================================================================
-- Disable Notification Function
-- ============================================================================
-- Disables PostgreSQL NOTIFY events for message insertions on a specific queue
-- Removes trigger and cleans up throttle configuration
-- ============================================================================

CREATE OR REPLACE FUNCTION pgmq.disable_notify_insert(queue_name TEXT)
    RETURNS void
    LANGUAGE plpgsql
AS
$$
DECLARE
    qtable       TEXT := pgmq.format_table_name(queue_name, 'q'); -- Formatted queue table name
    v_queue_name TEXT := queue_name; -- Local copy of parameter to avoid ambiguity
BEGIN
    -- ========================================================================
    -- Remove Trigger
    -- ========================================================================
    -- Drop the AFTER INSERT trigger from the queue table (if it exists)
    -- Uses IF EXISTS to make this operation idempotent

    EXECUTE FORMAT(
            $QUERY$
            DROP TRIGGER IF EXISTS trigger_notify_queue_insert_listeners ON pgmq.%I;
            $QUERY$,
            qtable
            );

    -- ========================================================================
    -- Clean Up Throttle Configuration
    -- ========================================================================
    -- Remove the queue's entry from the notify_insert_throttle table
    -- Uses table alias for clarity and to avoid column/parameter name conflicts

    DELETE FROM pgmq.notify_insert_throttle nit WHERE nit.queue_name = v_queue_name;
END;
$$;

-- Add comment for the disable function
COMMENT ON FUNCTION pgmq.disable_notify_insert(text) IS
    'Disables PostgreSQL NOTIFY events for message insertions on a specific queue. '
        'Removes the AFTER INSERT trigger and cleans up throttle configuration. '
        'Idempotent - safe to call multiple times (uses DROP TRIGGER IF EXISTS). '
        'After calling this, no NOTIFY events will be sent until enable_notify_insert() is called again. '
        'Usage: SELECT pgmq.disable_notify_insert(''my_queue'');';

-- ============================================================================
-- Drop Queue Function
-- ============================================================================
-- Redeclared because we also want to delete from the notify_insert_throttle table.
-- ============================================================================

CREATE FUNCTION pgmq.drop_queue(queue_name TEXT)
    RETURNS BOOLEAN AS
$$
DECLARE
    qtable      TEXT := pgmq.format_table_name(queue_name, 'q');
    qtable_seq  TEXT := qtable || '_msg_id_seq';
    fq_qtable   TEXT := 'pgmq.' || qtable;
    atable      TEXT := pgmq.format_table_name(queue_name, 'a');
    fq_atable   TEXT := 'pgmq.' || atable;
    partitioned BOOLEAN;
BEGIN
    PERFORM pgmq.acquire_queue_lock(queue_name);
    EXECUTE FORMAT(
            $QUERY$
            SELECT is_partitioned FROM pgmq.meta WHERE queue_name = %L
            $QUERY$,
            queue_name
            ) INTO partitioned;

    -- check if the queue exists
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.tables
                   WHERE table_name = qtable
                     and table_schema = 'pgmq') THEN
        RAISE NOTICE 'pgmq queue `%` does not exist', queue_name;
        RETURN FALSE;
    END IF;

    EXECUTE FORMAT(
            $QUERY$
            DROP TABLE IF EXISTS pgmq.%I
            $QUERY$,
            qtable
            );

    EXECUTE FORMAT(
            $QUERY$
            DROP TABLE IF EXISTS pgmq.%I
            $QUERY$,
            atable
            );

    IF EXISTS (SELECT 1
               FROM information_schema.tables
               WHERE table_name = 'meta'
                 and table_schema = 'pgmq') THEN
        EXECUTE FORMAT(
                $QUERY$
                DELETE FROM pgmq.meta WHERE queue_name = %L
                $QUERY$,
                queue_name
                );
    END IF;

    IF partitioned THEN
        EXECUTE FORMAT(
                $QUERY$
                DELETE FROM %I.part_config where parent_table in (%L, %L)
                $QUERY$,
                pgmq._get_pg_partman_schema(), fq_qtable, fq_atable
                );
    END IF;

    -- Clean up notification configuration if exists
    DELETE FROM pgmq.notify_insert_throttle WHERE notify_insert_throttle.queue_name = drop_queue.queue_name;

    RETURN TRUE;
END;
$$ LANGUAGE plpgsql;
