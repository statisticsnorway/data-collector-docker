package no.ssb.dc.server.service;

import no.ssb.dc.api.util.CommonUtils;
import org.junit.jupiter.api.Test;
import org.lmdbjava.CursorIterable;
import org.lmdbjava.Dbi;
import org.lmdbjava.Txn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Iterator;

import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class LmdbEnvironmentTest {

    private static final Logger LOG = LoggerFactory.getLogger(LmdbEnvironmentTest.class);

    /*
     * Test requires vm arg: --add-opens java.base/java.nio=lmdbjava --add-exports=java.base/sun.nio.ch=lmdbjava
     */
    @Test
    public void dbEnv() {
        Path dbPath = CommonUtils.currentPath().resolve("target").resolve("lmdb");
        try (LmdbEnvironment environment = new LmdbEnvironment(dbPath, "test-stream")) {
            IntegrityJobRepository repository = new IntegrityJobRepository(environment);

            Dbi<ByteBuffer> db = environment.open();
            ByteBuffer key = allocateDirect(environment.env.getMaxKeySize());
            ByteBuffer val = allocateDirect(700);
            key.put("greeting".getBytes(UTF_8)).flip();
            val.put("Hello world".getBytes(UTF_8)).flip();
            int valSize = val.remaining();

            db.put(key, val);

            try (Txn<ByteBuffer> txn = environment.env.txnRead()) {
                final ByteBuffer found = db.get(txn, key);
                assertNotNull(found);

                // The fetchedVal is read-only and points to LMDB memory
                final ByteBuffer fetchedVal = txn.val();
                assertEquals(fetchedVal.remaining(), valSize);

                // Let's double-check the fetched value is correct
                assertEquals(UTF_8.decode(fetchedVal).toString(), "Hello world");
            }

            // We can also delete. The simplest way is to let Dbi allocate a new Txn...
            db.delete(key);

            // Now if we try to fetch the deleted row, it won't be present
            try (Txn<ByteBuffer> txn = environment.env.txnRead()) {
                assertNull(db.get(txn, key));
            }

            try (Txn<ByteBuffer> txn = environment.env.txnWrite()) {
                key.put("key1".getBytes(UTF_8)).flip();
                val.put("lmdb".getBytes(UTF_8)).flip();
                db.put(txn, key, val);

                // We can read data too, even though this is a write Txn.
                final ByteBuffer found = db.get(txn, key);
                assertNotNull(found);

                // An explicit commit is required, otherwise Txn.close() rolls it back.
                txn.commit();
            }

            try (Txn<ByteBuffer> txn = environment.env.txnWrite()) {
                key.put("key2".getBytes(UTF_8)).flip();
                val.put("lmdb".getBytes(UTF_8)).flip();
                db.put(txn, key, val);

                // We can read data too, even though this is a write Txn.
                final ByteBuffer found = db.get(txn, key);
                assertNotNull(found);

                // An explicit commit is required, otherwise Txn.close() rolls it back.
                txn.commit();
            }

            try (Txn<ByteBuffer> txn = environment.env.txnRead()) {
                key.put("key1".getBytes(UTF_8)).flip();
                ByteBuffer buf = db.get(txn, key);

                Iterator<CursorIterable.KeyVal<ByteBuffer>> it = db.iterate(txn).iterator();

                System.out.printf("%s%n", UTF_8.decode(it.next().key()).toString());
                System.out.printf("%s%n", UTF_8.decode(it.next().key()).toString());
            }
        }
    }
}
