package akka.streams.impl

import scala.annotation.tailrec
import akka.streams.Operation.{ FromProducerSource, Sink, Source }
import rx.async.api.Producer
import rx.async.spi.{ Subscription, Subscriber, Publisher }
import akka.streams.impl.BasicEffects.HandleNextInSink
import akka.testkit.TestProbe
import akka.actor.ActorSystem
import org.scalatest.{ Suite, BeforeAndAfterAll }
import scala.concurrent.ExecutionContext

trait WithActorSystem extends Suite with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystem()
  override protected def afterAll(): Unit = system.shutdown()
}

trait SyncOperationSpec extends WithActorSystem {
  abstract class DoNothing extends ExternalEffect {
    def run(): Unit = ???
  }

  case class UpstreamRequestMore(n: Int) extends DoNothing
  case object UpstreamCancel extends DoNothing
  val upstream = new Upstream {
    val cancel: Effect = UpstreamCancel
    val requestMore: (Int) ⇒ Effect = UpstreamRequestMore
  }

  case class DownstreamNext[O](element: O) extends DoNothing
  case object DownstreamComplete extends DoNothing
  case class DownstreamError(cause: Throwable) extends DoNothing
  val downstream = new Downstream[Any] {
    val next: Any ⇒ Effect = DownstreamNext[Any]
    val complete: Effect = DownstreamComplete
    val error: Throwable ⇒ Effect = DownstreamError
  }

  implicit class AddRunOnce[O](result: Effect) {
    def runOnce(): Effect = result.asInstanceOf[SingleStep].runOne()
    def runToResult(trace: (Effect, Effect) ⇒ Unit = dontTrace): Effect = {
      @tailrec def rec(res: Effect): Effect =
        res match {
          case s: SingleStep ⇒ rec(s.runOne())
          case Effects(rs)   ⇒ rs.fold(Continue: Effect)(_ ~ _.runToResult(trace))
          case r             ⇒ r
        }

      val res = rec(result)
      trace(result, res)
      res
    }
  }

  val dontTrace: (Effect, Effect) ⇒ Unit = (_, _) ⇒ ()
  val printStep: (Effect, Effect) ⇒ Unit = (in, out) ⇒ println(s"$in => $out")

  trait NoOpSubscriber[I] extends Subscriber[I] {
    override def onSubscribe(subscription: Subscription): Unit = ???
    override def onNext(element: I): Unit = ???
    override def onComplete(): Unit = ???
    override def onError(cause: Throwable): Unit = ???
  }
  trait NoOpSink[-I] extends SyncSink[I] {
    def handleNext(element: I): Effect = ???
    def handleComplete(): Effect = ???
    def handleError(cause: Throwable): Effect = ???
  }
  trait NoOpSource extends SyncSource {
    override def handleCancel(): Effect = ???
    override def handleRequestMore(n: Int): Effect = ???
  }

  case class SubscribeToProducer[O](source: Producer[O], sinkConstructor: Upstream ⇒ SyncSink[O]) extends DoNothing
  case class SubscribeFrom[O](sink: Sink[O], sourceConstructor: Downstream[O] ⇒ SyncSource) extends DoNothing
  case class ExposedSource[O](source: Source[O]) extends Producer[O] {
    def getPublisher: Publisher[O] = throw new IllegalStateException("Should only be deconstructed")
  }

  case class RunEffect(body: Effect)
  val runInContextProbe = TestProbe()
  def expectThunk(): () ⇒ Effect = runInContextProbe.expectMsgType[RunEffect].body.asInstanceOf[SingleStep].runOne
  def expectAndRunContextEffect(): Effect = expectThunk()()
  object TestContextEffects extends AbstractContextEffects {

    override def subscribeTo[O](source: Source[O])(sinkConstructor: Upstream ⇒ SyncSink[O]): Effect = source match {
      case f @ FromProducerSource(i: InternalProducer[O]) ⇒ super.subscribeTo(f)(sinkConstructor)
      case FromProducerSource(p) ⇒ SubscribeToProducer(p, sinkConstructor)
      case x ⇒ super.subscribeTo(x)(sinkConstructor)
    }

    override def subscribeFrom[O](sink: Sink[O])(sourceConstructor: Downstream[O] ⇒ SyncSource): Effect = SubscribeFrom(sink, sourceConstructor)
    override def expose[O](source: Source[O]): Producer[O] = ExposedSource(source)

    override def internalProducer[O](sourceConstructor: Downstream[O] ⇒ SyncSource, shutdownEffect: Effect, initialFanOutBufferSize: Int, maxFanOutBufferSize: Int): Producer[O] =
      new InternalProducer[O] {
        override def createSource(downstream: Downstream[O]): SyncSource = sourceConstructor(downstream)
        override def getPublisher: Publisher[O] = ???
      }

    implicit def executionContext: ExecutionContext = system.dispatcher
    def runStrictInContext(effect: Effect): Unit = runInContextProbe.ref ! RunEffect(effect)

    def defaultInitialBufferSize: Int = 1
    def defaultMaxBufferSize: Int = 16
  }

  implicit class RichEffect(effect: Effect) {
    def expectHandleNextInSink[I](next: SyncSink[I]): I = effect match {
      case HandleNextInSink(`next`, value: I @unchecked) ⇒ value
      case x ⇒ throw new AssertionError(s"Expected HandleNextInSink but got $x")
    }
    def expectDownstreamNext[I](): I = effect match {
      case DownstreamNext(i: I @unchecked) ⇒ i
      case x                               ⇒ throw new AssertionError(s"Expected DownstreamNext but got $x")
    }
  }
  implicit class RichSource[I](source: Source[I]) {
    def expectInternalSourceHandler(): Downstream[I] ⇒ (SyncSource, Effect) = source match {
      case FromProducerSource(i: InternalProducer[I]) ⇒ { d ⇒
        val source = i.createSource(d)
        (source, source.start())
      }
      case x ⇒ throw new AssertionError(s"Expected InternalSource but got $x")
    }
  }

  def namedProducer[T](name: String): Producer[T] =
    new Producer[T] {
      override def toString: String = s"TestProducer<$name>"
      def getPublisher: Publisher[T] = ???
    }

  case object TestException extends RuntimeException("This is a test exception")
}
