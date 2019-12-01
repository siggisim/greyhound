package com.wixpress.dst.greyhound.core.consumer

import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetric.GreyhoundMetrics
import com.wixpress.dst.greyhound.core.metrics.{GreyhoundMetric, Metrics}
import com.wixpress.dst.greyhound.core.serialization.Deserializer
import com.wixpress.dst.greyhound.core.testkit.RecordMatchers.beRecordWithValue
import com.wixpress.dst.greyhound.core.testkit.{BaseTest, MessagesSink}
import com.wixpress.dst.greyhound.core.{Headers, Record, Topic, TopicName}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import zio._
import zio.clock.Clock
import zio.duration._
import zio.stream.ZSink.collectAllToSetN

class ParallelRecordHandlerTest extends BaseTest[GreyhoundMetrics with Clock] {

  override val env: Managed[Nothing, GreyhoundMetrics with Clock] = Managed.succeed {
    new GreyhoundMetrics with Clock.Live {
      override val metrics: Metrics.Service[GreyhoundMetric] =
        Metrics.Live()
    }
  }

  val stringDeserializer = Deserializer(new StringDeserializer)
  val topic = Topic[String, String]("some-topic")
  val group = "some-group"
  val headers = Headers.Empty
  val key = None

  "handle record by topic" in {
    val topic1 = Topic[String, Foo]("topic1")
    val topic2 = Topic[String, Bar]("topic2")
    val value1 = Foo("foo")
    val value2 = Bar("bar")

    for {
      sink1 <- MessagesSink.make[String, Foo]()
      spec1 <- ConsumerSpec.make(
        topic = topic1,
        group = group,
        handler = sink1.handler,
        keyDeserializer = stringDeserializer,
        valueDeserializer = stringDeserializer.map(Foo))

      sink2 <- MessagesSink.make[String, Bar]()
      spec2 <- ConsumerSpec.make(
        topic = topic2,
        group = group,
        handler = sink2.handler,
        keyDeserializer = stringDeserializer,
        valueDeserializer = stringDeserializer.map(Bar))

      handlers = Map(topic1.name -> List(spec1), topic2.name -> List(spec2))
      result <- ParallelRecordHandler.make(handlers).use {
        case (_, handler) =>
          handler.handle(Record(topic1.name, 0, 0L, headers, key, value1.bytes)) *>
            handler.handle(Record(topic2.name, 0, 0L, headers, key, value2.bytes)) *>
            (sink1.firstMessage zipPar sink2.firstMessage)
      }

      (message1, message2) = result
    } yield (message1 must beRecordWithValue(value1)) and
      (message2 must beRecordWithValue(value2))
  }

  "run all handlers for same topic" in {
    val value = "foo"

    for {
      sink1 <- MessagesSink.make[String, String]()
      spec1 <- ConsumerSpec.make(
        topic = topic,
        group = group,
        handler = sink1.handler,
        keyDeserializer = stringDeserializer,
        valueDeserializer = stringDeserializer)

      sink2 <- MessagesSink.make[String, String]()
      spec2 <- ConsumerSpec.make(
        topic = topic,
        group = group,
        handler = sink2.handler,
        keyDeserializer = stringDeserializer,
        valueDeserializer = stringDeserializer)

      specs = Map(topic.name -> List(spec1, spec2))
      result <- ParallelRecordHandler.make(specs).use {
        case (_, handler) =>
          handler.handle(Record(topic.name, 0, 0L, headers, key, value.getBytes)) *>
            (sink1.firstMessage zipPar sink2.firstMessage)
      }

      (message1, message2) = result
    } yield (message1 must beRecordWithValue(value)) and
      (message2 must beRecordWithValue(value))
  }

  "parallelize handling based on partition" in {
    // TODO test with fake clock?
    val partitions = 64

    for {
      sink <- MessagesSink.make[String, String]()
      slowHandler = sink.handler *> RecordHandler(_ => clock.sleep(1.second))
      spec <- ConsumerSpec.make(
        topic = topic,
        group = group,
        handler = slowHandler,
        keyDeserializer = stringDeserializer,
        valueDeserializer = stringDeserializer,
        parallelism = partitions)

      specs = Map(topic.name -> List(spec))
      handleResult <- ParallelRecordHandler.make(specs).use {
        case (_, handler) =>
          produceToPartitions(handler, topic.name, partitions) *>
            sink.messages.run(collectAllToSetN[Record[String, String]](partitions))
      }.timed

      (handleTime, records) = handleResult
    } yield (handleTime must beLessThan(2.seconds)) and (records must haveSize(partitions))
  }

  "update handled offsets map" in {
    val partitions = 4

    for {
      spec <- ConsumerSpec.make[Any, String, String](
        topic = topic,
        group = group,
        handler = RecordHandler(_ => ZIO.unit),
        keyDeserializer = stringDeserializer,
        valueDeserializer = stringDeserializer,
        parallelism = partitions)
      specs = Map(topic.name -> List(spec))
      result <- ParallelRecordHandler.make(specs).use {
        case (offsets, handler) =>
          produceToPartitions(handler, topic.name, partitions) *>
            offsets.get.doWhile(_.size < partitions).timeout(1.second)
      }
    } yield result must beSome(Map(
      new TopicPartition(topic.name, 0) -> 0L,
      new TopicPartition(topic.name, 1) -> 0L,
      new TopicPartition(topic.name, 2) -> 0L,
      new TopicPartition(topic.name, 3) -> 0L))
  }

  private def produceToPartitions(handler: ParallelRecordHandler.Handler,
                                  topic: TopicName,
                                  partitions: Int) =
    ZIO.foreach_(0 until partitions) { partition =>
      handler.handle(Record(topic, partition, 0L, headers, key, s"message-$partition".getBytes))
    }

}

case class Foo(foo: String) {
  def bytes: Array[Byte] = foo.getBytes
}

case class Bar(bar: String) {
  def bytes: Array[Byte] = bar.getBytes
}