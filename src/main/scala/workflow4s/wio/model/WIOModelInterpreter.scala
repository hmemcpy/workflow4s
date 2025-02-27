package workflow4s.wio.model

import cats.syntax.all.*
import workflow4s.wio.{VisitorModule, WorkflowContext}

trait WIOModelInterpreterModule extends VisitorModule {
  import c.WIO

  object WIOModelInterpreter {

    def run(wio: WIO[_, _, _]): WIOModel = {
      new ModelVisitor(wio, Metadata.empty).run
    }

    case class Metadata(name: Option[String], description: Option[String])

    object Metadata {
      val empty = Metadata(None, None)
    }

    private class ModelVisitor[In, Err, Out](wio: WIO[In, Err, Out], m: Metadata) extends Visitor[In, Err, Out](wio) {
      override type Result = WIOModel

      def onSignal[Sig, Evt, Resp](wio: WIO.HandleSignal[In, Out, Err, Sig, Resp, Evt]): Result           = {
        val signalName = ModelUtils.getPrettyNameForClass(wio.sigDef.reqCt)
        WIOModel.HandleSignal(signalName, wio.errorCt, m.name, m.description) // TODO error
      }
      def onRunIO[Evt](wio: WIO.RunIO[In, Err, Out, Evt]): Result                                         = {
        WIOModel.RunIO(wio.errorCt, m.name, m.description)
      }
      def onFlatMap[Out1, Err1 <: Err](wio: WIO.FlatMap[Err1, Err, Out1, Out, In]): Result                = {
        WIOModel.Sequence(Seq(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.errorCt)))
      }
      def onMap[In1, Out1](wio: WIO.Map[In1, Err, Out1, In, Out]): Result                                 = recurse(wio.base)
      def onHandleQuery[Qr, QrState, Resp](wio: WIO.HandleQuery[In, Err, Out, Qr, QrState, Resp]): Result = recurse(wio.inner)
      def onNoop(wio: WIO.Noop): Result                                                                   = WIOModel.Noop
      def onNamed(wio: WIO.Named[In, Err, Out]): Result                                                   =
        new ModelVisitor(wio.base, Metadata(wio.name.some, wio.description)).run
      def onHandleError[ErrIn](wio: WIO.HandleError[In, Err, Out, ErrIn]): Result                         = {
        WIOModel.HandleError(recurse(wio.base, None), WIOModel.Dynamic(m.name, wio.newErrorMeta), wio.handledErrorMeta)
      }
      def onHandleErrorWith[ErrIn](wio: WIO.HandleErrorWith[In, ErrIn, Out, Err]): Result                 = {
        WIOModel.HandleError(recurse(wio.base, None), recurse(wio.handleError, None), wio.handledErrorMeta)
      }
      def onAndThen[Out1](wio: WIO.AndThen[In, Err, Out1, Out]): Result                                   =
        WIOModel.Sequence(Seq(recurse(wio.first, None), recurse(wio.second, None))) // TODO flatten multiple sequences
      def onPure(wio: WIO.Pure[In, Err, Out]): Result                   = WIOModel.Pure(m.name, m.description)
      def onDoWhile[Out1](wio: WIO.DoWhile[In, Err, Out1, Out]): Result =
        WIOModel.Loop(recurse(wio.loop), None)
      def onFork(wio: WIO.Fork[In, Err, Out]): Result                   =
        WIOModel.Fork(wio.branches.map(x => WIOModel.Branch(recurse(x.wio), None)))

      def recurse[I1, E1, O1](wio: WIO[I1, E1, O1], meta: Option[Metadata] = Some(m)): WIOModel = {
        new ModelVisitor(wio, meta.getOrElse(Metadata.empty)).run
      }

    }

  }
}
