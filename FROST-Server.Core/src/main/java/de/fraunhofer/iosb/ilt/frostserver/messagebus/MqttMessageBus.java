/*
 * Copyright (C) 2018 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
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
package de.fraunhofer.iosb.ilt.frostserver.messagebus;

import de.fraunhofer.iosb.ilt.frostserver.model.EntityChangedMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.fraunhofer.iosb.ilt.frostserver.json.deserialize.EntityParser;
import de.fraunhofer.iosb.ilt.frostserver.json.serialize.EntityFormatter;
import de.fraunhofer.iosb.ilt.frostserver.persistence.PersistenceManagerFactory;
import de.fraunhofer.iosb.ilt.frostserver.settings.BusSettings;
import de.fraunhofer.iosb.ilt.frostserver.settings.ConfigDefaults;
import de.fraunhofer.iosb.ilt.frostserver.settings.CoreSettings;
import de.fraunhofer.iosb.ilt.frostserver.settings.Settings;
import de.fraunhofer.iosb.ilt.frostserver.settings.annotation.DefaultValue;
import de.fraunhofer.iosb.ilt.frostserver.settings.annotation.DefaultValueInt;
import de.fraunhofer.iosb.ilt.frostserver.util.ProcessorHelper;
import de.fraunhofer.iosb.ilt.frostserver.util.StringHelper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A message bus implementation for in-JVM use.
 *
 * @author scf
 */
public class MqttMessageBus implements MessageBus, MqttCallback, ConfigDefaults {

    @DefaultValueInt(2)
    public static final String TAG_SEND_WORKER_COUNT = "sendWorkerPoolSize";
    @DefaultValueInt(2)
    public static final String TAG_RECV_WORKER_COUNT = "recvWorkerPoolSize";
    @DefaultValueInt(100)
    public static final String TAG_SEND_QUEUE_SIZE = "sendQueueSize";
    @DefaultValueInt(100)
    public static final String TAG_RECV_QUEUE_SIZE = "recvQueueSize";
    @DefaultValue("tcp://127.0.0.1:1884")
    public static final String TAG_MQTT_BROKER = "mqttBroker";
    @DefaultValue("FROST-Bus")
    public static final String TAG_TOPIC_NAME = "topicName";
    @DefaultValueInt(2)
    public static final String TAG_QOS_LEVEL = "qosLevel";
    @DefaultValueInt(50)
    public static final String TAG_MAX_IN_FLIGHT = "maxInFlight";

