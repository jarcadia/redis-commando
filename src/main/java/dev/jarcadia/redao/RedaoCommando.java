package dev.jarcadia.redao;

import java.io.Closeable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import dev.jarcadia.redao.exception.RedisCommandoException;
import dev.jarcadia.redao.callbacks.DaoDeletedCallback;
import dev.jarcadia.redao.callbacks.DaoInsertedCallback;
import dev.jarcadia.redao.callbacks.DaoValueModifiedCallback;
import dev.jarcadia.redao.proxy.Proxy;
import io.lettuce.core.RedisCommandExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

public class RedaoCommando implements Closeable {
    
    private final Logger logger = LoggerFactory.getLogger(RedaoCommando.class);

    private final RedisClient redis;
    private final ObjectMapper objectMapper;
    private final ValueFormatter formatter;
    private final ProxyMetadataFactory proxyMetadataFactory;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final Map<String, String> scriptCache;
    private final Map<String, Set<DaoInsertedCallback>> insertCallbackMap;
    private final Map<String, Set<DaoDeletedCallback>> deleteCallbackMap;
    private final Map<String, Map<String, Set<DaoValueModifiedCallback>>> changeCallbackMap;
    private final Map<Class<? extends Proxy>, ProxyMetadata> proxyMetadataMap;
    private final AtomicBoolean closing;
    private final List<java.util.concurrent.CountDownLatch> shutdownLatches;

    private ExternalUpdatePopper updatePopper;

    public static RedaoCommando create(RedisClient client) {
        return new RedaoCommando(client);
    }

    RedaoCommando(RedisClient redis) {
        this.redis = redis;
    	this.objectMapper = new RedaoObjectMapper(this);
    	this.formatter = new ValueFormatter(objectMapper);
    	this.proxyMetadataFactory = new ProxyMetadataFactory(objectMapper);
        this.connection = redis.connect();
        this.commands = connection.sync();
        this.scriptCache = new ConcurrentHashMap<>();
        this.insertCallbackMap = new ConcurrentHashMap<>();
        this.deleteCallbackMap = new ConcurrentHashMap<>();
        this.changeCallbackMap = new ConcurrentHashMap<>();
        this.proxyMetadataMap = new ConcurrentHashMap<>();
        this.closing = new AtomicBoolean(false);
        this.shutdownLatches = Collections.synchronizedList(new LinkedList<>());
    }

    public void enableExternalUpdateProcessing() {
        Procrastinator procrastinator = new Procrastinator();
        ExternalUpdatePopperRepository popperRepository = new ExternalUpdatePopperRepository(this.clone());
        this.updatePopper = new ExternalUpdatePopper(this, popperRepository, procrastinator);
        this.updatePopper.start();
        this.registerShutdownLatches(this.updatePopper.getDrainedLatch());
    }

    public RedaoCommando clone() {
        return new RedaoCommando(redis);
    }

    public RedisCommands<String, String> core() {
        return commands;
    }
    
    public ObjectMapper getObjectMapper() {
    	return this.objectMapper;
    }

    public Integer hgetset(String hashKey, String field, int value) {
        String old = this.hgetset(hashKey, field, String.valueOf(value));
        return old == null ? null : Integer.parseInt(old);
    }

    public String hgetset(String hashKey, String field, String value) {
        return commands.eval("local old = redis.call('hget',KEYS[1],ARGV[1]); redis.call('hset',KEYS[1],ARGV[1],ARGV[2]); return old;", ScriptOutputType.VALUE, new String[] {hashKey}, field, value);
    }

    public Set<String> mergeIntoSetIfDistinct(String setKey, Collection<String> values) {
        Set<String> result = new HashSet<>();
        String tempSetKey = UUID.randomUUID().toString();
        List<String> duplicates =  eval()
        		.cachedScript(Scripts.MERGE_INTO_SET_IF_DISTINCT)
        		.addKeys(setKey, tempSetKey)
        		.addArgs(values)
        		.returnMulti();
        result.addAll(duplicates);
        return result;
    }

    public Dao getDao(String type, String id) {
        return new Index(this, formatter, type).get(id);
    }
    
    public <T extends Proxy> T getProxy(String type, String id, Class<T> proxyClass) {
        Index set = new Index(this, formatter, type);
        return new ProxyIndex<T>(set, proxyClass).get(id);
    }
        
    public Index getPrimaryIndex(String type) {
        return new Index(this, formatter, type);
    }

    public <T extends Proxy> ProxyIndex<T> getPrimaryIndex(String type, Class<T> proxyClass) {
        Index set = new Index(this, formatter, type);
        return new ProxyIndex<T>(set, proxyClass);
    }

    public Eval eval() {
        return new Eval(this, this.formatter);
    }

    public CountDownLatch getCountDownLatch(String id) {
        return new CountDownLatch(this, formatter, id);
    }

    public Subscription subscribe(BiConsumer<String, String> handler) {
        return new Subscription(redis.connectPubSub(), formatter, handler);
    }

    public Subscription subscribe(String channel, BiConsumer<String, String> handler) {
        return new Subscription(redis.connectPubSub(), formatter, handler, channel);
    }

