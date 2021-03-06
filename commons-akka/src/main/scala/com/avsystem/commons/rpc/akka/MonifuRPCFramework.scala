package com.avsystem.commons
package rpc.akka

import com.avsystem.commons.rpc.RPCFramework
import monifu.reactive.Observable

trait MonifuRPCFramework extends RPCFramework {
  override type RawRPC <: MonifuRawRPC

  trait MonifuRawRPC {this: RawRPC =>
    def observe(rpcName: String, argLists: List[List[RawValue]]): Observable[RawValue]
  }

  implicit def ObservableRealHandler[A: Writer]: RealInvocationHandler[Observable[A], Observable[RawValue]] =
    RealInvocationHandler[Observable[A], Observable[RawValue]](_.map(write[A] _))

  implicit def ObservableRawHandler[A: Reader]: RawInvocationHandler[Observable[A]] =
    RawInvocationHandler[Observable[A]]((rawRpc, rpcName, argLists) => rawRpc.observe(rpcName, argLists).map(read[A] _))
}
