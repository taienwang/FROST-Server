/*
 * Copyright (C) 2016 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
 * Karlsruhe, Germany.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fraunhofer.iosb.ilt.frostserver.mqtt;

import de.fraunhofer.iosb.ilt.frostserver.model.EntityChangedMessage;
import de.fraunhofer.iosb.ilt.frostserver.messagebus.MessageListener;
import de.fraunhofer.iosb.ilt.frostserver.model.Observation;
import de.fraunhofer.iosb.ilt.frostserver.model.core.Entity;
import de.fraunhofer.iosb.ilt.frostserver.mqtt.create.EntityCreateListener;
import de.fraunhofer.iosb.ilt.frostserver.mqtt.create.ObservationCreateEvent;
import de.fraunhofer.iosb.ilt.frostserver.mqtt.subscription.Subscription;
import de.fraunhofer.iosb.ilt.frostserver.mqtt.subscription.SubscriptionEvent;
import de.fraunhofer.iosb.ilt.frostserver.mqtt.subscription.SubscriptionFactory;
import de.fraunhofer.iosb.ilt.frostserver.mqtt.subscription.SubscriptionListener;
import de.fraunhofer.iosb.ilt.frostserver.path.EntityType;
import de.fraunhofer.iosb.ilt.frostserver.path.Property;
import de.fraunhofer.iosb.ilt.frostserver.persistence.PersistenceManager;
import de.fraunhofer.iosb.ilt.frostserver.persistence.PersistenceManagerFactory;
import de.fraunhofer.iosb.ilt.frostserver.service.RequestType;
import de.fraunhofer.iosb.ilt.frostserver.service.Service;
import de.fraunhofer.iosb.ilt.frostserver.service.ServiceRequestBuilder;
import de.fraunhofer.iosb.ilt.frostserver.service.ServiceResponse;
import de.fraunhofer.iosb.ilt.frostserver.settings.CoreSettings;
import de.fraunhofer.iosb.ilt.frostserver.settings.MqttSettings;
import de.fraunhofer.iosb.ilt.frostserver.util.ProcessorHelper;
import de.fraunhofer.iosb.ilt.frostserver.util.StringHelper;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author jab
 */
public class MqttManager implements SubscriptionListener, MessageListener, EntityCreateListener {

    private static MqttManager instance;
    private static final Logger LOGGER = LoggerFactory.getLogger(MqttManager.class);

    public static synchronized void init(CoreSettings settings) {
        if (instance == null) {
            instance = new MqttManager(settings);
        }
    }

    public static void shutdown() {
        if (instance != null) {
            instance.doShutdown();
        }
    }

