package com.datadog.iast.taint;

import com.datadog.iast.IastSystem;
import com.datadog.iast.util.NonBlockingSemaphore;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Map optimized for taint tracking.
 *
 * <p>This implementation is optimized for low concurrency scenarios, but never fails with high
 * concurrency.
 *
 * <p>This is intentionally a smaller API compared to {@link java.util.Map}. It is a hardcoded
 * interface for {@link TaintedObject} entries, which can be used themselves directly as hash table
 * entries and weak references.
 *
 * <p>This implementation is subject to the following characteristics:
 *
 * <ol>
 *   <li>Keys MUST be compared with identity.
 *   <li>Entries SHOULD be removed when key objects are garbage-collected.
 *   <li>All operations MUST NOT throw, even with concurrent access and modification.
 *   <li>Put operations MAY be lost under concurrent modification.
 * </ol>
 *
 * <p><i>Capacity</i> is fixed, so there is no rehashing.
 *
 * <p>This implementation works reasonably well under high concurrency, but it will lose some writes
 * in that case.
 */
public interface TaintedMap extends Iterable<TaintedObject> {

  /** Default capacity. It MUST be a power of 2. */
  int DEFAULT_CAPACITY = 1 << 14;

  /** Bitmask to convert hashes to positive integers. */
  int POSITIVE_MASK = Integer.MAX_VALUE;

  static TaintedMap build(final int capacity) {
    return IastSystem.DEBUG ? new DebugTaintedMap(capacity) : new TaintedMapImpl(capacity);
  }

  static TaintedMap build() {
    return build(DEFAULT_CAPACITY);
  }

  @Nullable
  TaintedObject get(@Nonnull Object key);

  void put(final @Nonnull TaintedObject entry);

  void clear();

  class TaintedMapImpl implements TaintedMap {

    protected final TaintedObject[] table;

    /** Bitmask for fast modulo with table length. */
    protected final int lengthMask;

    /** Flag to ensure we do not run multiple purges concurrently. */
    protected final NonBlockingSemaphore purge = NonBlockingSemaphore.withPermitCount(1);

    /** Default constructor. Uses {@link #DEFAULT_CAPACITY} */
    TaintedMapImpl() {
      this(DEFAULT_CAPACITY);
    }

    /**
     * Create a new hash map with the given capacity a purge queue.
     *
     * @param capacity Capacity of the internal array. It must be a power of 2.
     */
    TaintedMapImpl(final int capacity) {
      table = new TaintedObject[capacity];
      lengthMask = table.length - 1;
    }

    /**
     * Returns the {@link TaintedObject} for the given input object.
     *
     * @param key Key object.
     * @return The {@link TaintedObject} if it exists, {@code null} otherwise.
     */
    @Nullable
    @Override
    public TaintedObject get(final @Nonnull Object key) {
      final int index = indexObject(key);
      TaintedObject entry = table[index];
      while (entry != null) {
        if (key == entry.get()) {
          return entry;
        }
        entry = entry.next;
      }
      return null;
    }

    /**
     * Put a new {@link TaintedObject} in the hash table, always to the tail of the chain. It will
     * not insert the element if it is already present in the map.. This method will lose puts in
     * concurrent scenarios.
     *
     * @param entry Tainted object.
     */
    @Override
    public void put(final @Nonnull TaintedObject entry) {
      final int index = index(entry.positiveHashCode);
      TaintedObject cur = head(index);
      if (cur == null) {
        table[index] = entry;
      } else {
        TaintedObject next;
        while ((next = next(cur)) != null) {
          if (cur.positiveHashCode == entry.positiveHashCode && cur.get() == entry.get()) {
            // Duplicate, exit early.
            return;
          }
          cur = next;
        }
        cur.next = entry;
      }
    }

    @Override
    public void clear() {
      Arrays.fill(table, null);
    }

    /** Gets the head of the bucket removing intermediate GC'ed objects */
    @Nullable
    private TaintedObject head(final int index) {
      final TaintedObject head = firstAliveReference(table[index]);
      table[index] = head;
      return head;
    }

    /** Gets the next tainted object removing intermediate GC'ed objects */
    @Nullable
    private TaintedObject next(@Nonnull final TaintedObject item) {
      final TaintedObject next = firstAliveReference(item.next);
      item.next = next;
      return next;
    }

    /**
     * Purge entries that have been garbage collected. Only one concurrent call to this method is
     * allowed, further concurrent calls will be ignored.
     */
    void purge() {
      // Ensure we enter only once concurrently.
      if (!purge.acquire()) {
        return;
      }

      try {
        for (int index = 0; index < table.length; index++) {
          TaintedObject cur = head(index);
          if (cur != null) {
            TaintedObject next;
            while ((next = next(cur)) != null) {
              cur = next;
            }
          }
        }
      } finally {
        // Reset purging flag.
        purge.release();
      }
    }

