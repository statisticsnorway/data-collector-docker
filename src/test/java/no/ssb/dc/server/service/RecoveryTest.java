package no.ssb.dc.server.service;

import de.huxhorn.sulky.ulid.ULID;
import no.ssb.config.DynamicConfiguration;
import no.ssb.config.StoreBasedDynamicConfiguration;
import no.ssb.dc.api.content.ContentStore;
import no.ssb.dc.api.content.ContentStream;
import no.ssb.dc.api.content.ContentStreamBuffer;
import no.ssb.dc.api.content.ContentStreamConsumer;
import no.ssb.dc.api.content.ContentStreamProducer;
import no.ssb.dc.api.ulid.ULIDGenerator;
import no.ssb.dc.api.util.CommonUtils;
import no.ssb.dc.server.component.ContentStoreComponent;
import no.ssb.dc.server.component.RecoveryContentStoreComponent;
import no.ssb.dc.test.client.TestClient;
import no.ssb.dc.test.server.TestServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RecoveryTest {

    static final int NUMBER_OF_MESSAGES = 25000;
    private static final Logger LOG = LoggerFactory.getLogger(RecoveryTest.class);

    static void produceMessages(ContentStream contentStream, String prefixTopic) {
        try (ContentStreamProducer producer = contentStream.producer(prefixTopic + "test-stream")) {
            int min = 20000;
            int max = 25000 - 1;
            for (int n = 0; n < NUMBER_OF_MESSAGES; n++) {
                producer.publishBuilders(producer.builder().position(String.valueOf(n)).put("entry", "DATA".getBytes(StandardCharsets.UTF_8)));

                if (n >= min) {
                    int randomPosition = (int) (Math.random() * (max - min + 1) + min);
                    producer.publishBuilders(producer.builder().position(String.valueOf(randomPosition)).put("entry", "DATA".getBytes(StandardCharsets.UTF_8)));
                }
            }
            LOG.trace("Produced {} messages", NUMBER_OF_MESSAGES - 1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Disabled
    @Test
    void readIndex() {
        DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
                .values("content.stream.connector", "rawdata")
                .values("rawdata.client.provider", "memory")
                .values("data.collector.integrityCheck.consumer.timeoutInSeconds", "1")
                .values("data.collector.integrityCheck.dbSizeInMb", "100")
                .build();

        Path dbLocation = CommonUtils.currentPath().resolve("certs").resolve("lmdb");
        LmdbEnvironment lmdbEnvironment = new LmdbEnvironment(configuration, dbLocation, "ske-freg");

        long past = System.currentTimeMillis();
        SequenceDbHelper sequenceDbHelper = new SequenceDbHelper(lmdbEnvironment, lmdbEnvironment.open());
        PositionAndULIDVersion firstPosition = sequenceDbHelper.findFirstPosition();
        PositionAndULIDVersion lastPosition = sequenceDbHelper.findLastPosition();
        long now = System.currentTimeMillis() - past;
        LOG.trace("Time take: {}", now);
        LOG.trace("First: {} -> {} -> {}",
                firstPosition.position(),
                ULIDGenerator.toUUID(firstPosition.ulid()).toString(),
                new Date(firstPosition.ulid().timestamp())
        );
        LOG.trace("Last: {} -> {} -> {}",
                lastPosition.position(),
                ULIDGenerator.toUUID(lastPosition.ulid()).toString(),
                new Date(lastPosition.ulid().timestamp())
        );
    }

//    @Disabled
    @Test
    void readIndexAndConsumerSourceAndProduceTarget() throws InterruptedException {
        DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
                .values("content.stream.connector", "rawdata")
                .values("rawdata.client.provider", "memory")
                .values("data.collector.integrityCheck.consumer.timeoutInSeconds", "1")
                .values("data.collector.integrityCheck.dbSizeInMb", "100")
                .values("data.collector.integrityCheck.database.location", "./target")
                .build();

        TestServer server = TestServer.create(configuration);
        TestClient client = TestClient.create(server);
        server.start();

        ContentStoreComponent contentStoreComponent = server.getApplication().unwrap(ContentStoreComponent.class);
        ContentStore contentStore = contentStoreComponent.getDelegate();

        RecoveryContentStoreComponent recoveryContentStoreComponent = server.getApplication().unwrap(RecoveryContentStoreComponent.class);
        ContentStore recoveryContentStore = recoveryContentStoreComponent.getDelegate();

        /*
         * Produce random source data
         * Generate sequenceDb index
         * Find First and Last Position
         * Consume source and Produce target
         */

        Thread producerThread = new Thread(() -> produceMessages(contentStore.contentStream(), "source-"));
        producerThread.start();
        producerThread.join();

        IntegrityCheckService integrityCheckService = server.getApplication().unwrap(IntegrityCheckService.class);
        integrityCheckService.createJob("source-test-stream");
        while (integrityCheckService.isJobRunning("source-test-stream")) {
            Thread.sleep(50);
        }
        Thread.sleep(1500);
        LOG.trace("Completed integrity check !!");

        RecoveryWorker recoveryWorker = new RecoveryWorker(configuration, contentStoreComponent, recoveryContentStoreComponent);
        recoveryWorker.recover("source-test-stream", "target-test-stream");

        try (ContentStreamConsumer consumer = recoveryContentStore.contentStream().consumer("target-test-stream")) {
            ContentStreamBuffer buffer;
            ContentStreamBuffer peekBuffer = null;
            while ((buffer = consumer.receive(1, TimeUnit.SECONDS)) != null) {
                peekBuffer = buffer;
                if (Integer.parseInt(buffer.position()) < 10) {
                    LOG.trace("{} [{}]: {}", buffer.position(), buffer.ulid(), new Date(buffer.ulid().timestamp()), buffer.data().size());
                }
            }
            LOG.trace("{} [{}]: {}", peekBuffer.position(), peekBuffer.ulid(), new Date(peekBuffer.ulid().timestamp()), peekBuffer.data().size());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Thread.sleep(1000);

        server.stop();
    }

    @Test
    void thatLeastVersionIsSelected() {
        ULID.Value v1 = ULIDGenerator.generate();
        ULID.Value v2 = ULIDGenerator.generate();
        ULID.Value v3 = ULIDGenerator.generate();

        PositionAndULIDVersion version = new PositionAndULIDVersion();

        version.compareAndSet(v2, "1");
        assertEquals(v2, version.ulid());

        version.compareAndSet(v3, "1");
        assertEquals(v2, version.ulid());

        version.compareAndSet(v1, "1");
        assertEquals(v1, version.ulid());

        version.compareAndSet(v3, "1");
        assertEquals(v1, version.ulid());
    }


}
