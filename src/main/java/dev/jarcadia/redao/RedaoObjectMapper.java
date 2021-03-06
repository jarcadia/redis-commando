package dev.jarcadia.redao;

import java.io.IOException;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import dev.jarcadia.redao.proxy.Proxy;

public class RedaoObjectMapper extends ObjectMapper {
	
	public RedaoObjectMapper(RedaoCommando rcommando) {
		this.registerModules(rcProxyModule(), optionalModule(), rcObjectModule(rcommando), rcMapModule(rcommando));
	}
	
	public <T extends Proxy> void registerProxyClass(Class<T> proxyClass) {
		SimpleModule module = new SimpleModule();
		JsonDeserializer<T> deserializer = new StdDeserializer<T>(proxyClass) {
			@Override
			public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
				Dao object = p.readValueAs(Dao.class);
				return object.as(proxyClass);
			}
		};
		module.addDeserializer(proxyClass, deserializer); 
		this.registerModule(module);
	}
	
	private SimpleModule optionalModule() {
		SimpleModule module = new SimpleModule();
		module.addSerializer(new OptionalSerializer());
		module.addDeserializer(Optional.class, new OptionalDeserializer());
		return module;
		
	}
	private SimpleModule rcObjectModule(RedaoCommando rcommando) {
		SimpleModule rcObjModule = new SimpleModule();
		rcObjModule.addSerializer(new DaoSerializer());
		rcObjModule.addDeserializer(Dao.class, new DaoDeserializer(rcommando));
		return rcObjModule;
	}
	
	private SimpleModule rcMapModule(RedaoCommando rcommando) {
		SimpleModule module = new SimpleModule();
		module.addSerializer(new IndexSerializer());
		module.addDeserializer(Index.class, new IndexDeserializer(rcommando));
		return module;
	}
	
	private SimpleModule rcProxyModule() {
		SimpleModule module = new SimpleModule();
		module.addSerializer(new DaoProxySerializer());
		return module;
	}
	
	private class DaoProxySerializer extends StdSerializer<Proxy> {
		
		 public DaoProxySerializer() {
            super(Proxy.class);
        }
     
        @Override
        public void serialize(Proxy value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        	gen.writeString(value.getType() + "/" + value.getId());
        }
	}
	

	private class DaoSerializer extends StdSerializer<Dao> {
		
	    public DaoSerializer() {
	        super(Dao.class);
	    }
	 
		@Override
		public void serialize(Dao value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			gen.writeString(value.getType() + "/" + value.getId());
		}
	}

	
	private class DaoDeserializer extends StdDeserializer<Dao> {
		
		private RedaoCommando rcommando;
		
		public DaoDeserializer(RedaoCommando rcommando) {
			super(Dao.class);
			this.rcommando = rcommando;
	    }
	 
	    @Override
	    public Dao deserialize(JsonParser parser, DeserializationContext deserializer) throws IOException {
	    	JsonNode node = parser.readValueAsTree();
	    	if (node.isTextual()) {
	    		String str = node.asText();
	    		int index = str.indexOf("/");
	    		if (index > 0) {
                    String setKey = str.substring(0, index);
                    String id = str.substring(index + 1);
                    return rcommando.getPrimaryIndex(setKey).get(id);
	    		} else {
                    throw new JsonParseException(parser, "Unable to deserialize Dao. Expected String of form type/id but got " + str);
	    		}
	    	} else {
	    		throw new JsonParseException(parser, "Unable to deserialize Dao. Expected String of form type/id but got non-text node: " + node.toString());
	    	}
	    }
	}
	
	private class IndexSerializer extends StdSerializer<Index> {
		
	    public IndexSerializer() {
	        super(Index.class);
	    }
	 
		@Override
		public void serialize(Index value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(value.getType());
		}
	}
	
	private class IndexDeserializer extends StdDeserializer<Index> {
		
		private RedaoCommando rcommando;
		
		public IndexDeserializer(RedaoCommando rcommando) {
			super(Index.class);
			this.rcommando = rcommando;
	    }
	 
	    @Override
	    public Index deserialize(JsonParser parser, DeserializationContext deserializer) throws IOException {
	    	JsonNode node = parser.readValueAsTree();
	    	if (node.isTextual()) {
	    		return rcommando.getPrimaryIndex(node.asText());
	    	} else {
	    		throw new JsonParseException(parser, "Unable to deserialze Index. Expected String type");
	    	}
	    }
	}
	
	private class OptionalSerializer extends StdSerializer<Optional<?>> {

	    public OptionalSerializer() {
	        super(Optional.class, false);
	    }
	    
		@Override
		public void serialize(Optional<?> value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			if (value.isPresent()) {
				gen.writeObject(value.get());
			} else {
				gen.writeNull();
			}
		}
	}
	
	private static class OptionalDeserializer extends JsonDeserializer<Optional<?>> implements ContextualDeserializer {

	    private final JavaType innerType;
	    
	    OptionalDeserializer() {
	    	innerType = null;
	    }
	    
	    OptionalDeserializer(JavaType innerType) {
	    	this.innerType = innerType;
		}

	    @Override
	    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) throws JsonMappingException {
	    	JavaType type = ctxt.getContextualType().containedType(0);
	        return new OptionalDeserializer(type);
	    }

	    @Override
	    public Optional<?> deserialize(JsonParser parser, DeserializationContext ctxt) throws IOException {
	    	return Optional.ofNullable(ctxt.readValue(parser, innerType));
	    }
	}
}
