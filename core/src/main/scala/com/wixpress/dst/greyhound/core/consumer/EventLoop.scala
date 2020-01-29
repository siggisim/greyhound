package com.wixpress.dst.greyhound.core.consumer

import com.wixpress.dst.greyhound.core.Topic
import com.wixpress.dst.greyhound.core.metrics.GreyhoundMetric.GreyhoundMetrics
import com.wixpress.dst.greyhound.core.metrics.{GreyhoundMetric, Metrics}
import zio._
import zio.clock.Clock
import zio.duration._

import scala.collection.JavaConverters._

trait EventLoop[-R] {
  def pause: URIO[R, Unit]
  def resume: URIO[R, Unit]
}

object EventLoop {
  type Handler[-R] = RecordHandler[R, Nothing, Chunk[Byte], Chunk[Byte]]

  // TODO handle consumer failures
  def make[R1, R2](consumer: Consumer[R1],
                   handler: Handler[R2],
                   config: EventLoopConfig = EventLoopConfig.Default): RManaged[R1 with R2 with GreyhoundMetrics with Clock, EventLoop[R1 with R2 with GreyhoundMetrics]] = {
    Offsets.make.toManaged_.flatMap { offsets =>
      val handle = handler.andThen(offsets.update).handle(_)
      Dispatcher.make(handle, config.lowWatermark, config.highWatermark).flatMap { dispatcher =>
        start(consumer, dispatcher, handler.topics, offsets, config).toManaged {
          case (state, fiber) =>
            (for {
              _ <- Metrics.report(StoppingEventLoop)
              _ <- state.set(EventLoopState.ShuttingDown)
              drained <- fiber.join.timeout(config.drainTimeout)
              _ <- ZIO.when(drained.isEmpty)(Metrics.report(DrainTimeoutExceeded))
              _ <- commitOffsets(consumer, offsets)
            } yield ()).orDie
        }.map {
          case (state, _) =>
            new EventLoop[R1 with R2 with GreyhoundMetrics] {
              // TODO should we pause all assigned partitions eagerly?
              // TODO should we return a promise / suspend until paused?
              override def pause: URIO[R1 with R2 with GreyhoundMetrics, Unit] =
                Metrics.report(PausingEventLoop) *>
                  state.updateSome {
                    case EventLoopState.Running =>
                      EventLoopState.Paused
                  }.unit

              override def resume: URIO[R1 with R2 with GreyhoundMetrics, Unit] =
                Metrics.report(ResumingEventLoop) *>
                  state.updateSome {
                    case EventLoopState.Paused =>
                      EventLoopState.Running
                  }.unit
            }
        }
      }
    }
  }

  private def start[R1, R2](consumer: Consumer[R1],
                            dispatcher: Dispatcher[R2],
                            topics: Set[Topic],
                            offsets: Offsets,
                            config: EventLoopConfig) =
    for {
      _ <- Metrics.report(StartingEventLoop)
      state <- Ref.make[EventLoopState](EventLoopState.Running)
      partitionsAssigned <- Promise.make[Nothing, Unit]
      _ <- consumer.subscribe(
        topics = topics,
        onPartitionsAssigned = { _ =>
          partitionsAssigned.succeed(()).unit
        })
      fiber <- loop(state, consumer, dispatcher, Map.empty, Set.empty, offsets, config).fork
      _ <- partitionsAssigned.await
    } yield (state, fiber)

  private def loop[R1, R2](state: Ref[EventLoopState],
                           consumer: Consumer[R1],
                           dispatcher: Dispatcher[R2],
                           pending: Map[TopicPartition, UIO[Unit]],
                           paused: Set[TopicPartition],
                           offsets: Offsets,
                           config: EventLoopConfig): RIO[R1 with R2 with GreyhoundMetrics, Unit] =
    state.get.flatMap {
      case EventLoopState.Running =>
        for {
          newPaused1 <- resumePartitions(consumer, dispatcher, paused)
          (newPending, newPaused2) <- pollAndHandle(consumer, dispatcher, pending, newPaused1, config)
          _ <- commitOffsets(consumer, offsets)
          result <- loop(state, consumer, dispatcher, newPending, newPaused2, offsets, config)
        } yield result

      case EventLoopState.Paused =>
        pollPaused(consumer, config).flatMap { newPaused =>
          loop(state, consumer, dispatcher, pending, paused union newPaused, offsets, config)
        }

      case EventLoopState.ShuttingDown =>
        // Await for all pending tasks before closing the fiber
        ZIO.foreach_(pending)(_._2)
    }

  private def resumePartitions[R1, R2](consumer: Consumer[R1],
                                       dispatcher: Dispatcher[R2],
                                       paused: Set[TopicPartition]) =
    for {
      partitionsToResume <- dispatcher.resumeablePartitions(paused)
      _ <- consumer.resume(partitionsToResume)
    } yield paused diff partitionsToResume

  private def pollAndHandle[R1, R2](consumer: Consumer[R1],
                                    dispatcher: Dispatcher[R2],
                                    pending: Map[TopicPartition, UIO[Unit]],
                                    paused: Set[TopicPartition],
                                    config: EventLoopConfig) =
    consumer.poll(config.pollTimeout).flatMap { records =>
      ZIO.foldLeft(records.asScala)((pending, paused)) {
        case ((accPending, accPaused), kafkaRecord) =>
          val record = ConsumerRecord(kafkaRecord)
          val partition = TopicPartition(record)
          if (accPaused contains partition) {
            Metrics.report(PartitionThrottled(partition))
              .as((accPending, accPaused))
          } else {
            dispatcher.submit(record).flatMap {
              case SubmitResult.Submitted(awaitCompletion) =>
                ZIO.succeed((accPending + (partition -> awaitCompletion), accPaused))

              case SubmitResult.Rejected =>
                Metrics.report(HighWatermarkReached(partition)) *>
                  consumer.pause(record).as((accPending, accPaused + partition))
            }
          }
      }
    }

  private def pollPaused[R1, R2](consumer: Consumer[R1], config: EventLoopConfig) =
    consumer.poll(config.pollTimeout).flatMap { records =>
      ZIO.foldLeft(records.asScala)(Set.empty[TopicPartition]) { (acc, kafkaRecord) =>
        val record = ConsumerRecord(kafkaRecord)
        val partition = TopicPartition(record)
        if (acc contains partition) ZIO.succeed(acc)
        else consumer.pause(record).as(acc + partition)
      }
    }

  private def commitOffsets[R](consumer: Consumer[R], offsets: Offsets) =
    offsets.committable.flatMap(consumer.commit)

}

case class EventLoopConfig(pollTimeout: Duration,
                           drainTimeout: Duration,
                           lowWatermark: Int,
                           highWatermark: Int)

object EventLoopConfig {
  val Default = EventLoopConfig(
    pollTimeout = 100.millis,
    drainTimeout = 30.seconds,
    lowWatermark = 128,
    highWatermark = 256)
}

sealed trait EventLoopMetric extends GreyhoundMetric
case object StartingEventLoop extends EventLoopMetric
case object PausingEventLoop extends EventLoopMetric
case object ResumingEventLoop extends EventLoopMetric
case object StoppingEventLoop extends EventLoopMetric
case object DrainTimeoutExceeded extends EventLoopMetric
case class HighWatermarkReached(partition: TopicPartition) extends EventLoopMetric
case class PartitionThrottled(partition: TopicPartition) extends EventLoopMetric

sealed trait EventLoopState

object EventLoopState {
  case object Running extends EventLoopState
  case object Paused extends EventLoopState
  case object ShuttingDown extends EventLoopState
}
