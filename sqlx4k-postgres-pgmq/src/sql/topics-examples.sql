-- ========================================================================
-- Usage Examples
-- ========================================================================

-- ========================================================================
-- Example 1: Understanding Wildcards - Difference between * and #
-- ========================================================================
-- * (star)  = matches exactly ONE segment
-- # (hash)  = matches ZERO or MORE segments
-- ========================================================================

-- Setup: Create queues
SELECT pgmq.create('queue_star');
SELECT pgmq.create('queue_hash');

-- Bind patterns to demonstrate the difference
SELECT pgmq.bind_topic('logs.*', 'queue_star');      -- Matches ONE segment after 'logs.'
SELECT pgmq.bind_topic('logs.#', 'queue_hash');      -- Matches ZERO or MORE segments after 'logs.'

-- Test Case 1: "logs" - no dot, no segments
SELECT pgmq.send_topic('logs', '{"msg": "test1"}'::jsonb, NULL, 0);
-- Returns: 0 (neither matches - both patterns require a dot after 'logs')

-- Test Case 2: "logs.error" - one segment after 'logs.'
SELECT pgmq.send_topic('logs.error', '{"msg": "test2"}'::jsonb, NULL, 0);
-- Returns: 2 (both queues match - * matches one, # matches one)

-- Test Case 3: "logs.error.fatal" - two segments after 'logs.'
SELECT pgmq.send_topic('logs.error.fatal', '{"msg": "test3"}'::jsonb, NULL, 0);
-- Returns: 1 (only queue_hash matches - # matches multiple segments, * does not)

-- Test Case 4: "logs.error.fatal.critical" - three segments after 'logs.'
SELECT pgmq.send_topic('logs.error.fatal.critical', '{"msg": "test4"}'::jsonb, NULL, 0);
-- Returns: 1 (only queue_hash matches - # matches any number of segments)

-- Cleanup
SELECT pgmq.unbind_topic('logs.*', 'queue_star');
SELECT pgmq.unbind_topic('logs.#', 'queue_hash');
SELECT pgmq.drop_queue('queue_star');
SELECT pgmq.drop_queue('queue_hash');

-- ========================================================================
-- Example 2: Topic-based Routing (RabbitMQ-style)
-- ========================================================================
-- Route messages to different queues based on routing key patterns
-- ========================================================================

-- Setup: Create queues for different log levels
SELECT pgmq.create('logs_all');       -- Receives all logs
SELECT pgmq.create('logs_error');     -- Receives only error logs
SELECT pgmq.create('logs_critical');  -- Receives only critical logs

-- Bind patterns
SELECT pgmq.bind_topic('logs.#', 'logs_all');              -- Match all logs
SELECT pgmq.bind_topic('logs.*.error', 'logs_error');      -- Match errors from any service
SELECT pgmq.bind_topic('logs.*.critical', 'logs_critical'); -- Match critical from any service

-- Send messages with different routing keys
SELECT pgmq.send_topic('logs.api.info', '{"message": "API started"}'::jsonb, NULL, 0);
-- Returns: 1 (only logs_all receives it)

SELECT pgmq.send_topic('logs.api.error', '{"message": "API error"}'::jsonb, NULL, 0);
-- Returns: 2 (logs_all and logs_error receive it)

SELECT pgmq.send_topic('logs.db.critical', '{"message": "DB failure"}'::jsonb, NULL, 0);
-- Returns: 2 (logs_all and logs_critical receive it)

-- Cleanup
SELECT pgmq.unbind_topic('logs.#', 'logs_all');
SELECT pgmq.unbind_topic('logs.*.error', 'logs_error');
SELECT pgmq.unbind_topic('logs.*.critical', 'logs_critical');
SELECT pgmq.drop_queue('logs_all');
SELECT pgmq.drop_queue('logs_error');
SELECT pgmq.drop_queue('logs_critical');

-- ========================================================================
-- Example 3: Fanout Pattern (Broadcast)
-- ========================================================================
-- Send every message to ALL subscribed queues regardless of routing key
-- ========================================================================

-- Setup: Create queues for different consumers
SELECT pgmq.create('notifications');
SELECT pgmq.create('analytics');
SELECT pgmq.create('audit');

-- Bind all queues to '#' pattern (matches everything)
SELECT pgmq.bind_topic('#', 'notifications');
SELECT pgmq.bind_topic('#', 'analytics');
SELECT pgmq.bind_topic('#', 'audit');

-- Send any message - delivered to ALL queues
SELECT pgmq.send_topic('user.signup', '{"user_id": 123}'::jsonb, NULL, 0);
-- Returns: 3 (broadcast to all 3 queues)

SELECT pgmq.send_topic('order.created', '{"order_id": 456}'::jsonb, NULL, 0);
-- Returns: 3 (broadcast to all 3 queues)

-- Cleanup
SELECT pgmq.unbind_topic('#', 'notifications');
SELECT pgmq.unbind_topic('#', 'analytics');
SELECT pgmq.unbind_topic('#', 'audit');
SELECT pgmq.drop_queue('notifications');
SELECT pgmq.drop_queue('analytics');
SELECT pgmq.drop_queue('audit');

-- ========================================================================
-- Example 4: Direct Routing (Exact Match)
-- ========================================================================
-- Route messages to specific queues using exact pattern matches
-- ========================================================================

-- Setup: Create specialized queues
SELECT pgmq.create('user_events');
SELECT pgmq.create('order_events');

-- Bind exact patterns (no wildcards)
SELECT pgmq.bind_topic('user.created', 'user_events');
SELECT pgmq.bind_topic('user.updated', 'user_events');
SELECT pgmq.bind_topic('user.deleted', 'user_events');
SELECT pgmq.bind_topic('order.placed', 'order_events');
SELECT pgmq.bind_topic('order.shipped', 'order_events');

-- Send messages with exact routing keys
SELECT pgmq.send_topic('user.created', '{"user_id": 1}'::jsonb, NULL, 0);
-- Returns: 1 (only user_events)

SELECT pgmq.send_topic('order.placed', '{"order_id": 100}'::jsonb, NULL, 0);
-- Returns: 1 (only order_events)

SELECT pgmq.send_topic('user.login', '{"user_id": 1}'::jsonb, NULL, 0);
-- Returns: 0 (no matching pattern)

-- Cleanup
SELECT pgmq.unbind_topic('user.created', 'user_events');
SELECT pgmq.unbind_topic('user.updated', 'user_events');
SELECT pgmq.unbind_topic('user.deleted', 'user_events');
SELECT pgmq.unbind_topic('order.placed', 'order_events');
SELECT pgmq.unbind_topic('order.shipped', 'order_events');
SELECT pgmq.drop_queue('user_events');
SELECT pgmq.drop_queue('order_events');

-- ========================================================================
-- Example 5: Dry-Run Testing (Debug Routing)
-- ========================================================================
-- Test routing keys before sending to understand which queues will match
-- Useful for debugging pattern configurations
-- ========================================================================

-- Setup: Create test queues and bindings
SELECT pgmq.create('logs_all');
SELECT pgmq.create('logs_error');
SELECT pgmq.create('logs_critical');

SELECT pgmq.bind_topic('logs.#', 'logs_all');
SELECT pgmq.bind_topic('logs.*.error', 'logs_error');
SELECT pgmq.bind_topic('logs.*.critical', 'logs_critical');

-- Test which queues would receive messages (without actually sending)
SELECT * FROM pgmq.test_routing('logs.api.error');
-- Returns:
--   pattern         | queue_name   | compiled_regex
--   ----------------+--------------+----------------------
--   logs.#          | logs_all     | ^logs\..*$
--   logs.*.error    | logs_error   | ^logs\.[^.]+\.error$

-- Test a routing key that doesn't match error patterns
SELECT * FROM pgmq.test_routing('logs.api.info');
-- Returns:
--   pattern         | queue_name   | compiled_regex
--   ----------------+--------------+----------------------
--   logs.#          | logs_all     | ^logs\..*$
-- (Only logs_all matches - error and critical patterns don't match)

-- Test a routing key with no matches
SELECT * FROM pgmq.test_routing('metrics.cpu.usage');
-- Returns: (empty result set - no patterns match)

-- Use test_routing to debug complex patterns
SELECT * FROM pgmq.test_routing('logs.database.error');
-- Returns both logs.# and logs.*.error patterns

-- Count how many queues would receive a message
SELECT COUNT(*) as queue_count FROM pgmq.test_routing('logs.api.critical');
-- Returns: 2 (logs_all and logs_critical)

-- Cleanup
SELECT pgmq.unbind_topic('logs.#', 'logs_all');
SELECT pgmq.unbind_topic('logs.*.error', 'logs_error');
SELECT pgmq.unbind_topic('logs.*.critical', 'logs_critical');
SELECT pgmq.drop_queue('logs_all');
SELECT pgmq.drop_queue('logs_error');
SELECT pgmq.drop_queue('logs_critical');
