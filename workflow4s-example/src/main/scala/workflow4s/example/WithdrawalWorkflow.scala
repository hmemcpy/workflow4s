package workflow4s.example

import cats.effect.IO
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxEitherId}
import workflow4s.example.WithdrawalEvent.{MoneyLocked, WithdrawalAccepted, WithdrawalRejected}
import workflow4s.example.WithdrawalService.ExecutionResponse
import workflow4s.example.WithdrawalSignal.{CreateWithdrawal, ExecutionCompleted}
import workflow4s.example.WithdrawalWorkflow.{createWithdrawalSignal, dataQuery, executionCompletedSignal}
import workflow4s.example.checks.{ChecksEngine, ChecksInput, ChecksState, Decision}
import workflow4s.wio.{SignalDef, WorkflowContext}

object WithdrawalWorkflow {

  object WithdrawalWorkflowContext extends WorkflowContext {}

  val createWithdrawalSignal   = SignalDef[CreateWithdrawal, Unit]()
  val dataQuery                = SignalDef[Unit, WithdrawalData]()
  val executionCompletedSignal = SignalDef[ExecutionCompleted, Unit]()
}

class WithdrawalWorkflow(service: WithdrawalService, checksEngine: ChecksEngine) {

  import WithdrawalWorkflow.WithdrawalWorkflowContext.WIO

  val workflow: WIO[WithdrawalData.Empty, Nothing, Unit] = for {
    _ <- handleDataQuery(
           (for {
             _ <- receiveWithdrawal
             _ <- calculateFees
             _ <- putMoneyOnHold
             _ <- runChecks
             _ <- execute
             _ <- releaseFunds
           } yield ())
             .handleErrorWith(cancelFundsIfNeeded),
         )
    _ <- handleDataQuery(WIO.Noop())
  } yield ()

  val workflowDeclarative: WIO[WithdrawalData.Empty, Nothing, Unit] =
    handleDataQuery(
      (
        receiveWithdrawal >>>
          calculateFees >>>
          putMoneyOnHold >>>
          runChecks >>>
          execute >>>
          releaseFunds
      ).handleErrorWith(cancelFundsIfNeeded) >>> WIO.Noop(),
    )

  private def receiveWithdrawal: WIO[WithdrawalData.Empty, WithdrawalRejection.InvalidInput, WithdrawalData.Initiated] =
    WIO
      .handleSignal[WithdrawalData.Empty](createWithdrawalSignal) { (_, signal) =>
        IO {
          if (signal.amount > 0) WithdrawalAccepted(signal.amount, signal.recipient)
          else WithdrawalRejected("Amount must be positive")
        }
      }
      .handleEventWithError { (st, event) =>
        event match {
          case WithdrawalAccepted(amount, recipient) => st.initiated(amount, recipient).asRight
          case WithdrawalRejected(error)             => WithdrawalRejection.InvalidInput(error).asLeft
        }
      }
      .produceResponse((_, _) => ())
      .autoNamed()

  private def calculateFees: WIO[WithdrawalData.Initiated, Nothing, WithdrawalData.Validated] = WIO
    .runIO[WithdrawalData.Initiated](state => service.calculateFees(state.amount).map(WithdrawalEvent.FeeSet.apply))
    .handleEvent { (state, event) => state.validated(event.fee) }
    .autoNamed()

  private def putMoneyOnHold: WIO[WithdrawalData.Validated, WithdrawalRejection.NotEnoughFunds, WithdrawalData.Validated] =
    WIO
      .runIO[WithdrawalData.Validated](state =>
        service
          .putMoneyOnHold(state.amount)
          .map({
            case Left(WithdrawalService.NotEnoughFunds()) => WithdrawalEvent.MoneyLocked.NotEnoughFunds()
            case Right(_)                                 => WithdrawalEvent.MoneyLocked.Success()
          }),
      )
      .handleEventWithError { (state, evt) =>
        evt match {
          case MoneyLocked.Success()        => state.asRight
          case MoneyLocked.NotEnoughFunds() => WithdrawalRejection.NotEnoughFunds().asLeft
        }
      }
      .autoNamed()