    /** Gets the first reachable reference that has not been GC'ed */
    @Nullable
    private TaintedObject firstAliveReference(@Nullable TaintedObject item) {
      while (item != null && isReleased(item)) {
        item = item.next;
      }
      return item;
    }

    /** Overriden only for testing references that are garbage collected */
    protected boolean isReleased(final TaintedObject to) {
      return to.get() == null;
    }

    private int indexObject(final Object obj) {
      return index(positiveHashCode(System.identityHashCode(obj)));
    }

    private int positiveHashCode(final int h) {
      return h & POSITIVE_MASK;
    }

    private int index(int h) {
      return h & lengthMask;
    }

    private Iterator<TaintedObject> iterator(final int start, final int stop) {
      return new Iterator<TaintedObject>() {
        int currentIndex = start;
        @Nullable TaintedObject currentSubPos;

        @Override
        public boolean hasNext() {
          if (currentSubPos != null) {
            return true;
          }
          for (; currentIndex < stop; currentIndex++) {
            if (table[currentIndex] != null) {
              return true;
            }
          }
          return false;
        }

        @Override
        public TaintedObject next() {
          if (currentSubPos != null) {
            TaintedObject toReturn = currentSubPos;
            currentSubPos = toReturn.next;
            return toReturn;
          }
          for (; currentIndex < stop; currentIndex++) {
            final TaintedObject entry = table[currentIndex];
            if (entry != null) {
              currentSubPos = entry.next;
              currentIndex++;
              return entry;
            }
          }
          throw new NoSuchElementException();
        }
      };
    }

    @Nonnull
    @Override
    public Iterator<TaintedObject> iterator() {
      return iterator(0, table.length);
    }
  }

  class DebugTaintedMap extends TaintedMapImpl {

    private static final Logger LOGGER = LoggerFactory.getLogger(DebugTaintedMap.class);

    /** Number of puts before we start computing statistics about the map */
    private static final int COMPUTE_STATISTICS_COUNT = 1 << 12;

    private final AtomicLong puts = new AtomicLong(0);
    private final AtomicLong removed = new AtomicLong(0);
    private final int statsInterval;

    DebugTaintedMap() {
      this(DEFAULT_CAPACITY);
    }

    DebugTaintedMap(final int capacity) {
      this(capacity, COMPUTE_STATISTICS_COUNT);
    }

    DebugTaintedMap(final int capacity, final int statsInterval) {
      super(capacity);
      this.statsInterval = statsInterval;
    }

    @Override
    public void put(@Nonnull final TaintedObject entry) {
      super.put(entry);
      if (puts.incrementAndGet() % statsInterval == 0) {
        computeStatistics();
      }
    }

    @Override
    protected boolean isReleased(final TaintedObject to) {
      final boolean released = super.isReleased(to);
      if (released) {
        removed.incrementAndGet();
      }
      return released;
    }

    private void computeStatistics() {
      if (!LOGGER.isDebugEnabled()) {
        return;
      }
      final int[] chains = new int[11];
      int overflowedChains = 0;
      long putsExecuted = this.puts.get();
      long removedEntries = this.removed.get();
      long staleEntries = 0;
      long actualSize = 0;
      for (int bucket = 0; bucket < table.length; bucket++) {
        TaintedObject cur = table[bucket];
        int chainLength = 0;
        while (cur != null) {
          actualSize++;
          chainLength++;
          if (cur.get() == null) {
            staleEntries++;
          }
          cur = cur.next;
        }
        if (chainLength >= chains.length) {
          overflowedChains++;
        } else {
          chains[chainLength] += 1;
        }
      }
      final long expectedSize = putsExecuted - removedEntries;
      final StringBuilder message = new StringBuilder();
      message.append("Size [expected:").append(expectedSize).append(", actual:").append(actualSize);
      if (expectedSize != actualSize) {
        message.append(", lost: ").append(pct(expectedSize - actualSize, expectedSize)).append("%");
      }
      if (staleEntries > 0) {
        message.append(", stale: ").append(pct(staleEntries, actualSize)).append("%");
      }
      message.append("]");
      message.append(", Chain [");
      final long totalLengths = Arrays.stream(chains).sum() + overflowedChains;
      for (int length = 0; length < chains.length; length++) {
        message.append(length).append(": ").append(pct(chains[length], totalLengths)).append("%, ");
      }
      if (overflowedChains > 0) {
        message.append(" overflow: ").append(overflowedChains);
      }
      message.append("]");
      LOGGER.debug(message.toString());
    }

    private static double pct(final long received, final long expected) {
      return Math.round(received * 10000L / (double) expected) / 100D;
    }
  }
}
