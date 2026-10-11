package noisyneighbor.example

import cats.effect.{IO, Ref}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import fs2.Stream
import noisyneighbor.{FifoTrie, MemoryFifo}
import scala.concurrent.duration.*
import scodec.bits.BitVector

import FifoTrie.Item
import Input.Message

/** Produces messages into a [[FifoTrie]] and consumes them, logging before and
  * after every sleep.
  *
  * Producing a message sleeps for its first number of seconds, and then submits
  * it. Consuming a message sleeps for its second. `IO.sleep` is used, so a
  * fiber is suspended and no thread is blocked.
  */
object Session:
  /** The number of a message, counting from 1 in the order they are read, with
    * the time it takes to consume.
    */
  final case class Job(id: Int, consumeSeconds: Int)

  /** The trie the session runs on. */
  type Queues = FifoTrie[Job, MemoryFifo[Item[Job]]]

  /** Prints each line with the seconds since this was called. */
  def logger: IO[String => IO[Unit]] =
    IO.monotonic.map: start =>
      what =>
        IO.monotonic.flatMap(now =>
          IO.println(f"[${(now - start).toMillis / 1000.0}%6.1f s] $what")
        )

  /** Runs until `lines` ends and everything has been consumed. Every queue of
    * the trie has a fiber that serves it, and one that fails fails the run.
    *
    * The `initial` messages are produced one after the other by one producer.
    * Each line of `lines` is a message in the format of [[Input.message]], and
    * gets a producer of its own as soon as it is read, so a message that takes
    * long to produce does not hold up the lines after it. A line that is not a
    * message is logged and skipped.
    */
  def run(
    trie: Queues,
    initial: List[Message],
    lines: Stream[IO, String],
    maxBatch: Int,
    log: String => IO[Unit]
  ): IO[Unit] =
    Ref
      .of[IO, Int](0)
      .flatMap: ids =>
        val nextId = ids.updateAndGet(_ + 1)

        def name(id: Int, key: BitVector): String = s"#$id \"${key.toBin}\""

        def sleeping(what: String, seconds: Int): IO[Unit] =
          log(s"$what: sleeping $seconds s") *>
            IO.sleep(seconds.seconds) *>
            log(s"$what: slept")

        def consume(queue: BitVector, items: List[Item[Job]]): IO[Unit] =
          items.traverse_(item =>
            sleeping(
              s"consume ${name(item.value.id, item.key)} on queue \"${queue.toBin}\"",
              item.value.consumeSeconds
            )
          )

        def produce(id: Int, message: Message): IO[Unit] =
          val (key, produceSeconds, consumeSeconds) = message
          sleeping(s"produce ${name(id, key)}", produceSeconds) *>
            trie.submit(key, Job(id, consumeSeconds)).void

        def read(supervisor: Supervisor[IO], line: String): IO[Unit] =
          Input.message(line) match
            case Left(error) => log(s"ignored \"$line\": $error")
            case Right(None) => IO.unit
            case Right(Some(message)) =>
              nextId.flatMap: id =>
                val (key, produceSeconds, consumeSeconds) = message
                log(
                  s"received ${name(id, key)} (produce $produceSeconds s, consume $consumeSeconds s)"
                ) *> supervisor.supervise(produce(id, message)).void

        val producing =
          for
            numbered <- initial.traverse(m => nextId.map(_ -> m))
            _ <- Supervisor[IO](await = true).use: supervisor =>
              supervisor.supervise(
                numbered.traverse_((id, m) => produce(id, m))
              ) *> lines.evalMap(read(supervisor, _)).compile.drain
            _ <- trie.untilEmpty
            _ <- log("everything consumed")
          yield ()

        for
          serving <- trie.queues.flatMap(
            _.traverse(q => trie.run(q, maxBatch)(consume).start)
          )
          // A queue whose fiber fails ends the run with that failure.
          failed = serving.parTraverse_(_.joinWithNever) >> IO.never[Unit]
          _ <- IO.race(failed, producing).guarantee(serving.traverse_(_.cancel))
        yield ()