  private def runChecks: WIO[WithdrawalData.Validated, WithdrawalRejection.RejectedInChecks, WithdrawalData.Checked] = {
    val doRunChecks: WIO[WithdrawalData.Validated, Nothing, WithdrawalData.Checked] =
      WIO.embed(
        checksEngine.runChecks
          .transformInput((x: WithdrawalData.Validated) => ChecksInput(x, List()))
          .transformOutput((validated, checkState) => validated.checked(checkState)),
      )

    val actOnDecision = WIO
      .pure[WithdrawalData.Checked]
      .makeError(_.checkResults.decision match {
        case Decision.RejectedBySystem()   => Some(WithdrawalRejection.RejectedInChecks())
        case Decision.ApprovedBySystem()   => None
        case Decision.RejectedByOperator() => Some(WithdrawalRejection.RejectedInChecks())
        case Decision.ApprovedByOperator() => None
      })

    doRunChecks >>> actOnDecision
  }

  // TODO can fail with provider fatal failure, need retries, needs cancellation
  private def execute: WIO[WithdrawalData.Checked, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    initiateExecution >>> awaitExecutionCompletion

  private def initiateExecution: WIO[WithdrawalData.Checked, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    WIO
      .runIO[WithdrawalData.Checked](s =>
        service
          .initiateExecution(s.netAmount, s.recipient)
          .map(WithdrawalEvent.ExecutionInitiated.apply),
      )
      .handleEventWithError((s, event) =>
        event.response match {
          case ExecutionResponse.Accepted(externalId) => Right(s.executed(externalId))
          case ExecutionResponse.Rejected(error)      => Left(WithdrawalRejection.RejectedByExecutionEngine(error))
        },
      )
      .autoNamed()

  private def awaitExecutionCompletion: WIO[WithdrawalData.Executed, WithdrawalRejection.RejectedByExecutionEngine, WithdrawalData.Executed] =
    WIO
      .handleSignal[WithdrawalData.Executed](executionCompletedSignal)((_, sig) => IO(WithdrawalEvent.ExecutionCompleted(sig)))
      .handleEventWithError((s, e: WithdrawalEvent.ExecutionCompleted) =>
        e.status match {
          case ExecutionCompleted.Succeeded => Right(s)
          case ExecutionCompleted.Failed    => Left(WithdrawalRejection.RejectedByExecutionEngine("Execution failed"))
        },
      )
      .produceResponse((_, _) => ())
      .autoNamed()

  private def releaseFunds: WIO[WithdrawalData.Executed, Nothing, WithdrawalData.Completed] =
    WIO
      .runIO[WithdrawalData.Executed](st => service.releaseFunds(st.amount).as(WithdrawalEvent.MoneyReleased()))
      .handleEvent((st, _) => st.completed())
      .autoNamed()

  private def handleDataQuery =
    WIO.handleQuery[WithdrawalData](dataQuery) { (state, _) => state }

  private def cancelFundsIfNeeded: WIO[(WithdrawalData, WithdrawalRejection), Nothing, WithdrawalData.Completed.Failed] = {
    WIO
      .runIO[(WithdrawalData, WithdrawalRejection)]({ case (_, r) =>
        r match {
          case WithdrawalRejection.InvalidInput(error)              => WithdrawalEvent.RejectionHandled(error).pure[IO]
          case WithdrawalRejection.NotEnoughFunds()                 => WithdrawalEvent.RejectionHandled("Not enough funds on the user's account").pure[IO]
          case WithdrawalRejection.RejectedInChecks()               =>
            service.cancelFundsLock().as(WithdrawalEvent.RejectionHandled("Transaction rejected in checks"))
          case WithdrawalRejection.RejectedByExecutionEngine(error) => service.cancelFundsLock().as(WithdrawalEvent.RejectionHandled(error))
        }
      })
      .handleEvent((_: (WithdrawalData, WithdrawalRejection), evt) => WithdrawalData.Completed.Failed(evt.error))
      .autoNamed()
  }
}
