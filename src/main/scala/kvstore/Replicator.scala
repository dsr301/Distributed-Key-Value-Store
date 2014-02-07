package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration._
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.actor.Terminated
import akka.actor.ReceiveTimeout

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)

  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)
  

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import Replicator._
  import Replica._
  import context.dispatcher

  val cancellable =
    context.system.scheduler.schedule(100 milliseconds, 100 milliseconds)(sendR)

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Snapshot]

  var _seqCounter = 0L
  def nextSeq = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  /* TODO Behavior for the Replicator. */
  def receive: Receive = {
    case r @ Replicate(k, v, i) => {
      val rep = r
      val ns = nextSeq
      replica ! Snapshot(k, v, ns)
      acks += (ns -> (sender, rep))
//      sendR()
    }

    case SnapshotAck(k, s) => {

     acks get s map {x=>
      x._1 ! Replicated(k, x._2.id)  
      }
     acks -= s
      
    }

    case TerminateReplica => {
       val s = self
       acks foreach { kl => kl._2._1 ! TerminateReplicator(s,replica,kl._1)}
//      context.parent ! TerminateReplicator(replica)
      cancellable.cancel;
      context.stop(self)

    }

  }

  def sendR() = {
    acks foreach { kl => replica ! Snapshot(kl._2._2.key, kl._2._2.valueOption,kl._1) }
  }

}
