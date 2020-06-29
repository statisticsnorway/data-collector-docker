package no.ssb.dc.server.service;

import de.huxhorn.sulky.ulid.ULID;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.Txn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static java.nio.charset.StandardCharsets.UTF_8;

public class IntegrityCheckIndex implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrityCheckIndex.class);

    static int CONTENT_LENGTH = 0;

    private final LmdbEnvironment lmdbEnvironment;
    private final DirectByteBufferPool keyBufferPool;
    private final DirectByteBufferPool contentBufferPool;
    private final Dbi<ByteBuffer> sequenceDb;
    private final AtomicLong flushCounter = new AtomicLong();
    private final int flushBufferCount;
    private Txn<ByteBuffer> writeTransaction;

    public IntegrityCheckIndex(LmdbEnvironment lmdbEnvironment, int flushBufferCount) {
        this.lmdbEnvironment = lmdbEnvironment;
        this.flushBufferCount = flushBufferCount;
        this.keyBufferPool = new DirectByteBufferPool(10, lmdbEnvironment.maxKeySize());
        this.contentBufferPool = new DirectByteBufferPool(10, CONTENT_LENGTH);
        this.sequenceDb = lmdbEnvironment.open();
    }

    public Path getDatabaseDir() {
        return lmdbEnvironment.getDatabaseDir();
    }

    Txn<ByteBuffer> getWriteTransaction() {
        if (writeTransaction == null) {
            writeTransaction = lmdbEnvironment.env().txnWrite();
        }
        if (flushCounter.incrementAndGet() == flushBufferCount) {
            try {
                writeTransaction.commit();
            } catch (Txn.NotReadyException e) {
                LOG.error("Transaction is NOT Ready! Ignoring commit");
            }
            writeTransaction.close();
            writeTransaction = lmdbEnvironment.env().txnWrite();
            flushCounter.set(0);
        }
        return writeTransaction;
    }

    public void commit() {
        if (flushCounter.get() > 0) {
            flushCounter.set(flushBufferCount - 1L);
            getWriteTransaction(); // enforce flush
        }
    }

    void writeSequence(ULID.Value ulid, String position) {
        ByteBuffer keyBuffer = keyBufferPool.acquire();
        try {
            SequenceKey sequenceKey = new SequenceKey(ulid, position);
            sequenceKey.toByteBuffer(keyBuffer);
            ByteBuffer contentBuffer = contentBufferPool.acquire();
            try {
                contentBuffer.flip();
                Txn<ByteBuffer> txn = getWriteTransaction();
                sequenceDb.put(txn, keyBuffer, contentBuffer);
            } finally {
                contentBufferPool.release(contentBuffer);
            }
        } finally {
            keyBufferPool.release(keyBuffer);
        }
    }

    void readSequence(BiConsumer<SequenceKey, Boolean> visit) {
        try (Txn<ByteBuffer> txn = lmdbEnvironment.env().txnRead()) {
            Iterator<CursorIterable.KeyVal<ByteBuffer>> it = sequenceDb.iterate(txn).iterator();
            while (it.hasNext()) {
                CursorIterable.KeyVal<ByteBuffer> next = it.next();
                ByteBuffer keyBuffer = next.key();
                SequenceKey sequenceKey = SequenceKey.fromByteBuffer(keyBuffer);
                visit.accept(sequenceKey, it.hasNext());
            }
        }
    }

    @Override
    public void close() {
        commit();
    }

    static class SequenceKey implements Comparable<SequenceKey> {
        final ULID.Value ulid;
        final String position;

        SequenceKey(ULID.Value ulid, String position) {
            this.ulid = ulid;
            this.position = position;
        }

        static SequenceKey fromByteBuffer(ByteBuffer keyBuffer) {
            int positionLength = keyBuffer.get();
            byte[] positionBytes = new byte[positionLength];
            keyBuffer.get(positionBytes);
            long ulidMostSignificantBits = keyBuffer.getLong();
            long ulidLeastSignificantBits = keyBuffer.getLong();
            String position = new String(positionBytes, UTF_8);
            ULID.Value ulid = new ULID.Value(ulidMostSignificantBits, ulidLeastSignificantBits);
            return new SequenceKey(ulid, position);
        }

        ByteBuffer toByteBuffer(ByteBuffer allocatedBuffer) {
            byte[] key = position.getBytes(UTF_8);
            allocatedBuffer.put((byte) key.length);
            allocatedBuffer.put(key);
            allocatedBuffer.putLong(ulid.getMostSignificantBits());
            allocatedBuffer.putLong(ulid.getLeastSignificantBits());
            return allocatedBuffer.flip();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SequenceKey that = (SequenceKey) o;
            return Objects.equals(ulid, that.ulid) &&
                    Objects.equals(position, that.position);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ulid, position);
        }

        @Override
        public int compareTo(SequenceKey that) {
            return this.ulid.compareTo(that.ulid);
        }

        @Override
        public String toString() {
            return "SequenceKey{" +
                    "ulid=" + ulid +
                    ", position='" + position + '\'' +
                    '}';
        }
    }

    static class Writer {

    }

}
