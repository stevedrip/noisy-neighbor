package noisyneighbor.example

import cats.effect.{ExitCode, IO, IOApp}
import fs2.Stream
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scodec.bits.BitVector

import Input.Message
import Session.Job

/** An interactive example. The trie is given as absolute labels, messages can
  * be given in a file, and stdin stays open for more, one per line, in real
  * time. It stops when stdin ends (Ctrl-D) and everything has been consumed.
  *
  * {{{
  * sbt "example/runMain noisyneighbor.example.InteractiveExample --labels 01,1,10 --messages example/mixed.csv"
  * }}}
  *
  * A message is `key,produceSeconds,consumeSeconds`, such as `01,1,8`. The key
  * is a string of 0 and 1, and may be empty, as in `,1,8`.
  */
object InteractiveExample extends IOApp:
  val usage: String =
    """Usage: InteractiveExample [--labels a,b,c] [--messages file.csv] [--batch n]
      |
      |  --labels    absolute labels of the queues, such as 01,1,10. The root
      |              exists without being listed. Default: only the root.
      |  --messages  a file of messages to produce first, one after the other.
      |  --batch     the most messages a queue hands out at once. Default: 1.
      |
      |A message is key,produceSeconds,consumeSeconds, such as 01,1,8. The key
      |is a string of 0 and 1, and may be empty. Lines starting with # are
      |ignored. Messages typed on stdin are produced as they are read, until
      |Ctrl-D.""".stripMargin

  final case class Options(
    labels: List[BitVector] = Nil,
    messages: Option[String] = None,
    batch: Int = 1
  )

  def parse(
    args: List[String],
    options: Options = Options()
  ): Either[String, Options] =
    args match
      case Nil => Right(options)
      case "--labels" :: csv :: rest =>
        Input.labels(csv).flatMap(l => parse(rest, options.copy(labels = l)))
      case "--messages" :: path :: rest =>
        parse(rest, options.copy(messages = Some(path)))
      case "--batch" :: n :: rest =>
        n.toIntOption
          .filter(_ > 0)
          .toRight(s"\"$n\" is not a batch size")
          .flatMap(b => parse(rest, options.copy(batch = b)))
      case other => Left(s"unexpected arguments: ${other.mkString(" ")}")

  def initial(options: Options): IO[Either[String, List[Message]]] =
    options.messages match
      case None => IO.pure(Right(Nil))
      case Some(path) =>
        IO.blocking(Files.readAllLines(Path.of(path)).asScala.toList)
          .attempt
          .map(
            _.left
              .map(e => s"cannot read $path: ${e.getMessage}")
              .flatMap(Input.messages(_).left.map(e => s"$path, $e"))
          )

  /** The lines of stdin, until it ends. */
  val stdin: Stream[IO, String] =
    Stream
      .repeatEval(IO.blocking(Option(scala.io.StdIn.readLine())))
      .unNoneTerminate

  def run(args: List[String]): IO[ExitCode] =
    def fail(error: String): IO[ExitCode] =
      IO.consoleForIO.errorln(s"$error\n\n$usage").as(ExitCode.Error)

    parse(args) match
      case Left(error) => fail(error)
      case Right(options) =>
        initial(options).flatMap:
          case Left(error) => fail(error)
          case Right(messages) =>
            for
              trie <- Input.trie[Job](options.labels)
              queues <- trie.queues
              log <- Session.logger
              _ <- IO.println(
                s"queues: ${queues.map(q => s"\"${q.toBin}\"").mkString(", ")}"
              )
              _ <- IO.println(
                s"${messages.size} messages to produce first. Type key,produceSeconds,consumeSeconds lines, and end with Ctrl-D."
              )
              _ <- Session.run(trie, messages, stdin, options.batch, log)
            yield ExitCode.Success
