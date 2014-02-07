package kvstore

import akka.actor.{ OneForOneStrategy, Props, ActorRef, Actor }
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy
import akka.util.Timeout
import scala.util.Try
import scala.math
import scala.concurrent.Future
import akka.actor.ReceiveTimeout
import akka.actor.Cancellable
import akka.actor.Status

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply

  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  case class Validate(times: Int, id: Long)
  case class TerminateReplica()
  case class TerminateReplicator(replicator: ActorRef, replica: ActorRef, id: Long)
  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  //To send message to arbiter for joining
  arbiter ! Join

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (String, Option[String], Long)]

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]

  //To record for the corresponding request id and presistence call
  var persistC = Map.empty[Long, Cancellable]

  //Maintaining list with to whom response to be sent post persistence completion
  var persistSender = Map.empty[Long, ActorRef]

  //Maintaining list with to whom response to be sent post replication completion
  var replicationSender = Map.empty[Long, ActorRef]

  //to maintain list of replicators for pending messeges
  var remRepMap = Map.empty[Long, scala.collection.immutable.Set[ActorRef]]

  // the current set of replicators
  var replicators = Set.empty[ActorRef]

  //used by secondaries
  var expN: Long = 0L

  //used to send for new replcias
  var id: Long = 0L

  def receive = {
    case JoinedPrimary => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* Behavior for  the leader role. */
  val leader: Receive = {

    case Insert(k, v, i) => {
      kv += (k -> v)
      primaryDependencies(k, Some(v), i)
    }

    case Remove(k, i) => {
      kv -= k
      primaryDependencies(k, None, i)
    }

    case Get(k, i) => sender ! GetResult(k, kv get k, i)

    case Replicas(s) => {

      val reps = s.filter(x => x != self)

      val removedReps = secondaries.keySet -- reps

      //stop removed ones
      removedReps foreach { remRep =>
        //        context.stop(remRep)
        val replicator = secondaries get remRep
        replicator map { rctr =>
          context.stop(rctr)
          replicators -= rctr
        }

      }

      //collect the removed replicators
      var removedReplicators = Set.empty[ActorRef]

      removedReps map { reps =>
        secondaries get reps map { x =>
          removedReplicators += x
        }
      }

      // remove from replicas
      secondaries = secondaries -- removedReps

      //to remove from remaining replica messeges to be received
      remRepMap foreach (ks => {
        var repSet = ks._2
        val newRepSet = repSet -- removedReplicators
        remRepMap += (ks._1 -> newRepSet)
      })

      val newReps = reps -- secondaries.keySet

      var newReplicators = Set.empty[ActorRef]

      newReps foreach { rep =>
        val replicatorProps = Replicator.props(rep)
        val replicator = context.actorOf(replicatorProps)
        replicators += replicator
        newReplicators += replicator
        secondaries += (rep -> replicator)
      }

      val send = sender
      sendAlltoReplica(newReplicators, send)
    }

    case Validate(t, i) => {
      if (t == 10) {
        val c = persistC get i
        c.map(ca => ca.cancel)
        var perS = (persistSender get i)
        perS map (s => s ! OperationFailed(i))
        persistSender -= i

        var repSet = (remRepMap get i)
        repSet match {
          case Some(x) => if (x.size > 0) {
            replicationSender get i map (s => s ! OperationFailed(i))
            replicationSender -= i
          }
          case None =>
        }
      } else {
        context.system.scheduler.scheduleOnce(100 milliseconds, self, Validate(t + 1, i))
      }

    }

    case Persisted(k, i) => {
      val c = (persistC get i)
      persistC -= i
      //cancel presistence calls
      c.map(ca => ca.cancel)
      var perS = (persistSender get i)
      var repSet = (remRepMap get i)
      //Check if any messeges pending to receive replicated messege
      repSet = (remRepMap get i)
      repSet match {
        case Some(x) => if (x.size == 0) { perS map (s => s ! OperationAck(i)) }
        case None => perS map (s => s ! OperationAck(i))
      }

      persistSender -= i
    }

    case Replicated(k, i) => {
      remRepMap get i map { repSet =>
        var r = repSet
        var k = r - sender
        remRepMap += (i -> k)
        if (k.size == 0) {
          //check if any persistence messeges to be received
          (persistSender get i) match {
            case Some(x) =>
            case None => {
              (replicationSender get i) map (s => s ! OperationAck(i))
              replicationSender -= i
            }

          }
          remRepMap -= i
        }

      }

    }

    case TerminateReplicator(replicator, replica, id) => {
      replicators -= replica
      secondaries -= replica

      val d = remRepMap get id
      d.map(x => {
        val k = x - replicator
        remRepMap += (id -> k)
      })

    }

    case _ => //TODO : timeour

  }

  //To send all the key-values to new replicas
  def sendAlltoReplica(replicators: Set[ActorRef], sender: ActorRef) = {

    if (replicators.size > 0 && kv.size > 0) {
      kv foreach (keyval => {
        id = id + 1
        replicators foreach { r => r ! Replicate(keyval._1, kv get keyval._1, id) }
        remRepMap += (id -> replicators)
        replicationSender += (id -> sender)
      })

    }
  }

  //To schedule calls for persistence
  def persistenceSchedule(key: String, valueOption: Option[String], id: Long) = {
    persistC += (id -> context.system.scheduler.schedule(0 milliseconds, 100 milliseconds, context.actorOf(persistenceProps), Persist(key, valueOption, id)))
    context.system.scheduler.scheduleOnce(100 milliseconds, self, Validate(0, id))
  }

  //update the dependencies
  def primaryDependencies(key: String, valueOption: Option[String], id: Long) = {
    persistSender += (id -> sender)
    replicationSender += (id -> sender)
    persistenceSchedule(key, valueOption, id)
    sendtoAllReplica(key, valueOption, id)
  }

  //To send new values to the existing replicas
  def sendtoAllReplica(key: String, vl: Option[String], id: Long) = {
    remRepMap += (id -> replicators)
    replicators foreach (rep => rep ! Replicate(key, vl, id))
  }

  /* Behavior for the replica role. */
  val replica: Receive = {
    case Get(k, i) => sender ! GetResult(k, kv get k, i)

    case Snapshot(k, v, s) => {
      //check on value if to be inserted or deleted
      v match {
        case Some(x) => Try {
          var notNow = true
          if (s == expN) {
            kv += (k -> x)
            notNow = false
            expN = math.max(expN, s + 1)
            persistSender += (s -> sender)
            persistenceSchedule(k, Some(x), s)

          } else if (s < expN && notNow) {
            val c = (persistC get s)
            c.getOrElse(sender ! SnapshotAck(k, s))
          }
        }
        case None => Try {
          var notNow = true
          if (s == expN) {
            kv -= k
            notNow = false
            expN = math.max(expN, s + 1)
            //
            persistSender += (s -> sender)
            persistenceSchedule(k, None, s)

            //
          } else if (s < expN && notNow) {
            val c = (persistC get s)
            c.getOrElse(sender ! SnapshotAck(k, s))
          }
        }
      }
    }

    case Validate(t, i) => {
      if (t == 10) {
        val c = persistC get i
        c.map(ca => ca.cancel)
        (persistSender get i) map (s => s ! OperationFailed(i))
        persistSender -= i
      } else {
        context.system.scheduler.scheduleOnce(100 milliseconds, self, Validate(t + 1, i))
      }

    }
    case Persisted(k, i) => {
      val c = (persistC get i)
      persistC -= i
      c.map(ca => ca.cancel)
      (persistSender get i) map (s => s ! SnapshotAck(k, i))
      persistSender -= i
    }

    case _: Status.Failure => {
      sender ! TerminateReplica
      context.stop(self)
    }

  }

}
