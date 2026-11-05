UPDATE messages AS m
SET content_json = CASE e.event_type
    WHEN 'system/message' THEN json_array(json_object('type', 'text', 'text', json_extract(e.data_json, '$.text')))
    WHEN 'user/message' THEN json_extract(e.data_json, '$.content')
    WHEN 'assistant/message' THEN json_extract(e.data_json, '$.content')
    WHEN 'tool/result' THEN json_array(json_object(
        'type', 'tool_result',
        'callId', json_extract(e.data_json, '$.callId'),
        'toolName', json_extract(e.data_json, '$.toolName'),
        'content', json_extract(e.data_json, '$.content'),
        'error', json(CASE WHEN json_extract(e.data_json, '$.isError') THEN 'true' ELSE 'false' END)))
    END
FROM message_nodes AS n JOIN session_events AS e ON e.session_id = n.session_id AND e.seq = n.source_seq
WHERE m.node_id = n.id
  AND e.event_type IN ('system/message', 'user/message', 'assistant/message', 'tool/result')
  AND EXISTS (SELECT 1 FROM json_each(m.content_json) AS block WHERE json_type(block.value, '$.type') IS NULL);

UPDATE messages AS m
SET tool_calls_json = (
    SELECT json_group_array(json(block.value)) FROM json_each(m.content_json) AS block
    WHERE json_extract(block.value, '$.type') = 'tool_call'
)
WHERE m.tool_calls_json IS NOT NULL
  AND EXISTS (SELECT 1 FROM json_each(m.tool_calls_json) AS block WHERE json_type(block.value, '$.type') IS NULL);
