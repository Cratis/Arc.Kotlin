// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.persistence.recipes.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.BsonDocument;
import org.bson.UuidRepresentation;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/** Application-owned configuration exercised against mongo-java-server, not a production MongoDB. */
public final class RecipeMongoStore implements AutoCloseable {
    private final List<String> findDatabases = new CopyOnWriteArrayList<>();
    private final MongoServer server;
    private final MongoClient client;
    private final MongoCustomConversions conversions;
    private final MongoMappingContext mappingContext;

    public RecipeMongoStore(Set<Class<?>> entities, MongoCustomConversions conversions) {
        this.conversions = conversions;
        mappingContext = new MongoMappingContext();
        // The same authoritative registration must inform both mapping and conversion before use.
        mappingContext.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        mappingContext.setInitialEntitySet(entities);
        mappingContext.afterPropertiesSet();
        server = new MongoServer(new MemoryBackend());
        try {
            var address = server.bind();
            client = MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://" + address.getHostString() + ":" + address.getPort()))
                .uuidRepresentation(UuidRepresentation.STANDARD)
                .addCommandListener(new CommandListener() {
                    @Override
                    public void commandStarted(CommandStartedEvent event) {
                        if (event.getCommandName().equals("find")) findDatabases.add(event.getDatabaseName());
                    }
                })
                .build());
        } catch (RuntimeException failure) {
            server.shutdownNow();
            throw failure;
        }
    }

    public List<String> findDatabases() {
        return List.copyOf(findDatabases);
    }

    public void clearFindDatabases() {
        findDatabases.clear();
    }

    public MongoMappingContext mappingContext() {
        return mappingContext;
    }

    /** Creates a fresh converter/template for the same configured database; no entity identity cache. */
    public MongoTemplate template(String database) {
        var factory = new SimpleMongoClientDatabaseFactory(client, database);
        var converter = new MappingMongoConverter(new DefaultDbRefResolver(factory), mappingContext);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        return new MongoTemplate(factory, converter);
    }

    public BsonDocument raw(String database, String collection) {
        return client.getDatabase(database).getCollection(collection, BsonDocument.class).find().first();
    }

    @Override
    public void close() {
        try {
            client.close();
        } finally {
            server.shutdownNow();
        }
    }
}
