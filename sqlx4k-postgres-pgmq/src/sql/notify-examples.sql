
-- ========================================================================
-- Usage Examples
-- ========================================================================

-- ========================================================================
-- Example 1: Basic Notification (No Throttling)
-- ========================================================================
-- Enable immediate notifications for every message insertion
-- ========================================================================

-- Setup: Create a queue and enable notifications
SELECT pgmq.create('events');
SELECT pgmq.enable_notify_insert('events');  -- Default throttle_interval_ms = 0 (no throttling)

-- Listen for notifications (in a separate session/client)
LISTEN "pgmq.q_events.INSERT";

-- Send messages (each triggers a notification)
SELECT pgmq.send('events', '{"type": "user_signup", "user_id": 1}'::jsonb);
SELECT pgmq.send('events', '{"type": "user_signup", "user_id": 2}'::jsonb);
SELECT pgmq.send('events', '{"type": "user_signup", "user_id": 3}'::jsonb);
-- Result: 3 notifications received (one for each insert)

-- Cleanup
-- noinspection SqlResolve
UNLISTEN "pgmq.q_events.INSERT";
SELECT pgmq.disable_notify_insert('events');
SELECT pgmq.drop_queue('events');

-- ========================================================================
-- Example 2: Throttled Notifications (High-Volume Queue)
-- ========================================================================
-- Limit notification frequency to prevent flooding under high message rates
-- ========================================================================

-- Setup: Create a queue with 1-second throttle interval
SELECT pgmq.create('logs');
SELECT pgmq.enable_notify_insert('logs', 1000);  -- Max 1 notification per second

-- Listen for notifications
LISTEN "pgmq.q_logs.INSERT";

-- Send multiple messages rapidly (within 1 second)
SELECT pgmq.send('logs', '{"level": "info", "msg": "Log entry 1"}'::jsonb);
SELECT pgmq.send('logs', '{"level": "info", "msg": "Log entry 2"}'::jsonb);
SELECT pgmq.send('logs', '{"level": "info", "msg": "Log entry 3"}'::jsonb);
-- Result: Only 1 notification received (subsequent inserts are throttled)

-- Wait 1+ second, then send another message
SELECT pg_sleep(1.1);
SELECT pgmq.send('logs', '{"level": "error", "msg": "Error occurred"}'::jsonb);
-- Result: 1 notification received (throttle interval elapsed)

-- Cleanup
-- noinspection SqlResolve
UNLISTEN "pgmq.q_logs.INSERT";
SELECT pgmq.disable_notify_insert('logs');
SELECT pgmq.drop_queue('logs');

-- ========================================================================
-- Example 3: Updating Throttle Settings
-- ========================================================================
-- Change throttle interval on an already-enabled queue
-- ========================================================================

-- Setup: Create a queue with no throttling
SELECT pgmq.create('tasks');
SELECT pgmq.enable_notify_insert('tasks', 0);  -- No throttling initially

-- Later, decide to throttle to max 1 notification per 5 seconds
SELECT pgmq.enable_notify_insert('tasks', 5000);  -- Idempotent - updates existing config

-- Verify the updated configuration
SELECT queue_name, throttle_interval_ms FROM pgmq.notify_insert_throttle WHERE queue_name = 'tasks';
-- Returns: queue_name = 'tasks', throttle_interval_ms = 5000

-- Cleanup
SELECT pgmq.disable_notify_insert('tasks');
SELECT pgmq.drop_queue('tasks');

-- ========================================================================
-- Example 4: Multiple Queues with Different Throttle Settings
-- ========================================================================
-- Configure different throttle intervals for different queue types
-- ========================================================================

-- Setup: Create queues for different use cases
SELECT pgmq.create('critical_alerts');   -- Critical alerts need immediate notifications
SELECT pgmq.create('metrics');           -- High-volume metrics can be throttled
SELECT pgmq.create('audit_logs');        -- Audit logs moderately throttled

-- Configure throttling based on queue characteristics
SELECT pgmq.enable_notify_insert('critical_alerts', 0);      -- No throttling (immediate)
SELECT pgmq.enable_notify_insert('metrics', 10000);          -- Max 1 notification per 10 seconds
SELECT pgmq.enable_notify_insert('audit_logs', 5000);        -- Max 1 notification per 5 seconds

-- View all configurations
SELECT queue_name, throttle_interval_ms, last_notified_at FROM pgmq.notify_insert_throttle ORDER BY queue_name;

-- Cleanup
SELECT pgmq.disable_notify_insert('critical_alerts');
SELECT pgmq.disable_notify_insert('metrics');
SELECT pgmq.disable_notify_insert('audit_logs');
SELECT pgmq.drop_queue('critical_alerts');
SELECT pgmq.drop_queue('metrics');
SELECT pgmq.drop_queue('audit_logs');

-- ========================================================================
-- Example 5: Monitoring Notification Activity
-- ========================================================================
-- Check when queues were last notified
-- ========================================================================

-- Setup: Create and configure queues
SELECT pgmq.create('orders');
SELECT pgmq.enable_notify_insert('orders', 2000);  -- Max 1 notification per 2 seconds

-- Send some messages
SELECT pgmq.send('orders', '{"order_id": 1, "status": "pending"}'::jsonb);

-- Check when last notification was sent
SELECT queue_name,
       throttle_interval_ms,
       last_notified_at,
       EXTRACT(EPOCH FROM (clock_timestamp() - last_notified_at)) * 1000 as ms_since_last_notify
FROM pgmq.notify_insert_throttle
WHERE queue_name = 'orders';

-- Send more messages and observe throttling behavior
SELECT pgmq.send('orders', '{"order_id": 2, "status": "pending"}'::jsonb);  -- Throttled (no new notification)
SELECT queue_name, last_notified_at FROM pgmq.notify_insert_throttle WHERE queue_name = 'orders';
-- Notice: last_notified_at hasn't changed (notification was throttled)

-- Wait for throttle interval to elapse
SELECT pg_sleep(2.1);
SELECT pgmq.send('orders', '{"order_id": 3, "status": "pending"}'::jsonb);  -- New notification sent
SELECT queue_name, last_notified_at FROM pgmq.notify_insert_throttle WHERE queue_name = 'orders';
-- Notice: last_notified_at has updated to current time

-- Cleanup
SELECT pgmq.disable_notify_insert('orders');
SELECT pgmq.drop_queue('orders');
