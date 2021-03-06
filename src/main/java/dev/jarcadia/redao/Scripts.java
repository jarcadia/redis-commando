package dev.jarcadia.redao;


class Scripts {
	
	protected static String DAO_TOUCH = """
        --Keys types, type, path, changeChannelKey
        --Args score
        local v = redis.call('hincrby', KEYS[3], 'v', 1);
        if (v == 1) then
            redis.call('sadd', KEYS[1], KEYS[2]);
            local id = string.sub(KEYS[3], string.len(KEYS[2]) + 2);
            redis.call('zadd', KEYS[2], ARGV[1], id);
            redis.call('publish', KEYS[4], '{"' .. id .. '":{"v":' .. v .. '}}');
        end
        return v;
    """;
        
    protected static String DAO_CHECKED_DELETE = """
    	--Keys types, type, path, changeChannelKey
		local removed = redis.call('del', KEYS[3]);
		if (removed == 1) then 
			-- TODO REMOVE from RC if empty
			
            local id = string.sub(KEYS[3], string.len(KEYS[2]) + 2);
			redis.call('zrem', KEYS[2], id);
			redis.call('publish', KEYS[4], '{"' .. id .. '":null}')
		end
		return removed;
    """;
    
    protected static String DAO_SET = """
        --Keys types, type, path, changeChannelKey
        --Args score field value [field value...] 
        local changed = false;
        local publish = false;
        local update = {};
        local changes = {};
        for i=2,#ARGV,2 do
            local prev = redis.call('hget', KEYS[3], ARGV[i]);
            if (prev ~= ARGV[i+1]) then
                redis.call('hset', KEYS[3], ARGV[i], ARGV[i+1]);
                changed = true;
                table.insert(changes, ARGV[i]);
                table.insert(changes, prev);
                table.insert(changes, ARGV[i+1]);
                if (string.sub(ARGV[i], 1, 1) ~= '_') then
                    update[ARGV[i]] = cjson.decode(ARGV[i+1])
                	publish = true;
                end
            end
        end

        if (changed) then
    		-- Extract ID from keys
            local id = string.sub(KEYS[3], string.len(KEYS[2]) + 2);
            -- Bump version 
            local ver = redis.call('hincrby', KEYS[3], 'v', 1);
            -- Add version as first element in response
            table.insert(changes, 1, tostring(ver));
            if (ver == 1) then
                -- If this is version 1, add to zset
                redis.call('zadd', KEYS[2], ARGV[1], id);
                -- Add to RC set
				redis.call('sadd', KEYS[1], KEYS[2]);
                -- Always publish on insert (even if only internal fields were changed - they won't be in the update)
                publish = true;
            end
            if (publish) then
                update['v'] = ver;
                redis.call('publish', KEYS[4], '{"' .. id .. '":' .. cjson.encode(update) .. '}');
            end
        end
        return changes
    """;
    
    protected static String DAO_CLEAR_FIELD = """
    	--Keys setKey, hashKey, changeChannelKey
        --Args [fields ...]
        local changed = false;
        local cleared = {};
        local update = ''
        for i=1,#ARGV do
            local prev = redis.call('hget', KEYS[2], ARGV[i]);
            if (prev) then
                redis.call('hdel', KEYS[2], ARGV[i]);
                changed = true;
                table.insert(cleared, ARGV[i]);
                table.insert(cleared, prev);
                update = update .. ',"' .. ARGV[i] .. '":null'
            end
        end
        if (changed) then
            local id = string.sub(KEYS[2], string.len(KEYS[1]) + 2);
            local ver = redis.call('hincrby', KEYS[2], 'v', 1);
            table.insert(cleared, 1, tostring(ver));
            redis.call('publish', KEYS[3], '{"' .. id .. '":{"v":'.. ver .. update .. '}}');
        end
        return cleared;
    """;

    protected static String MERGE_INTO_SET_IF_DISTINCT = """
        redis.call('sadd', KEYS[2], unpack(ARGV));
        local inter = redis.call('sinter', KEYS[1], KEYS[2])
        if (#inter == 0) then
            redis.call('sadd', KEYS[1], unpack(ARGV));
        end
        redis.call('del', KEYS[2]);
        return inter;
    """;
}