    public static MqttManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("MqttManager is not initialized! Call init() before accessing the instance.");
        }
        return instance;
    }

    private final Map<EntityType, Map<Subscription, AtomicInteger>> subscriptions = new EnumMap<>(EntityType.class);
    private final CoreSettings settings;
    private MqttServer server;
    private BlockingQueue<EntityChangedMessage> entityChangedEventQueue;
    private ExecutorService entityChangedExecutorService;
    private BlockingQueue<ObservationCreateEvent> observationCreateEventQueue;
    private ExecutorService observationCreateExecutorService;
    private boolean enabledMqtt = false;
    private boolean shutdown = false;

    private MqttManager(CoreSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("setting must be non-null");
        }
        this.settings = settings;
        for (EntityType entityType : EntityType.values()) {
            subscriptions.put(entityType, new ConcurrentHashMap<>());
        }
        init();
    }

    private void init() {
        MqttSettings mqttSettings = settings.getMqttSettings();
        SubscriptionFactory.init(settings);
        if (mqttSettings.isEnableMqtt()) {
            enabledMqtt = true;
            shutdown = false;
            entityChangedEventQueue = new ArrayBlockingQueue<>(mqttSettings.getSubscribeMessageQueueSize());
            // start watching for EntityChangedEvents
            entityChangedExecutorService = ProcessorHelper.createProcessors(
                    mqttSettings.getSubscribeThreadPoolSize(),
                    entityChangedEventQueue,
                    this::handleEntityChangedEvent,
                    "Mqtt-ECEP");
            // start watching for ObservationCreateEvents
            observationCreateEventQueue = new ArrayBlockingQueue<>(mqttSettings.getCreateMessageQueueSize());
            observationCreateExecutorService = ProcessorHelper.createProcessors(
                    mqttSettings.getCreateThreadPoolSize(),
                    observationCreateEventQueue,
                    this::handleObservationCreateEvent,
                    "Mqtt-OCEP");
            // start MQTT server
            server = MqttServerFactory.getInstance().get(settings);
            server.addSubscriptionListener(this);
            server.addEntityCreateListener(this);
            server.start();

        } else {
            enabledMqtt = false;
            entityChangedExecutorService = null;
            entityChangedEventQueue = new ArrayBlockingQueue<>(1);
            observationCreateExecutorService = null;
            observationCreateEventQueue = new ArrayBlockingQueue<>(1);
            server = null;
        }
    }

    private void doShutdown() {
        shutdown = true;
        ProcessorHelper.shutdownProcessors(entityChangedExecutorService, entityChangedEventQueue, 10, TimeUnit.SECONDS);
        ProcessorHelper.shutdownProcessors(observationCreateExecutorService, observationCreateEventQueue, 10, TimeUnit.SECONDS);
        if (server != null) {
            server.stop();
        }
    }

    private void handleEntityChangedEvent(EntityChangedMessage message) {
        if (message.getEventType() == EntityChangedMessage.Type.DELETE) {
            // v1.0 does not do delete notification.
            return;
        }
        // check if there is any subscription, if not do not publish at all
        EntityType entityType = message.getEntityType();
        if (!subscriptions.containsKey(entityType)) {
            return;
        }
        // Send a complete entity through the bus, or just an entity-id?
        Entity entity = message.getEntity();
        Set<Property> fields = message.getFields();
        try (PersistenceManager persistenceManager = PersistenceManagerFactory.getInstance().create()) {
            // for each subscription on EntityType check match
            for (Subscription subscription : subscriptions.get(entityType).keySet()) {
                if (subscription.matches(persistenceManager, entity, fields)) {
                    notifySubscription(subscription, entity);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("error handling MQTT subscriptions", ex);
        }
    }

    private void notifySubscription(Subscription subscription, Entity entity) {
        try {
            String payload = subscription.formatMessage(entity);
            server.publish(subscription.getTopic(), payload.getBytes(StringHelper.UTF8), settings.getMqttSettings().getQosLevel());
        } catch (IOException ex) {
            LOGGER.error("publishing to MQTT on topic '" + subscription.getTopic() + "' failed", ex);
        }
    }

    private void handleObservationCreateEvent(ObservationCreateEvent e) {
        // check path?
        String topic = e.getTopic();
        if (!topic.endsWith("Observations")) {
            LOGGER.info("received message on topic '{}' which is no valid topic to create an observation.", topic);
            return;
        }
        String url = topic.replaceFirst(settings.getApiVersion(), "");
        try (Service service = new Service(settings)) {
            ServiceResponse<Observation> response = service.execute(
                    new ServiceRequestBuilder(settings.getFormatter())
                            .withRequestType(RequestType.CREATE)
                            .withContent(e.getPayload())
                            .withUrlPath(url)
                            .build());
            if (response.isSuccessful()) {
                LOGGER.debug("Observation (ID {}) created via MQTT", response.getResult().getId().getValue());
            } else {
                LOGGER.error("Creating observation via MQTT failed (topic: {}, payload: {}, code: {}, message: {})",
                        topic, e.getPayload(), response.getCode(), response.getMessage());
            }
        }
    }

    private void entityChanged(EntityChangedMessage e) {
        if (shutdown || !enabledMqtt) {
            return;
        }
        if (!entityChangedEventQueue.offer(e)) {
            LOGGER.warn("EntityChangedevent discarded because message queue is full {}! Increase mqtt.SubscribeMessageQueueSize and/or mqtt.SubscribeThreadPoolSize.", entityChangedEventQueue.size());
        }
    }

    @Override
    public void onSubscribe(SubscriptionEvent e) {
        Subscription subscription = SubscriptionFactory.getInstance().get(e.getTopic());
        if (subscription == null) {
            // Not a valid topic.
            return;
        }

        Map<Subscription, AtomicInteger> subscriptionsMap = subscriptions.get(subscription.getEntityType());
        synchronized (subscriptionsMap) {
            AtomicInteger clientCount = subscriptionsMap.get(subscription);
            if (clientCount == null) {
                clientCount = new AtomicInteger(1);
                subscriptionsMap.put(subscription, clientCount);
                LOGGER.debug("Created new subscription for topic {}.", subscription.getTopic());
            } else {
                int newCount = clientCount.incrementAndGet();
                LOGGER.debug("Now {} subscriptions for topic {}.", newCount, subscription.getTopic());
            }
        }
    }

    @Override
    public void onUnsubscribe(SubscriptionEvent e) {
        Subscription subscription = SubscriptionFactory.getInstance().get(e.getTopic());
        if (subscription == null) {
            // Not a valid topic.
            return;
        }
        final Map<Subscription, AtomicInteger> subscriptionsMap = subscriptions.get(subscription.getEntityType());
        synchronized (subscriptionsMap) {
            AtomicInteger clientCount = subscriptionsMap.get(subscription);
            if (clientCount != null) {
                int newCount = clientCount.decrementAndGet();
                LOGGER.debug("Now {} subscriptions for topic {}.", newCount, subscription.getTopic());
                if (newCount <= 0) {
                    subscriptionsMap.remove(subscription);
                    LOGGER.debug("Removed last subscription for topic {}.", subscription.getTopic());
                }
            }
        }
    }

    @Override
    public void messageReceived(EntityChangedMessage message) {
        entityChanged(message);
    }

    @Override
    public void onObservationCreate(ObservationCreateEvent e) {
        if (shutdown || !enabledMqtt) {
            return;
        }
        if (!observationCreateEventQueue.offer(e)) {
            LOGGER.warn("ObservationCreateEvent discarded because message queue is full {}! Increase mqtt.SubscribeMessageQueueSize and/or mqtt.SubscribeThreadPoolSize", observationCreateEventQueue.size());
        }
    }
}
