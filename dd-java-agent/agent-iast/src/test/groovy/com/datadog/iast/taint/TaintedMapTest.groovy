package com.datadog.iast.taint

import com.datadog.iast.model.Range
import com.datadog.iast.test.log.ReplaceLogger
import datadog.trace.test.util.DDSpecification
import org.junit.Rule
import org.slf4j.Logger

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TaintedMapTest extends DDSpecification {

  private Logger logger = Mock(Logger)

  @SuppressWarnings('CodeNarc')
  @Rule
  private ReplaceLogger replaceLogger = new ReplaceLogger(TaintedMap.DebugTaintedMap, 'LOGGER', logger)


  def 'simple workflow'() {
    given:
    def map = TaintedMap.build()
    final o = new Object()
    final to = new TaintedObject(o, [] as Range[])

    expect:
    map.size() == 0

    when:
    map.put(to)

    then:
    map.size() == 1

    and:
    map.get(o) != null
    map.get(o).get() == o

    when:
    map.clear()

    then:
    map.size() == 0
  }

  def 'get non-existent object'() {
    given:
    def map = TaintedMap.build()
    final o = new Object()

    expect:
    map.get(o) == null
    map.size() == 0
  }

  def 'last put always exists'() {
    given:
    int capacity = 256
    def map = TaintedMap.build(capacity)
    int nTotalObjects = capacity * 10

    expect:
    (1..nTotalObjects).each { i ->
      final o = new Object()
      final to = new TaintedObject(o, [] as Range[])
      map.put(to)
      assert map.get(o) == to
    }
  }

  def 'garbage-collected entries are purged'() {
    given:
    int capacity = 128
    final objectBuffer = Collections.newSetFromMap(new IdentityHashMap<Object, ?>(capacity))
    def map = new TaintedMap.TaintedMapImpl(capacity)

    int iters = 16
    int nObjectsPerIter = (int) (capacity / 2) - 1
    def gen = new ObjectGen(capacity)

    when:
    (1..iters).each {
      // Insert objects to be purged
      final tainteds = [] as List<TaintedObject>
      final toPurge = gen.genObjects(nObjectsPerIter).each { o ->
        def to = new TaintedObject(o, [] as Range[])
        tainteds.add(to)
        map.put(to)
      }
      tainteds.each { tainted ->
        toPurge.remove(tainted.get())
        tainted.clear()
      }

      // Insert surviving object
      final o = gen.genObjects(1)[0]
      objectBuffer.add(o)
      final to = new TaintedObject(o, [] as Range[])
      map.put(to)

      // Trigger purge
      map.purge()
    }

    then:
    map.size() == iters
    final entries = map.toList()
    entries.findAll { it.get() != null }.size() == iters

    and: 'all objects are as expected'
    objectBuffer.each { o ->
      final to = map.get(o)
      assert to != null
      assert to.get() == o
    }
  }

  def 'multi-threaded with no collisions, no GC'() {
    given:
    int capacity = 128
    def map = TaintedMap.build(capacity)

    and:
    int nThreads = 16
    int nObjectsPerThread = 1000
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)
    def buckets = gen.genBuckets(nThreads, nObjectsPerThread)

    when: 'puts from different threads to different buckets'
    def futures = (0..nThreads - 1).collect { thread ->
      executorService.submit({
        ->
        latch.countDown()
        latch.await()
        buckets[thread].each { o ->
          final to = new TaintedObject(o, [] as Range[])
          map.put(to)
        }
      } as Runnable)
    }
    futures.collect({
      it.get()
    })

    then:
    nThreads == buckets.size()

    and: 'all objects are as expected'
    buckets.each { bucket ->
      bucket.each { o ->
        assert map.get(o) != null
        assert map.get(o).get() == o
      }
    }

    cleanup:
    executorService?.shutdown()
  }

  def 'clear is thread-safe (does not throw)'() {
    given:
    int capacity = 128
    def map = TaintedMap.build(capacity)

    and:
    int nThreads = 16
    def gen = new ObjectGen(capacity)
    def executorService = Executors.newFixedThreadPool(nThreads)
    def latch = new CountDownLatch(nThreads)

    when: 'puts from different threads to any buckets'
    def futures = (0..nThreads - 1).collect { thread ->
      // Each thread has multiple objects for each bucket
      def objects = gen.genBuckets(capacity, 32).flatten()
      def taintedObjects = objects.collect { o ->
        return new TaintedObject(o, [] as Range[])
      }
      Collections.shuffle(taintedObjects)

      executorService.submit({
        ->
        latch.countDown()
        latch.await()
        taintedObjects.each { to ->
          if (System.identityHashCode(to) % 10 == 0) {
            map.clear()
          }
          map.put(to)
        }
      } as Runnable)
    }
    futures.collect({
      it.get()
    })
    map.clear()

    then:
    map.size() == 0

    cleanup:
    executorService?.shutdown()
  }

  void 'ensure stale objects are properly removed'() {
    given:
    final objects = [] as List<TaintedObject>
    final map = TaintedMap.build(1) // single bucket
    (1..10).each { objects.add(new TaintedObject("Item$it".toString(), [] as Range[])) }

    when:
    objects.each { map.put(it) }

    then:
    map.size() == objects.size()

    when:
    final toPurge = objects.findAll { it.get().toString().replaceAll('[^0-9]', '').toInteger() % 2 == 1 }
    toPurge.each { tainted -> tainted.clear() }
    objects.removeAll(toPurge)

    then: 'all objects remain'
    map.size() == 10

    when:
    final last = new TaintedObject('My newly created object', [] as Range[])
    objects.add(last)
    map.put(last)

    then:
    map.size() == objects.size()
  }

  void 'test debug implementation'() {
    given:
    final map = new TaintedMap.DebugTaintedMap(1, 1)

    when:
    final first = new TaintedObject('first', [] as Range[])
    map.put(first)

    then:
    1 * logger.isDebugEnabled() >> false
    0 * logger.debug(_ as String)
    map.size() == 1

    when:
    first.clear()
    final second = new TaintedObject('second', [] as Range[])
    map.put(second)

    then:
    1 * logger.isDebugEnabled() >> true
    1 * logger.debug({
      final message = it as String
      assert message.contains('expected:1, actual:1') : 'only one element in the map'
    })
    map.size() == 1
  }
}