    protected <T> T executeScript(String script, ScriptOutputType outputType, String[] keys, String[] args) {
        String digest = scriptCache.computeIfAbsent(script, s -> commands.scriptLoad(s));
        try {
            return commands.evalsha(digest, outputType, keys, args);
        } catch (RedisNoScriptException ex) {
        	scriptCache.remove(script);
        	return executeScript(script, outputType, keys, args);
        } catch (RedisCommandExecutionException ex) {
            throw new RedisCommandoException("Error executing " + script, ex);
        }
    }

    @SuppressWarnings("unchecked")
	protected <T extends Proxy> T createObjectProxy(Dao object, Class<T> proxyClass) {
    	ProxyMetadata metadata = proxyMetadataMap.computeIfAbsent(proxyClass, pc -> proxyMetadataFactory.create(pc));
    	ProxyInvocationHandler handler = new ProxyInvocationHandler(object, metadata);
    	return (T) java.lang.reflect.Proxy.newProxyInstance(this.getClass().getClassLoader(), new Class[] {proxyClass}, handler);
    }

    public void registerObjectInsertCallback(String setKey, DaoInsertedCallback handler) {
        this.insertCallbackMap.computeIfAbsent(setKey, k -> ConcurrentHashMap.newKeySet()).add(handler);
    }

    public void registerObjectDeleteCallback(String setKey, DaoDeletedCallback handler) {
        this.deleteCallbackMap.computeIfAbsent(setKey, k -> ConcurrentHashMap.newKeySet()).add(handler);
    }

    public void registerFieldChangeCallback(String setKey, String fieldName, DaoValueModifiedCallback handler) {
        Map<String, Set<DaoValueModifiedCallback>> keyUpdateHandlers = changeCallbackMap
                .computeIfAbsent(setKey, k-> new ConcurrentHashMap<>());
        keyUpdateHandlers.computeIfAbsent(fieldName, k -> ConcurrentHashMap.newKeySet()).add(handler);
    }

    protected void invokeObjectInsertCallbacks(Dao dao) {
        Set<DaoInsertedCallback> insertCallbacks = insertCallbackMap.get(dao.getType());
        if (insertCallbacks != null) {
            for (DaoInsertedCallback callback : insertCallbacks) {
                callback.onInsert(dao);
            }
        }
    }

    protected void invokeChangeCallbacks(Modification result) {
        Set<DaoInsertedCallback> insertCallbacks = insertCallbackMap.get(result.getDao().getType());
        Map<String, Set<DaoValueModifiedCallback>> changeCallbacksForSet = changeCallbackMap.get(result.getDao().getType());
        
        if (insertCallbacks != null && result.isInsert()) {
            for (DaoInsertedCallback callback : insertCallbacks) {
                callback.onInsert(result.getDao());
            }
        }

        if (changeCallbacksForSet != null) {
            for (ModifiedValue changedValue : result.getChanges()) {
                Set<DaoValueModifiedCallback> changeCallbacksForField = changeCallbacksForSet.get(changedValue.getField());
                if (changeCallbacksForField != null) {
                    logger.trace("Invoking {} change callbacks for {}.{}", changeCallbacksForField.size(), result.getDao().getType(), changedValue.getField());
                    for (DaoValueModifiedCallback callback : changeCallbacksForField) {
                        callback.onChange(result.getDao(), changedValue.getField(), changedValue.getBefore(), changedValue.getAfter());
                    }
                }
                Set<DaoValueModifiedCallback> changeCallbacksForStar = changeCallbacksForSet.get("*");
                if (changeCallbacksForStar != null) {
                    logger.trace("Invoking {} change callbacks for {}.{}", changeCallbacksForStar.size(), result.getDao().getType(), changedValue.getField());
                    for (DaoValueModifiedCallback callback : changeCallbacksForStar) {
                        callback.onChange(result.getDao(), changedValue.getField(), changedValue.getBefore(), changedValue.getAfter());
                    }
                }
            }
        }
    }

    protected void invokeDeleteCallbacks(String setKey, String id) {
        Set<DaoDeletedCallback> deleteCallbacks = deleteCallbackMap.get(setKey);
        if (deleteCallbacks != null) {
            for (DaoDeletedCallback callback : deleteCallbacks) {
                callback.onDelete(setKey, id);
            }
        }
    }

    public void registerShutdownLatches(java.util.concurrent.CountDownLatch... latches) {
        for (java.util.concurrent.CountDownLatch latch : latches) {
            this.shutdownLatches.add(latch);
        }
    }

    @Override
    public void close() {
        if (closing.compareAndSet(false, true)) {

            // Close the external update popper if it has been starter
            if (updatePopper != null) {
                updatePopper.close();
            }

            for (java.util.concurrent.CountDownLatch blocker : shutdownLatches) {
                try {
                    blocker.await();
                } catch (InterruptedException e) {
                    new RuntimeException("Interrupted while waiting for RedisCommando shutdown latch").printStackTrace();
                }
            }
            connection.close();
        }
    }
} 