package com.avsystem.commons
package rpc.akka.server

import akka.actor.{Actor, ActorLogging, Props}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import com.avsystem.commons.concurrent.RunNowEC
import com.avsystem.commons.rpc.akka._
import monifu.concurrent.Scheduler
import monifu.reactive.{Ack, Observable}

import scala.util.{Failure, Success}

/**
  * @author Wojciech Milewski
  */
private final class ServerActor(rawRPC: AkkaRPCFramework.RawRPC, config: AkkaRPCServerConfig) extends Actor with ActorLogging {

  override def receive: Receive = {
    case msg@ProcedureInvocationMessage(name, argLists, getterChain) =>
      resolveRpc(msg).fire(name, argLists)
    case msg@FunctionInvocationMessage(name, argLists, getterChain) =>
      import com.avsystem.commons.concurrent.RunNowEC.Implicits.executionContext
      val s = sender()
      resolveRpc(msg).call(name, argLists).onComplete {
        case Success(value) => s ! InvocationSuccess(value)
        case Failure(e) =>
          logError(e, name)
          s ! InvocationFailure(e.getClass.getCanonicalName, e.getMessage)
      }
    case msg@ObservableInvocationMessage(name, argLists, getterChain) =>
      implicit val scheduler = Scheduler(RunNowEC)
      implicit val timeout = Timeout(config.observableAckTimeout)
      val s = sender()

      val heartbeat = Observable.timerRepeated(config.heartbeatInterval, config.heartbeatInterval, MonifuProtocol.Heartbeat)
        .subscribe { beat =>
          s ! beat
          Ack.Continue
        }

      resolveRpc(msg).observe(name, argLists).subscribe(
        value => {
          val result = s ? InvocationSuccess(value)
          //noinspection NestedStatefulMonads
          result.mapTo[MonifuProtocol.RemoteAck].map {
            case MonifuProtocol.Continue => Ack.Continue
            case MonifuProtocol.Cancel =>
              heartbeat.cancel()
              Ack.Cancel
          }.recover {
            case e: AskTimeoutException =>
              heartbeat.cancel()
              log.error(e, "Client actor didn't respond within requested time.")
              Ack.Cancel
          }
        },
        e => {
          heartbeat.cancel()
          logError(e, name)
          s ! InvocationFailure(e.getClass.getCanonicalName, e.getMessage)
        },
        () => {
          heartbeat.cancel()
          s ! MonifuProtocol.StreamCompleted
        }
      )
  }

  private def resolveRpc(msg: InvocationMessage) = rawRPC.resolveGetterChain(msg.getterChain.map(r => AkkaRPCFramework.RawInvocation(r.rpcName, r.argLists)).toList)

  private def logError(e: Throwable, methodName: String): Unit = {
    log.error(e,
      """
        |Server exception. Remote method called: {}.
        |Original message: {}.
      """.stripMargin, methodName, e.getMessage)
  }
}

private[akka] object ServerActor {
  def props(rawRPC: AkkaRPCFramework.RawRPC, config: AkkaRPCServerConfig): Props = Props(new ServerActor(rawRPC, config))
}