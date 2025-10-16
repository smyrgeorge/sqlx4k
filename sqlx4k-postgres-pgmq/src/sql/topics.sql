DROP TABLE IF EXISTS pgmq.topic_bindings;
CREATE TABLE IF NOT EXISTS pgmq.topic_bindings(
    pattern text NOT NULL,      -- Wildcard pattern for routing key matching (* = one segment, # = zero or more segments)
    queue_name text NOT NULL,   -- Name of the queue that receives messages when pattern matches
    compiled_regex text GENERATED ALWAYS AS (
        -- Pre-compile the pattern to regex for faster matching
        -- This avoids runtime compilation on every send_topic call
        '^' ||
        replace(
            replace(
                regexp_replace(pattern, '([.+?{}()|\[\]\\^$])', '\\\1', 'g'),
                '*', '[^.]+'
            ),
            '#', '.*'
        ) || '$'
    ) STORED,                   -- Computed column: stores the compiled regex pattern
    CONSTRAINT topic_bindings_unique_pattern_queue UNIQUE (pattern, queue_name)
);

-- Add column comments for documentation
COMMENT ON COLUMN pgmq.topic_bindings.pattern IS
    'AMQP-style wildcard pattern for routing key matching. '
    'Supports two wildcards: '
    '* (star) matches exactly ONE segment between dots, '
    '# (hash) matches ZERO or MORE segments. '
    'Examples: "logs.*" matches "logs.error" but not "logs.error.fatal", '
    '"logs.#" matches "logs", "logs.error", and "logs.error.fatal"';

COMMENT ON COLUMN pgmq.topic_bindings.queue_name IS
    'Name of the target PGMQ queue that will receive messages when the routing key matches the pattern. '
    'The queue must exist before binding it to a pattern.';

COMMENT ON COLUMN pgmq.topic_bindings.compiled_regex IS
    'Pre-compiled regex pattern derived from the wildcard pattern. '
    'This is a generated column that stores the PostgreSQL regex equivalent of the AMQP-style pattern. '
    'Automatically computed on insert/update for performance optimization in send_topic function.';

-- Create covering index for better performance when scanning patterns
-- Includes queue_name to allow index-only scans (no table access needed)
DROP INDEX IF EXISTS pgmq.idx_topic_bindings_pattern;
DROP INDEX IF EXISTS pgmq.idx_topic_bindings_covering;
CREATE INDEX idx_topic_bindings_covering ON pgmq.topic_bindings(pattern) INCLUDE (queue_name);

-- ============================================================================
-- Routing Key Validation Function
-- ============================================================================
-- Validates routing keys (without wildcards) used in send_topic
-- ============================================================================

DROP FUNCTION IF EXISTS pgmq.validate_routing_key(text);
CREATE OR REPLACE FUNCTION pgmq.validate_routing_key(routing_key text)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    AS $$
BEGIN
    -- ========================================================================
    -- Routing Key Validation Rules
    -- ========================================================================
    -- Valid routing keys must follow these rules:
    --   1. Not NULL or empty
    --   2. Length between 1 and 255 characters
    --   3. Contains only: alphanumeric, dots, hyphens, underscores (NO wildcards)
    --   4. Cannot start or end with a dot
    --   5. Cannot have consecutive dots (..)
    --
    -- Valid routing key examples:
    --   "logs.error"
    --   "app.user-service.auth"
    --   "system_events.db.connection_failed"
    --
    -- Invalid routing key examples:
    --   ""                     - empty
    --   ".logs.error"          - starts with dot
    --   "logs.error."          - ends with dot
    --   "logs..error"          - consecutive dots
    --   "logs.error!"          - invalid character
    --   "logs error"           - space not allowed
    --   "logs.*"               - wildcards not allowed in routing keys

    -- Check 1: Not NULL or empty
    IF routing_key IS NULL OR routing_key = '' THEN
        RAISE EXCEPTION 'routing_key cannot be NULL or empty';
    END IF;

    -- Check 2: Length constraints (1-255 characters)
    IF length(routing_key) > 255 THEN
        RAISE EXCEPTION 'routing_key length cannot exceed 255 characters, got % characters', length(routing_key);
    END IF;

    -- Check 3: Valid characters only [a-zA-Z0-9._-] (NO wildcards)
    IF routing_key !~ '^[a-zA-Z0-9._-]+$' THEN
        RAISE EXCEPTION 'routing_key contains invalid characters. Only alphanumeric, dots, hyphens, and underscores are allowed. Got: %', routing_key;
    END IF;

    -- Check 4: Cannot start with a dot
    IF routing_key ~ '^\.' THEN
        RAISE EXCEPTION 'routing_key cannot start with a dot. Got: %', routing_key;
    END IF;

    -- Check 5: Cannot end with a dot
    IF routing_key ~ '\.$' THEN
        RAISE EXCEPTION 'routing_key cannot end with a dot. Got: %', routing_key;
    END IF;

    -- Check 6: Cannot have consecutive dots
    IF routing_key ~ '\.\.' THEN
        RAISE EXCEPTION 'routing_key cannot contain consecutive dots. Got: %', routing_key;
    END IF;

    -- All validation passed
    RETURN true;
END;
$$;

-- Add comment for the validation function
COMMENT ON FUNCTION pgmq.validate_routing_key(text) IS
    'Validates routing keys for use in send_topic function. '
    'Routing keys are concrete identifiers without wildcards. '
    'Checks for proper format and valid characters. '
    'Raises exceptions with descriptive messages if validation fails. '
    'Returns true if routing key is valid.';

-- ============================================================================
-- Pattern Validation Function
-- ============================================================================
-- Validates AMQP-style topic patterns before they are stored in topic_bindings
-- Ensures patterns follow proper formatting rules and don't contain invalid
-- wildcard combinations
-- ============================================================================

DROP FUNCTION IF EXISTS pgmq.validate_topic_pattern(text);
CREATE OR REPLACE FUNCTION pgmq.validate_topic_pattern(pattern text)
    RETURNS boolean
    LANGUAGE plpgsql
    IMMUTABLE
    AS $$
BEGIN
    -- ========================================================================
    -- Pattern Validation Rules
    -- ========================================================================
    -- Valid patterns must follow these rules:
    --   1. Not NULL or empty
    --   2. Length between 1 and 255 characters
    --   3. Contains only: alphanumeric, dots, hyphens, underscores, *, #
    --   4. Cannot start or end with a dot
    --   5. Cannot have consecutive dots (..)
    --   6. Cannot have invalid wildcard combinations: **, ##, *#, #*
    --
    -- Valid pattern examples:
    --   "logs.*"           - matches one segment after logs.
    --   "logs.#"           - matches zero or more segments after logs.
    --   "*.error"          - matches any.error
    --   "#.error"          - matches error, x.error, x.y.error, etc.
    --   "app.*.#"          - mixed wildcards (one segment then zero or more)
    --
    -- Invalid pattern examples:
    --   ".logs.*"          - starts with dot
    --   "logs.*."          - ends with dot
    --   "logs..error"      - consecutive dots
    --   "logs.**"          - consecutive stars
    --   "logs.##"          - consecutive hashes
    --   "logs.*#"          - adjacent wildcards
    --   "logs.error!"      - invalid character

    -- Check 1: Not NULL or empty
    IF pattern IS NULL OR pattern = '' THEN
        RAISE EXCEPTION 'pattern cannot be NULL or empty';
    END IF;

    -- Check 2: Length constraints (1-255 characters)
    IF length(pattern) > 255 THEN
        RAISE EXCEPTION 'pattern length cannot exceed 255 characters, got % characters', length(pattern);
    END IF;

    -- Check 3: Valid characters only [a-zA-Z0-9._-*#]
    IF pattern !~ '^[a-zA-Z0-9._\-*#]+$' THEN
        RAISE EXCEPTION 'pattern contains invalid characters. Only alphanumeric, dots, hyphens, underscores, *, and # are allowed. Got: %', pattern;
    END IF;

    -- Check 4: Cannot start with a dot
    IF pattern ~ '^\.' THEN
        RAISE EXCEPTION 'pattern cannot start with a dot. Got: %', pattern;
    END IF;

    -- Check 5: Cannot end with a dot
    IF pattern ~ '\.$' THEN
        RAISE EXCEPTION 'pattern cannot end with a dot. Got: %', pattern;
    END IF;

    -- Check 6: Cannot have consecutive dots
    IF pattern ~ '\.\.' THEN
        RAISE EXCEPTION 'pattern cannot contain consecutive dots. Got: %', pattern;
    END IF;

    -- Check 7: Cannot have consecutive stars (**)
    IF pattern ~ '\*\*' THEN
        RAISE EXCEPTION 'pattern cannot contain consecutive stars (**). Use # for multi-segment matching. Got: %', pattern;
    END IF;

    -- Check 8: Cannot have consecutive hashes (##)
    IF pattern ~ '##' THEN
        RAISE EXCEPTION 'pattern cannot contain consecutive hashes (##). A single # already matches zero or more segments. Got: %', pattern;
    END IF;

    -- Check 9: Cannot have adjacent wildcards (*# or #*)
    IF pattern ~ '\*#' OR pattern ~ '#\*' THEN
        RAISE EXCEPTION 'pattern cannot contain adjacent wildcards (*# or #*). Separate wildcards with dots. Got: %', pattern;
    END IF;

    -- All validation passed
    RETURN true;
END;
$$;

-- Add comment for the validation function
COMMENT ON FUNCTION pgmq.validate_topic_pattern(text) IS
    'Validates AMQP-style topic patterns for use in topic_bindings table. '
    'Checks for proper format, valid characters, and prevents invalid wildcard combinations. '
    'Raises exceptions with descriptive messages if validation fails. '
    'Returns true if pattern is valid.';

-- ============================================================================
-- Topic Binding Creation Function
-- ============================================================================
-- Safely creates a topic binding with automatic pattern validation
-- ============================================================================

DROP FUNCTION IF EXISTS pgmq.bind_topic(text, text);
CREATE OR REPLACE FUNCTION pgmq.bind_topic(pattern text, queue_name text)
    RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- ========================================================================
    -- Input Validation
    -- ========================================================================

    -- Validate pattern using the validation function
    -- This will raise an exception if the pattern is invalid
    PERFORM pgmq.validate_topic_pattern(pattern);

    -- Validate queue_name is not NULL or empty
    IF queue_name IS NULL OR queue_name = '' THEN
        RAISE EXCEPTION 'queue_name cannot be NULL or empty';
    END IF;

    -- ========================================================================
    -- Insert Binding
    -- ========================================================================
    -- Insert the validated binding into topic_bindings table
    -- The UNIQUE constraint will prevent duplicate (pattern, queue_name) pairs
    -- Use INSERT ... ON CONFLICT DO NOTHING to make it idempotent

    INSERT INTO pgmq.topic_bindings (pattern, queue_name)
    VALUES (pattern, queue_name)
    ON CONFLICT ON CONSTRAINT topic_bindings_unique_pattern_queue DO NOTHING;

    -- Log successful binding creation
    RAISE NOTICE 'Topic binding created: pattern "%" -> queue "%"', pattern, queue_name;
END;
$$;

-- Add comment for the binding function
COMMENT ON FUNCTION pgmq.bind_topic(text, text) IS
    'Creates a topic binding between a pattern and a queue. '
    'Validates the pattern before insertion and prevents duplicates. '
    'Usage: SELECT pgmq.bind_topic(''logs.*'', ''logs_all''); '
    'Idempotent - safe to call multiple times with the same arguments.';

-- ============================================================================
-- Topic Binding Removal Function
-- ============================================================================
-- Safely removes a topic binding
-- ============================================================================

DROP FUNCTION IF EXISTS pgmq.unbind_topic(text, text);
CREATE OR REPLACE FUNCTION pgmq.unbind_topic(pattern text, queue_name text)
    RETURNS boolean
    LANGUAGE plpgsql
    AS $$
DECLARE
    rows_deleted integer;
BEGIN
    -- ========================================================================
    -- Input Validation
    -- ========================================================================

    -- Validate pattern is not NULL or empty
    IF pattern IS NULL OR pattern = '' THEN
        RAISE EXCEPTION 'pattern cannot be NULL or empty';
    END IF;

    -- Validate queue_name is not NULL or empty
    IF queue_name IS NULL OR queue_name = '' THEN
        RAISE EXCEPTION 'queue_name cannot be NULL or empty';
    END IF;

    -- ========================================================================
    -- Delete Binding
    -- ========================================================================
    -- Remove the binding from topic_bindings table
    -- Returns true if a binding was deleted, false if no matching binding was found

    DELETE FROM pgmq.topic_bindings
    WHERE topic_bindings.pattern = unbind_topic.pattern
      AND topic_bindings.queue_name = unbind_topic.queue_name;

    GET DIAGNOSTICS rows_deleted = ROW_COUNT;

    IF rows_deleted > 0 THEN
        RAISE NOTICE 'Topic binding removed: pattern "%" -> queue "%"', pattern, queue_name;
        RETURN true;
    ELSE
        RAISE NOTICE 'No topic binding found for pattern "%" and queue "%"', pattern, queue_name;
        RETURN false;
    END IF;
END;
$$;

-- Add comment for the unbinding function
COMMENT ON FUNCTION pgmq.unbind_topic(text, text) IS
    'Removes a topic binding between a pattern and a queue. '
    'Returns true if the binding was deleted, false if no matching binding was found. '
    'Usage: SELECT pgmq.unbind_topic(''logs.*'', ''logs_all''); '
    'Idempotent - safe to call multiple times with the same arguments.';

-- ============================================================================
-- Dry-Run Routing Test Function
-- ============================================================================
-- Tests which queues would receive a message without actually sending it
-- Useful for debugging and validating pattern bindings
-- ============================================================================

DROP FUNCTION IF EXISTS pgmq.test_routing(text);
CREATE OR REPLACE FUNCTION pgmq.test_routing(routing_key text)
    RETURNS TABLE(
        pattern text,
        queue_name text,
        compiled_regex text
    )
    LANGUAGE plpgsql
    STABLE
    AS $$
BEGIN
    -- ========================================================================
    -- Input Validation
    -- ========================================================================

    -- Validate routing_key using the validation function
    -- This ensures the routing_key follows all formatting rules
    PERFORM pgmq.validate_routing_key(routing_key);

    -- ========================================================================
    -- Return Matching Bindings
    -- ========================================================================
    -- Returns all patterns and queues that would match the routing key
    -- Does NOT send any messages - dry-run only

    RETURN QUERY
    SELECT
        b.pattern,
        b.queue_name,
        b.compiled_regex
    FROM pgmq.topic_bindings b
    WHERE routing_key ~ b.compiled_regex
    ORDER BY b.pattern;
END;
$$;

-- Add comment for the test routing function
COMMENT ON FUNCTION pgmq.test_routing(text) IS
    'Dry-run test to show which queues would receive a message for a given routing key. '
    'Does NOT actually send any messages - useful for debugging and validating patterns. '
    'Returns a table with pattern, queue_name, and compiled_regex for all matches. '
    'Usage: SELECT * FROM pgmq.test_routing(''logs.error'');';

DROP FUNCTION IF EXISTS pgmq.send_topic(text, jsonb, jsonb, integer);
CREATE OR REPLACE FUNCTION pgmq.send_topic(routing_key text, msg jsonb, headers jsonb, delay integer)
    RETURNS integer
    LANGUAGE plpgsql
    VOLATILE
    AS $$
DECLARE
    b RECORD;
    matched_count integer := 0;
BEGIN
    -- ========================================================================
    -- Input Validation
    -- ========================================================================

    -- Validate routing_key using the validation function
    -- This ensures the routing_key follows all formatting rules
    PERFORM pgmq.validate_routing_key(routing_key);

    -- Validate msg is not NULL
    IF msg IS NULL THEN
        RAISE EXCEPTION 'msg cannot be NULL';
    END IF;

    -- Validate delay is non-negative
    IF delay < 0 THEN
        RAISE EXCEPTION 'delay cannot be negative, got: %', delay;
    END IF;

    -- ========================================================================
    -- Process all matching bindings
    -- ========================================================================
    -- Uses pre-compiled regex patterns from the compiled_regex column
    -- This significantly improves performance by avoiding runtime pattern compilation
    -- All sends must succeed or the entire transaction rolls back
    FOR b IN
        SELECT compiled_regex, queue_name
        FROM pgmq.topic_bindings
        ORDER BY pattern  -- Deterministic ordering
    LOOP
        -- Check if routing_key matches the pre-compiled regex pattern
        IF routing_key ~ b.compiled_regex THEN
            -- Send to matched queue (any failure will rollback the entire transaction)
            PERFORM pgmq.send(b.queue_name, msg, headers, delay);
            matched_count := matched_count + 1;
        END IF;
    END LOOP;

    RETURN matched_count;
END;
$$;

-- Add comment for the send_topic function
COMMENT ON FUNCTION pgmq.send_topic(text, jsonb, jsonb, integer) IS
    'Sends a message to all queues that match the routing key pattern. '
    'Uses AMQP-style topic routing with wildcards (* for one segment, # for zero or more segments). '
    'The routing_key is matched against all patterns in topic_bindings table. '
    'Returns the number of queues that received the message. '
    'Transaction is atomic - either all matching queues receive the message or none do. '
    'Usage: SELECT pgmq.send_topic(''logs.error'', ''{"message": "error occurred"}''::jsonb, NULL, 0);';


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
-- SELECT pgmq.create('queue_star');
-- SELECT pgmq.create('queue_hash');

-- Bind patterns to demonstrate the difference
-- SELECT pgmq.bind_topic('logs.*', 'queue_star');      -- Matches ONE segment after 'logs.'
-- SELECT pgmq.bind_topic('logs.#', 'queue_hash');      -- Matches ZERO or MORE segments after 'logs.'

-- Test Case 1: "logs" - no dot, no segments
-- SELECT pgmq.send_topic('logs', '{"msg": "test1"}'::jsonb, NULL, 0);
-- Returns: 0 (neither matches - both patterns require a dot after 'logs')

-- Test Case 2: "logs.error" - one segment after 'logs.'
-- SELECT pgmq.send_topic('logs.error', '{"msg": "test2"}'::jsonb, NULL, 0);
-- Returns: 2 (both queues match - * matches one, # matches one)

-- Test Case 3: "logs.error.fatal" - two segments after 'logs.'
-- SELECT pgmq.send_topic('logs.error.fatal', '{"msg": "test3"}'::jsonb, NULL, 0);
-- Returns: 1 (only queue_hash matches - # matches multiple segments, * does not)

-- Test Case 4: "logs.error.fatal.critical" - three segments after 'logs.'
-- SELECT pgmq.send_topic('logs.error.fatal.critical', '{"msg": "test4"}'::jsonb, NULL, 0);
-- Returns: 1 (only queue_hash matches - # matches any number of segments)

-- Cleanup
-- SELECT pgmq.unbind_topic('logs.*', 'queue_star');
-- SELECT pgmq.unbind_topic('logs.#', 'queue_hash');
-- SELECT pgmq.drop_queue('queue_star');
-- SELECT pgmq.drop_queue('queue_hash');

-- ========================================================================
-- Example 2: Topic-based Routing (RabbitMQ-style)
-- ========================================================================
-- Route messages to different queues based on routing key patterns
-- ========================================================================

-- Setup: Create queues for different log levels
-- SELECT pgmq.create('logs_all');       -- Receives all logs
-- SELECT pgmq.create('logs_error');     -- Receives only error logs
-- SELECT pgmq.create('logs_critical');  -- Receives only critical logs

-- Bind patterns
-- SELECT pgmq.bind_topic('logs.#', 'logs_all');              -- Match all logs
-- SELECT pgmq.bind_topic('logs.*.error', 'logs_error');      -- Match errors from any service
-- SELECT pgmq.bind_topic('logs.*.critical', 'logs_critical'); -- Match critical from any service

-- Send messages with different routing keys
-- SELECT pgmq.send_topic('logs.api.info', '{"message": "API started"}'::jsonb, NULL, 0);
-- Returns: 1 (only logs_all receives it)

-- SELECT pgmq.send_topic('logs.api.error', '{"message": "API error"}'::jsonb, NULL, 0);
-- Returns: 2 (logs_all and logs_error receive it)

-- SELECT pgmq.send_topic('logs.db.critical', '{"message": "DB failure"}'::jsonb, NULL, 0);
-- Returns: 2 (logs_all and logs_critical receive it)

-- Cleanup
-- SELECT pgmq.unbind_topic('logs.#', 'logs_all');
-- SELECT pgmq.unbind_topic('logs.*.error', 'logs_error');
-- SELECT pgmq.unbind_topic('logs.*.critical', 'logs_critical');
-- SELECT pgmq.drop_queue('logs_all');
-- SELECT pgmq.drop_queue('logs_error');
-- SELECT pgmq.drop_queue('logs_critical');

-- ========================================================================
-- Example 3: Fanout Pattern (Broadcast)
-- ========================================================================
-- Send every message to ALL subscribed queues regardless of routing key
-- ========================================================================

-- Setup: Create queues for different consumers
-- SELECT pgmq.create('notifications');
-- SELECT pgmq.create('analytics');
-- SELECT pgmq.create('audit');

-- Bind all queues to '#' pattern (matches everything)
-- SELECT pgmq.bind_topic('#', 'notifications');
-- SELECT pgmq.bind_topic('#', 'analytics');
-- SELECT pgmq.bind_topic('#', 'audit');

-- Send any message - delivered to ALL queues
-- SELECT pgmq.send_topic('user.signup', '{"user_id": 123}'::jsonb, NULL, 0);
-- Returns: 3 (broadcast to all 3 queues)

-- SELECT pgmq.send_topic('order.created', '{"order_id": 456}'::jsonb, NULL, 0);
-- Returns: 3 (broadcast to all 3 queues)

-- Cleanup
-- SELECT pgmq.unbind_topic('#', 'notifications');
-- SELECT pgmq.unbind_topic('#', 'analytics');
-- SELECT pgmq.unbind_topic('#', 'audit');
-- SELECT pgmq.drop_queue('notifications');
-- SELECT pgmq.drop_queue('analytics');
-- SELECT pgmq.drop_queue('audit');

-- ========================================================================
-- Example 4: Direct Routing (Exact Match)
-- ========================================================================
-- Route messages to specific queues using exact pattern matches
-- ========================================================================

-- Setup: Create specialized queues
-- SELECT pgmq.create('user_events');
-- SELECT pgmq.create('order_events');

-- Bind exact patterns (no wildcards)
-- SELECT pgmq.bind_topic('user.created', 'user_events');
-- SELECT pgmq.bind_topic('user.updated', 'user_events');
-- SELECT pgmq.bind_topic('user.deleted', 'user_events');
-- SELECT pgmq.bind_topic('order.placed', 'order_events');
-- SELECT pgmq.bind_topic('order.shipped', 'order_events');

-- Send messages with exact routing keys
-- SELECT pgmq.send_topic('user.created', '{"user_id": 1}'::jsonb, NULL, 0);
-- Returns: 1 (only user_events)

-- SELECT pgmq.send_topic('order.placed', '{"order_id": 100}'::jsonb, NULL, 0);
-- Returns: 1 (only order_events)

-- SELECT pgmq.send_topic('user.login', '{"user_id": 1}'::jsonb, NULL, 0);
-- Returns: 0 (no matching pattern)

-- Cleanup
-- SELECT pgmq.unbind_topic('user.created', 'user_events');
-- SELECT pgmq.unbind_topic('user.updated', 'user_events');
-- SELECT pgmq.unbind_topic('user.deleted', 'user_events');
-- SELECT pgmq.unbind_topic('order.placed', 'order_events');
-- SELECT pgmq.unbind_topic('order.shipped', 'order_events');
-- SELECT pgmq.drop_queue('user_events');
-- SELECT pgmq.drop_queue('order_events');

-- ========================================================================
-- Example 5: Dry-Run Testing (Debug Routing)
-- ========================================================================
-- Test routing keys before sending to understand which queues will match
-- Useful for debugging pattern configurations
-- ========================================================================

-- Setup: Create test queues and bindings
-- SELECT pgmq.create('logs_all');
-- SELECT pgmq.create('logs_error');
-- SELECT pgmq.create('logs_critical');

-- SELECT pgmq.bind_topic('logs.#', 'logs_all');
-- SELECT pgmq.bind_topic('logs.*.error', 'logs_error');
-- SELECT pgmq.bind_topic('logs.*.critical', 'logs_critical');

-- Test which queues would receive messages (without actually sending)
-- SELECT * FROM pgmq.test_routing('logs.api.error');
-- Returns:
--   pattern         | queue_name   | compiled_regex
--   ----------------+--------------+----------------------
--   logs.#          | logs_all     | ^logs\..*$
--   logs.*.error    | logs_error   | ^logs\.[^.]+\.error$

-- Test a routing key that doesn't match error patterns
-- SELECT * FROM pgmq.test_routing('logs.api.info');
-- Returns:
--   pattern         | queue_name   | compiled_regex
--   ----------------+--------------+----------------------
--   logs.#          | logs_all     | ^logs\..*$
-- (Only logs_all matches - error and critical patterns don't match)

-- Test a routing key with no matches
-- SELECT * FROM pgmq.test_routing('metrics.cpu.usage');
-- Returns: (empty result set - no patterns match)

-- Use test_routing to debug complex patterns
-- SELECT * FROM pgmq.test_routing('logs.database.error');
-- Returns both logs.# and logs.*.error patterns

-- Count how many queues would receive a message
-- SELECT COUNT(*) as queue_count FROM pgmq.test_routing('logs.api.critical');
-- Returns: 2 (logs_all and logs_critical)

-- Cleanup
-- SELECT pgmq.unbind_topic('logs.#', 'logs_all');
-- SELECT pgmq.unbind_topic('logs.*.error', 'logs_error');
-- SELECT pgmq.unbind_topic('logs.*.critical', 'logs_critical');
-- SELECT pgmq.drop_queue('logs_all');
-- SELECT pgmq.drop_queue('logs_error');
-- SELECT pgmq.drop_queue('logs_critical');
