import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.Rule
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.BatchMessageListener
import org.springframework.kafka.listener.KafkaMessageListenerContainer
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.rule.EmbeddedKafkaRule
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.kafka.test.utils.KafkaTestUtils
import spock.lang.Unroll

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.ConfigDefaults.DEFAULT_KAFKA_CLIENT_PROPAGATION_ENABLED
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

class KafkaClientTest extends AgentTestRunner {
  static final SHARED_TOPIC = "shared.topic"

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @Rule
  EmbeddedKafkaRule kafkaRule = new EmbeddedKafkaRule(1, true, SHARED_TOPIC)
  EmbeddedKafkaBroker embeddedKafka = kafkaRule.embeddedKafka

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.kafka.e2e.duration.enabled", "true")
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(CheckpointValidationMode.INTERVALS)
  }

  def "test kafka produce and consume"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    Producer<String, String> producer = new KafkaProducer<>(producerProps, new StringSerializer(), new StringSerializer())

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = containerProperties()

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    String greeting = "Hello Spring Kafka Sender!"
    runUnderTrace("parent") {
      producer.send(new ProducerRecord(SHARED_TOPIC, greeting)) { meta, ex ->
        assert activeScope().isAsyncPropagating()
        if (ex == null) {
          runUnderTrace("producer callback") {}
        } else {
          runUnderTrace("producer exception: " + ex) {}
        }
      }
      blockUntilChildSpansFinished(2)
    }


    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null

    assertTraces(2) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
      }
      trace(1) {
        // CONSUMER span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[2]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            // TODO - test with and without feature enabled once Config is easier to control
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }

            defaultTags(true)
          }
        }
      }
    }

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[0][2].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[0][2].spanId}"

    cleanup:
    producer.close()
    container?.stop()
  }

  def "test spring kafka template produce and consume"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = containerProperties()

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    String greeting = "Hello Spring Kafka Sender!"
    runUnderTrace("parent") {
      kafkaTemplate.send(SHARED_TOPIC, greeting).addCallback({
        runUnderTrace("producer callback") {}
      }, { ex ->
        runUnderTrace("producer exception: " + ex) {}
      })
      blockUntilChildSpansFinished(2)
    }


    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == greeting
    received.key() == null

    assertTraces(2) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
      }
      trace(1) {
        // CONSUMER span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[2]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            // TODO - test with and without feature enabled once Config is easier to control
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }

            defaultTags(true)
          }
        }
      }
    }

    def headers = received.headers()
    headers.iterator().hasNext()
    new String(headers.headers("x-datadog-trace-id").iterator().next().value()) == "${TEST_WRITER[0][2].traceId}"
    new String(headers.headers("x-datadog-parent-id").iterator().next().value()) == "${TEST_WRITER[0][2].spanId}"

    cleanup:
    producerFactory.destroy()
    container?.stop()
  }


  def "test pass through tombstone"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = containerProperties()

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    kafkaTemplate.send(SHARED_TOPIC, null)


    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)
    received.value() == null
    received.key() == null

    assertTraces(2) {
      trace(1) {
        // PRODUCER span 0
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.TOMBSTONE" true
            defaultTags()
          }
        }
      }
      trace(1) {
        // CONSUMER span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.TOMBSTONE" true
            // TODO - test with and without feature enabled once Config is easier to control
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }

            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    producerFactory.destroy()
    container?.stop()
  }

  def "test records(TopicPartition) kafka consume"() {
    setup:

    // set up the Kafka consumer properties
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(producerProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def greeting = "Hello from MockConsumer!"
    producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, greeting))

    then:
    TEST_WRITER.waitForTraces(1)
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
    def pollResult = KafkaTestUtils.getRecords(consumer)

    def recs = pollResult.records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).iterator()

    def first = null
    if (recs.hasNext()) {
      first = recs.next()
    }

    then:
    recs.hasNext() == false
    first.value() == greeting
    first.key() == null

    assertTraces(2) {
      trace(1) {
        // PRODUCER span 0
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        // CONSUMER span 0
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            // TODO - test with and without feature enabled once Config is easier to control
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }

            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()

  }

  def "test iteration backwards over ConsumerRecords"() {
    setup:
    def kafkaPartition = 0
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    def consumer = new KafkaConsumer<String, String>(consumerProperties)

    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producer = new KafkaProducer(producerProps)

    consumer.assign(Arrays.asList(new TopicPartition(SHARED_TOPIC, kafkaPartition)))

    when:
    def greetings = ["msg 1", "msg 2", "msg 3"]
    greetings.each {
      producer.send(new ProducerRecord<Integer, String>(SHARED_TOPIC, kafkaPartition, null, it))
    }

    then:
    TEST_WRITER.waitForTraces(3)
    def pollRecords = KafkaTestUtils.getRecords(consumer)

    def listIter =
      pollRecords.records(new TopicPartition(SHARED_TOPIC, kafkaPartition)).listIterator()

    then:
    def receivedSet = greetings.toSet()
    while (listIter.hasNext()) {
      listIter.next()
    }
    while (listIter.hasPrevious()) {
      def record = listIter.previous()
      receivedSet.remove(record.value())
      assert record.key() == null
    }
    receivedSet.isEmpty()

    assertTraces(9) {
      // producing traces
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          parent()
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            defaultTags(true)
          }
        }
      }

      // iterating to the end of ListIterator:
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(1)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 1
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(2)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 2
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }

      // backwards iteration over ListIterator to the beginning
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(2)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 2
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(1)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 1
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf trace(0)[0]
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }
            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    consumer.close()
    producer.close()

  }

  def "test spring kafka template produce and batch consume"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)
    def containerProperties = containerProperties()


    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()
    container.setupMessageListener(new BatchMessageListener<String, String>() {
        @Override
        void onMessage(List<ConsumerRecord<String, String>> consumerRecords) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          consumerRecords.each {
            records.add(it)
          }
        }
      })
    container.start()
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    List<String> greetings = ["msg 1", "msg 2", "msg 3"]
    runUnderTrace("parent") {
      for (g in greetings) {
        kafkaTemplate.send(SHARED_TOPIC, g).addCallback({
          runUnderTrace("producer callback") {}
        }, { ex ->
          runUnderTrace("producer exception: " + ex) {}
        })
      }
      blockUntilChildSpansFinished(2 * greetings.size())
    }

    then:
    def receivedSet = greetings.toSet()
    greetings.eachWithIndex { g, i ->
      def received = records.poll(5, TimeUnit.SECONDS)
      receivedSet.remove(received.value()) //maybe received out of order in case several partitions
      assert received.key() == null

      def headers = received.headers()
      assert headers.iterator().hasNext()

    }
    assert receivedSet.isEmpty()

    assertTraces(4) {
      trace(7) {
        basicSpan(it, "parent")
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
        basicSpan(it, "producer callback", span(0))
        span {
          serviceName "kafka"
          operationName "kafka.produce"
          resourceName "Produce Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          childOf span(0)
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_PRODUCER
            defaultTags()
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" 0
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }

            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" { it >= 0 && it < 2 }
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }

            defaultTags(true)
          }
        }
      }
      trace(1) {
        span {
          serviceName "kafka"
          operationName "kafka.consume"
          resourceName "Consume Topic $SHARED_TOPIC"
          spanType "queue"
          errored false
          tags {
            "$Tags.COMPONENT" "java-kafka"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CONSUMER
            "$InstrumentationTags.PARTITION" { it >= 0 }
            "$InstrumentationTags.OFFSET" { it >= 0 && it < 2 }
            "$InstrumentationTags.RECORD_QUEUE_TIME_MS" { it >= 0 }
            "$InstrumentationTags.RECORD_END_TO_END_DURATION_MS" { it >= 0 }

            defaultTags(true)
          }
        }
      }
    }

    cleanup:
    producerFactory.destroy()
    container?.stop()
  }

  @Unroll
  def "test kafka client header propagation manual config"() {
    setup:
    def producerProps = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString())
    def producerFactory = new DefaultKafkaProducerFactory<String, String>(producerProps)
    def kafkaTemplate = new KafkaTemplate<String, String>(producerFactory)

    // set up the Kafka consumer properties
    def consumerProperties = KafkaTestUtils.consumerProps("sender", "false", embeddedKafka)

    // create a Kafka consumer factory
    def consumerFactory = new DefaultKafkaConsumerFactory<String, String>(consumerProperties)

    // set the topic that needs to be consumed
    def containerProperties = containerProperties()

    // create a Kafka MessageListenerContainer
    def container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties)

    // create a thread safe queue to store the received message
    def records = new LinkedBlockingQueue<ConsumerRecord<String, String>>()

    // setup a Kafka message listener
    container.setupMessageListener(new MessageListener<String, String>() {
        @Override
        void onMessage(ConsumerRecord<String, String> record) {
          TEST_WRITER.waitForTraces(1) // ensure consistent ordering of traces
          records.add(record)
        }
      })

    // start the container and underlying message listener
    container.start()

    // wait until the container has the required number of assigned partitions
    ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic())

    when:
    String message = "Testing without headers"
    injectSysConfig(TraceInstrumentationConfig.KAFKA_CLIENT_PROPAGATION_ENABLED, value)
    kafkaTemplate.send(SHARED_TOPIC, message)

    then:
    // check that the message was received
    def received = records.poll(5, TimeUnit.SECONDS)

    received.headers().iterator().hasNext() == expected

    cleanup:
    producerFactory.destroy()
    container?.stop()

    where:
    value                                                    | expected
    "false"                                                  | false
    "true"                                                   | true
    String.valueOf(DEFAULT_KAFKA_CLIENT_PROPAGATION_ENABLED) | true

  }


  def containerProperties() {
    try {
      // Different class names for test and latestDepTest.
      return Class.forName("org.springframework.kafka.listener.config.ContainerProperties").newInstance(SHARED_TOPIC)
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return Class.forName("org.springframework.kafka.listener.ContainerProperties").newInstance(SHARED_TOPIC)
    }
  }

}
