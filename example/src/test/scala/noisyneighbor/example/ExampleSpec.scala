package noisyneighbor.example

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import fs2.Stream
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scodec.bits.BitVector

import Session.Job

// The session runs on the virtual clock of TestControl, so the times are exact.
class ExampleSpec extends CatsEffectSuite:
  def bits(s: String): BitVector = BitVector.fromValidBin(s)

  test("labels are absolute, and the root is not listed"):
    assertEquals(
      Input.labels("01,1,10"),
      Right(List("01", "1", "10").map(bits))
    )
    assertEquals(Input.labels(""), Right(Nil))
    assertEquals(Input.labels(" 0 , ,1"), Right(List("0", "1").map(bits)))
    assert(Input.labels("01,2").isLeft)

  def queues(csv: String): IO[List[String]] =
    Input
      .trie[Job](Input.labels(csv).toOption.get)
      .flatMap(_.queues)
      .map(_.map(_.toBin))

  test("a label deep in the trie is a queue with only the root above it"):
    queues("10").map(assertEquals(_, List("", "10")))

  test("the trie is the same whichever order the labels are given in"):
    for
      one <- queues("01,1,10")
      other <- queues("10,1,01")
    yield
      assertEquals(one, List("", "01", "1", "10"))
      assertEquals(other, List("", "01", "1", "10"))

  test("a message is a key and two numbers of seconds"):
    assertEquals(Input.message("01,1,8"), Right(Some((bits("01"), 1, 8))))
    assertEquals(Input.message(" , 0 , 7 "), Right(Some((bits(""), 0, 7))))

  test("blank lines and comments are not messages"):
    assertEquals(Input.message(""), Right(None))
    assertEquals(Input.message("  "), Right(None))
    assertEquals(Input.message("# a comment,1,2"), Right(None))

  test("a message that is wrong says what is wrong"):
    assert(Input.message("01,1").isLeft)
    assert(Input.message("01,1,8,1").isLeft)
    assert(Input.message("02,1,8").isLeft)
    assert(Input.message("01,-5,8").isLeft)
    assert(Input.message("01,fast,8").isLeft)
    assert(Input.message("01,0.5,8").isLeft)

  test("a file of messages says which line is wrong"):
    assertEquals(
      Input.messages(List("0,1,2", "# note", "", "1,2,3")),
      Right(List((bits("0"), 1, 2), (bits("1"), 2, 3)))
    )
    assert(
      Input.messages(List("0,1,2", "oops")).left.exists(_.startsWith("line 2"))
    )

  /** Runs a session, and returns the log with the second of each line. */
  def session(
    labels: String,
    initial: List[Input.Message],
    lines: Stream[IO, String]
  ): IO[List[String]] =
    Ref
      .of[IO, List[String]](Nil)
      .flatMap: logged =>
        val log: String => IO[Unit] = what =>
          IO.monotonic
            .flatMap(now => logged.update(_ :+ s"${now.toSeconds} $what"))
        TestControl.executeEmbed(
          Input
            .trie[Job](Input.labels(labels).toOption.get)
            .flatMap(Session.run(_, initial, lines, maxBatch = 1, log))
        ) *> logged.get

  test("messages in the file are produced one after the other"):
    session("", List((bits("0"), 2, 1), (bits("1"), 2, 1)), Stream.empty)
      .map: log =>
        assertEquals(
          log.filter(_.contains("produce")),
          List(
            "0 produce #1 \"0\": sleeping 2 s",
            "2 produce #1 \"0\": slept",
            "2 produce #2 \"1\": sleeping 2 s",
            "4 produce #2 \"1\": slept"
          )
        )

  test("lines typed while it runs are produced as they are read"):
    val typed =
      Stream.sleep_[IO](1.second) ++ Stream.emit("10,0,2") ++
        Stream.sleep_[IO](3.seconds) ++ Stream.emit("oops")
    // No two things happen at the same second, apart from those that follow
    // from one another, so the order of the log is the same on every run.
    session("0,1", List((bits("00"), 2, 6)), typed).map: log =>
      assertEquals(
        log.filterNot(_.contains("produce #1")),
        List(
          "1 received #2 \"10\" (produce 0 s, consume 2 s)",
          "1 produce #2 \"10\": sleeping 0 s",
          "1 produce #2 \"10\": slept",
          "1 consume #2 \"10\" on queue \"1\": sleeping 2 s",
          "2 consume #1 \"00\" on queue \"0\": sleeping 6 s",
          "3 consume #2 \"10\" on queue \"1\": slept",
          "4 ignored \"oops\": expected key,produceSeconds,consumeSeconds but found 1 fields",
          "8 consume #1 \"00\" on queue \"0\": slept",
          "8 everything consumed"
        )
      )

  test("it ends once input has ended and everything has been consumed"):
    session("", Nil, Stream.emit("0,1,5")).map: log =>
      assertEquals(log.last, "6 everything consumed")

  /** The messages of the file the example documents, read from the root of the
    * repository, where sbt runs.
    */
  val mixed: IO[List[Input.Message]] =
    IO.blocking(Files.readAllLines(Path.of("example/mixed.csv")).asScala.toList)
      .map(lines => Input.messages(lines).toOption.get)

  test("example/mixed.csv has the keys of behavior.md"):
    mixed.map: messages =>
      assertEquals(
        messages.map(_._1.toBin),
        List("", "0", "00", "01", "10", "11", "1", "")
      )

  test("example/mixed.csv with --labels 01,1,10 is the mixed scenario"):
    // Each message takes a second to produce. '11' is produced at 6 s, but is
    // consumed from 7 s, when '10' is done, although they do not conflict. ''
    // comes last, from 10 s, when everything below it is empty.
    mixed
      .flatMap(session("01,1,10", _, Stream.empty))
      .map: log =>
        assertEquals(
          log
            .filter(l => l.contains("consume") && l.contains("sleeping"))
            .toSet,
          Set(
            "1 consume #1 \"\" on queue \"\": sleeping 1 s",
            "2 consume #2 \"0\" on queue \"\": sleeping 1 s",
            "3 consume #3 \"00\" on queue \"\": sleeping 1 s",
            "4 consume #4 \"01\" on queue \"01\": sleeping 4 s",
            "5 consume #5 \"10\" on queue \"10\": sleeping 2 s",
            "7 consume #6 \"11\" on queue \"1\": sleeping 2 s",
            "9 consume #7 \"1\" on queue \"1\": sleeping 1 s",
            "10 consume #8 \"\" on queue \"\": sleeping 1 s"
          )
        )
        assertEquals(log.last, "11 everything consumed")
