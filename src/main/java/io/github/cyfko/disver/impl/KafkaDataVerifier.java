package io.github.cyfko.disver.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.cyfko.disver.DataVerifier;
import io.github.cyfko.disver.exceptions.DataExtractionException;
import io.github.cyfko.disver.util.JacksonUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwt;
import io.jsonwebtoken.Jwts;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;


public class KafkaDataVerifier implements DataVerifier {
    private static final org.slf4j.Logger log = org. slf4j. LoggerFactory. getLogger(KafkaDataVerifier.class);
    private static final KeyFactory keyFactory;
    private final KafkaConsumer<String, String> consumer;
    private final Cache<String, String> cache;

    static {
        try {
            keyFactory = KeyFactory.getInstance("RSA");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Properties initProperties() {
        Properties props = new Properties();
        props.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Constant.KAFKA_BOOSTRAP_SERVERS);
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }

    /// Construct a KafkaDataVerifier with environment variable properties and default ones.
    public KafkaDataVerifier() {
        this(initProperties());
    }

    /// Construct a KafkaDataVerifier with environment properties (and defaults ones) using the provided `boostrapServers`.
    /// @param boostrapServers the value to assign to the property `ProducerConfig.BOOTSTRAP_SERVERS_CONFIG`.
    public KafkaDataVerifier(String boostrapServers) {
        this(PropertiesUtil.of(initProperties(), boostrapServers));
    }

    private KafkaDataVerifier(Properties props) {
        this.consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(Constant.KAFKA_TOKEN_VERIFIER_TOPIC));

        // configure cache
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000) // Limit cache size to avoid memory overload
                .build();
    }

    @Override
    public <T> T verify(String jwt, Class<T> clazz) throws DataExtractionException {
        String keyId = getKeyId(jwt);

        try {
            Jwt<?, Claims> parsedJwt = Jwts.parser()
                    .verifyWith(getPublicKey(keyId))
                    .build()
                    .parseSignedClaims(jwt);
            final var data = parsedJwt.getPayload().get("data", String.class);
            return JacksonUtil.fromJson(data, clazz);
        } catch (Exception e) {
            throw new DataExtractionException("Failed to extract subject from JWT: " + e.getMessage());
        }
    }

    private String getKeyId(String jwt) {
        try {
            String payloadBase64 = jwt.split("\\.")[1];
            String payloadJson = new String(Base64.getDecoder().decode(payloadBase64));

            // Parse JSON and extract the "sub" field
            JsonNode jsonNode = JacksonUtil.fromJson(payloadJson, JsonNode.class);
            return jsonNode.get("sub").asText();
        } catch (Exception e) {
            throw new DataExtractionException("Failed to extract subject from JWT: " + e.getMessage());
        }
    }

    private PublicKey getPublicKey(String keyId) {
        String base64Key = cache.getIfPresent(keyId);
        if (base64Key == null) { // consumes kafka messages
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(5000)); // At most 5 secs to wait for.
            records.forEach(record -> {
                cache.put(record.key(), record.value());
            });

            base64Key = cache.getIfPresent(keyId);
            if (base64Key == null) {
                log.debug("Failed to find public key for keyId: {} from -> {}", keyId, cache);
                throw new DataExtractionException("Key not found");
            }
        }

        // Decode the Base64 encoded string into a byte array
        byte[] decodedKey = Base64.getDecoder().decode(base64Key);
        try {
            return keyFactory.generatePublic(new X509EncodedKeySpec(decodedKey));
        } catch (InvalidKeySpecException e) {
            throw new RuntimeException(e);
        }
    }
}
