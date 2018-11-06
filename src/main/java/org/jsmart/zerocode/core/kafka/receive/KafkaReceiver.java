package org.jsmart.zerocode.core.kafka.receive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.jsmart.zerocode.core.di.provider.GsonSerDeProvider;
import org.jsmart.zerocode.core.di.provider.ObjectMapperProvider;
import org.jsmart.zerocode.core.kafka.ConsumedRecords;
import org.jsmart.zerocode.core.kafka.DeliveryStatus;
import org.jsmart.zerocode.core.kafka.KafkaConstants;
import org.jsmart.zerocode.core.kafka.consume.ConsumerLocalConfigsWrap;
import org.jsmart.zerocode.core.kafka.consume.ConsumerLocalConfigs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static java.time.Duration.ofMillis;
import static org.jsmart.zerocode.core.domain.ZerocodeConstants.OK;
import static org.jsmart.zerocode.core.kafka.helper.KafkaFileRecordHelper.handleRecordsDump;
import static org.jsmart.zerocode.core.kafka.helper.KafkaHelper.createConsumer;
import static org.jsmart.zerocode.core.utils.SmartUtils.prettyPrintJson;

@Singleton
public class KafkaReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaReceiver.class);

    private final ObjectMapper objectMapper = new ObjectMapperProvider().get();
    private static Gson gson = new GsonSerDeProvider().get();

    @Inject(optional = true)
    @Named("consumer.commitSync")
    private boolean commitSync;

    @Inject(optional = true)
    @Named("consumer.commitAsync")
    private boolean commitAsync;

    @Inject(optional = true)
    @Named("kafka.consumer.properties")
    private String consumerPropertyFile;

    public String receive(String kafkaServers, String topicName, String consumePropertiesAsJson) throws IOException {

        Consumer<Long, String> consumer = createConsumer(kafkaServers, consumerPropertyFile, topicName);

        ConsumerLocalConfigs consumeLocalTestProps = readConsumerLocalTestProperties(consumePropertiesAsJson);

        final List<ConsumerRecord> fetchedRecords = new ArrayList<>();

        int noOfTimeOuts = 0;

        while (true) {
            //TODO- Configure poll millisec in localTestProperties
            final ConsumerRecords<Long, String> records = consumer.poll(ofMillis(100));

            //String jsonRecords = gson.toJson(records);
            //System.out.println("jsonRecords>>>>>>>>>>\n" + jsonRecords);

            if (records.count() == 0) {
                noOfTimeOuts++;
                //TODO- make this configurable
                if (noOfTimeOuts > KafkaConstants.MAX_NO_OF_RETRY_POLLS_OR_TIME_OUTS) {
                    break;
                } else {
                    continue;
                }
            } else {
                LOGGER.info("Got {} records after {} timeouts\n", records.count(), noOfTimeOuts);
                // -----------------------------------
                // reset after it fetched some records
                // -----------------------------------
                noOfTimeOuts = 0;
            }

            if (records != null) {
                records.forEach(thisRecord -> {
                    fetchedRecords.add(thisRecord);
                    LOGGER.info("\nRecord Key - {} , Record value - {}, Record partition - {}, Record offset - {}",
                            thisRecord.key(), thisRecord.value(), thisRecord.partition(), thisRecord.offset());
                });
            }

            handleCommitSyncAsync(consumer, consumeLocalTestProps);
        }

        consumer.close();

        handleRecordsDump(consumeLocalTestProps, fetchedRecords);

        return prepareResult(consumeLocalTestProps, fetchedRecords);

    }

    private String prepareResult(ConsumerLocalConfigs consumeLocalTestProps, List<ConsumerRecord> fetchedRecords) throws JsonProcessingException {
        if (consumeLocalTestProps != null && !consumeLocalTestProps.getShowRecordsAsResponse()) {

            return objectMapper.writeValueAsString(new DeliveryStatus(OK, fetchedRecords.size()));

        } else {
            //TODO - inject this Gson
            return prettyPrintJson(gson.toJson(new ConsumedRecords(fetchedRecords)));

        }
    }

    private void handleCommitSyncAsync(Consumer<Long, String> consumer, ConsumerLocalConfigs consumeLocalTestProps) {

        if (consumeLocalTestProps != null) {

            Boolean localCommitSync = consumeLocalTestProps.getCommitSync();
            Boolean localCommitAsync = consumeLocalTestProps.getCommitAsync();

            validateIfBothEnabled(localCommitSync, localCommitAsync);

            Boolean effectiveCommitSync = localCommitSync != null ? localCommitSync : commitSync;
            Boolean effectiveCommitAsync = localCommitAsync != null ? localCommitAsync : commitAsync;


            if (effectiveCommitSync != null && effectiveCommitSync == true) {
                consumer.commitSync();

            } else if (effectiveCommitAsync != null && effectiveCommitAsync == true) {
                consumer.commitAsync();

            } else {
                LOGGER.warn("Kafka client neither did `commitAsync()` nor `commitSync()`");
            }
        }
        // ---------------------------------------------------
        // Leave this to the user to commit it explicitly
        // ---------------------------------------------------
    }

    private void validateIfBothEnabled(Boolean localCommitSync, Boolean localCommitAsync) {
        if( (localCommitSync != null && localCommitAsync != null )
                && localCommitSync == true && localCommitAsync == true){
            throw new RuntimeException("\n********* Both commitSync and commitAsync can not be true *********\n");
        }
    }

    private ConsumerLocalConfigs readConsumerLocalTestProperties(String consumePropertiesAsJson) {
        try {
            ConsumerLocalConfigsWrap consumerLocalConfigsWrap = objectMapper.readValue(consumePropertiesAsJson, ConsumerLocalConfigsWrap.class);

            return consumerLocalConfigsWrap.getConsumerLocalConfigs();

        } catch (IOException exx) {
            throw new RuntimeException(exx);
        }
    }

}