    /**
     * The logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(MqttMessageBus.class);

    private int sendPoolSize;
    private int sendQueueSize;
    private int recvPoolSize;
    private int recvQueueSize;
    private BlockingQueue<EntityChangedMessage> sendQueue;
    private ExecutorService sendService;
    private BlockingQueue<EntityChangedMessage> recvQueue;
    private ExecutorService recvService;
    private final List<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private String broker;
    private String clientId = "FROST-MQTT-Bus-" + UUID.randomUUID();
    private MqttClient client;
    private String topicName;
    private int qosLevel;
    private int maxInFlight;
    private boolean listening = false;

    private ObjectMapper formatter;
    private EntityParser parser;

    @Override
    public void init(CoreSettings settings) {
        BusSettings busSettings = settings.getBusSettings();
        Settings customSettings = busSettings.getCustomSettings();
        sendPoolSize = customSettings.getInt(TAG_SEND_WORKER_COUNT, getClass());
        sendQueueSize = customSettings.getInt(TAG_SEND_QUEUE_SIZE, getClass());
        recvPoolSize = customSettings.getInt(TAG_RECV_WORKER_COUNT, getClass());
        recvQueueSize = customSettings.getInt(TAG_RECV_QUEUE_SIZE, getClass());

        sendQueue = new ArrayBlockingQueue<>(sendQueueSize);
        sendService = ProcessorHelper.createProcessors(
                sendPoolSize,
                sendQueue,
                this::handleMessageSent,
                "mqtt-BusS");

        recvQueue = new ArrayBlockingQueue<>(recvQueueSize);
        recvService = ProcessorHelper.createProcessors(
                recvPoolSize,
                recvQueue,
                this::handleMessageReceived,
                "mqtt-BusR");

        broker = customSettings.get(TAG_MQTT_BROKER, getClass());
        topicName = customSettings.get(TAG_TOPIC_NAME, getClass());
        qosLevel = customSettings.getInt(TAG_QOS_LEVEL, getClass());
        maxInFlight = customSettings.getInt(TAG_MAX_IN_FLIGHT, getClass());
        connect();

        formatter = EntityFormatter.getObjectMapper();
        parser = new EntityParser(PersistenceManagerFactory.getInstance().getIdManager().getIdClass());
    }

    private synchronized void connect() {
        if (client == null) {
            try {
                client = new MqttClient(broker, clientId, new MemoryPersistence());
                client.setCallback(this);
            } catch (MqttException ex) {
                LOGGER.error("Failed to connect to broker: {}", broker);
                LOGGER.error("", ex);
                return;
            }
        }
        if (!client.isConnected()) {
            try {
                LOGGER.info("paho-client connecting to broker: {}", broker);
                MqttConnectOptions connOpts = new MqttConnectOptions();
                connOpts.setAutomaticReconnect(true);
                connOpts.setCleanSession(false);
                connOpts.setKeepAliveInterval(30);
                connOpts.setConnectionTimeout(30);
                connOpts.setMaxInflight(maxInFlight);
                client.connect(connOpts);
                LOGGER.info("paho-client connected to broker");
            } catch (MqttException ex) {
                LOGGER.error("Failed to connect to broker: {}", broker);
                LOGGER.error("", ex);
            }
        }

    }

    private synchronized void disconnect() {
        if (client == null) {
            return;
        }
        if (client.isConnected()) {
            try {
                LOGGER.info("paho-client disconnecting from broker: {}", broker);
                client.disconnect(1000);
            } catch (MqttException ex) {
                LOGGER.error("Exception disconnecting client.", ex);
            }
        }
        try {
            LOGGER.info("paho-client closing");
            client.close();
        } catch (MqttException ex) {
            LOGGER.error("Exception closing client.", ex);
        }
        client = null;
    }

    private synchronized void startListening() {
        try {
            LOGGER.info("paho-client subscribing to topic: {}", topicName);
            if (!client.isConnected()) {
                connect();
            }
            client.subscribe(topicName, qosLevel);
            listening = true;
        } catch (MqttException ex) {
            LOGGER.error("Failed to start listening.", ex);
        }
    }

    private synchronized void stopListening() {
        if (!listening) {
            return;
        }
        try {
            LOGGER.info("paho-client unsubscribing from topic: {}", topicName);
            client.unsubscribe(topicName);
            listening = false;
        } catch (MqttException ex) {
            LOGGER.error("Failed to stop listening.", ex);
        }
    }

    @Override
    public void stop() {
        LOGGER.info("Message bus shutting down.");
        stopListening();
        disconnect();
        ProcessorHelper.shutdownProcessors(sendService, sendQueue, 10, TimeUnit.SECONDS);
        ProcessorHelper.shutdownProcessors(recvService, recvQueue, 10, TimeUnit.SECONDS);
        LOGGER.info("Message bus closed.");
    }

    @Override
    public void sendMessage(EntityChangedMessage message) {
        if (!sendQueue.offer(message)) {
            LOGGER.error("Failed to add message to send-queue. Increase {} (currently {}) to allow a bigger buffer, or increase {} (currently {}) to empty the buffer quicker.",
                    TAG_SEND_QUEUE_SIZE, sendQueueSize, TAG_SEND_WORKER_COUNT, sendPoolSize);
        }
    }

    @Override
    public synchronized void addMessageListener(MessageListener listener) {
        listeners.add(listener);
        if (!listening) {
            startListening();
        }
    }

    @Override
    public synchronized void removeMessageListener(MessageListener listener) {
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            stopListening();
        }
    }

    private void handleMessageSent(EntityChangedMessage message) {
        try {
            String serialisedMessage = formatter.writeValueAsString(message);
            byte[] bytes = serialisedMessage.getBytes(StringHelper.UTF8);
            if (!client.isConnected()) {
                connect();
            }
            client.publish(topicName, bytes, qosLevel, false);
        } catch (MqttException | JsonProcessingException ex) {
            LOGGER.error("Failed to publish message to bus.", ex);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        LOGGER.warn("Connection to message bus lost.");
        LOGGER.debug("", cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage mqttMessage) throws Exception {
        String serialisedEcMessage = new String(mqttMessage.getPayload(), StringHelper.UTF8);
        EntityChangedMessage ecMessage = parser.parseObject(EntityChangedMessage.class, serialisedEcMessage);
        if (!recvQueue.offer(ecMessage)) {
            LOGGER.error("Failed to add message to receive-queue. Increase {} (currently {}) to allow a bigger buffer, or increase {} (currently {}) to empty the buffer quicker.",
                    TAG_RECV_QUEUE_SIZE, recvQueueSize, TAG_RECV_WORKER_COUNT, recvPoolSize);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Nothing to do...
    }

    private void handleMessageReceived(EntityChangedMessage message) {
        for (MessageListener listener : listeners) {
            try {
                listener.messageReceived(message);
            } catch (Exception ex) {
                LOGGER.error("Listener threw exception on message reception.", ex);
            }
        }
    }
}
