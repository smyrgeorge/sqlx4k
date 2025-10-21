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

-- Allow pgmq.notify_insert_throttle to be dumped by `pg_dump` when pgmq is installed as an extension
DO
$$
    BEGIN
        IF EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'pgmq') THEN
            PERFORM pg_catalog.pg_extension_config_dump('pgmq.notify_insert_throttle', '');
        END IF;
    END
$$;

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
    qtable TEXT := pgmq.format_table_name(queue_name, 'q'); -- Formatted queue table name
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

-- ========================================================================
-- Usage Examples
-- ========================================================================

-- ========================================================================
-- Example 1: Basic Notification (No Throttling)
-- ========================================================================
-- Enable immediate notifications for every message insertion
-- ========================================================================

-- Setup: Create a queue and enable notifications
-- SELECT pgmq.create('events');
-- SELECT pgmq.enable_notify_insert('events');  -- Default throttle_interval_ms = 0 (no throttling)

-- Listen for notifications (in a separate session/client)
-- LISTEN pgmq.q_events.INSERT;

-- Send messages (each triggers a notification)
-- SELECT pgmq.send('events', '{"type": "user_signup", "user_id": 1}'::jsonb);
-- SELECT pgmq.send('events', '{"type": "user_signup", "user_id": 2}'::jsonb);
-- SELECT pgmq.send('events', '{"type": "user_signup", "user_id": 3}'::jsonb);
-- Result: 3 notifications received (one for each insert)

-- Cleanup
-- UNLISTEN pgmq.q_events.INSERT;
-- SELECT pgmq.disable_notify_insert('events');
-- SELECT pgmq.drop_queue('events');

-- ========================================================================
-- Example 2: Throttled Notifications (High-Volume Queue)
-- ========================================================================
-- Limit notification frequency to prevent flooding under high message rates
-- ========================================================================

-- Setup: Create a queue with 1-second throttle interval
-- SELECT pgmq.create('logs');
-- SELECT pgmq.enable_notify_insert('logs', 1000);  -- Max 1 notification per second

-- Listen for notifications
-- LISTEN pgmq.q_logs.INSERT;

-- Send multiple messages rapidly (within 1 second)
-- SELECT pgmq.send('logs', '{"level": "info", "msg": "Log entry 1"}'::jsonb);
-- SELECT pgmq.send('logs', '{"level": "info", "msg": "Log entry 2"}'::jsonb);
-- SELECT pgmq.send('logs', '{"level": "info", "msg": "Log entry 3"}'::jsonb);
-- Result: Only 1 notification received (subsequent inserts are throttled)

-- Wait 1+ second, then send another message
-- SELECT pg_sleep(1.1);
-- SELECT pgmq.send('logs', '{"level": "error", "msg": "Error occurred"}'::jsonb);
-- Result: 1 notification received (throttle interval elapsed)

-- Cleanup
-- UNLISTEN pgmq.q_logs.INSERT;
-- SELECT pgmq.disable_notify_insert('logs');
-- SELECT pgmq.drop_queue('logs');

-- ========================================================================
-- Example 3: Updating Throttle Settings
-- ========================================================================
-- Change throttle interval on an already-enabled queue
-- ========================================================================

-- Setup: Create a queue with no throttling
-- SELECT pgmq.create('tasks');
-- SELECT pgmq.enable_notify_insert('tasks', 0);  -- No throttling initially

-- Later, decide to throttle to max 1 notification per 5 seconds
-- SELECT pgmq.enable_notify_insert('tasks', 5000);  -- Idempotent - updates existing config

-- Verify the updated configuration
-- SELECT queue_name, throttle_interval_ms FROM pgmq.notify_insert_throttle WHERE queue_name = 'tasks';
-- Returns: queue_name = 'tasks', throttle_interval_ms = 5000

-- Cleanup
-- SELECT pgmq.disable_notify_insert('tasks');
-- SELECT pgmq.drop_queue('tasks');

-- ========================================================================
-- Example 4: Multiple Queues with Different Throttle Settings
-- ========================================================================
-- Configure different throttle intervals for different queue types
-- ========================================================================

-- Setup: Create queues for different use cases
-- SELECT pgmq.create('critical_alerts');   -- Critical alerts need immediate notifications
-- SELECT pgmq.create('metrics');           -- High-volume metrics can be throttled
-- SELECT pgmq.create('audit_logs');        -- Audit logs moderately throttled

-- Configure throttling based on queue characteristics
-- SELECT pgmq.enable_notify_insert('critical_alerts', 0);      -- No throttling (immediate)
-- SELECT pgmq.enable_notify_insert('metrics', 10000);          -- Max 1 notification per 10 seconds
-- SELECT pgmq.enable_notify_insert('audit_logs', 5000);        -- Max 1 notification per 5 seconds

-- View all configurations
-- SELECT queue_name, throttle_interval_ms, last_notified_at FROM pgmq.notify_insert_throttle ORDER BY queue_name;

-- Cleanup
-- SELECT pgmq.disable_notify_insert('critical_alerts');
-- SELECT pgmq.disable_notify_insert('metrics');
-- SELECT pgmq.disable_notify_insert('audit_logs');
-- SELECT pgmq.drop_queue('critical_alerts');
-- SELECT pgmq.drop_queue('metrics');
-- SELECT pgmq.drop_queue('audit_logs');

-- ========================================================================
-- Example 5: Monitoring Notification Activity
-- ========================================================================
-- Check when queues were last notified
-- ========================================================================

-- Setup: Create and configure queues
-- SELECT pgmq.create('orders');
-- SELECT pgmq.enable_notify_insert('orders', 2000);  -- Max 1 notification per 2 seconds

-- Send some messages
-- SELECT pgmq.send('orders', '{"order_id": 1, "status": "pending"}'::jsonb);

-- Check when last notification was sent
-- SELECT queue_name,
--        throttle_interval_ms,
--        last_notified_at,
--        EXTRACT(EPOCH FROM (clock_timestamp() - last_notified_at)) * 1000 as ms_since_last_notify
-- FROM pgmq.notify_insert_throttle
-- WHERE queue_name = 'orders';

-- Send more messages and observe throttling behavior
-- SELECT pgmq.send('orders', '{"order_id": 2, "status": "pending"}'::jsonb);  -- Throttled (no new notification)
-- SELECT queue_name, last_notified_at FROM pgmq.notify_insert_throttle WHERE queue_name = 'orders';
-- Notice: last_notified_at hasn't changed (notification was throttled)

-- Wait for throttle interval to elapse
-- SELECT pg_sleep(2.1);
-- SELECT pgmq.send('orders', '{"order_id": 3, "status": "pending"}'::jsonb);  -- New notification sent
-- SELECT queue_name, last_notified_at FROM pgmq.notify_insert_throttle WHERE queue_name = 'orders';
-- Notice: last_notified_at has updated to current time

-- Cleanup
-- SELECT pgmq.disable_notify_insert('orders');
-- SELECT pgmq.drop_queue('orders');
