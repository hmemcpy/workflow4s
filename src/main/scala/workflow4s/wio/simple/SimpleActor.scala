package workflow4s.wio.simple

import cats.effect.unsafe.IORuntime
import workflow4s.wio.Interpreter.EventResponse
import workflow4s.wio.{ActiveWorkflow, QueryResponse, SignalDef, SignalResponse}

class SimpleActor[State](private var wf: ActiveWorkflow[State, Any])(implicit IORuntime: IORuntime) {

  def handleSignal[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): SimpleActor.SignalResponse[Resp] =
    wf.handleSignal(signalDef)(req) match {
      case SignalResponse.Ok(value)          =>
        val newWf = value.unsafeRunSync()
        wf = newWf
        SimpleActor.SignalResponse.Ok(newWf.value)
      case SignalResponse.UnexpectedSignal() => SimpleActor.SignalResponse.UnexpectedSignal
    }
  def handleQuery[Req, Resp](signalDef: SignalDef[Req, Resp])(req: Req): QueryResponse[Resp]               =
    wf.handleQuery(signalDef)(req)

  def handleEvent(event: Any): SimpleActor.EventResponse = wf.handleEvent(event) match {
    case EventResponse.Ok(newFlow) =>
      wf = newFlow
      SimpleActor.EventResponse.Ok
    case EventResponse.UnexpectedEvent() => SimpleActor.EventResponse.UnexpectedEvent
  }

}

object SimpleActor {
  sealed trait SignalResponse[+Resp]
  object SignalResponse {
    case class Ok[Resp](result: Resp) extends SignalResponse[Resp]
    case object UnexpectedSignal      extends SignalResponse[Nothing]
  }

  sealed trait EventResponse
  object EventResponse{
    case object Ok extends EventResponse
    case object UnexpectedEvent extends EventResponse
  }
}
